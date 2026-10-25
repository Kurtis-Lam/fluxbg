package com.me.fluxbg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

/**
 * Draws a fixed-aspect crop window over the preview. Drag a side or corner to resize it, drag
 * inside to move it, or pinch to resize. The window keeps the screen's aspect ratio, so dragging
 * a side scales the whole window while the opposite side stays where it is.
 */
class CropOverlayView(c: Context) : View(c) {
    var aspect = 0.5f
    var onChange: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private var vr = RectF()
    private var pending: FloatArray? = null
    private var cx = 0.5f
    private var cy = 0.5f
    private var s = 1f
    private var lx = 0f
    private var ly = 0f

    private var edges = 0
    private var offX = 0f
    private var offY = 0f

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

    /** The overlay always covers exactly the video, so its own bounds are the video rectangle. */
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val prev = if (vr.width() > 0f && vr.height() > 0f) getCrop() else pending
        vr = RectF(0f, 0f, w.toFloat(), h.toFloat())
        pending = null
        if (prev != null) applyCrop(prev[0], prev[1], prev[2], prev[3]) else clamp()
        invalidate()
    }

    fun reset() {
        pending = null
        cx = 0.5f; cy = 0.5f; s = 1f
        invalidate()
    }

    fun setCrop(l: Float, t: Float, r: Float, b: Float) {
        if (vr.width() <= 0f || vr.height() <= 0f) {
            pending = floatArrayOf(l, t, r, b)
            return
        }
        applyCrop(l, t, r, b)
        invalidate()
    }

    private fun applyCrop(l: Float, t: Float, r: Float, b: Float) {
        val (mw, mh) = maxSize()
        cx = (l + r) / 2f
        cy = (t + b) / 2f
        s = maxOf((r - l) * vr.width() / mw, (b - t) * vr.height() / mh).coerceIn(MIN_SCALE, 1f)
        clamp()
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

    // ------------------------------------------------------------ side / corner dragging

    private fun has(flag: Int) = (edges and flag) != 0

    /** Which sides of the crop window the finger is on (a bit mask of E_L, E_T, E_R, E_B), or 0 for none. */
    private fun hitEdges(x: Float, y: Float): Int {
        val r = rect()
        if (r.width() <= 0f) return 0
        val slop = minOf(d * 28f, minOf(r.width(), r.height()) / 3f)
        val inY = y >= r.top - slop && y <= r.bottom + slop
        val inX = x >= r.left - slop && x <= r.right + slop
        var m = 0
        if (inY) {
            if (abs(x - r.left) <= slop) m = m or E_L else if (abs(x - r.right) <= slop) m = m or E_R
        }
        if (inX) {
            if (abs(y - r.top) <= slop) m = m or E_T else if (abs(y - r.bottom) <= slop) m = m or E_B
        }
        return m
    }

    /** Resizes the window so the grabbed side(s) follow the finger and the opposite side(s) stay fixed. */
    private fun resizeTo(px: Float, py: Float) {
        val r = rect()
        if (r.width() <= 0f) return
        val (mw, mh) = maxSize()
        val horiz = has(E_L) || has(E_R)
        val vert = has(E_T) || has(E_B)

        val wantW = if (has(E_L)) r.right - px else px - r.left
        val wantH = if (has(E_T)) r.bottom - py else py - r.top
        var ns = MIN_SCALE
        if (horiz) ns = maxOf(ns, wantW / mw)
        if (vert) ns = maxOf(ns, wantH / mh)

        var maxS = 1f
        if (has(E_L)) maxS = minOf(maxS, (r.right - vr.left) / mw)
        if (has(E_R)) maxS = minOf(maxS, (vr.right - r.left) / mw)
        if (has(E_T)) maxS = minOf(maxS, (r.bottom - vr.top) / mh)
        if (has(E_B)) maxS = minOf(maxS, (vr.bottom - r.top) / mh)
        ns = ns.coerceIn(MIN_SCALE, maxOf(MIN_SCALE, maxS))

        val nw = mw * ns
        val nh = mh * ns
        val ncx = when {
            has(E_L) -> r.right - nw / 2f
            has(E_R) -> r.left + nw / 2f
            else -> r.centerX()
        }
        val ncy = when {
            has(E_T) -> r.bottom - nh / 2f
            has(E_B) -> r.top + nh / 2f
            else -> r.centerY()
        }
        s = ns
        cx = (ncx - vr.left) / vr.width()
        cy = (ncy - vr.top) / vr.height()
        clamp()
        invalidate()
        onChange?.invoke()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x; ly = e.y
                edges = hitEdges(e.x, e.y)
                val r = rect()
                offX = when {
                    has(E_L) -> e.x - r.left
                    has(E_R) -> e.x - r.right
                    else -> 0f
                }
                offY = when {
                    has(E_T) -> e.y - r.top
                    has(E_B) -> e.y - r.bottom
                    else -> 0f
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> edges = 0
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDet.isInProgress && e.pointerCount == 1 && vr.width() > 0f && vr.height() > 0f) {
                    if (edges != 0) {
                        resizeTo(e.x - offX, e.y - offY)
                    } else {
                        cx += (e.x - lx) / vr.width()
                        cy += (e.y - ly) / vr.height()
                        clamp()
                        invalidate()
                        onChange?.invoke()
                    }
                }
                lx = e.x; ly = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                edges = 0
                val i = if (e.actionIndex == 0) 1 else 0
                lx = e.getX(i); ly = e.getY(i)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edges = 0
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

        // Side handles: short bars in the middle of each side show they can be dragged.
        val half = minOf(d * 14f, minOf(r.width(), r.height()) / 6f)
        canvas.drawLine(r.centerX() - half, r.top, r.centerX() + half, r.top, cornerPaint)
        canvas.drawLine(r.centerX() - half, r.bottom, r.centerX() + half, r.bottom, cornerPaint)
        canvas.drawLine(r.left, r.centerY() - half, r.left, r.centerY() + half, cornerPaint)
        canvas.drawLine(r.right, r.centerY() - half, r.right, r.centerY() + half, cornerPaint)
    }

    companion object {
        private const val MIN_SCALE = 0.2f
        private const val E_L = 1
        private const val E_T = 2
        private const val E_R = 4
        private const val E_B = 8
    }
}