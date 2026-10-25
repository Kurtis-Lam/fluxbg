package com.me.fluxbg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.min

/**
 * Video-editor style timeline: thumbnail strip, draggable in/out handles and a scrub playhead.
 *
 * Place it inside a HorizontalScrollView. It shows [WINDOW_MS] of video per screen width; longer
 * videos make the bar wider so the timeline can be scrolled sideways. Handles and the playhead are
 * dragged directly, dragging anywhere else scrolls, and a tap seeks.
 */
class TrimBarView(c: Context) : View(c) {
    interface Listener {
        fun onTrimChanged(startMs: Long, endMs: Long, handle: Int)
        fun onSeek(ms: Long)
    }

    companion object {
        const val WINDOW_MS = 10_000L
        private const val MAX_TRACK_PX = 40_000
    }

    var listener: Listener? = null

    private val d = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(c).scaledTouchSlop
    private var dur = 0L
    private var startMs = 0L
    private var endMs = 0L
    private var posMs = 0L
    private var thumbs = arrayOfNulls<Bitmap>(0)
    private var active = -1
    private var viewport = 0
    private var pendingScroll = -1L
    private var lastPlayX = -1f
    private var downX = 0f
    private var moved = false
    private var autoDir = 0
    private var fingerX = 0f

    private val hw = d * 16f
    private val accent = Color.parseColor("#5EEAD4")
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B0E14") }
    private val dimPaint = Paint().apply { color = Color.argb(185, 8, 10, 16) }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B0E14"); strokeWidth = d * 2f; strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = d * 2f
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun hsv(): HorizontalScrollView? = parent as? HorizontalScrollView

    /** Width of the bar: one screen for clips up to [WINDOW_MS], proportionally wider for longer ones. */
    private fun desiredWidth(): Int {
        if (viewport <= 0) return viewport
        if (dur <= WINDOW_MS) return viewport
        val track = (viewport - 2 * hw)
        val w = (track * dur.toFloat() / WINDOW_MS).toInt().coerceAtMost(MAX_TRACK_PX)
        return w + (2 * hw).toInt()
    }

    fun setRange(duration: Long, s: Long, e: Long, viewportPx: Int) {
        dur = duration; startMs = s; endMs = e; posMs = s; viewport = viewportPx
        val lp = layoutParams
        val w = desiredWidth()
        if (lp != null && w > 0 && lp.width != w) {
            lp.width = w
            layoutParams = lp
        }
        pendingScroll = s
        requestLayout()
        invalidate()
    }

    /**
     * A HorizontalScrollView measures its child with an UNSPECIFIED width and ignores the child's
     * layout width, so the default View measuring would give this bar a width of 0 and the
     * timeline would never appear. The width is therefore decided here.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fallback = layoutParams?.width?.takeIf { it > 0 } ?: MeasureSpec.getSize(widthMeasureSpec)
        val w = desiredWidth().takeIf { it > 0 } ?: fallback
        val h = resolveSize((d * 76f).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (pendingScroll >= 0L && width > 0) {
            scrollToTime(pendingScroll)
            pendingScroll = -1L
        }
    }

    fun scrollToTime(t: Long) {
        val h = hsv() ?: return
        val max = (width - h.width).coerceAtLeast(0)
        h.scrollTo((xOf(t) - hw * 2f).toInt().coerceIn(0, max), 0)
    }

    fun currentPosition(): Long = posMs.coerceIn(startMs, endMs)

    /** Moves the playhead. With [follow] the timeline scrolls along when playback runs off the right edge. */
    fun setPosition(p: Long, follow: Boolean = false) {
        posMs = p
        if (follow) followPlayhead(p) else lastPlayX = -1f
        invalidate()
    }

    private fun followPlayhead(t: Long) {
        val h = hsv() ?: return
        if (width <= h.width) return
        val x = xOf(t)
        val prev = lastPlayX
        lastPlayX = x
        val left = h.scrollX.toFloat()
        val right = left + h.width
        val wasVisible = prev >= left && prev <= right
        val loopedBack = prev >= 0f && x < prev - d * 40f
        if ((wasVisible && x > right - hw * 1.5f) || loopedBack) scrollToTime(t)
    }

    fun clearThumbs(n: Int) {
        thumbs = arrayOfNulls(n)
        invalidate()
    }

    fun setThumb(i: Int, b: Bitmap) {
        if (i in thumbs.indices) {
            thumbs[i] = b
            invalidate()
        }
    }

    private fun minGap() = min(500L, dur)
    private fun trackL() = hw
    private fun trackR() = width - hw
    private fun xOf(t: Long) = if (dur <= 0) trackL() else trackL() + (t.toFloat() / dur) * (trackR() - trackL())
    private fun tOf(x: Float): Long {
        val w = trackR() - trackL()
        if (w <= 0f || dur <= 0) return 0L
        return (((x - trackL()) / w) * dur).toLong().coerceIn(0L, dur)
    }

