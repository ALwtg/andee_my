package net.kuafuai.andee.desktop

import net.kuafuai.andee.ui.ball.gl.GlBackend
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import java.nio.Buffer

/**
 * The shared ball renderer's GL calls, on desktop OpenGL through LWJGL.
 *
 * Compiles its shader sources as GLSL ES 100 but wraps them for desktop GL 2.1;
 * see [adaptShader]. Everything else is straight-through: the subset used by the
 * renderer (`TRIANGLES`, array buffers, plain 4x4 matrix uniforms, alpha blend)
 * runs unchanged whether it came from `android.opengl.GLES20` or `org.lwjgl.opengl`.
 *
 * Not thread-safe: only call from the render thread that owns the GL context.
 */
object DesktopGl : GlBackend {
    // Pass-through GL 2.0 / ES 2.0 calls
    override fun glAttachShader(program: Int, shader: Int) = GL20.glAttachShader(program, shader)
    override fun glBindBuffer(target: Int, buffer: Int) = GL15.glBindBuffer(target, buffer)
    override fun glBlendFunc(s: Int, d: Int) = GL11.glBlendFunc(s, d)
    override fun glBufferData(target: Int, size: Int, data: Buffer?, usage: Int) {
        // LWJGL has per-type overloads and infers size from remaining().
        if (data == null) {
            GL15.glBufferData(target, size.toLong(), usage)
            return
        }
        require(data.remaining() * elementSize(data) == size) {
            "glBufferData: size=$size but buffer.remaining()=${data.remaining()}"
        }
        when (data) {
            is java.nio.ByteBuffer -> GL15.glBufferData(target, data, usage)
            is java.nio.ShortBuffer -> GL15.glBufferData(target, data, usage)
            is java.nio.IntBuffer -> GL15.glBufferData(target, data, usage)
            is java.nio.FloatBuffer -> GL15.glBufferData(target, data, usage)
            else -> throw IllegalArgumentException("glBufferData: unsupported ${data.javaClass.simpleName}")
        }
    }
    override fun glBufferSubData(target: Int, offset: Int, size: Int, data: Buffer) {
        val elemSize = elementSize(data)
        require(data.remaining() * elemSize == size) {
            "glBufferSubData: size=$size but buffer.remaining()=${data.remaining()}"
        }
        when (data) {
            is java.nio.ByteBuffer -> GL15.glBufferSubData(target, offset.toLong(), data)
            is java.nio.ShortBuffer -> GL15.glBufferSubData(target, offset.toLong(), data)
            is java.nio.IntBuffer -> GL15.glBufferSubData(target, offset.toLong(), data)
            is java.nio.FloatBuffer -> GL15.glBufferSubData(target, offset.toLong(), data)
            else -> throw IllegalArgumentException("glBufferSubData: unsupported ${data.javaClass.simpleName}")
        }
    }
    private fun elementSize(buf: Buffer) = when (buf) {
        is java.nio.ByteBuffer -> 1
        is java.nio.ShortBuffer -> 2
        is java.nio.IntBuffer -> 4
        is java.nio.FloatBuffer -> 4
        is java.nio.DoubleBuffer -> 8
        else -> throw IllegalArgumentException("Unknown buffer type: ${buf.javaClass.simpleName}")
    }
    override fun glClear(mask: Int) = GL11.glClear(mask)
    override fun glClearColor(r: Float, g: Float, b: Float, a: Float) = GL11.glClearColor(r, g, b, a)
    override fun glCompileShader(shader: Int) = GL20.glCompileShader(shader)
    override fun glCreateProgram(): Int = GL20.glCreateProgram()
    override fun glCreateShader(type: Int): Int = GL20.glCreateShader(type)
    override fun glDeleteProgram(program: Int) = GL20.glDeleteProgram(program)
    override fun glDeleteShader(shader: Int) = GL20.glDeleteShader(shader)
    override fun glDepthMask(flag: Boolean) = GL11.glDepthMask(flag)
    override fun glDisable(cap: Int) = GL11.glDisable(cap)
    override fun glDrawArrays(mode: Int, first: Int, count: Int) = GL11.glDrawArrays(mode, first, count)
    override fun glDrawElements(mode: Int, count: Int, type: Int, offset: Int) = GL11.glDrawElements(mode, count, type, offset.toLong())
    override fun glEnable(cap: Int) = GL11.glEnable(cap)
    override fun glEnableVertexAttribArray(index: Int) = GL20.glEnableVertexAttribArray(index)
    override fun glGenBuffers(n: Int, out: IntArray, offset: Int) {
        for (i in 0 until n) out[offset + i] = GL15.glGenBuffers()
    }
    override fun glGetAttribLocation(program: Int, name: String): Int = GL20.glGetAttribLocation(program, name)
    override fun glGetProgramInfoLog(program: Int): String = GL20.glGetProgramInfoLog(program)
    override fun glGetProgramiv(program: Int, pname: Int, out: IntArray, offset: Int) {
        out[offset] = GL20.glGetProgrami(program, pname)
    }
    override fun glGetShaderInfoLog(shader: Int): String = GL20.glGetShaderInfoLog(shader)
    override fun glGetShaderiv(shader: Int, pname: Int, out: IntArray, offset: Int) {
        out[offset] = GL20.glGetShaderi(shader, pname)
    }
    override fun glGetUniformLocation(program: Int, name: String): Int = GL20.glGetUniformLocation(program, name)
    override fun glLinkProgram(program: Int) = GL20.glLinkProgram(program)
    override fun glScissor(x: Int, y: Int, w: Int, h: Int) = GL11.glScissor(x, y, w, h)
    override fun glShaderSource(shader: Int, src: String) = GL20.glShaderSource(shader, src)
    override fun glUniform1f(loc: Int, v: Float) = GL20.glUniform1f(loc, v)
    override fun glUniform3fv(loc: Int, count: Int, v: FloatArray, offset: Int) {
        GL20.glUniform3fv(loc, slice(v, offset, count * 3))
    }
    override fun glUniformMatrix4fv(loc: Int, count: Int, transpose: Boolean, v: FloatArray, offset: Int) {
        GL20.glUniformMatrix4fv(loc, transpose, slice(v, offset, count * 16))
    }
    /** Android's (array, offset, count) as the exact span LWJGL infers its count from. */
    private fun slice(v: FloatArray, offset: Int, n: Int): FloatArray =
        if (offset == 0 && v.size == n) v else v.copyOfRange(offset, offset + n)

