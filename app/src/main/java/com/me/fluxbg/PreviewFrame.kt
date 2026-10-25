package com.me.fluxbg

import android.content.Context
import android.widget.FrameLayout

/**
 * Lays every child out at the video's aspect ratio, centred and as large as fits. The video view
 * and the crop overlay therefore always cover exactly the same rectangle.
 */
class PreviewFrame(c: Context) : FrameLayout(c) {
    private var vw = 0
    private var vh = 0

    fun setVideoSize(w: Int, h: Int) {
        if (w == vw && h == vh) return
        vw = w
        vh = h
        requestLayout()
    }

    private fun fit(w: Int, h: Int): IntArray {
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return intArrayOf(w, h)
        val sc = minOf(w.toFloat() / vw, h.toFloat() / vh)
        return intArrayOf((vw * sc).toInt().coerceAtLeast(1), (vh * sc).toInt().coerceAtLeast(1))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val f = fit(w, h)
        val cw = MeasureSpec.makeMeasureSpec(f[0], MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(f[1], MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val f = fit(w, h)
        val left = (w - f[0]) / 2
        val top = (h - f[1]) / 2
        for (i in 0 until childCount) getChildAt(i).layout(left, top, left + f[0], top + f[1])
    }
}