    override fun onDraw(canvas: Canvas) {
        if (dur <= 0) return
        val top = d * 10f
        val bottom = height - d * 10f
        val l = trackL()
        val r = trackR()
        val track = RectF(l, top, r, bottom)
        canvas.drawRoundRect(track, d * 6f, d * 6f, bgPaint)

        val h = hsv()
        val visL = (h?.scrollX ?: 0).toFloat() - d * 60f
        val visR = visL + d * 120f + (h?.width ?: width)

        canvas.save()
        val clip = Path().apply { addRoundRect(track, d * 6f, d * 6f, Path.Direction.CW) }
        canvas.clipPath(clip)
        if (thumbs.isNotEmpty()) {
            val segW = (r - l) / thumbs.size
            for (i in thumbs.indices) {
                val x0 = l + i * segW
                if (x0 + segW < visL || x0 > visR) continue
                val b = thumbs[i] ?: continue
                val dst = RectF(x0, top, x0 + segW + 1f, bottom)
                val dstAspect = dst.width() / dst.height()
                val srcAspect = b.width.toFloat() / b.height
                val src = if (srcAspect > dstAspect) {
                    val w = (b.height * dstAspect).toInt()
                    val xs0 = (b.width - w) / 2
                    Rect(xs0, 0, xs0 + w, b.height)
                } else {
                    val hh = (b.width / dstAspect).toInt()
                    val y0 = (b.height - hh) / 2
                    Rect(0, y0, b.width, y0 + hh)
                }
                canvas.drawBitmap(b, src, dst, bmpPaint)
            }
        }
        val xs = xOf(startMs)
        val xe = xOf(endMs)
        canvas.drawRect(l, top, xs, bottom, dimPaint)
        canvas.drawRect(xe, top, r, bottom, dimPaint)
        canvas.restore()

        val bar = d * 3f
        canvas.drawRect(xs, top, xe, top + bar, accentPaint)
        canvas.drawRect(xs, bottom - bar, xe, bottom, accentPaint)

        val hl = RectF(xs - hw, top - d * 3f, xs, bottom + d * 3f)
        val hr = RectF(xe, top - d * 3f, xe + hw, bottom + d * 3f)
        canvas.drawRoundRect(hl, d * 6f, d * 6f, accentPaint)
        canvas.drawRoundRect(hr, d * 6f, d * 6f, accentPaint)
        for (hRect in arrayOf(hl, hr)) {
            val cx = hRect.centerX()
            val cy = hRect.centerY()
            canvas.drawLine(cx - d * 2f, cy - d * 7f, cx - d * 2f, cy + d * 7f, gripPaint)
            canvas.drawLine(cx + d * 2f, cy - d * 7f, cx + d * 2f, cy + d * 7f, gripPaint)
        }

        val px = xOf(posMs.coerceIn(startMs, endMs))
        canvas.drawLine(px, d * 3f, px, height - d * 3f, headPaint)
        canvas.drawCircle(px, d * 4f, d * 4.5f, headPaint)
    }

    // ------------------------------------------------------------ touch

    private fun dragHandle(handle: Int, x: Float) {
        if (handle == 0) {
            startMs = tOf(x + hw / 2).coerceIn(0L, endMs - minGap())
            posMs = startMs
            listener?.onTrimChanged(startMs, endMs, 0)
        } else {
            endMs = tOf(x - hw / 2).coerceIn(startMs + minGap(), dur)
            posMs = endMs
            listener?.onTrimChanged(startMs, endMs, 1)
        }
        invalidate()
    }

    private val autoRun = object : Runnable {
        override fun run() {
            val h = hsv() ?: return
            if (autoDir == 0 || active < 0 || active > 2) return
            val max = (width - h.width).coerceAtLeast(0)
            val before = h.scrollX
            val next = (before + autoDir * d * 10f).toInt().coerceIn(0, max)
            if (next != before) {
                h.scrollTo(next, 0)
                val x = fingerX + next
                if (active == 2) seekTo(x) else dragHandle(active, x)
            }
            postOnAnimation(this)
        }
    }

    /** Scrolls the timeline on its own while a handle or the playhead is held near the screen edge. */
    private fun updateAutoScroll(e: MotionEvent) {
        val h = hsv() ?: return
        if (width <= h.width) return
        fingerX = e.x - h.scrollX
        val margin = d * 40f
        val dir = when {
            fingerX < margin -> -1
            fingerX > h.width - margin -> 1
            else -> 0
        }
        if (dir != autoDir) {
            autoDir = dir
            removeCallbacks(autoRun)
            if (dir != 0) postOnAnimation(autoRun)
        }
    }

    private fun stopAutoScroll() {
        autoDir = 0
        removeCallbacks(autoRun)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (dur <= 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                moved = false
                val xs = xOf(startMs)
                val xe = xOf(endMs)
                val dl = abs(e.x - (xs - hw / 2))
                val dr = abs(e.x - (xe + hw / 2))
                val dh = abs(e.x - xOf(posMs.coerceIn(startMs, endMs)))
                val slop = d * 26f
                active = when {
                    min(dl, dr) <= slop -> if (dl <= dr) 0 else 1
                    dh <= slop * 0.8f -> 2
                    else -> 3
                }
                if (active != 3) parent?.requestDisallowInterceptTouchEvent(true)
                if (active == 2) seekTo(e.x)
            }
            MotionEvent.ACTION_MOVE -> when (active) {
                0, 1 -> {
                    dragHandle(active, e.x)
                    updateAutoScroll(e)
                }
                2 -> {
                    seekTo(e.x)
                    updateAutoScroll(e)
                }
                3 -> if (abs(e.x - downX) > touchSlop) moved = true
            }
            MotionEvent.ACTION_UP -> {
                if (active == 3 && !moved) seekTo(e.x)
                stopAutoScroll()
                active = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                stopAutoScroll()
                active = -1
            }
        }
        return true
    }

    private fun seekTo(x: Float) {
        posMs = tOf(x).coerceIn(startMs, endMs)
        listener?.onSeek(posMs)
        invalidate()
    }
}