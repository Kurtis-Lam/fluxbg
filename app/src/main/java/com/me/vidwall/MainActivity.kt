package com.me.vidwall

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var startE: EditText
    private lateinit var endE: EditText
    private val reqPick = 1

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == reqPick && resultCode == RESULT_OK && uri != null) {
            Thread {
                contentResolver.openInputStream(uri)?.use { i ->
                    openFileOutput("wall.mp4", MODE_PRIVATE).use { o -> i.copyTo(o) }
                }
                runOnUiThread { status.text = "Video loaded"; save() }
            }.start()
        }
    }

    private fun save() {
        val s = startE.text.toString().toFloatOrNull() ?: 0f
        val e = endE.text.toString().toFloatOrNull() ?: 0f
        getSharedPreferences("p", MODE_PRIVATE).edit()
            .putLong("start", (s * 1000).toLong()).putLong("end", (e * 1000).toLong())
            .putLong("rev", System.currentTimeMillis()).apply()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        status = TextView(this).apply { text = "No video chosen" }
        startE = EditText(this).apply { hint = "Loop start (seconds)"; setText("0"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        endE = EditText(this).apply { hint = "Loop end (seconds, 0 = full video)"; setText("0"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val pick = Button(this).apply {
            text = "1. Choose video from gallery"
            setOnClickListener {
                val i = Intent(Intent.ACTION_GET_CONTENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
                @Suppress("DEPRECATION")
                startActivityForResult(i, reqPick)
            }
        }
        val sv = Button(this).apply { text = "2. Save loop range"; setOnClickListener { save(); status.text = "Saved" } }
        val set = Button(this).apply {
            text = "3. Set as wallpaper"
            setOnClickListener {
                save()
                startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@MainActivity, VideoWallpaperService::class.java)))
            }
        }
        listOf(status, pick, startE, endE, sv, set).forEach { l.addView(it) }
        setContentView(l)
    }
}