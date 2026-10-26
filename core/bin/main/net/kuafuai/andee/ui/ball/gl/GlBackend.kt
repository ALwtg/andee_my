package net.kuafuai.andee.ui.ball.gl

import java.nio.Buffer

/**
 * The OpenGL calls the ball uses, behind one interface so the renderer runs on
 * Android's GLES 2.0 and on the Mac's desktop OpenGL alike. Signatures mirror
 * `android.opengl.GLES20`; [GLES20] below is a drop-in with the same names, so
 * the renderer's code reads exactly as it did.
 */
interface GlBackend {
    fun glAttachShader(program: Int, shader: Int)
    fun glBindBuffer(target: Int, buffer: Int)
    fun glBlendFunc(s: Int, d: Int)
    fun glBufferData(target: Int, size: Int, data: Buffer?, usage: Int)
    fun glBufferSubData(target: Int, offset: Int, size: Int, data: Buffer)
    fun glClear(mask: Int)
    fun glClearColor(r: Float, g: Float, b: Float, a: Float)
    fun glCompileShader(shader: Int)
    fun glCreateProgram(): Int
    fun glCreateShader(type: Int): Int
    fun glDeleteProgram(program: Int)
    fun glDeleteShader(shader: Int)
    fun glDepthMask(flag: Boolean)
    fun glDisable(cap: Int)
    fun glDrawArrays(mode: Int, first: Int, count: Int)
    fun glDrawElements(mode: Int, count: Int, type: Int, offset: Int)
    fun glEnable(cap: Int)
    fun glEnableVertexAttribArray(index: Int)
    fun glGenBuffers(n: Int, out: IntArray, offset: Int)
    fun glGetAttribLocation(program: Int, name: String): Int
    fun glGetProgramInfoLog(program: Int): String
    fun glGetProgramiv(program: Int, pname: Int, out: IntArray, offset: Int)
    fun glGetShaderInfoLog(shader: Int): String
    fun glGetShaderiv(shader: Int, pname: Int, out: IntArray, offset: Int)
    fun glGetUniformLocation(program: Int, name: String): Int
    fun glLinkProgram(program: Int)
    fun glScissor(x: Int, y: Int, w: Int, h: Int)
    fun glShaderSource(shader: Int, src: String)
    fun glUniform1f(loc: Int, v: Float)
    fun glUniform3fv(loc: Int, count: Int, v: FloatArray, offset: Int)
    fun glUniformMatrix4fv(loc: Int, count: Int, transpose: Boolean, v: FloatArray, offset: Int)
    fun glUseProgram(program: Int)
    fun glVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int)
    fun glViewport(x: Int, y: Int, w: Int, h: Int)

    /**
     * A shader written in GLSL ES 1.00, rewritten for this platform's GLSL if
     * it needs to be (desktop GLSL 1.20 has no `precision` statements).
     */
    fun adaptShader(src: String, fragment: Boolean): String = src
}

/**
 * Same names and constants as `android.opengl.GLES20`, forwarding to whichever
 * [GlBackend] the platform installed before the first frame.
 */
object GLES20 {
    @Volatile lateinit var backend: GlBackend

    const val GL_ARRAY_BUFFER = 0x8892
    const val GL_ELEMENT_ARRAY_BUFFER = 0x8893
    const val GL_STATIC_DRAW = 0x88E4
    const val GL_DYNAMIC_DRAW = 0x88E8
    const val GL_BLEND = 0x0BE2
    const val GL_DEPTH_TEST = 0x0B71
    const val GL_SCISSOR_TEST = 0x0C11
    const val GL_COLOR_BUFFER_BIT = 0x4000
    const val GL_DEPTH_BUFFER_BIT = 0x0100
    const val GL_COMPILE_STATUS = 0x8B81
    const val GL_LINK_STATUS = 0x8B82
    const val GL_VERTEX_SHADER = 0x8B31
    const val GL_FRAGMENT_SHADER = 0x8B30
    const val GL_FLOAT = 0x1406
    const val GL_UNSIGNED_SHORT = 0x1403
    const val GL_TRIANGLES = 0x0004
    const val GL_POINTS = 0x0000
    const val GL_ONE = 1
    const val GL_SRC_ALPHA = 0x0302
    const val GL_ONE_MINUS_SRC_ALPHA = 0x0303

