package com.me.fluxbg

import android.app.WallpaperManager
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class FluxWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = FluxEngine()

    inner class FluxEngine : Engine() {
        private val renderer = VideoRenderer(applicationContext)

        private fun resolve(): Config? {
            var slot = Slot.HOME
            if (Build.VERSION.SDK_INT >= 34) {
                val f = wallpaperFlags
                val lockOnly = (f and WallpaperManager.FLAG_LOCK) != 0 && (f and WallpaperManager.FLAG_SYSTEM) == 0
                if (lockOnly) slot = Slot.LOCK
            }
            return Store.load(applicationContext, slot) ?: Store.load(applicationContext, Slot.HOME)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer.setSurface(holder.surface, width, height)
            renderer.setConfig(resolve())
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            renderer.setSurface(null, 0, 0)
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) renderer.setConfig(resolve())
            renderer.setVisible(visible)
        }

        override fun onDestroy() {
            renderer.destroy()
            super.onDestroy()
        }
    }
}
