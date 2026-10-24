package com.me.fluxbg

import android.content.Context
import java.io.File

object Slot {
    const val HOME = "home"
    const val LOCK = "lock"
}

data class Config(
    val file: String,
    val startMs: Long,
    val endMs: Long,
    val cl: Float,
    val ct: Float,
    val cr: Float,
    val cb: Float,
    val rev: Long
)

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("fluxbg", Context.MODE_PRIVATE)

    fun load(c: Context, slot: String): Config? {
        val p = sp(c)
        val f = p.getString("${slot}_file", null) ?: return null
        if (!File(c.filesDir, f).exists()) return null
        return Config(
            f,
            p.getLong("${slot}_start", 0L),
            p.getLong("${slot}_end", 0L),
            p.getFloat("${slot}_cl", 0f),
            p.getFloat("${slot}_ct", 0f),
            p.getFloat("${slot}_cr", 1f),
            p.getFloat("${slot}_cb", 1f),
            p.getLong("${slot}_rev", 0L)
        )
    }

    fun save(c: Context, slot: String, cfg: Config) {
        sp(c).edit()
            .putString("${slot}_file", cfg.file)
            .putLong("${slot}_start", cfg.startMs)
            .putLong("${slot}_end", cfg.endMs)
            .putFloat("${slot}_cl", cfg.cl)
            .putFloat("${slot}_ct", cfg.ct)
            .putFloat("${slot}_cr", cfg.cr)
            .putFloat("${slot}_cb", cfg.cb)
            .putLong("${slot}_rev", System.nanoTime())
            .apply()
    }

    fun cleanup(c: Context) {
        val p = sp(c)
        val keep = setOf(p.getString("${Slot.HOME}_file", null), p.getString("${Slot.LOCK}_file", null))
        c.filesDir.listFiles()?.forEach {
            if (it.name.startsWith("wall_") && it.name !in keep) it.delete()
        }
    }

    /**
     * Adjusts a normalized crop rectangle (l, t, r, b in 0..1 of the video frame) so that its
     * aspect ratio matches the target surface, trimming from the centre. This makes the same
     * crop work on every screen size, including foldables.
     */
    fun fit(l0: Float, t0: Float, r0: Float, b0: Float, vw: Int, vh: Int, sw: Int, sh: Int): FloatArray {
        var l = l0; var t = t0; var r = r0; var b = b0
        if (vw > 0 && vh > 0 && sw > 0 && sh > 0 && r > l && b > t) {
            val ar = ((r - l) * vw) / ((b - t) * vh)
            val sar = sw.toFloat() / sh.toFloat()
            if (ar > sar) {
                val nw = (r - l) * sar / ar
                val m = (l + r) / 2f
                l = m - nw / 2f; r = m + nw / 2f
            } else if (ar < sar) {
                val nh = (b - t) * ar / sar
                val m = (t + b) / 2f
                t = m - nh / 2f; b = m + nh / 2f
            }
        }
        return floatArrayOf(l, t, r, b)
    }
}
