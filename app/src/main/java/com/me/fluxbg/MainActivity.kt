package com.me.fluxbg

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Home screen: shows every video stored in the app. Tap one to edit it, or add a new one.
 */
class MainActivity : Activity() {

    private val reqPick = 1

    private lateinit var ui: Ui
    private lateinit var addBtn: TextView
    private lateinit var heading: TextView
    private lateinit var hint: TextView
    private lateinit var grid: LinearLayout
    private var importing = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ui = Ui(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

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
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(32))
        }
        root.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val title = ui.label("", 34f, Ui.TEXT, true)
        title.text = SpannableString("fluxbg").apply {
            setSpan(ForegroundColorSpan(Ui.A), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Ui.B), 4, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        col.addView(title)
        col.addView(ui.label("Video wallpapers for Huawei", 14f, Ui.MUTED), ui.lp(-2, -2, 2))

        addBtn = ui.button("+  Add video", true).apply { setOnClickListener { pickVideo() } }
        col.addView(addBtn, ui.lp(-1, -2, 20))

        heading = ui.label("", 16f, Ui.TEXT, true)
        col.addView(heading, ui.lp(-2, -2, 24))
        hint = ui.label("Tap a video to edit it  ·  Hold to delete", 12f, Ui.MUTED)
        col.addView(hint, ui.lp(-2, -2, 2))

        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(grid, ui.lp(-1, -2, 12))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------------------------------------------------------- library

    private fun refresh() {
        grid.removeAllViews()
        val files = Store.library(this)
        heading.text = if (files.isEmpty()) "No videos yet" else "Your videos  ·  ${files.size}"
        hint.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE

        if (files.isEmpty()) {
            grid.addView(emptyCard(), ui.lp(-1, ui.dp(200)))
            return
        }

        val dm = resources.displayMetrics
        val gap = ui.dp(12)
        val w = (dm.widthPixels - ui.dp(40) - gap) / 2
        val ratio = (dm.heightPixels.toFloat() / dm.widthPixels).coerceIn(1.3f, 2.1f)
        val h = (w * ratio).toInt()

        var i = 0
        while (i < files.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(card(files[i], w, h), LinearLayout.LayoutParams(w, h))
            if (i + 1 < files.size) {
                row.addView(card(files[i + 1], w, h), LinearLayout.LayoutParams(w, h).apply { leftMargin = gap })
            }
            grid.addView(row, ui.lp(-1, -2, if (i == 0) 0 else 12))
            i += 2
        }
    }

    private fun emptyCard(): View {
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ui.ripple(GradientDrawable().apply {
                setColor(Ui.CARD)
                cornerRadius = ui.dp(20).toFloat()
                setStroke(ui.dp(2), Color.parseColor("#2C3646"), ui.dp(8).toFloat(), ui.dp(6).toFloat())
            })
            setOnClickListener { pickVideo() }
        }
        empty.addView(ui.label("+", 44f, Ui.A, true).apply { gravity = Gravity.CENTER })
        empty.addView(ui.label("Add your first video", 18f, Ui.TEXT, true).apply { gravity = Gravity.CENTER })
        empty.addView(ui.label("Pick a clip from your gallery,\nthen crop and trim it", 13f, Ui.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0)
        })
        return empty
    }

    private fun card(file: String, w: Int, h: Int): View {
        val fl = FrameLayout(this).apply {
            background = ui.rounded(Ui.CARD, 16)
            clipToOutline = true
            setOnClickListener { openEditor(file) }
            setOnLongClickListener { confirmDelete(file); true }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            tag = file
        }
        fl.addView(img, FrameLayout.LayoutParams(-1, -1))

        val used = Store.slotsUsing(this, file)
        if (used.isNotEmpty()) {
            val names = used.joinToString(" · ") { if (it == Slot.HOME) "HOME" else "LOCK" }
            val badge = ui.label(names, 10f, Ui.DARK, true).apply {
                setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3))
                background = ui.gradient(8)
            }
            fl.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(ui.dp(8), ui.dp(8), 0, 0)
            })
        }

        val chip = ui.label("", 11f, Ui.TEXT, true).apply {
            setPadding(ui.dp(7), ui.dp(3), ui.dp(7), ui.dp(3))
            background = ui.rounded(Color.argb(170, 0, 0, 0), 8)
            visibility = View.GONE
        }
        fl.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, ui.dp(8), ui.dp(8))
        })

        Thumbs.load(this, file) { bmp, dur ->
            if (img.tag == file) {
                if (bmp != null) img.setImageBitmap(bmp)
                if (dur > 0L) {
                    chip.text = fmt(dur)
                    chip.visibility = View.VISIBLE
                }
            }
        }
        return fl
    }

    private fun fmt(ms: Long): String {
        val t = ms.coerceAtLeast(0L) / 1000
        return "${t / 60}:${(t % 60).toString().padStart(2, '0')}"
    }

    private fun openEditor(file: String) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_FILE, file))
    }

    private fun confirmDelete(file: String) {
        val used = Store.slotsUsing(this, file)
        val msg = if (used.isEmpty()) "This removes the video from the app."
        else "This video is set as your ${used.joinToString(" and ") { if (it == Slot.HOME) "home screen" else "lock screen" }} " +
            "wallpaper. Deleting it stops that wallpaper."
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Delete video?")
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ ->
                Thumbs.forget(file)
                Store.delete(this, file)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- import

    private fun pickVideo() {
        if (importing) return
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
        @Suppress("DEPRECATION")
        startActivityForResult(i, reqPick)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != reqPick || resultCode != RESULT_OK || uri == null) return
        importing = true
        addBtn.text = "Importing video…"
        Thread {
            val name = "wall_${System.currentTimeMillis()}.mp4"
            val part = "$name.part"
            var ok = try {
                contentResolver.openInputStream(uri)?.use { i ->
                    openFileOutput(part, MODE_PRIVATE).use { o -> i.copyTo(o) }
                } != null
            } catch (_: Exception) {
                false
            }
            if (ok) ok = File(filesDir, part).renameTo(File(filesDir, name))
            if (ok) ok = readable(name)
            if (!ok) {
                File(filesDir, part).delete()
                File(filesDir, name).delete()
            }
            runOnUiThread {
                importing = false
                addBtn.text = "+  Add video"
                if (ok) {
                    openEditor(name)
                } else {
                    Toast.makeText(this, "Couldn’t read that video.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun readable(name: String): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(File(filesDir, name).absolutePath)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            d > 0L && w > 0
        } catch (_: Exception) {
            false
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}