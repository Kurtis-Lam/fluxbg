package com.me.fluxbg

import android.content.Context
import android.content.SharedPreferences
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
    private val KEYS = listOf("file", "start", "end", "cl", "ct", "cr", "cb", "rev")

    private fun sp(c: Context) = c.getSharedPreferences("fluxbg", Context.MODE_PRIVATE)

    private fun read(p: SharedPreferences, key: String, file: String) = Config(
        file,
        p.getLong("${key}_start", 0L),
        p.getLong("${key}_end", 0L),
        p.getFloat("${key}_cl", 0f),
        p.getFloat("${key}_ct", 0f),
        p.getFloat("${key}_cr", 1f),
        p.getFloat("${key}_cb", 1f),
        p.getLong("${key}_rev", 0L)
    )

    private fun write(e: SharedPreferences.Editor, key: String, cfg: Config) {
        e.putLong("${key}_start", cfg.startMs)
            .putLong("${key}_end", cfg.endMs)
            .putFloat("${key}_cl", cfg.cl)
            .putFloat("${key}_ct", cfg.ct)
            .putFloat("${key}_cr", cfg.cr)
            .putFloat("${key}_cb", cfg.cb)
            .putLong("${key}_rev", System.nanoTime())
    }

    // ------------------------------------------------------------ screen slots (what the wallpaper plays)

    fun load(c: Context, slot: String): Config? {
        val p = sp(c)
        val f = p.getString("${slot}_file", null) ?: return null
        if (!File(c.filesDir, f).exists()) return null
        return read(p, slot, f)
    }

    fun save(c: Context, slot: String, cfg: Config) {
        val e = sp(c).edit()
        e.putString("${slot}_file", cfg.file)
        write(e, slot, cfg)
        e.apply()
    }

    fun slotsUsing(c: Context, file: String): List<String> {
        val p = sp(c)
        return listOf(Slot.HOME, Slot.LOCK).filter { p.getString("${it}_file", null) == file }
    }

    // ------------------------------------------------------------ library (every stored video)

    fun library(c: Context): List<String> {
        val files = c.filesDir.listFiles { f -> f.isFile && f.name.startsWith("wall") && f.name.endsWith(".mp4") }
            ?: return emptyList()
        return files.map { it.name }.sortedByDescending {
            it.removePrefix("wall_").removeSuffix(".mp4").toLongOrNull() ?: 0L
        }
    }

    /** Last crop and trim the user chose for a stored video, independent of any screen. */
    fun loadVideo(c: Context, file: String): Config? {
        val p = sp(c)
        if (!File(c.filesDir, file).exists() || !p.contains("v_${file}_rev")) return null
        return read(p, "v_$file", file)
    }

    fun saveVideo(c: Context, file: String, cfg: Config) {
        val e = sp(c).edit()
        write(e, "v_$file", cfg)
        e.apply()
    }

    fun delete(c: Context, file: String) {
        val p = sp(c)
        val e = p.edit()
        for (slot in listOf(Slot.HOME, Slot.LOCK)) {
            if (p.getString("${slot}_file", null) == file) KEYS.forEach { e.remove("${slot}_$it") }
        }
        KEYS.forEach { e.remove("v_${file}_$it") }
        e.apply()
        File(c.filesDir, file).delete()
        File(c.cacheDir, "thumb_$file.jpg").delete()
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