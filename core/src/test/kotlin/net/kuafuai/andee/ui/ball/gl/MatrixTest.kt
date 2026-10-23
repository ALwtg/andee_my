package net.kuafuai.andee.ui.ball.gl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ball's matrices used to come from android.opengl.Matrix; a mistake in
 * this copy shows up only as a ball drawn in the wrong place, so the
 * conventions are pinned here: column-major, degrees, post-multiplication.
 */
class MatrixTest {

    private val eps = 1e-5f

    private fun identity() = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private fun apply(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val v = floatArrayOf(x, y, z, 1f)
        return FloatArray(4) { r -> (0 until 4).sumOf { k -> (m[k * 4 + r] * v[k]).toDouble() }.toFloat() }
    }

    @Test
    fun `rotation about z turns x into y`() {
        val m = identity()
        Matrix.rotateM(m, 0, 90f, 0f, 0f, 1f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f, 1f), apply(m, 1f, 0f, 0f), eps)
    }

    @Test
    fun `an arbitrary axis agrees with the special-cased one`() {
        val a = identity(); Matrix.rotateM(a, 0, 37f, 0f, 1f, 0f)
        val b = identity(); Matrix.rotateM(b, 0, 37f, 0f, 2f, 0f)
        assertArrayEquals(a, b, eps)
        val c = identity(); Matrix.rotateM(c, 0, 37f, 1f, 1f, 0f)
        val p = apply(c, 1f, 1f, 0f)
        assertArrayEquals("the axis itself does not move", floatArrayOf(1f, 1f, 0f, 1f), p, eps)
    }

    @Test
    fun `translate then scale post-multiplies, as Android does`() {
        val m = identity()
        Matrix.translateM(m, 0, 1f, 2f, 3f)
        Matrix.scaleM(m, 0, 2f, 2f, 2f)
        // m = T * S, so a point is scaled first and then moved.
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 1f), apply(m, 1f, 1f, 1f), eps)
    }

    @Test
    fun `multiply composes right to left and tolerates aliasing`() {
        val t = identity(); Matrix.translateM(t, 0, 5f, 0f, 0f)
        val r = identity(); Matrix.rotateM(r, 0, 90f, 0f, 0f, 1f)
        val out = FloatArray(16)
        Matrix.multiplyMM(out, 0, t, 0, r, 0)
        assertArrayEquals(floatArrayOf(5f, 1f, 0f, 1f), apply(out, 1f, 0f, 0f), eps)
        Matrix.multiplyMM(t, 0, t, 0, r, 0)
        assertArrayEquals(out, t, eps)
    }

    @Test
    fun `perspective matches the GL formula`() {
        val m = FloatArray(16)
        Matrix.perspectiveM(m, 0, 90f, 2f, 1f, 10f)
        assertEquals(0.5f, m[0], eps)
        assertEquals(1f, m[5], eps)
        assertEquals(-11f / 9f, m[10], eps)
        assertEquals(-1f, m[11], eps)
        assertEquals(-20f / 9f, m[14], eps)
    }

    @Test
    fun `look-at puts the eye at the origin looking down minus z`() {
        val m = FloatArray(16)
        Matrix.setLookAtM(m, 0, 0f, 0f, 5f, 0f, 0f, 0f, 0f, 1f, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), apply(m, 0f, 0f, 5f), eps)
        assertArrayEquals(floatArrayOf(0f, 0f, -5f, 1f), apply(m, 0f, 0f, 0f), eps)
    }
}
