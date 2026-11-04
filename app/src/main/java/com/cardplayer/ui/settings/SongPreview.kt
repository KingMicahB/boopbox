package com.cardplayer.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** What the song editor works on: a file on the phone (waveform + exact listening) or a YouTube video. */
sealed interface SongSource {
    data class File(val uri: String) : SongSource
    /** No audio of our own (the Play build never downloads YouTube): listened to through YouTube's embedded player. */
    data class YouTube(val videoId: String) : SongSource
}

/** Plays bits of the song while the grown-up picks cuts. Its fields are Compose state. */
interface SongPreview {
    /** Length once known, else 0. */
    val durationMs: Long
    val ready: Boolean
    /** Set when nothing can be played (the reason, for the screen). */
    val failed: Boolean
    fun play(fromMs: Long)
    fun pause()
    fun isPlaying(): Boolean
    fun positionMs(): Long
    fun release()
}

/** A file on the phone, through MediaPlayer: exact seeks, so a cut is heard right where it is. */
class FileSongPreview(context: Context, uri: String) : SongPreview {
    private val player = MediaPlayer()
    override var durationMs by mutableLongStateOf(0L)
        private set
    override var ready by mutableStateOf(false)
        private set
    override var failed by mutableStateOf(false)
        private set

    init {
        try {
            player.setDataSource(context, Uri.parse(uri))
            player.setOnPreparedListener {
                durationMs = it.duration.toLong().coerceAtLeast(0L)
                ready = true
            }
            player.setOnErrorListener { _, _, _ ->
                failed = !ready
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            failed = true
        }
    }

    override fun play(fromMs: Long) {
        if (!ready) return
        try {
            player.seekTo(fromMs.coerceAtLeast(0L), MediaPlayer.SEEK_CLOSEST)
            player.start()
        } catch (_: Exception) {
        }
    }

    override fun pause() {
        try {
            if (player.isPlaying) player.pause()
        } catch (_: Exception) {
        }
    }

    override fun isPlaying(): Boolean = try {
        player.isPlaying
    } catch (_: Exception) {
        false
    }

    override fun positionMs(): Long = try {
        player.currentPosition.toLong()
    } catch (_: Exception) {
        0L
    }

    override fun release() {
        try {
            player.release()
        } catch (_: Exception) {
        }
    }
}

/**
 * A YouTube video through YouTube's own embedded (IFrame) player in a WebView, shown in the editor.
 * Its position arrives from the page every 100 ms and is carried forward in between.
 * Some videos refuse to play embedded; [failed] is then set.
 */
@SuppressLint("SetJavaScriptEnabled")
class YouTubeSongPreview(context: Context, videoId: String) : SongPreview {
    private val main = Handler(Looper.getMainLooper())
    override var durationMs by mutableLongStateOf(0L)
        private set
    override var ready by mutableStateOf(false)
        private set
    override var failed by mutableStateOf(false)
        private set
    private var playing = false
    private var lastTimeMs = 0L
    private var lastTimeAt = 0L

    val webView: WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // play() comes from our own buttons, not a touch on the page.
        settings.mediaPlaybackRequiresUserGesture = false
        webChromeClient = WebChromeClient()
        addJavascriptInterface(Bridge(), "Boop")
        // An https base page gives the embed the referrer YouTube requires.
        loadDataWithBaseURL("https://boopbox.app/", page(videoId), "text/html", "utf-8", null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun ready(seconds: Double) = main.post {
            durationMs = (seconds * 1000).toLong()
            ready = true
        }

        @JavascriptInterface
        fun state(s: Int) = main.post {
            playing = s == 1 || s == 3 // playing, buffering
        }

        @JavascriptInterface
        fun time(seconds: Double) = main.post {
            lastTimeMs = (seconds * 1000).toLong()
            lastTimeAt = SystemClock.elapsedRealtime()
        }

        @JavascriptInterface
        fun error(code: Int) = main.post {
            // 2 bad id, 5 can't play here, 100 gone, 101 / 150 owner forbids embedding.
            failed = true
        }
    }

    private fun js(code: String) = main.post {
        try {
            webView.evaluateJavascript(code, null)
        } catch (_: Exception) {
        }
    }

    override fun play(fromMs: Long) {
        if (!ready) return
        lastTimeMs = fromMs.coerceAtLeast(0L)
        lastTimeAt = SystemClock.elapsedRealtime()
        playing = true
        js("if(window.p){p.seekTo(${fromMs.coerceAtLeast(0L) / 1000.0},true);p.playVideo();}")
    }

    override fun pause() {
        playing = false
        js("if(window.p){p.pauseVideo();}")
    }

    override fun isPlaying(): Boolean = playing

    override fun positionMs(): Long =
        if (playing) lastTimeMs + (SystemClock.elapsedRealtime() - lastTimeAt).coerceIn(0L, 500L) else lastTimeMs

    override fun release() {
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        } catch (_: Exception) {
        }
    }

    @Composable
    fun Video(modifier: Modifier = Modifier) {
        AndroidView(factory = { webView.also { (it.parent as? ViewGroup)?.removeView(it) } }, modifier = modifier)
    }

    private fun page(videoId: String) = videoId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.let { id -> """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}</style></head>
        <body><div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
        var p;
        function onYouTubeIframeAPIReady(){
          p=new YT.Player('p',{videoId:'$id',playerVars:{playsinline:1,controls:0,rel:0,fs:0,disablekb:1,iv_load_policy:3},
            events:{
              onReady:function(){Boop.ready(p.getDuration());},
              onStateChange:function(e){Boop.state(e.data);},
              onError:function(e){Boop.error(e.data);}
            }});
          setInterval(function(){if(p&&p.getCurrentTime){Boop.time(p.getCurrentTime());}},100);
        }
        </script></body></html>
    """.trimIndent() }
}