    fun glAttachShader(p: Int, s: Int) = backend.glAttachShader(p, s)
    fun glBindBuffer(t: Int, b: Int) = backend.glBindBuffer(t, b)
    fun glBlendFunc(s: Int, d: Int) = backend.glBlendFunc(s, d)
    fun glBufferData(t: Int, size: Int, data: Buffer?, usage: Int) = backend.glBufferData(t, size, data, usage)
    fun glBufferSubData(t: Int, off: Int, size: Int, data: Buffer) = backend.glBufferSubData(t, off, size, data)
    fun glClear(m: Int) = backend.glClear(m)
    fun glClearColor(r: Float, g: Float, b: Float, a: Float) = backend.glClearColor(r, g, b, a)
    fun glCompileShader(s: Int) = backend.glCompileShader(s)
    fun glCreateProgram(): Int = backend.glCreateProgram()
    fun glCreateShader(t: Int): Int = backend.glCreateShader(t)
    fun glDeleteProgram(p: Int) = backend.glDeleteProgram(p)
    fun glDeleteShader(s: Int) = backend.glDeleteShader(s)
    fun glDepthMask(f: Boolean) = backend.glDepthMask(f)
    fun glDisable(c: Int) = backend.glDisable(c)
    fun glDrawArrays(m: Int, f: Int, c: Int) = backend.glDrawArrays(m, f, c)
    fun glDrawElements(m: Int, c: Int, t: Int, o: Int) = backend.glDrawElements(m, c, t, o)
    fun glEnable(c: Int) = backend.glEnable(c)
    fun glEnableVertexAttribArray(i: Int) = backend.glEnableVertexAttribArray(i)
    fun glGenBuffers(n: Int, out: IntArray, off: Int) = backend.glGenBuffers(n, out, off)
    fun glGetAttribLocation(p: Int, n: String): Int = backend.glGetAttribLocation(p, n)
    fun glGetProgramInfoLog(p: Int): String = backend.glGetProgramInfoLog(p)
    fun glGetProgramiv(p: Int, n: Int, out: IntArray, off: Int) = backend.glGetProgramiv(p, n, out, off)
    fun glGetShaderInfoLog(s: Int): String = backend.glGetShaderInfoLog(s)
    fun glGetShaderiv(s: Int, n: Int, out: IntArray, off: Int) = backend.glGetShaderiv(s, n, out, off)
    fun glGetUniformLocation(p: Int, n: String): Int = backend.glGetUniformLocation(p, n)
    fun glLinkProgram(p: Int) = backend.glLinkProgram(p)
    fun glScissor(x: Int, y: Int, w: Int, h: Int) = backend.glScissor(x, y, w, h)
    fun glShaderSource(s: Int, src: String) = backend.glShaderSource(s, src)
    fun glUniform1f(l: Int, v: Float) = backend.glUniform1f(l, v)
    fun glUniform3fv(l: Int, c: Int, v: FloatArray, off: Int) = backend.glUniform3fv(l, c, v, off)
    fun glUniformMatrix4fv(l: Int, c: Int, t: Boolean, v: FloatArray, off: Int) = backend.glUniformMatrix4fv(l, c, t, v, off)
    fun glUseProgram(p: Int) = backend.glUseProgram(p)
    fun glVertexAttribPointer(i: Int, s: Int, t: Int, n: Boolean, st: Int, off: Int) = backend.glVertexAttribPointer(i, s, t, n, st, off)
    fun glViewport(x: Int, y: Int, w: Int, h: Int) = backend.glViewport(x, y, w, h)
}
