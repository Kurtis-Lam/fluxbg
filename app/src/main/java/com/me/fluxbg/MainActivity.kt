package com.me.fluxbg

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.min

class MainActivity : Activity(), TextureView.SurfaceTextureListener {

    private enum class Target { HOME, LOCK, BOTH }

    private object C {
        val BG = Color.parseColor("#0B0E14")
        val CARD = Color.parseColor("#151A24")
        val CARD2 = Color.parseColor("#1D2430")
        val TEXT = Color.parseColor("#F2F4F8")
        val MUTED = Color.parseColor("#8A93A6")
        val A = Color.parseColor("#5EEAD4")
        val B = Color.parseColor("#8B9CFF")
        val DARK = Color.parseColor("#0B0E14")
    }

    private val reqPick = 1
    private val thumbCount = 12
    private val ui = Handler(Looper.getMainLooper())

    private var target = Target.HOME
    private var file: String? = null
    private var durationMs = 0L
    private var startMs = 0L
    private var endMs = 0L
    private var pendingEnd = 0L
    private var pendingCrop: FloatArray? = null
    private var vidW = 0
    private var vidH = 0
    private var mp: MediaPlayer? = null
    private var surf: Surface? = null
    private var playing = false
    @Volatile private var gen = 0

    private lateinit var segViews: List<TextView>
    private lateinit var emptyBox: View
    private lateinit var editorBox: View
    private lateinit var previewBox: FrameLayout
    private lateinit var tv: TextureView
    private lateinit var crop: CropOverlayView
    private lateinit var trim: TrimBarView
    private lateinit var playBtn: TextView
    private lateinit var posLabel: TextView
    private lateinit var inLabel: TextView
    private lateinit var lenLabel: TextView
    private lateinit var outLabel: TextView
    private lateinit var applyBtn: TextView
    private lateinit var stillBtn: TextView
    private lateinit var note: TextView
    private lateinit var status: TextView

