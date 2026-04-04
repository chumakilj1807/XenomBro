package com.xenombrowser

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class VideoActivity : FragmentActivity() {

    companion object {
        const val EXTRA_URL   = "url"
        const val EXTRA_TITLE = "title"
    }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_video)

        playerView = findViewById(R.id.player_view)

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                )
            ).build()

        playerView.player = player
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        return when (event.keyCode) {
            // Play / Pause
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (player.isPlaying) player.pause() else player.play()
                true
            }
            // Seek -30s
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                val pos = (player.currentPosition - 30_000L).coerceAtLeast(0L)
                player.seekTo(pos)
                true
            }
            // Seek +30s
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val dur = player.duration
                val pos = if (dur > 0)
                    (player.currentPosition + 30_000L).coerceAtMost(dur)
                else player.currentPosition + 30_000L
                player.seekTo(pos)
                true
            }
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onPause()   { super.onPause();   player.pause() }
    override fun onResume()  { super.onResume();  player.play()  }
    override fun onDestroy() { super.onDestroy(); player.release() }
}
