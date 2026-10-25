package com.me.fluxbg

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Editor for one stored image or video: preview, crop, timeline trim (video only), and applying it
 * as a wallpaper. After applying, the editor closes and returns to the library.
 */
class EditorActivity : Activity(), TextureView.SurfaceTextureListener {

    companion object {
        const val EXTRA_FILE = "file"
        const val EXTRA_MSG = "msg"
        private const val REQ_STORAGE = 7
        private const val RES_OK = 1
        private const val RES_UNVERIFIED = 2
    }

    private enum class Target { HOME, LOCK, BOTH }

    private var thumbCount = 12
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ui: Ui

    private lateinit var file: String
    private var isImage = false
    private var ready = false
    private var busy = false
    private var target = Target.HOME
    private var durationMs = 0L
    private var startMs = 0L
    private var endMs = 0L
    private var vidW = 0
    private var vidH = 0

    private var mp: MediaPlayer? = null
    private var surf: Surface? = null
    private var prepared = false
    private var wantPlay = true
    @Volatile private var gen = 0

    private var afterPermission: (() -> Unit)? = null
    private var afterDenied: (() -> Unit)? = null

    private lateinit var segViews: List<TextView>
    private lateinit var videoOnly: List<View>
    private lateinit var titleLabel: TextView
    private lateinit var cropHint: TextView
    private lateinit var trimHint: TextView
    private lateinit var preview: PreviewFrame
    private lateinit var tv: TextureView
    private var imageView: ImageView? = null
    private lateinit var crop: CropOverlayView
    private lateinit var trimScroll: HorizontalScrollView
    private lateinit var trim: TrimBarView
    private lateinit var playBtn: TextView
    private lateinit var posLabel: TextView
    private lateinit var inLabel: TextView
    private lateinit var lenLabel: TextView
    private lateinit var outLabel: TextView
    private lateinit var primaryBtn: TextView
    private lateinit var secondaryBtn: TextView

