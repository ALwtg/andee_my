package net.kuafuai.andee.ui.ball.gl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The seven `android.opengl.Matrix` operations the ball uses, with identical
 * semantics: 4x4 matrices as column-major FloatArrays, angles in degrees,
 * `rotateM`/`translateM`/`scaleM` post-multiply in place.
 */
object Matrix {

    fun setIdentityM(m: FloatArray, off: Int) {
        for (i in 0 until 16) m[off + i] = 0f
        m[off] = 1f; m[off + 5] = 1f; m[off + 10] = 1f; m[off + 15] = 1f
    }

    /** r = a × b. Safe when r aliases a or b. */
    fun multiplyMM(r: FloatArray, ro: Int, a: FloatArray, ao: Int, b: FloatArray, bo: Int) {
        val t = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[ao + k * 4 + row] * b[bo + col * 4 + k]
            t[col * 4 + row] = s
        }
        System.arraycopy(t, 0, r, ro, 16)
    }

    fun translateM(m: FloatArray, off: Int, x: Float, y: Float, z: Float) {
        for (i in 0 until 4) {
            m[off + 12 + i] += m[off + i] * x + m[off + 4 + i] * y + m[off + 8 + i] * z
        }
    }

    fun scaleM(m: FloatArray, off: Int, x: Float, y: Float, z: Float) {
        for (i in 0 until 4) {
            m[off + i] *= x; m[off + 4 + i] *= y; m[off + 8 + i] *= z
        }
    }

    fun rotateM(m: FloatArray, off: Int, a: Float, x: Float, y: Float, z: Float) {
        val r = FloatArray(16)
        setRotateM(r, a, x, y, z)
        val t = FloatArray(16)
        multiplyMM(t, 0, m, off, r, 0)
        System.arraycopy(t, 0, m, off, 16)
    }

    private fun setRotateM(rm: FloatArray, a: Float, x0: Float, y0: Float, z0: Float) {
        setIdentityM(rm, 0)
        val rad = a * (PI / 180.0).toFloat()
        val s = sin(rad); val c = cos(rad)
        var x = x0; var y = y0; var z = z0
        if (x == 1f && y == 0f && z == 0f) {
            rm[5] = c; rm[10] = c; rm[6] = s; rm[9] = -s
        } else if (x == 0f && y == 1f && z == 0f) {
            rm[0] = c; rm[10] = c; rm[8] = s; rm[2] = -s
        } else if (x == 0f && y == 0f && z == 1f) {
            rm[0] = c; rm[5] = c; rm[1] = s; rm[4] = -s
        } else {
            val len = sqrt(x * x + y * y + z * z)
            if (len != 1f) { x /= len; y /= len; z /= len }
            val nc = 1f - c
            val xy = x * y; val yz = y * z; val zx = z * x
            val xs = x * s; val ys = y * s; val zs = z * s
            rm[0] = x * x * nc + c; rm[4] = xy * nc - zs; rm[8] = zx * nc + ys
            rm[1] = xy * nc + zs; rm[5] = y * y * nc + c; rm[9] = yz * nc - xs
            rm[2] = zx * nc - ys; rm[6] = yz * nc + xs; rm[10] = z * z * nc + c
        }
    }

    fun perspectiveM(m: FloatArray, off: Int, fovy: Float, aspect: Float, zNear: Float, zFar: Float) {
        val f = 1.0f / tan(fovy * (PI / 360.0)).toFloat()
        val rangeReciprocal = 1.0f / (zNear - zFar)
        m[off] = f / aspect; m[off + 1] = 0f; m[off + 2] = 0f; m[off + 3] = 0f
        m[off + 4] = 0f; m[off + 5] = f; m[off + 6] = 0f; m[off + 7] = 0f
        m[off + 8] = 0f; m[off + 9] = 0f; m[off + 10] = (zFar + zNear) * rangeReciprocal; m[off + 11] = -1f
        m[off + 12] = 0f; m[off + 13] = 0f; m[off + 14] = 2f * zFar * zNear * rangeReciprocal; m[off + 15] = 0f
    }

    fun setLookAtM(
        rm: FloatArray, off: Int, ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float,
        ux: Float, uy: Float, uz: Float,
    ) {
        var fx = cx - ex; var fy = cy - ey; var fz = cz - ez
        val rlf = 1f / sqrt(fx * fx + fy * fy + fz * fz)
        fx *= rlf; fy *= rlf; fz *= rlf
        var sx = fy * uz - fz * uy; var sy = fz * ux - fx * uz; var sz = fx * uy - fy * ux
        val rls = 1f / sqrt(sx * sx + sy * sy + sz * sz)
        sx *= rls; sy *= rls; sz *= rls
        val ux2 = sy * fz - sz * fy; val uy2 = sz * fx - sx * fz; val uz2 = sx * fy - sy * fx
        rm[off] = sx; rm[off + 1] = ux2; rm[off + 2] = -fx; rm[off + 3] = 0f
        rm[off + 4] = sy; rm[off + 5] = uy2; rm[off + 6] = -fy; rm[off + 7] = 0f
        rm[off + 8] = sz; rm[off + 9] = uz2; rm[off + 10] = -fz; rm[off + 11] = 0f
        rm[off + 12] = 0f; rm[off + 13] = 0f; rm[off + 14] = 0f; rm[off + 15] = 1f
        translateM(rm, off, -ex, -ey, -ez)
    }
}
