package com.me.fluxbg

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Plays a (trimmed, cropped) video onto a wallpaper surface using OpenGL ES.
 * Everything runs on a single dedicated thread.
 */
class VideoRenderer(private val ctx: Context) {
    private val thread = HandlerThread("fluxbg-render").apply { start() }
    private val h = Handler(thread.looper)

    private var surface: Surface? = null
    private var sw = 0
    private var sh = 0
    private var visible = false
    private var cfg: Config? = null

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var glReady = false
    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var uTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var playerSurface: Surface? = null
    private val texMatrix = FloatArray(16)

    private var player: MediaPlayer? = null
    private var playerRev = -1L
    private var vw = 0
    private var vh = 0

    private val posBuf: FloatBuffer = floatBuf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texBuf: FloatBuffer = floatBuf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    private fun floatBuf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a); position(0)
        }

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val c = cfg ?: return
            try {
                val dur = p.duration.toLong()
                val end = if (c.endMs > c.startMs && c.endMs < dur) c.endMs else dur
                if (p.currentPosition >= end - 40) p.seekTo(c.startMs, MediaPlayer.SEEK_CLOSEST)
            } catch (_: Exception) {
            }
            h.postDelayed(this, 30)
        }
    }

    fun setSurface(s: Surface?, w: Int, hh: Int) {
        h.post {
            teardown()
            surface = s
            sw = w
            sh = hh
            if (s != null && s.isValid) initGl()
            reconcile()
        }
    }

    fun setVisible(v: Boolean) {
        h.post { visible = v; reconcile() }
    }

    fun setConfig(c: Config?) {
        h.post { cfg = c; reconcile() }
    }

    fun destroy() {
        h.post {
            teardown()
            thread.quit()
        }
    }

    private fun reconcile() {
        if (!glReady) return
        val c = cfg
        if (!visible || c == null) {
            pausePlayer()
            return
        }
        if (player == null || playerRev != c.rev) startPlayer(c) else resumePlayer()
    }

    private fun startPlayer(c: Config) {
        releasePlayer()
        val st = surfaceTexture ?: return
        val f = File(ctx.filesDir, c.file)
        if (!f.exists()) return
        vw = 0; vh = 0
        val ps = Surface(st)
        playerSurface = ps
        val mp = MediaPlayer()
        player = mp
        playerRev = c.rev
        try {
            mp.setDataSource(f.absolutePath)
            mp.setSurface(ps)
            mp.setVolume(0f, 0f)
            mp.isLooping = false
            mp.setOnVideoSizeChangedListener { _, w, hh ->
                vw = w; vh = hh
                updateUv()
            }
            mp.setOnPreparedListener {
                it.seekTo(c.startMs, MediaPlayer.SEEK_CLOSEST)
                if (visible) {
                    it.start()
                    h.removeCallbacks(tick)
                    h.post(tick)
                }
            }
            mp.setOnCompletionListener {
                it.seekTo(c.startMs, MediaPlayer.SEEK_CLOSEST)
                if (visible) it.start()
            }
            mp.setOnErrorListener { _, _, _ -> releasePlayer(); true }
            mp.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    private fun resumePlayer() {
        try {
            player?.start()
            h.removeCallbacks(tick)
            h.post(tick)
        } catch (_: Exception) {
        }
    }

    private fun pausePlayer() {
        h.removeCallbacks(tick)
        try {
            player?.pause()
        } catch (_: Exception) {
        }
    }

    private fun releasePlayer() {
        h.removeCallbacks(tick)
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        playerRev = -1L
        playerSurface?.release()
        playerSurface = null
    }

    private fun updateUv() {
        val c = cfg ?: return
        val r = Store.fit(c.cl, c.ct, c.cr, c.cb, vw, vh, sw, sh)
        val l = r[0]; val t = r[1]; val rr = r[2]; val b = r[3]
        texBuf.position(0)
        texBuf.put(floatArrayOf(l, 1f - b, rr, 1f - b, l, 1f - t, rr, 1f - t))
        texBuf.position(0)
    }

    private fun initGl() {
        val s = surface ?: return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        val config = configs[0] ?: return
        eglContext = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, config, s, intArrayOf(EGL14.EGL_NONE), 0)
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) return

        program = buildProgram()
        if (program == 0) return
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTex = GLES20.glGetUniformLocation(program, "uTex")

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texId = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(texId)
        st.setOnFrameAvailableListener({ drawFrame() }, h)
        surfaceTexture = st
        glReady = true
        GLES20.glViewport(0, 0, sw, sh)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    private fun drawFrame() {
        if (!glReady) return
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
        } catch (_: Exception) {
            return
        }
        GLES20.glViewport(0, 0, sw, sh)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniformMatrix4fv(uTex, 1, false, texMatrix, 0)
        posBuf.position(0)
        texBuf.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, posBuf)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, texBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    private fun teardown() {
        releasePlayer()
        glReady = false
        try {
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
        } catch (_: Exception) {
        }
        surfaceTexture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        program = 0
        texId = 0
        surface = null
    }

    private fun buildProgram(): Int {
        val vs = compile(
            GLES20.GL_VERTEX_SHADER,
            "attribute vec4 aPos;attribute vec2 aTex;uniform mat4 uTex;varying vec2 vTex;" +
                "void main(){gl_Position=aPos;vTex=(uTex*vec4(aTex,0.0,1.0)).xy;}"
        )
        val fs = compile(
            GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;varying vec2 vTex;uniform samplerExternalOES sTex;" +
                "void main(){gl_FragColor=texture2D(sTex,vTex);}"
        )
        if (vs == 0 || fs == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        return if (ok[0] == GLES20.GL_TRUE) p else 0
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        return if (ok[0] == GLES20.GL_TRUE) s else 0
    }
}
