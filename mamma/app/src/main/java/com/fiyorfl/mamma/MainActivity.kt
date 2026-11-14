package com.fiyorfl.mamma

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.webkit.WebViewAssetLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newFixedThreadPool(4)
    private var lastError: String = ""
    private var pendingSongsId: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            pushState()
            main.postDelayed(this, 1000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#7A1F2B")
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#FBF6EE"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.textZoom = 100
        web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        web.settings.mediaPlaybackRequiresUserGesture = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(req.url)
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean = true
        }
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlayerService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        controllerFuture = f
        f.addListener({
            try {
                val c = f.get()
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { pushState() }
                    override fun onPlayerError(error: PlaybackException) {
                        lastError = "No se pudo reproducir. Proba con otra."
                        pushState()
                    }
                })
                pushState()
            } catch (_: Exception) { }
        }, MoreExecutors.directExecutor())
        main.post(ticker)
    }

    override fun onStop() {
        main.removeCallbacks(ticker)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.goBack ? goBack() : false") { r ->
            // Si ya esta en el inicio, se manda la app al fondo SIN cortar la musica
            if (r != "true") moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        net.shutdownNow()
        web.destroy()
        super.onDestroy()
    }

    private fun js(code: String) = main.post { web.evaluateJavascript(code, null) }

    private fun pushState() {
        val c = controller ?: return
        val o = JSONObject()
        val md = c.mediaMetadata
        o.put("playing", c.isPlaying)
        o.put("wants", c.playWhenReady)
        o.put("buffering", c.playbackState == Player.STATE_BUFFERING)
        o.put("idle", c.mediaItemCount == 0)
        o.put("title", md.title?.toString() ?: "")
        o.put("artist", md.artist?.toString() ?: "")
        o.put("art", md.artworkUri?.toString() ?: "")
        o.put("live", c.isCurrentMediaItemLive || c.duration <= 0)
        o.put("pos", c.currentPosition)
        o.put("dur", if (c.duration > 0) c.duration else 0)
        o.put("hasNext", c.hasNextMediaItem())
        o.put("hasPrev", c.hasPreviousMediaItem())
        o.put("index", c.currentMediaItemIndex)
        o.put("sleepAt", PlayerService.sleepAt)
        o.put("error", lastError)
        js("window.onPlayer && onPlayer($o)")
    }

    private fun answer(id: String, status: Int, text: String) {
        js("window.__res(${JSONObject.quote(id)}, $status, ${JSONObject.quote(text)})")
    }

    private fun hasAudioPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadSongs(id: String) {
        net.execute {
            val arr = JSONArray()
            try {
                val proj = arrayOf(
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID
                )
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 45000",
                    null, "${MediaStore.Audio.Media.ARTIST} COLLATE NOCASE, ${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        val sid = cur.getLong(0)
                        val o = JSONObject()
                        o.put("url", ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, sid).toString())
                        o.put("title", cur.getString(1) ?: "")
                        val ar = cur.getString(2) ?: ""
                        o.put("artist", if (ar == "<unknown>") "" else ar)
                        o.put("album", cur.getString(3) ?: "")
                        o.put("dur", cur.getLong(4))
                        o.put("art", ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), cur.getLong(5)).toString())
                        arr.put(o)
                    }
                }
                answer(id, 200, arr.toString())
            } catch (e: Exception) {
                answer(id, 500, e.message ?: "error")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val id = pendingSongsId ?: return
        pendingSongsId = null
        if (hasAudioPermission()) loadSongs(id) else answer(id, 403, "sin permiso")
    }

    inner class Bridge {
        /** Pedido HTTP hecho desde el celu (Discogs, radios). Responde en window.__res(id, status, texto) */
        @JavascriptInterface
        fun get(url: String, id: String) {
            net.execute {
                var status = 0
                var text: String
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000
                    c.readTimeout = 20000
                    c.setRequestProperty("User-Agent", "MusicaDeMama/1.0 +https://github.com/FiyoRFL")
                    c.setRequestProperty("Accept", "application/json")
                    status = c.responseCode
                    val s = if (status in 200..299) c.inputStream else c.errorStream
                    text = s?.bufferedReader()?.use { it.readText() } ?: ""
                    c.disconnect()
                } catch (e: Exception) {
                    text = e.message ?: "error de red"
                }
                answer(id, status, text)
            }
        }

        /** Reproduce una lista: [{url,title,artist,art}] desde la posicion index */
        @JavascriptInterface
        fun play(queueJson: String, index: Int) {
            main.post {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                val c = controller ?: return@post
                lastError = ""
                val arr = JSONArray(queueJson)
                val items = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val md = MediaMetadata.Builder()
                        .setTitle(o.optString("title"))
                        .setArtist(o.optString("artist"))
                        .setIsPlayable(true)
                    val art = o.optString("art")
                    if (art.isNotEmpty()) md.setArtworkUri(Uri.parse(art))
                    MediaItem.Builder()
                        .setUri(o.getString("url"))
                        .setMediaId("$i-" + o.getString("url"))
                        .setMediaMetadata(md.build())
                        .build()
                }
                c.setMediaItems(items, index.coerceIn(0, maxOf(0, items.size - 1)), 0L)
                c.prepare()
                c.play()
            }
        }

        @JavascriptInterface
        fun cmd(name: String) {
            main.post {
                val c = controller ?: return@post
                when (name) {
                    "toggle" -> if (c.playWhenReady) c.pause() else {
                        if (c.playbackState == Player.STATE_IDLE) c.prepare()
                        c.play()
                    }
                    "next" -> c.seekToNextMediaItem()
                    "prev" -> if (c.currentPosition > 4000) c.seekTo(0) else c.seekToPreviousMediaItem()
                    "stop" -> { c.stop(); c.clearMediaItems() }
                }
                pushState()
            }
        }

        @JavascriptInterface
        fun seek(ms: Long) { main.post { controller?.seekTo(ms) } }

        @JavascriptInterface
        fun sleep(minutes: Int) { main.post { PlayerService.instance?.sleep(minutes); pushState() } }

        /** Canciones guardadas en el celu */
        @JavascriptInterface
        fun songs(id: String) {
            main.post {
                if (hasAudioPermission()) loadSongs(id)
                else {
                    pendingSongsId = id
                    val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                    else Manifest.permission.READ_EXTERNAL_STORAGE
                    requestPermissions(arrayOf(p), 1)
                }
            }
        }
    }
}
