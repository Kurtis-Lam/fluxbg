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
import kotlin.math.abs
import kotlin.math.min

/**
 * Video-editor style timeline: thumbnail strip, draggable in/out handles and a scrub playhead.
 */
class TrimBarView(c: Context) : View(c) {
    interface Listener {
        fun onTrimChanged(startMs: Long, endMs: Long, handle: Int)
        fun onSeek(ms: Long)
    }

    var listener: Listener? = null

    private val d = resources.displayMetrics.density
    private var dur = 0L
    private var startMs = 0L
    private var endMs = 0L
    private var posMs = 0L
    private var thumbs = arrayOfNulls<Bitmap>(0)
    private var active = -1

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

    fun setRange(duration: Long, s: Long, e: Long) {
        dur = duration; startMs = s; endMs = e; posMs = s
        invalidate()
    }

    fun currentPosition(): Long = posMs.coerceIn(startMs, endMs)

    fun setPosition(p: Long) {
        posMs = p
        invalidate()
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

        canvas.save()
        val clip = Path().apply { addRoundRect(track, d * 6f, d * 6f, Path.Direction.CW) }
        canvas.clipPath(clip)
        if (thumbs.isNotEmpty()) {
            val segW = (r - l) / thumbs.size
            for (i in thumbs.indices) {
                val b = thumbs[i] ?: continue
                val dst = RectF(l + i * segW, top, l + (i + 1) * segW + 1f, bottom)
                val dstAspect = dst.width() / dst.height()
                val srcAspect = b.width.toFloat() / b.height
                val src = if (srcAspect > dstAspect) {
                    val w = (b.height * dstAspect).toInt()
                    val x0 = (b.width - w) / 2
                    Rect(x0, 0, x0 + w, b.height)
                } else {
                    val h = (b.width / dstAspect).toInt()
                    val y0 = (b.height - h) / 2
                    Rect(0, y0, b.width, y0 + h)
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

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (dur <= 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val xs = xOf(startMs)
                val xe = xOf(endMs)
                val dl = abs(e.x - (xs - hw / 2))
                val dr = abs(e.x - (xe + hw / 2))
                val slop = d * 26f
                active = when {
                    min(dl, dr) <= slop -> if (dl <= dr) 0 else 1
                    else -> 2
                }
                if (active == 2) seekTo(e.x)
            }
            MotionEvent.ACTION_MOVE -> when (active) {
                0 -> {
                    startMs = tOf(e.x + hw / 2).coerceIn(0L, endMs - minGap())
                    posMs = startMs
                    listener?.onTrimChanged(startMs, endMs, 0)
                    invalidate()
                }
                1 -> {
                    endMs = tOf(e.x - hw / 2).coerceIn(startMs + minGap(), dur)
                    posMs = endMs
                    listener?.onTrimChanged(startMs, endMs, 1)
                    invalidate()
                }
                2 -> seekTo(e.x)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> active = -1
        }
        return true
    }

    private fun seekTo(x: Float) {
        posMs = tOf(x).coerceIn(startMs, endMs)
        listener?.onSeek(posMs)
        invalidate()
    }
}