    override fun glUseProgram(program: Int) = GL20.glUseProgram(program)
    override fun glVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) =
        GL20.glVertexAttribPointer(index, size, type, normalized, stride, offset.toLong())
    override fun glViewport(x: Int, y: Int, w: Int, h: Int) = GL11.glViewport(x, y, w, h)

    /**
     * Rewrites a mobile-side GLSL ES shader to run on desktop GL.
     *
     * The renderer's shaders target ES 2.0 (precision, `gl_PointCoord`, the ES
     * 100 `varying`/`attribute` syntax) but this Mac's drivers expose GL 2.1
     * with GLSL 1.20. Desktop GL demands `#version 120` in the source and does
     * not understand `precision`. It *does* accept ES-style `attribute` as an
     * alias for `in` and `varying` as an alias for out/in the way ES accepted
     * them in 100 (before GLSL 3.00 forced `in`/`out` everywhere), which means
     * the body code runs unchanged. `gl_PointCoord` works in both.
     */
    override fun adaptShader(src: String, fragment: Boolean): String = buildString {
        append("#version 120\n")
        var inBlock = false
        src.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("/*")) inBlock = true
            val inComment = inBlock || trimmed.startsWith("//") || trimmed.startsWith("*")
            when {
                trimmed.startsWith("precision") -> Unit  // strip
                inComment -> appendLine(line)
                else -> appendLine(line)
            }
            if (trimmed.endsWith("*/")) inBlock = false
        }
    }
}