    private val tick = object : Runnable {
        override fun run() {
            val p = mp ?: return
            try {
                val pos = p.currentPosition.toLong()
                if (pos >= endMs - 30) p.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                val shown = pos.coerceIn(startMs, endMs)
                trim.setPosition(shown)
                posLabel.text = "${fmt(shown)} / ${fmt(durationMs)}"
            } catch (_: Exception) {
            }
            if (playing) ui.postDelayed(this, 33)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun fmt(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        val m = t / 60000
        val s = (t % 60000) / 1000
        val d = (t % 1000) / 100
        return "$m:${s.toString().padStart(2, '0')}.$d"
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun gradient(radiusDp: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C.A, C.B))
            .apply { cornerRadius = dp(radiusDp).toFloat() }

    private fun ripple(bg: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), bg, null)

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(text: String, filled: Boolean): TextView = label(text, 15f, if (filled) C.DARK else C.TEXT, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = ripple(if (filled) gradient(16) else rounded(C.CARD2, 16))
    }

    private fun lp(w: Int, h: Int, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun slots(): List<String> = when (target) {
        Target.HOME -> listOf(Slot.HOME)
        Target.LOCK -> listOf(Slot.LOCK)
        Target.BOTH -> listOf(Slot.HOME, Slot.LOCK)
    }

    // ---------------------------------------------------------------- UI

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG

        val metrics = resources.displayMetrics
        val root = ScrollView(this).apply {
            setBackgroundColor(C.BG)
            isFillViewport = true
            setOnApplyWindowInsetsListener { v, ins ->
                @Suppress("DEPRECATION")
                v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
                ins
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        root.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Header
        val title = label("", 34f, C.TEXT, true)
        title.text = SpannableString("fluxbg").apply {
            setSpan(ForegroundColorSpan(C.A), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(C.B), 4, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        col.addView(title)
        col.addView(label("Video wallpapers for Huawei", 14f, C.MUTED), lp(-2, -2, 2))

        // Target selector
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C.CARD, 16)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val names = listOf("Home screen", "Lock screen", "Both")
        segViews = names.mapIndexed { i, n ->
            label(n, 14f, C.MUTED, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(11), 0, dp(11))
                setOnClickListener { setTarget(Target.values()[i]) }
            }.also { seg.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        col.addView(seg, lp(-1, -2, 20))

        // Empty state
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ripple(GradientDrawable().apply {
                setColor(C.CARD)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(2), Color.parseColor("#2C3646"), dp(8).toFloat(), dp(6).toFloat())
            })
            setOnClickListener { pickVideo() }
        }
        empty.addView(label("+", 44f, C.A, true).apply { gravity = Gravity.CENTER })
        empty.addView(label("Choose a video", 18f, C.TEXT, true).apply { gravity = Gravity.CENTER })
        empty.addView(label("Pick any clip from your gallery,\nthen crop and trim it", 13f, C.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        })
        emptyBox = empty
        col.addView(empty, lp(-1, dp(240), 16))

        // Editor card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.CARD, 20)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            visibility = View.GONE
        }
        editorBox = card
        col.addView(card, lp(-1, -2, 16))

        previewBox = FrameLayout(this).apply {
            background = rounded(Color.BLACK, 14)
            clipToOutline = true
        }
        tv = TextureView(this).apply { surfaceTextureListener = this@MainActivity }
        previewBox.addView(tv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        crop = CropOverlayView(this).apply {
            aspect = metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
        }
        previewBox.addView(crop, FrameLayout.LayoutParams(-1, -1))
        previewBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPreview() }
        card.addView(previewBox, lp(-1, (metrics.heightPixels * 0.44f).toInt()))

        card.addView(label("Drag to move  ·  Pinch to resize the crop", 12f, C.MUTED).apply {
            gravity = Gravity.CENTER
        }, lp(-1, -2, 8))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playBtn = label("▶", 18f, C.DARK, true).apply {
            gravity = Gravity.CENTER
            background = ripple(gradient(24))
            setOnClickListener { setPlaying(!playing) }
        }
        controls.addView(playBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        posLabel = label("0:00.0 / 0:00.0", 14f, C.TEXT, true).apply { setPadding(dp(12), 0, 0, 0) }
        controls.addView(posLabel, LinearLayout.LayoutParams(0, -2, 1f))
        val change = label("Change video", 13f, C.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = ripple(rounded(C.CARD2, 12))
            setOnClickListener { pickVideo() }
        }
        controls.addView(change)
        card.addView(controls, lp(-1, -2, 12))

        trim = TrimBarView(this).apply {
            listener = object : TrimBarView.Listener {
                override fun onTrimChanged(startMs: Long, endMs: Long, handle: Int) {
                    this@MainActivity.startMs = startMs
                    this@MainActivity.endMs = endMs
                    setPlaying(false)
                    val at = if (handle == 0) startMs else (endMs - 100).coerceAtLeast(startMs)
                    safeSeek(at)
                    updateTimes(at)
                }

                override fun onSeek(ms: Long) {
                    setPlaying(false)
                    safeSeek(ms)
                    updateTimes(ms)
                }
            }
        }
        card.addView(trim, lp(-1, dp(76), 14))

        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        inLabel = label("IN 0:00.0", 12f, C.MUTED, true)
        lenLabel = label("LENGTH 0:00.0", 12f, C.A, true).apply { gravity = Gravity.CENTER }
        outLabel = label("OUT 0:00.0", 12f, C.MUTED, true).apply { gravity = Gravity.END }
        times.addView(inLabel, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(lenLabel, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(outLabel, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(times, lp(-1, -2, 4))

        // Actions
        applyBtn = button("Set as live wallpaper", true).apply { setOnClickListener { applyLive() } }
        col.addView(applyBtn, lp(-1, -2, 20))
        stillBtn = button("Set still frame on lock screen", false).apply { setOnClickListener { applyStill() } }
        col.addView(stillBtn, lp(-1, -2, 10))
        note = label("", 12f, C.MUTED).apply { setLineSpacing(0f, 1.15f) }
        col.addView(note, lp(-1, -2, 14))
        status = label("", 12f, C.A)
        col.addView(status, lp(-1, -2, 6))

        setContentView(root)

        val first = Store.load(this, Slot.HOME) ?: Store.load(this, Slot.LOCK)
        if (first != null) loadConfig(first)
        updateTargetUi()
    }

    private fun updateTargetUi() {
        segViews.forEachIndexed { i, v ->
            val on = i == target.ordinal
            v.setTextColor(if (on) C.DARK else C.MUTED)
            v.background = if (on) gradient(12) else null
        }
        stillBtn.visibility = if (target == Target.HOME) View.GONE else View.VISIBLE
        applyBtn.text = when (target) {
            Target.HOME -> "Set as home screen wallpaper"
            Target.LOCK -> "Set as lock screen wallpaper"
            Target.BOTH -> "Set as home + lock wallpaper"
        }
        note.text = when (target) {
            Target.HOME -> "Applies to your home screen. Each screen keeps its own video, crop and trim."
            Target.LOCK -> "In the system preview, choose Lock screen if asked. If your Huawei Themes app " +
                "doesn’t allow live lock screens, use the still frame option, which sets the frame at the playhead."
            Target.BOTH -> "Uses the same video on both screens. If the lock screen stays static, use the still " +
                "frame option, which sets the frame at the playhead."
        }
    }

    private fun setTarget(t: Target) {
        if (t == target) return
        saveAll()
        target = t
        val cfg = Store.load(this, slots()[0])
        if (cfg != null) loadConfig(cfg)
        updateTargetUi()
    }

    // ---------------------------------------------------------------- video import

    private fun pickVideo() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
        @Suppress("DEPRECATION")
        startActivityForResult(i, reqPick)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != reqPick || resultCode != RESULT_OK || uri == null) return
        status.text = "Importing video…"
        Thread {
            val name = "wall_${System.currentTimeMillis()}.mp4"
            val ok = try {
                contentResolver.openInputStream(uri)?.use { i ->
                    openFileOutput(name, MODE_PRIVATE).use { o -> i.copyTo(o) }
                } != null
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (ok) {
                    file = name
                    startMs = 0L
                    pendingEnd = 0L
                    pendingCrop = null
                    crop.reset()
                    if (setupEditor()) {
                        saveAll()
                        status.text = "Video loaded. Adjust crop and trim, then apply."
                    }
                } else {
                    status.text = "Couldn’t read that video."
                }
            }
        }.start()
    }

    private fun loadConfig(cfg: Config) {
        file = cfg.file
        startMs = cfg.startMs
        pendingEnd = cfg.endMs
        pendingCrop = floatArrayOf(cfg.cl, cfg.ct, cfg.cr, cfg.cb)
        setupEditor()
    }

    private fun setupEditor(): Boolean {
        val f = file ?: return false
        setPlaying(false)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(File(filesDir, f).absolutePath)
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            vidW = w; vidH = h
        } catch (_: Exception) {
            durationMs = 0L
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
        if (durationMs <= 0L || vidW <= 0 || vidH <= 0) {
            status.text = "This video format isn’t supported."
            return false
        }
        startMs = startMs.coerceIn(0L, (durationMs - 500L).coerceAtLeast(0L))
        endMs = if (pendingEnd > startMs && pendingEnd < durationMs) pendingEnd else durationMs

        emptyBox.visibility = View.GONE
        editorBox.visibility = View.VISIBLE
        trim.setRange(durationMs, startMs, endMs)
        updateTimes(startMs)
        layoutPreview()
        loadThumbs()
        startEditorPlayer()
        return true
    }

    private fun layoutPreview() {
        val cw = previewBox.width
        val ch = previewBox.height
        if (cw <= 0 || ch <= 0 || vidW <= 0 || vidH <= 0) return
        val sc = min(cw.toFloat() / vidW, ch.toFloat() / vidH)
        val w = (vidW * sc).toInt()
        val h = (vidH * sc).toInt()
        val p = tv.layoutParams as FrameLayout.LayoutParams
        if (p.width != w || p.height != h) {
            p.width = w
            p.height = h
            tv.layoutParams = p
        }
        val l = (cw - w) / 2f
        val t = (ch - h) / 2f
        crop.setVideoRect(RectF(l, t, l + w, t + h))
        pendingCrop?.let {
            crop.setCrop(it[0], it[1], it[2], it[3])
            pendingCrop = null
        }
    }

    private fun loadThumbs() {
        val f = file ?: return
        val g = ++gen
        val dur = durationMs
        trim.clearThumbs(thumbCount)
        Thread {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(File(filesDir, f).absolutePath)
                for (i in 0 until thumbCount) {
                    if (g != gen) break
                    val t = ((i + 0.5) / thumbCount * dur * 1000).toLong()
                    val b = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                    val th = 160
                    val tw = (b.width * th / b.height.toFloat()).toInt().coerceAtLeast(1)
                    val s = Bitmap.createScaledBitmap(b, tw, th, true)
                    if (s !== b) b.recycle()
                    runOnUiThread { if (g == gen) trim.setThumb(i, s) }
                }
            } catch (_: Exception) {
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
        }.start()
    }

    // ---------------------------------------------------------------- editor playback

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surf = Surface(st)
        if (file != null) startEditorPlayer()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        releaseEditorPlayer()
        surf?.release()
        surf = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private fun startEditorPlayer() {
        releaseEditorPlayer()
        val f = file ?: return
        val s = surf ?: return
        val p = MediaPlayer()
        mp = p
        try {
            p.setDataSource(File(filesDir, f).absolutePath)
            p.setSurface(s)
            p.setVolume(0f, 0f)
            p.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0 && (w != vidW || h != vidH)) {
                    vidW = w; vidH = h
                    layoutPreview()
                }
            }
            p.setOnPreparedListener { it.seekTo(startMs, MediaPlayer.SEEK_CLOSEST) }
            p.setOnCompletionListener {
                it.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                if (playing) it.start()
            }
            p.setOnErrorListener { _, _, _ -> status.text = "Playback error."; true }
            p.prepareAsync()
        } catch (_: Exception) {
            status.text = "Couldn’t open that video."
        }
    }

    private fun releaseEditorPlayer() {
        ui.removeCallbacks(tick)
        playing = false
        if (this::playBtn.isInitialized) playBtn.text = "▶"
        try { mp?.release() } catch (_: Exception) {}
        mp = null
    }

    private fun safeSeek(ms: Long) {
        try { mp?.seekTo(ms, MediaPlayer.SEEK_CLOSEST) } catch (_: Exception) {}
    }

    private fun setPlaying(on: Boolean) {
        val p = mp
        if (on && p == null) return
        playing = on
        playBtn.text = if (on) "❚❚" else "▶"
        ui.removeCallbacks(tick)
        try {
            if (on) {
                if (p!!.currentPosition < startMs || p.currentPosition >= endMs - 30) p.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                p.start()
                ui.post(tick)
            } else {
                p?.pause()
            }
        } catch (_: Exception) {
        }
    }

    private fun updateTimes(pos: Long) {
        inLabel.text = "IN ${fmt(startMs)}"
        outLabel.text = "OUT ${fmt(endMs)}"
        lenLabel.text = "LENGTH ${fmt(endMs - startMs)}"
        posLabel.text = "${fmt(pos)} / ${fmt(durationMs)}"
        trim.setPosition(pos)
    }

    // ---------------------------------------------------------------- saving / applying

    private fun saveAll() {
        val f = file ?: return
        if (durationMs <= 0L) return
        val c = crop.getCrop()
        val cfg = Config(f, startMs, if (endMs >= durationMs) 0L else endMs, c[0], c[1], c[2], c[3], 0L)
        slots().forEach { Store.save(this, it, cfg) }
        Store.cleanup(this)
    }

    private fun applyLive() {
        if (file == null) { toast("Choose a video first."); return }
        saveAll()
        val comp = ComponentName(this, FluxWallpaperService::class.java)
        try {
            startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, comp)
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                toast("Pick fluxbg from the live wallpaper list.")
            } catch (_: Exception) {
                toast("This device has no live wallpaper picker. Try Settings > Home screen & wallpaper.")
            }
        }
    }