    private val tick = object : Runnable {
        override fun run() {
            val p = mp ?: return
            if (!wantPlay || !prepared) return
            try {
                val pos = p.currentPosition.toLong()
                if (pos >= endMs - 30) p.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                val shown = pos.coerceIn(startMs, endMs)
                trim.setPosition(shown, true)
                posLabel.text = "${fmt(shown)} / ${fmt(durationMs)}"
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 33)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun fmt(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        val m = t / 60000
        val s = (t % 60000) / 1000
        val d = (t % 1000) / 100
        return "$m:${s.toString().padStart(2, '0')}.$d"
    }

    private fun info(title: String, msg: String) = FluxDialog.info(this, title, msg)

    private fun slots(): List<String> = when (target) {
        Target.HOME -> listOf(Slot.HOME)
        Target.LOCK -> listOf(Slot.LOCK)
        Target.BOTH -> listOf(Slot.HOME, Slot.LOCK)
    }

    private fun targetNames(): String = when (target) {
        Target.HOME -> "home screen"
        Target.LOCK -> "lock screen"
        Target.BOTH -> "home screen and lock screen"
    }

    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun viewportPx() = resources.displayMetrics.widthPixels - ui.dp(68)

    /** Closes the editor and returns to the library, optionally with a short message to show there. */
    private fun finishHome(msg: String? = null) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_MSG, msg))
        finish()
    }

    // ---------------------------------------------------------------- UI

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val f = intent.getStringExtra(EXTRA_FILE)
        if (f == null || !File(filesDir, f).exists()) {
            finish()
            return
        }
        file = f
        isImage = Store.isImage(f)
        ui = Ui(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        buildUi()

        val saved = Store.loadVideo(this, file)
            ?: Store.load(this, Slot.HOME)?.takeIf { it.file == file }
            ?: Store.load(this, Slot.LOCK)?.takeIf { it.file == file }

        val usedSlots = Store.slotsUsing(this, file)
        target = if (usedSlots.contains(Slot.LOCK) && !usedSlots.contains(Slot.HOME)) Target.LOCK else Target.HOME

        if (isImage) {
            wantPlay = false
            val bmp = Images.decode(File(filesDir, file), 2048)
            if (bmp == null) {
                FluxDialog(this).icon("!").title("Image not supported")
                    .message("fluxbg couldn’t open this image.")
                    .button("OK", FluxDialog.PRIMARY) { finish() }
                    .cancelable(false).show()
                return
            }
            vidW = bmp.width
            vidH = bmp.height
            imageView?.setImageBitmap(bmp)
            preview.setVideoSize(vidW, vidH)
            saved?.let { crop.setCrop(it.cl, it.ct, it.cr, it.cb) }
            updateTargetUi()
            ready = true
            return
        }

        if (!readMetadata()) {
            FluxDialog(this).icon("!").title("Video not supported")
                .message("This video format isn’t supported.")
                .button("OK", FluxDialog.PRIMARY) { finish() }
                .cancelable(false).show()
            return
        }
        startMs = (saved?.startMs ?: 0L).coerceIn(0L, (durationMs - 500L).coerceAtLeast(0L))
        val savedEnd = saved?.endMs ?: 0L
        endMs = if (savedEnd > startMs && savedEnd < durationMs) savedEnd else durationMs

        preview.setVideoSize(vidW, vidH)
        saved?.let { crop.setCrop(it.cl, it.ct, it.cr, it.cb) }
        trim.setRange(durationMs, startMs, endMs, viewportPx())
        trimHint.text = if (durationMs > TrimBarView.WINDOW_MS) {
            "Showing 10 s at a time  ·  Swipe the timeline to scroll  ·  Tap to seek"
        } else {
            "Drag the handles to trim  ·  Tap the bar to seek"
        }
        updateTimes(startMs)
        playBtn.text = "❚❚"
        updateTargetUi()
        loadThumbs()
        ready = true
    }

    private fun buildUi() {
        val dm = resources.displayMetrics
        val root = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            setOnApplyWindowInsetsListener { v, ins ->
                @Suppress("DEPRECATION")
                v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
                ins
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(32))
        }
        root.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val back = ui.label("‹  My library", 15f, Ui.A, true).apply {
            setPadding(0, ui.dp(8), ui.dp(16), ui.dp(8))
            setOnClickListener { finish() }
        }
        col.addView(back, ui.lp(-2, -2))
        titleLabel = ui.label(if (isImage) "Edit image" else "Edit video", 28f, Ui.TEXT, true)
        col.addView(titleLabel, ui.lp(-2, -2, 2))

        // Editor card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.rounded(Ui.CARD, 20)
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        }
        col.addView(card, ui.lp(-1, -2, 16))

        preview = PreviewFrame(this).apply { setBackgroundColor(Color.BLACK) }
        if (isImage) {
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
            imageView = iv
            preview.addView(iv, FrameLayout.LayoutParams(-1, -1))
        } else {
            tv = TextureView(this).apply { surfaceTextureListener = this@EditorActivity }
            preview.addView(tv, FrameLayout.LayoutParams(-1, -1))
        }
        val sm = realMetrics()
        crop = CropOverlayView(this).apply {
            aspect = sm.widthPixels.toFloat() / sm.heightPixels.toFloat()
        }
        preview.addView(crop, FrameLayout.LayoutParams(-1, -1))
        card.addView(preview, ui.lp(-1, (dm.heightPixels * 0.46f).toInt()))

        cropHint = ui.label("Drag the sides or corners to resize  ·  Drag inside to move  ·  Pinch works too", 12f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
        }
        card.addView(cropHint, ui.lp(-1, -2, 8))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playBtn = ui.label("▶", 18f, Ui.DARK, true).apply {
            gravity = Gravity.CENTER
            background = ui.ripple(ui.gradient(24))
            setOnClickListener { setPlaying(!wantPlay) }
        }
        controls.addView(playBtn, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        posLabel = ui.label("0:00.0 / 0:00.0", 14f, Ui.TEXT, true).apply { setPadding(ui.dp(12), 0, 0, 0) }
        controls.addView(posLabel, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(controls, ui.lp(-1, -2, 12))

        trim = TrimBarView(this).apply {
            listener = object : TrimBarView.Listener {
                override fun onTrimChanged(startMs: Long, endMs: Long, handle: Int) {
                    this@EditorActivity.startMs = startMs
                    this@EditorActivity.endMs = endMs
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
        trimScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        trimScroll.addView(trim, FrameLayout.LayoutParams(viewportPx(), ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(trimScroll, ui.lp(-1, ui.dp(76), 14))

        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        inLabel = ui.label("IN 0:00.0", 12f, Ui.MUTED, true)
        lenLabel = ui.label("LENGTH 0:00.0", 12f, Ui.A, true).apply { gravity = Gravity.CENTER }
        outLabel = ui.label("OUT 0:00.0", 12f, Ui.MUTED, true).apply { gravity = Gravity.END }
        times.addView(inLabel, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(lenLabel, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(outLabel, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(times, ui.lp(-1, -2, 4))

        trimHint = ui.label("", 11f, Ui.MUTED).apply { gravity = Gravity.CENTER }
        card.addView(trimHint, ui.lp(-1, -2, 6))

        videoOnly = listOf(controls, trimScroll, times, trimHint)
        if (isImage) videoOnly.forEach { it.visibility = View.GONE }

        // Target selector
        col.addView(ui.label("Apply to", 13f, Ui.MUTED, true), ui.lp(-2, -2, 20))
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ui.rounded(Ui.CARD, 16)
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        }
        val names = listOf("Home screen", "Lock screen", "Both")
        segViews = names.mapIndexed { i, n ->
            ui.label(n, 14f, Ui.MUTED, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(11), 0, ui.dp(11))
                setOnClickListener { setTarget(Target.values()[i]) }
            }.also { seg.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        col.addView(seg, ui.lp(-1, -2, 8))

        // Actions
        primaryBtn = ui.button("", true).apply { setOnClickListener { onPrimary() } }
        col.addView(primaryBtn, ui.lp(-1, -2, 16))
        secondaryBtn = ui.button("", false).apply { setOnClickListener { onSecondary() } }
        col.addView(secondaryBtn, ui.lp(-1, -2, 10))

        setContentView(root)
    }

    private fun updateTargetUi() {
        segViews.forEachIndexed { i, v ->
            val on = i == target.ordinal
            v.setTextColor(if (on) Ui.DARK else Ui.MUTED)
            v.background = if (on) ui.gradient(12) else null
        }
        val a = if (busy) 0.6f else 1f
        primaryBtn.alpha = a
        secondaryBtn.alpha = a
        if (busy) {
            primaryBtn.text = "Applying…"
            return
        }
        when (target) {
            Target.HOME -> {
                primaryBtn.text = "Set as home screen wallpaper"
                secondaryBtn.visibility = View.GONE
            }
            Target.LOCK -> {
                primaryBtn.text = "Set as lock screen wallpaper"
                secondaryBtn.text = "Try live video on lock screen"
                secondaryBtn.visibility = if (isImage) View.GONE else View.VISIBLE
            }
            Target.BOTH -> {
                primaryBtn.text = if (isImage) "Set on both screens" else "Set live wallpaper on both screens"
                secondaryBtn.text = "Also set still frame on lock screen"
                secondaryBtn.visibility = if (isImage) View.GONE else View.VISIBLE
            }
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        updateTargetUi()
    }

    private fun setTarget(t: Target) {
        if (t == target) return
        target = t
        updateTargetUi()
    }

    // ---------------------------------------------------------------- apply buttons

    /** Always asks before replacing the wallpaper that is currently set. */
    private fun confirmApply(names: String, extra: String?, go: () -> Unit) {
        val msg = "This will overwrite your current $names wallpaper." + (if (extra != null) "\n\n$extra" else "")
        FluxDialog(this).icon("↻").title("Apply wallpaper?").message(msg)
            .button("Cancel", FluxDialog.SECONDARY)
            .button("Apply", FluxDialog.PRIMARY) { go() }
            .show()
    }

    private fun onPrimary() {
        if (busy) return
        if (isImage) {
            confirmApply(targetNames(), null) { applyImage() }
            return
        }
        when (target) {
            Target.HOME, Target.BOTH ->
                confirmApply(targetNames(), "You will confirm the live wallpaper on the next screen.") { applyLive() }
            Target.LOCK ->
                confirmApply(targetNames(), "A still frame at the playhead is used for the lock screen.") { applyStill() }
        }
    }

    private fun onSecondary() {
        if (busy) return
        when (target) {
            Target.LOCK ->
                confirmApply("lock screen", "You will confirm the live wallpaper on the next screen.") { applyLive() }
            Target.BOTH ->
                confirmApply("lock screen", "A still frame at the playhead replaces the lock screen picture.") { applyStill() }
            Target.HOME -> Unit
        }
    }

    // ---------------------------------------------------------------- video info

    private fun readMetadata(): Boolean {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(File(filesDir, file).absolutePath)
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            vidW = w
            vidH = h
        } catch (_: Exception) {
            durationMs = 0L
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
        return durationMs > 0L && vidW > 0 && vidH > 0
    }

    private fun loadThumbs() {
        val g = ++gen
        val dur = durationMs
        val n = if (dur <= TrimBarView.WINDOW_MS) 12 else (dur / 1500L).toInt().coerceIn(12, 150)
        thumbCount = n
        trim.clearThumbs(n)
        Thread {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(File(filesDir, file).absolutePath)
                for (i in 0 until n) {
                    if (g != gen) break
                    val t = ((i + 0.5) / n * dur * 1000).toLong()
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

    // ---------------------------------------------------------------- preview playback

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surf?.release()
        surf = Surface(st)
        startPlayer()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        releasePlayer()
        surf?.release()
        surf = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private fun startPlayer() {
        releasePlayer()
        val s = surf ?: return
        val p = MediaPlayer()
        mp = p
        try {
            p.setDataSource(File(filesDir, file).absolutePath)
            p.setSurface(s)
            p.setVolume(0f, 0f)
            p.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) {
                    vidW = w
                    vidH = h
                    preview.setVideoSize(w, h)
                }
            }
            p.setOnPreparedListener { pl ->
                prepared = true
                pl.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                if (wantPlay) startPlayback()
            }
            p.setOnCompletionListener { pl ->
                try {
                    pl.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                    if (wantPlay) pl.start()
                } catch (_: Exception) {
                }
            }
            p.setOnErrorListener { _, what, extra ->
                prepared = false
                wantPlay = false
                playBtn.text = "▶"
                info("Couldn’t play this video", "Playback failed ($what/$extra).")
                true
            }
            p.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
            wantPlay = false
            playBtn.text = "▶"
            info("Couldn’t open that video", "fluxbg couldn’t start playback for this file.")
        }
    }

    private fun releasePlayer() {
        handler.removeCallbacks(tick)
        prepared = false
        try { mp?.release() } catch (_: Exception) {}
        mp = null
    }

    private fun startPlayback() {
        val p = mp ?: return
        if (!prepared) return
        try {
            val pos = p.currentPosition
            if (pos < startMs || pos >= endMs - 30) p.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
            p.start()
            handler.removeCallbacks(tick)
            handler.post(tick)
        } catch (_: Exception) {
        }
    }

    private fun pausePlayback() {
        handler.removeCallbacks(tick)
        try {
            if (prepared) mp?.pause()
        } catch (_: Exception) {
        }
    }

    private fun setPlaying(on: Boolean) {
        wantPlay = on
        playBtn.text = if (on) "❚❚" else "▶"
        if (on) startPlayback() else pausePlayback()
    }

    private fun safeSeek(ms: Long) {
        try {
            if (prepared) mp?.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
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

    // ---------------------------------------------------------------- applying

    private fun currentConfig(): Config {
        val c = crop.getCrop()
        if (isImage) return Config(file, 0L, 0L, c[0], c[1], c[2], c[3], 0L)
        return Config(file, startMs, if (endMs >= durationMs) 0L else endMs, c[0], c[1], c[2], c[3], 0L)
    }

    /** Live video: saves the settings, opens the system live wallpaper preview, and returns to the library. */
    private fun applyLive() {
        val cfg = currentConfig()
        Store.saveVideo(this, file, cfg)
        slots().forEach { Store.save(this, it, cfg) }
        val comp = ComponentName(this, FluxWallpaperService::class.java)
        try {
            startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, comp)
            )
            finishHome()
        } catch (_: Exception) {
            FluxDialog(this).icon("▶").title("Choose fluxbg")
                .message("Your phone can’t open the preview directly. Pick fluxbg from the live wallpaper list on the next screen.")
                .button("Cancel", FluxDialog.SECONDARY)
                .button("Open list", FluxDialog.PRIMARY) {
                    try {
                        startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                        finishHome()
                    } catch (_: Exception) {
                        info("No wallpaper picker", "This device has no live wallpaper picker. Try Settings > Home screen & wallpaper.")
                    }
                }
                .show()
        }
    }

    /** Still image: crops it to the screen and sets it directly, without the system preview. */
    private fun applyImage() {
        Store.saveVideo(this, file, currentConfig())
        val c = crop.getCrop()
        val dm = realMetrics()
        val f = File(filesDir, file)
        val flags = when (target) {
            Target.HOME -> WallpaperManager.FLAG_SYSTEM
            Target.LOCK -> WallpaperManager.FLAG_LOCK
            Target.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        val msg = when (target) {
            Target.HOME -> "Home screen updated"
            Target.LOCK -> "Lock screen updated"
            Target.BOTH -> "Home and lock screens updated"
        }
        val sl = slots()
        applyBitmap(flags, msg, { Images.render(f, c, dm.widthPixels, dm.heightPixels) }) {
            sl.forEach { Store.saveImage(this, it, file) }
        }
    }

    /** Video: renders the cropped frame at the playhead and sets it as the lock screen picture. */
    private fun applyStill() {
        Store.saveVideo(this, file, currentConfig())
        val c = crop.getCrop()
        val posUs = trim.currentPosition() * 1000L
        val dm = realMetrics()
        applyBitmap(WallpaperManager.FLAG_LOCK, "Lock screen updated", {
            renderFrame(posUs, c, dm.widthPixels, dm.heightPixels)
        }) {}
    }

    /**
     * Renders a bitmap off the main thread and sets it with WallpaperManager. For the lock screen the
     * result is verified, because some phones accept the call but keep showing the old picture; in
     * that case (or if it is blocked) the user is offered the system "Set as" screen instead.
     */
    private fun applyBitmap(flags: Int, doneMsg: String, render: () -> Bitmap, onApplied: () -> Unit) {
        val lock = (flags and WallpaperManager.FLAG_LOCK) != 0
        val go = {
            setBusy(true)
            Thread {
                var bmp: Bitmap? = null
                var err = ""
                try {
                    bmp = render()
                } catch (t: Throwable) {
                    err = t.javaClass.simpleName
                }
                val frame = bmp
                val res = if (frame != null) setWallpaperBitmap(frame, flags) else 0
                runOnUiThread {
                    setBusy(false)
                    if (frame == null) {
                        info("Couldn’t prepare the picture", "Something went wrong while cropping ($err). Try a different crop.")
                        return@runOnUiThread
                    }
                    when {
                        res == RES_OK -> {
                            onApplied()
                            finishHome(doneMsg)
                        }
                        lock -> {
                            if (res == RES_UNVERIFIED) onApplied()
                            lockFallbackDialog(frame, res == RES_UNVERIFIED) { finishHome(doneMsg) }
                        }
                        else -> info("Couldn’t set the wallpaper", "Your phone didn’t let fluxbg change the home screen wallpaper.")
                    }
                }
            }.start()
        }
        if (lock && !hasStorage()) ensureStorage(go, go) else go()
    }

    /** 0 = failed, RES_OK = set (and confirmed for the lock screen), RES_UNVERIFIED = set but not confirmed. */
    private fun setWallpaperBitmap(frame: Bitmap, flags: Int): Int {
        try {
            val wm = WallpaperManager.getInstance(this)
            if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) return 0
            val id = wm.setBitmap(frame, null, true, flags)
            if (id == 0) return 0
            if ((flags and WallpaperManager.FLAG_LOCK) != 0 && wm.getWallpaperId(WallpaperManager.FLAG_LOCK) != id) {
                return RES_UNVERIFIED
            }
            return RES_OK
        } catch (_: Throwable) {
            return 0
        }
    }

    private fun lockFallbackDialog(frame: Bitmap, unverified: Boolean, done: () -> Unit) {
        val d = FluxDialog(this).icon("🔒")
        if (unverified) {
            d.title("Check your lock screen")
                .message("fluxbg set the picture, but your phone didn’t confirm the change. Some lock screens override apps. If it still looks the same, send the picture to the Set as screen and choose Lock screen.")
                .button("It worked", FluxDialog.SECONDARY) { done() }
        } else {
            d.title("Couldn’t set it directly")
                .message("Your phone blocked fluxbg from changing the lock screen. You can send the picture to the Set as screen and choose Lock screen there.")
                .button("Cancel", FluxDialog.SECONDARY)
        }
        d.button("Use Set as", FluxDialog.PRIMARY) { openSetAsFlow(frame) }.show()
    }

    // ---------------------------------------------------------------- storage permission (Android 9 and older)

    private fun hasStorage() =
        Build.VERSION.SDK_INT >= 29 ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Asks for storage access when it is needed (Android 9 and older), then runs [go]. */
    private fun ensureStorage(go: () -> Unit, declined: (() -> Unit)? = null) {
        if (hasStorage()) {
            go()
            return
        }
        FluxDialog(this).icon("🔒").title("Allow storage access")
            .message("To hand the picture to your phone’s Set as screen, fluxbg first saves it to your gallery. Tap Allow, then choose Allow in the next prompt.")
            .button("Not now", FluxDialog.SECONDARY) { declined?.invoke() }
            .button("Allow", FluxDialog.PRIMARY) {
                afterPermission = go
                afterDenied = declined
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STORAGE) return
        val go = afterPermission
        val denied = afterDenied
        afterPermission = null
        afterDenied = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            go?.invoke()
        } else if (denied != null) {
            denied()
        } else {
            FluxDialog(this).icon("🔒").title("Storage access denied")
                .message("Without it fluxbg can’t hand the picture to the Set as screen. You can allow it in Settings > Apps > fluxbg > Permissions.")
                .button("Not now", FluxDialog.SECONDARY)
                .button("Open settings", FluxDialog.PRIMARY) {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", packageName, null))
                        )
                    } catch (_: Exception) {
                    }
                }
                .show()
        }
    }

    // ---------------------------------------------------------------- "Set as" fallback

    private fun openSetAsFlow(frame: Bitmap) {
        ensureStorage({
            Thread {
                val uri = try { saveToGallery(frame) } catch (_: Throwable) { null }
                runOnUiThread {
                    if (uri != null) {
                        openSetAs(uri)
                    } else {
                        info("Couldn’t save the picture", "fluxbg couldn’t save the picture to your gallery, so it can’t open the Set as screen.")
                    }
                }
            }.start()
        })
    }

    private fun renderFrame(posUs: Long, c: FloatArray, sw: Int, sh: Int): Bitmap {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(File(filesDir, file).absolutePath)
            val first = try {
                r.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (_: Throwable) {
                null
            }
            val frame = first
                ?: r.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IllegalStateException("no frame")
            val fit = Store.fit(c[0], c[1], c[2], c[3], frame.width, frame.height, sw, sh)
            val l = (fit[0] * frame.width).toInt().coerceIn(0, frame.width - 1)
            val t = (fit[1] * frame.height).toInt().coerceIn(0, frame.height - 1)
            val rr = (fit[2] * frame.width).toInt().coerceIn(l + 1, frame.width)
            val bb = (fit[3] * frame.height).toInt().coerceIn(t + 1, frame.height)
            val part = Bitmap.createBitmap(frame, l, t, rr - l, bb - t)
            val out = Bitmap.createScaledBitmap(part, sw, sh, true)
            if (part !== out) part.recycle()
            if (frame !== part && frame !== out) frame.recycle()
            return out
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    private fun saveToGallery(bmp: Bitmap): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "fluxbg_lock_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/fluxbg")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
        if (!ok) {
            contentResolver.delete(uri, null, null)
            return null
        }
        if (Build.VERSION.SDK_INT >= 29) {
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    /** Opens the system "Set as" sheet, where Huawei lets you choose Lock screen, then returns to the library. */
    private fun openSetAs(uri: Uri) {
        val i = Intent(Intent.ACTION_ATTACH_DATA)
            .setDataAndType(uri, "image/png")
            .putExtra("mimeType", "image/png")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(i, "Set as lock screen"))
            finishHome()
        } catch (_: Exception) {
            info("Saved to your gallery", "The picture was saved to Pictures/fluxbg. Open it in Gallery and use Set as > Lock screen.")
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        if (!isImage && wantPlay) startPlayback()
    }

    override fun onPause() {
        pausePlayback()
        if (ready && ::crop.isInitialized) Store.saveVideo(this, file, currentConfig())
        super.onPause()
    }

    override fun onDestroy() {
        gen++
        releasePlayer()
        super.onDestroy()
    }
}