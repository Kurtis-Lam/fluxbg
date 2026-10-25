package com.me.fluxbg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

/** Decoding, EXIF rotation and cropping for the still images stored in the library. */
object Images {
    private fun orientation(f: File): Int = try {
        ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
    } catch (_: Exception) {
        1
    }

    private fun swaps(o: Int) = o == 5 || o == 6 || o == 7 || o == 8

    private fun matrixFor(o: Int): Matrix? {
        val m = Matrix()
        when (o) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f)
            7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            8 -> m.postRotate(270f)
            else -> return null
        }
        return m
    }

    private fun rotate(b: Bitmap, o: Int): Bitmap {
        val m = matrixFor(o) ?: return b
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r !== b) b.recycle()
        return r
    }

    /** Width and height as the picture is meant to be seen (EXIF rotation applied), or null if unreadable. */
    fun size(f: File): IntArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return if (swaps(orientation(f))) intArrayOf(o.outHeight, o.outWidth) else intArrayOf(o.outWidth, o.outHeight)
    }

    /** Decodes a downsampled, correctly rotated copy whose longest side is at least maxDim where possible. */
    fun decode(f: File, maxDim: Int): Bitmap? {
        val sz = size(f) ?: return null
        var sample = 1
        while (maxOf(sz[0], sz[1]) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
        return rotate(raw, orientation(f))
    }

    /** Crops the normalized rectangle c (l, t, r, b) and scales it to exactly sw x sh. */
    fun render(f: File, c: FloatArray, sw: Int, sh: Int): Bitmap {
        val sz = size(f) ?: throw IllegalStateException("unreadable")
        val fit = Store.fit(c[0], c[1], c[2], c[3], sz[0], sz[1], sw, sh)
        val cropW = (fit[2] - fit[0]) * sz[0]
        val cropH = (fit[3] - fit[1]) * sz[1]
        var sample = 1
        while (cropW / (sample * 2) >= sw && cropH / (sample * 2) >= sh) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeFile(f.absolutePath, opts) ?: throw IllegalStateException("decode")
        val bmp = rotate(raw, orientation(f))

        val l = (fit[0] * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val t = (fit[1] * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val r = (fit[2] * bmp.width).toInt().coerceIn(l + 1, bmp.width)
        val b = (fit[3] * bmp.height).toInt().coerceIn(t + 1, bmp.height)
        val part = Bitmap.createBitmap(bmp, l, t, r - l, b - t)
        val out = Bitmap.createScaledBitmap(part, sw, sh, true)
        if (part !== out && part !== bmp) part.recycle()
        if (bmp !== out) bmp.recycle()
        return out
    }
}