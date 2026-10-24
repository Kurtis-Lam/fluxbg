package com.me.vidwall

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

class VideoWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = VEngine()

    inner class VEngine : Engine() {
        private var mp: MediaPlayer? = null
        private val h = Handler(Looper.getMainLooper())
        private var startMs = 0L
        private var endMs = 0L
        private var rev = 0L
        private var holder: SurfaceHolder? = null

        private val tick = object : Runnable {
            override fun run() {
                val p = mp ?: return
                try {
                    val end = if (endMs > startMs) endMs else p.duration.toLong()
                    if (p.currentPosition >= end - 40) p.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                } catch (_: Exception) {}
                h.postDelayed(this, 30)
            }
        }

        private fun release() { h.removeCallbacks(tick); mp?.release(); mp = null }

        private fun start() {
            release()
            val f = File(filesDir, "wall.mp4")
            val sh = holder ?: return
            if (!f.exists()) return
            val pr = getSharedPreferences("p", MODE_PRIVATE)
            startMs = pr.getLong("start", 0); endMs = pr.getLong("end", 0); rev = pr.getLong("rev", 0)
            mp = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setSurface(sh.surface)
                setVolume(0f, 0f)
                isLooping = false
                setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                setOnPreparedListener { it.seekTo(startMs, MediaPlayer.SEEK_CLOSEST); it.start(); h.post(tick) }
                setOnCompletionListener { it.seekTo(startMs.toInt()); it.start() }
                prepareAsync()
            }
        }

        override fun onSurfaceCreated(sh: SurfaceHolder) { holder = sh; super.onSurfaceCreated(sh) }
        override fun onSurfaceChanged(sh: SurfaceHolder, f: Int, w: Int, hh: Int) { holder = sh; start() }
        override fun onSurfaceDestroyed(sh: SurfaceHolder) { release(); holder = null; super.onSurfaceDestroyed(sh) }
        override fun onVisibilityChanged(v: Boolean) {
            if (v) {
                val r = getSharedPreferences("p", MODE_PRIVATE).getLong("rev", 0)
                if (mp == null || r != rev) start() else { mp?.start(); h.post(tick) }
            } else { mp?.pause(); h.removeCallbacks(tick) }
        }
        override fun onDestroy() { release(); super.onDestroy() }
    }
}
