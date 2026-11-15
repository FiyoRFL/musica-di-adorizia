package com.fiyorfl.mamma

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Consigue el link directo al AUDIO de un video de YouTube (sin video, sin anuncios),
 * para que suene en el servicio de musica con la pantalla apagada.
 * 1) Motor directo: un solo pedido a YouTube como Apple Vision (igual que FIYODIGGER).
 * 2) Respaldo: yt-dlp (Python) si el directo falla.
 */
object YouTube {
    const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    private const val PLAYER = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

    class Unavailable(msg: String) : IOException(msg)
    data class Stream(val url: String, val ua: String, val expires: Long)

    private val cache = ConcurrentHashMap<String, Stream>()
    @Volatile private var visitor = ""
    @Volatile private var directOffUntil = 0L
    @Volatile private var directFails = 0
    @Volatile private var py: PyObject? = null
    private val pyLock = Any()

    /**
     * Prepara yt-dlp (el respaldo) sin molestar: espera 20 s y corre en prioridad baja,
     * asi no le quita procesador a la primera cancion en celus de gama media (Samsung A23/A33).
     * Si el motor directo falla antes, python() lo arranca en el momento.
     */
    fun warmUp(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try { Thread.sleep(20_000); python(app) } catch (_: Throwable) { }
        }.start()
    }

    private fun python(ctx: Context): PyObject = synchronized(pyLock) {
        py ?: run {
            if (!Python.isStarted()) Python.start(AndroidPlatform(ctx.applicationContext))
            val m = Python.getInstance().getModule("mm")
            val zip = File(ctx.filesDir, "ytdlp.pyz").absolutePath
            m.callAttr("setup", zip)
            try { m.callAttr("update_bg", zip) } catch (_: Throwable) { }
            py = m
            m
        }
    }

    fun resolve(ctx: Context, id: String): Stream {
        cache[id]?.let { if (it.expires > System.currentTimeMillis()) return it }
        var why = ""
        if (System.currentTimeMillis() > directOffUntil) {
            try {
                val s = direct(id)
                directFails = 0
                cache[id] = s
                return s
            } catch (e: Unavailable) {
                throw e
            } catch (e: Exception) {
                why = e.message ?: "error"
                if (++directFails >= 4) { directOffUntil = System.currentTimeMillis() + 20 * 60_000; directFails = 0 }
            }
        }
        try {
            val r = JSONObject(python(ctx).callAttr("resolve", id).toString())
            val url = r.getString("url")
            val exp = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
            val s = Stream(url, r.optString("ua").ifEmpty { UA },
                if (exp != null) exp * 1000 - 600_000 else System.currentTimeMillis() + 1_800_000)
            cache[id] = s
            return s
        } catch (e: Throwable) {
            throw IOException("YouTube: $why / ${e.message}")
        }
    }

    private fun direct(id: String): Stream {
        val client = JSONObject()
            .put("clientName", "VISIONOS").put("clientVersion", "1.02")
            .put("deviceMake", "Apple").put("deviceModel", "RealityDevice17,1")
            .put("userAgent", UA).put("osName", "visionOS").put("osVersion", "26.5.23O471")
            .put("hl", "it").put("timeZone", "UTC").put("utcOffsetMinutes", 0)
        if (visitor.isNotEmpty()) client.put("visitorData", visitor)
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", id)
            .put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().put("html5Preference", "HTML5_PREF_WANTS")))
            .put("contentCheckOk", true).put("racyCheckOk", true)
        val c = URL(PLAYER).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10000
        c.readTimeout = 12000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("X-YouTube-Client-Name", "101")
        c.setRequestProperty("X-YouTube-Client-Version", "1.02")
        c.setRequestProperty("Origin", "https://www.youtube.com")
        if (visitor.isNotEmpty()) c.setRequestProperty("X-Goog-Visitor-Id", visitor)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        c.disconnect()
        j.optJSONObject("responseContext")?.optString("visitorData")?.let { if (it.isNotEmpty()) visitor = it }
        val ps = j.optJSONObject("playabilityStatus")
        val status = ps?.optString("status") ?: "NONE"
        val reason = ps?.optString("reason") ?: ""
        if (status != "OK") {
            if (Regex("bot|sign in to confirm", RegexOption.IGNORE_CASE).containsMatchIn(reason)) throw IOException(reason)
            if (status == "ERROR" || Regex("countr|region|not available|age|inappropriate", RegexOption.IGNORE_CASE).containsMatchIn(reason))
                throw Unavailable(reason.ifEmpty { status })
            throw IOException(status)
        }
        val fmts = j.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: throw IOException("sin formatos")
        var best: JSONObject? = null
        var bestScore = -1L
        for (i in 0 until fmts.length()) {
            val f = fmts.getJSONObject(i)
            val mime = f.optString("mimeType")
            if (!mime.startsWith("audio/") || f.optString("url").isEmpty()) continue
            // m4a primero (lo reproduce cualquier celu), y el de mejor calidad
            val score = (if (mime.contains("audio/mp4")) 10_000_000L else 0L) + f.optLong("bitrate")
            if (score > bestScore) { bestScore = score; best = f }
        }
        val url = best?.optString("url") ?: throw IOException("sin audio")
        val exp = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return Stream(url, UA, if (exp != null) exp * 1000 - 600_000 else System.currentTimeMillis() + 1_800_000)
    }
}
