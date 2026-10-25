package com.me.fluxbg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Loads and caches a poster frame and the duration for each stored video, or a preview of each image. */
object Thumbs {
    private val cache = LruCache<String, Bitmap>(64)
    private val durations = ConcurrentHashMap<String, Long>()
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun load(ctx: Context, file: String, cb: (Bitmap?, Long) -> Unit) {
        val hit = cache.get(file)
        val known = durations[file]
        if (hit != null && known != null) {
            cb(hit, known)
            return
        }
        val app = ctx.applicationContext
        exec.execute {
            var bmp: Bitmap? = null
            var dur = 0L
            val isImage = Store.isImage(file)
            val r = MediaMetadataRetriever()
            try {
                val disk = File(app.cacheDir, "thumb_$file.jpg")
                if (isImage) {
                    if (disk.exists()) bmp = BitmapFactory.decodeFile(disk.absolutePath)
                    if (bmp == null) {
                        bmp = Images.decode(File(app.filesDir, file), 640)
                        val made = bmp
                        if (made != null) {
                            try {
                                FileOutputStream(disk).use { made.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                            } catch (_: Exception) {
                            }
                        }
                    }
                } else {
                    r.setDataSource(File(app.filesDir, file).absolutePath)
                    dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    if (disk.exists()) bmp = BitmapFactory.decodeFile(disk.absolutePath)
                    if (bmp == null) {
                        val at = minOf(1_000_000L, dur * 500L)
                        val raw = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (raw != null) {
                            val scaled: Bitmap = if (raw.height > 480) {
                                Bitmap.createScaledBitmap(raw, (raw.width * 480f / raw.height).toInt().coerceAtLeast(1), 480, true)
                            } else raw
                            if (scaled !== raw) raw.recycle()
                            try {
                                FileOutputStream(disk).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                            } catch (_: Exception) {
                            }
                            bmp = scaled
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
            val result = bmp
            if (result != null) cache.put(file, result)
            if (dur > 0L || isImage) durations[file] = dur
            main.post { cb(result, dur) }
        }
    }

    fun forget(file: String) {
        cache.remove(file)
        durations.remove(file)
    }
}