    private fun applyStill() {
        val f = file
        if (f == null) { toast("Choose a video first."); return }
        saveAll()
        val c = crop.getCrop()
        val posUs = (trim.currentPosition()) * 1000L
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        status.text = "Setting lock screen…"
        Thread {
            var msg: String
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(File(filesDir, f).absolutePath)
                val frame = r.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: throw IllegalStateException("no frame")
                val fit = Store.fit(c[0], c[1], c[2], c[3], frame.width, frame.height, dm.widthPixels, dm.heightPixels)
                val rect = Rect(
                    (fit[0] * frame.width).toInt().coerceIn(0, frame.width - 1),
                    (fit[1] * frame.height).toInt().coerceIn(0, frame.height - 1),
                    (fit[2] * frame.width).toInt().coerceIn(1, frame.width),
                    (fit[3] * frame.height).toInt().coerceIn(1, frame.height)
                )
                val part = Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
                val out = Bitmap.createScaledBitmap(part, dm.widthPixels, dm.heightPixels, true)
                WallpaperManager.getInstance(this).setBitmap(out, null, true, WallpaperManager.FLAG_LOCK)
                msg = "Lock screen updated."
            } catch (_: Exception) {
                msg = "Couldn’t set the lock screen on this device."
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
            runOnUiThread { status.text = msg }
        }.start()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onPause() {
        setPlaying(false)
        saveAll()
        super.onPause()
    }

    override fun onDestroy() {
        gen++
        releaseEditorPlayer()
        super.onDestroy()
    }
}
