package com.me.fluxbg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Draws a fixed-aspect crop window over the video preview. Drag to move, pinch to resize.
 */
class CropOverlayView(c: Context) : View(c) {
    var aspect = 0.5f
    var onChange: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private var vr = RectF()
    private var cx = 0.5f
    private var cy = 0.5f
    private var s = 1f
    private var lx = 0f
    private var ly = 0f

    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(200, 255, 255, 255); strokeWidth = d * 1.5f
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(70, 255, 255, 255); strokeWidth = d
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#5EEAD4"); strokeWidth = d * 3.5f
        strokeCap = Paint.Cap.ROUND
    }

    private val scaleDet = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            s = (s * detector.scaleFactor).coerceIn(MIN_SCALE, 1f)
            clamp()
            invalidate()
            onChange?.invoke()
            return true
        }
    })

    fun setVideoRect(r: RectF) {
        vr = r
        clamp()
        invalidate()
    }

    fun reset() {
        cx = 0.5f; cy = 0.5f; s = 1f
        invalidate()
    }

    fun setCrop(l: Float, t: Float, r: Float, b: Float) {
        if (vr.width() <= 0f || vr.height() <= 0f) return
        val (mw, mh) = maxSize()
        cx = (l + r) / 2f
        cy = (t + b) / 2f
        s = maxOf((r - l) * vr.width() / mw, (b - t) * vr.height() / mh).coerceIn(MIN_SCALE, 1f)
        clamp()
        invalidate()
    }

    fun getCrop(): FloatArray {
        val r = rect()
        if (vr.width() <= 0f || vr.height() <= 0f) return floatArrayOf(0f, 0f, 1f, 1f)
        fun nx(v: Float) = ((v - vr.left) / vr.width()).coerceIn(0f, 1f)
        fun ny(v: Float) = ((v - vr.top) / vr.height()).coerceIn(0f, 1f)
        return floatArrayOf(nx(r.left), ny(r.top), nx(r.right), ny(r.bottom))
    }

    private fun maxSize(): Pair<Float, Float> {
        val w = vr.width()
        val h = vr.height()
        return if (w / h > aspect) Pair(h * aspect, h) else Pair(w, w / aspect)
    }

    private fun rect(): RectF {
        if (vr.width() <= 0f || vr.height() <= 0f) return RectF()
        val (mw, mh) = maxSize()
        val w = mw * s
        val h = mh * s
        val px = vr.left + cx * vr.width()
        val py = vr.top + cy * vr.height()
        return RectF(px - w / 2, py - h / 2, px + w / 2, py + h / 2)
    }

    private fun clamp() {
        if (vr.width() <= 0f || vr.height() <= 0f) return
        val (mw, mh) = maxSize()
        val hw = (mw * s / 2f) / vr.width()
        val hh = (mh * s / 2f) / vr.height()
        cx = cx.coerceIn(minOf(hw, 0.5f), maxOf(1f - hw, 0.5f))
        cy = cy.coerceIn(minOf(hh, 0.5f), maxOf(1f - hh, 0.5f))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x; ly = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDet.isInProgress && e.pointerCount == 1 && vr.width() > 0f && vr.height() > 0f) {
                    cx += (e.x - lx) / vr.width()
                    cy += (e.y - ly) / vr.height()
                    clamp()
                    invalidate()
                    onChange?.invoke()
                }
                lx = e.x; ly = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = if (e.actionIndex == 0) 1 else 0
                lx = e.getX(i); ly = e.getY(i)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (vr.width() <= 0f) return
        val r = rect()
        canvas.drawRect(vr.left, vr.top, vr.right, r.top, dim)
        canvas.drawRect(vr.left, r.bottom, vr.right, vr.bottom, dim)
        canvas.drawRect(vr.left, r.top, r.left, r.bottom, dim)
        canvas.drawRect(r.right, r.top, vr.right, r.bottom, dim)
        canvas.drawRect(r, border)
        for (i in 1..2) {
            val gx = r.left + r.width() * i / 3f
            val gy = r.top + r.height() * i / 3f
            canvas.drawLine(gx, r.top, gx, r.bottom, grid)
            canvas.drawLine(r.left, gy, r.right, gy, grid)
        }
        val len = d * 18f
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            canvas.drawLine(x, y, x + dx * len, y, cornerPaint)
            canvas.drawLine(x, y, x, y + dy * len, cornerPaint)
        }
        corner(r.left, r.top, 1f, 1f)
        corner(r.right, r.top, -1f, 1f)
        corner(r.left, r.bottom, 1f, -1f)
        corner(r.right, r.bottom, -1f, -1f)
    }

    companion object {
        private const val MIN_SCALE = 0.2f
    }
}
