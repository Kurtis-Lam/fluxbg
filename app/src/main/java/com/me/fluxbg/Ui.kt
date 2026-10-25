package com.me.fluxbg

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Small view-building helpers shared by the library and editor screens. */
class Ui(private val ctx: Context) {
    companion object {
        val BG = Color.parseColor("#0B0E14")
        val CARD = Color.parseColor("#151A24")
        val CARD2 = Color.parseColor("#1D2430")
        val TEXT = Color.parseColor("#F2F4F8")
        val MUTED = Color.parseColor("#8A93A6")
        val A = Color.parseColor("#5EEAD4")
        val B = Color.parseColor("#8B9CFF")
        val DARK = Color.parseColor("#0B0E14")
        val DANGER = Color.parseColor("#FF6B7A")
    }

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    fun gradient(radiusDp: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(A, B))
            .apply { cornerRadius = dp(radiusDp).toFloat() }

    fun ripple(bg: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), bg, null)

    fun label(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    fun button(text: String, filled: Boolean): TextView =
        label(text, 15f, if (filled) DARK else TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ripple(if (filled) gradient(16) else rounded(CARD2, 16))
        }

    fun lp(w: Int, h: Int, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    /** A small rounded message that fades in at the bottom of the screen and fades out again. */
    fun snack(act: Activity, msg: String) {
        val decor = act.window.decorView as? ViewGroup ?: return
        val tv = label(msg, 14f, TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(CARD2, 16)
            elevation = dp(8).toFloat()
            alpha = 0f
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(64)
            leftMargin = dp(24)
            rightMargin = dp(24)
        }
        decor.addView(tv, lp)
        tv.animate().alpha(1f).setDuration(180).withEndAction {
            tv.postDelayed({
                tv.animate().alpha(0f).setDuration(220).withEndAction { decor.removeView(tv) }.start()
            }, 2400)
        }.start()
    }
}