package com.me.fluxbg

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * App-styled pop-up used everywhere instead of the system AlertDialog and Toast: a dark rounded
 * card with an icon badge, a title, a message, optional tappable option rows and buttons.
 */
class FluxDialog(private val act: Activity) {

    companion object {
        const val PRIMARY = 0
        const val SECONDARY = 1
        const val DANGER = 2

        fun info(act: Activity, title: String, message: String, icon: String = "!") {
            FluxDialog(act).icon(icon).title(title).message(message).button("OK", PRIMARY).show()
        }
    }

    private class Btn(val text: String, val style: Int, val onClick: (() -> Unit)?)
    private class Opt(val icon: String, val title: String, val sub: String, val onClick: () -> Unit)

    private val ui = Ui(act)
    private var iconText = "!"
    private var titleText = ""
    private var messageText: String? = null
    private var cancelable = true
    private val buttons = mutableListOf<Btn>()
    private val options = mutableListOf<Opt>()

    fun icon(i: String) = apply { iconText = i }
    fun title(t: String) = apply { titleText = t }
    fun message(m: String) = apply { messageText = m }
    fun cancelable(c: Boolean) = apply { cancelable = c }
    fun option(icon: String, title: String, sub: String, onClick: () -> Unit) =
        apply { options.add(Opt(icon, title, sub, onClick)) }

    fun button(text: String, style: Int = SECONDARY, onClick: (() -> Unit)? = null) =
        apply { buttons.add(Btn(text, style, onClick)) }

    fun show(): Dialog? {
        if (act.isFinishing || act.isDestroyed) return null
        val dlg = Dialog(act)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val danger = buttons.any { it.style == DANGER }
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(22), ui.dp(24), ui.dp(22), ui.dp(20))
            background = GradientDrawable().apply {
                setColor(Ui.CARD)
                cornerRadius = ui.dp(26).toFloat()
                setStroke(ui.dp(1), Color.parseColor("#252D3C"))
            }
        }

        val badge = ui.label(iconText, 22f, if (danger) Ui.DANGER else Ui.DARK, true).apply {
            gravity = Gravity.CENTER
            background = if (danger) {
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(38, 255, 107, 122)) }
            } else {
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.A, Ui.B))
                    .apply { shape = GradientDrawable.OVAL }
            }
        }
        card.addView(badge, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))

        card.addView(ui.label(titleText, 20f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }, ui.lp(-1, -2, 14))

        messageText?.let {
            val m = ui.label(it, 14f, Ui.MUTED).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.2f)
            }
            card.addView(m, ui.lp(-1, -2, 8))
        }

        options.forEachIndexed { i, o ->
            card.addView(optionRow(o, dlg), ui.lp(-1, -2, if (i == 0) 18 else 10))
        }

        if (buttons.isNotEmpty()) {
            val top = if (options.isEmpty()) 22 else 16
            if (buttons.size <= 2) {
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                buttons.forEachIndexed { i, b ->
                    row.addView(buttonView(b, dlg), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) leftMargin = ui.dp(10)
                    })
                }
                card.addView(row, ui.lp(-1, -2, top))
            } else {
                buttons.forEachIndexed { i, b ->
                    card.addView(buttonView(b, dlg), ui.lp(-1, -2, if (i == 0) top else 10))
                }
            }
        }

        dlg.setContentView(card)
        dlg.setCancelable(cancelable)
        dlg.setCanceledOnTouchOutside(cancelable)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = minOf(act.resources.displayMetrics.widthPixels - ui.dp(40), ui.dp(400))
            setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.65f)
            setWindowAnimations(android.R.style.Animation_Dialog)
        }
        dlg.show()
        return dlg
    }

    private fun optionRow(o: Opt, dlg: Dialog): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            background = ui.ripple(ui.rounded(Ui.CARD2, 18))
            setOnClickListener {
                dlg.dismiss()
                o.onClick()
            }
        }
        val ic = ui.label(o.icon, 22f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER
            background = ui.rounded(Ui.CARD, 14)
        }
        row.addView(ic, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))

        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.label(o.title, 16f, Ui.TEXT, true))
        col.addView(ui.label(o.sub, 12f, Ui.MUTED), ui.lp(-2, -2, 2))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(14) })

        row.addView(ui.label("›", 24f, Ui.MUTED, true))
        return row
    }

    private fun buttonView(b: Btn, dlg: Dialog): TextView {
        val bg = when (b.style) {
            PRIMARY -> ui.gradient(16)
            DANGER -> ui.rounded(Ui.DANGER, 16)
            else -> ui.rounded(Ui.CARD2, 16)
        }
        val fg = if (b.style == SECONDARY) Ui.TEXT else Ui.DARK
        return ui.label(b.text, 15f, fg, true).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            background = ui.ripple(bg)
            setOnClickListener {
                dlg.dismiss()
                b.onClick?.invoke()
            }
        }
    }
}