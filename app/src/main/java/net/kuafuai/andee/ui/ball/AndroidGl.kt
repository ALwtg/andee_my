package net.kuafuai.andee.ui.ball

import android.opengl.GLES20 as A
import net.kuafuai.andee.ui.ball.gl.GlBackend
import java.nio.Buffer

/** The shared ball renderer's GL calls, on Android's own GLES 2.0. */
object AndroidGl : GlBackend {
    override fun glAttachShader(program: Int, shader: Int) = A.glAttachShader(program, shader)
    override fun glBindBuffer(target: Int, buffer: Int) = A.glBindBuffer(target, buffer)
    override fun glBlendFunc(s: Int, d: Int) = A.glBlendFunc(s, d)
    override fun glBufferData(target: Int, size: Int, data: Buffer?, usage: Int) = A.glBufferData(target, size, data, usage)
    override fun glBufferSubData(target: Int, offset: Int, size: Int, data: Buffer) = A.glBufferSubData(target, offset, size, data)
    override fun glClear(mask: Int) = A.glClear(mask)
    override fun glClearColor(r: Float, g: Float, b: Float, a: Float) = A.glClearColor(r, g, b, a)
    override fun glCompileShader(shader: Int) = A.glCompileShader(shader)
    override fun glCreateProgram(): Int = A.glCreateProgram()
    override fun glCreateShader(type: Int): Int = A.glCreateShader(type)
    override fun glDeleteProgram(program: Int) = A.glDeleteProgram(program)
    override fun glDeleteShader(shader: Int) = A.glDeleteShader(shader)
    override fun glDepthMask(flag: Boolean) = A.glDepthMask(flag)
    override fun glDisable(cap: Int) = A.glDisable(cap)
    override fun glDrawArrays(mode: Int, first: Int, count: Int) = A.glDrawArrays(mode, first, count)
    override fun glDrawElements(mode: Int, count: Int, type: Int, offset: Int) = A.glDrawElements(mode, count, type, offset)
    override fun glEnable(cap: Int) = A.glEnable(cap)
    override fun glEnableVertexAttribArray(index: Int) = A.glEnableVertexAttribArray(index)
    override fun glGenBuffers(n: Int, out: IntArray, offset: Int) = A.glGenBuffers(n, out, offset)
    override fun glGetAttribLocation(program: Int, name: String): Int = A.glGetAttribLocation(program, name)
    override fun glGetProgramInfoLog(program: Int): String = A.glGetProgramInfoLog(program)
    override fun glGetProgramiv(program: Int, pname: Int, out: IntArray, offset: Int) = A.glGetProgramiv(program, pname, out, offset)
    override fun glGetShaderInfoLog(shader: Int): String = A.glGetShaderInfoLog(shader)
    override fun glGetShaderiv(shader: Int, pname: Int, out: IntArray, offset: Int) = A.glGetShaderiv(shader, pname, out, offset)
    override fun glGetUniformLocation(program: Int, name: String): Int = A.glGetUniformLocation(program, name)
    override fun glLinkProgram(program: Int) = A.glLinkProgram(program)
    override fun glScissor(x: Int, y: Int, w: Int, h: Int) = A.glScissor(x, y, w, h)
    override fun glShaderSource(shader: Int, src: String) = A.glShaderSource(shader, src)
    override fun glUniform1f(loc: Int, v: Float) = A.glUniform1f(loc, v)
    override fun glUniform3fv(loc: Int, count: Int, v: FloatArray, offset: Int) = A.glUniform3fv(loc, count, v, offset)
    override fun glUniformMatrix4fv(loc: Int, count: Int, transpose: Boolean, v: FloatArray, offset: Int) =
        A.glUniformMatrix4fv(loc, count, transpose, v, offset)
    override fun glUseProgram(program: Int) = A.glUseProgram(program)
    override fun glVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) =
        A.glVertexAttribPointer(index, size, type, normalized, stride, offset)
    override fun glViewport(x: Int, y: Int, w: Int, h: Int) = A.glViewport(x, y, w, h)
}
