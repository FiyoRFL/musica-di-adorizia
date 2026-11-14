package com.fiyorfl.mamma

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Servicio de musica: sigue sonando con la pantalla apagada o con la app cerrada,
 * y muestra los controles en la notificacion y en la pantalla de bloqueo.
 */
class PlayerService : MediaSessionService() {
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var errorsInRow = 0
    private val sleepRunnable = Runnable {
        session?.player?.pause()
        sleepAt = 0L
    }

    override fun onCreate() {
        super.onCreate()
        YouTube.warmUp(this)
        // Las canciones de YouTube vienen como yt://ID y se convierten al link del audio justo antes de sonar
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec: DataSpec ->
            if (spec.uri.scheme == "yt") {
                val st = YouTube.resolve(this, spec.uri.host ?: "")
                spec.buildUpon()
                    .setUri(Uri.parse(st.url))
                    .setHttpRequestHeaders(mapOf("User-Agent" to st.ua))
                    .build()
            } else spec
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true // pausa sola si entra una llamada u otra app pone sonido
            )
            .setHandleAudioBecomingNoisy(true) // pausa si se desconectan los auriculares
            .setWakeMode(C.WAKE_MODE_NETWORK) // no se corta con la pantalla apagada
            .build()
        player.repeatMode = Player.REPEAT_MODE_OFF
        // Si un video esta bloqueado o borrado, pasa solo al siguiente (hasta 10 seguidos)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (++errorsInRow <= 10 && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) errorsInRow = 0 }
        })
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
        instance = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Apagar la musica en N minutos (0 = cancelar) */
    fun sleep(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        if (minutes > 0) {
            sleepAt = System.currentTimeMillis() + minutes * 60_000L
            handler.postDelayed(sleepRunnable, minutes * 60_000L)
        } else sleepAt = 0L
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Si se cierra la app deslizando y no esta sonando nada, se apaga el servicio
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(sleepRunnable)
        session?.run {
            player.release()
            release()
        }
        session = null
        instance = null
        sleepAt = 0L
        super.onDestroy()
    }

    companion object {
        var instance: PlayerService? = null
        var sleepAt: Long = 0L
    }
}
