package net.kuafuai.andee.ui.ball.gl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A flat outline pushed up into a pillow — the soft, puffy sticker the stars,
 * the shield and the tears are drawn as.
 *
 * Flat-filled shapes read as clip-art; the same outline with a dome on it, lit
 * by the shell shader, reads as something you could squeeze. The whole effect is
 * in the normals: the centre faces the camera and the edge turns away from it,
 * so the key light rolls across the shape the way it does across the ball.
 *
 * Built as concentric copies of the outline shrinking toward the centre, each
 * lifted along `z = depth · √(1 − s²)`. That profile has a vertical tangent at
 * the rim, which would make the outermost normal point straight sideways and
 * the edge go black against the light, so the last ring is pinned to `z = 0`
 * with a normal tilted a little toward the viewer.
 *
 * The outline must be **star-shaped about the origin** (every point visible from
 * the centre) — true of every shape here — because the fan from the centre is
 * what fills it.
 */
object Puff {

    fun build(outline: FloatArray, depth: Float, rings: Int = 4): Mesh {
        val n = outline.size / 2
        var mean = 0f
        for (i in 0 until n) mean += sqrt(outline[2 * i] * outline[2 * i] + outline[2 * i + 1] * outline[2 * i + 1])
        mean /= n

        val vertCount = 1 + (rings + 1) * n
        val positions = FloatArray(vertCount * 3)
        val normals = FloatArray(vertCount * 3)
        val uvs = FloatArray(vertCount * 2)

        positions[2] = depth
        normals[2] = 1f

        for (k in 1..rings + 1) {
            val last = k == rings + 1
            val s = if (last) 1f else k.toFloat() / (rings + 1)
            val z = if (last) 0f else depth * sqrt(1f - s * s)
            for (i in 0 until n) {
                val ox = outline[2 * i]
                val oy = outline[2 * i + 1]
                val len = sqrt(ox * ox + oy * oy).coerceAtLeast(1e-4f)
                val dx = ox / len
                val dy = oy / len
                val v = 1 + (k - 1) * n + i
                positions[v * 3] = ox * s
                positions[v * 3 + 1] = oy * s
                positions[v * 3 + 2] = z
                val nx: Float
                val ny: Float
                val nz: Float
                if (last) {
                    nx = dx; ny = dy; nz = 0.35f
                } else {
                    val slope = depth * s / (mean * sqrt(1f - s * s))
                    nx = dx * slope; ny = dy * slope; nz = 1f
                }
                val inv = 1f / sqrt(nx * nx + ny * ny + nz * nz)
                normals[v * 3] = nx * inv
                normals[v * 3 + 1] = ny * inv
                normals[v * 3 + 2] = nz * inv
                uvs[v * 2] = 0.5f + ox * s * 0.5f
                uvs[v * 2 + 1] = 0.5f + oy * s * 0.5f
            }
        }

        val idx = ShortArray((n + rings * n * 2) * 3)
        var w = 0
        for (i in 0 until n) {
            idx[w++] = 0
            idx[w++] = (1 + i).toShort()
            idx[w++] = (1 + (i + 1) % n).toShort()
        }
        for (k in 1..rings) {
            val a0 = 1 + (k - 1) * n
            val b0 = 1 + k * n
            for (i in 0 until n) {
                val j = (i + 1) % n
                idx[w++] = (a0 + i).toShort(); idx[w++] = (b0 + i).toShort(); idx[w++] = (b0 + j).toShort()
                idx[w++] = (a0 + i).toShort(); idx[w++] = (b0 + j).toShort(); idx[w++] = (a0 + j).toShort()
            }
        }
        return Mesh(positions, normals, uvs, idx)
    }

    /**
     * A star whose points are round. [points] tips, [inner] is the radius of the
     * valleys as a fraction of the tips', and [power] above 1 narrows the tips.
     * A tip points straight up.
     */
    fun roundStar(points: Int, inner: Float, power: Float = 1f, samples: Int = 80): FloatArray {
        val out = FloatArray(samples * 2)
        for (i in 0 until samples) {
            val phi = i.toFloat() / samples * 2f * PI.toFloat()
            val k = (0.5f + 0.5f * cos(points * (phi - PI.toFloat() / 2f))).coerceIn(0f, 1f)
            val r = inner + (1f - inner) * k.pow(power)
            out[2 * i] = r * cos(phi)
            out[2 * i + 1] = r * sin(phi)
        }
        return out
    }

    /** A shield: flat shoulders, straight sides, a rounded point at the bottom. */
    fun shield(): FloatArray {
        val pts = floatArrayOf(
            -0.82f, 0.86f, 0f, 0.98f, 0.82f, 0.86f,
            0.86f, 0.10f, 0.58f, -0.52f, 0f, -1.0f,
            -0.58f, -0.52f, -0.86f, 0.10f,
        )
        return chaikin(pts, 2)
    }

    /**
     * A heart, point down. The classic parametric curve, scaled to about unit
     * height and shifted down so the origin sits inside it: it is star-shaped about
     * a point a little below the cleft and not about the cleft itself, which is the
     * only reason [build]'s fan can fill it.
     */
    fun heart(samples: Int = 64): FloatArray {
        val out = FloatArray(samples * 2)
        for (i in 0 until samples) {
            val t = i.toFloat() / samples * 2f * PI.toFloat()
            val x = 16f * sin(t).pow(3)
            val y = 13f * cos(t) - 5f * cos(2f * t) - 2f * cos(3f * t) - cos(4f * t)
            out[2 * i] = x / 17f
            out[2 * i + 1] = y / 17f - 0.10f
        }
        return out
    }

    /** A teardrop, tip up. Centred a little low so the origin is inside the belly. */
    fun drop(samples: Int = 28): FloatArray {
        val half = ArrayList<Float>()
        for (i in 0..samples) {
            val t = i.toFloat() / samples * PI.toFloat()
            val x = 0.66f * sin(t) * sin(t / 2f).pow(0.9f)
            val y = cos(t)
            half.add(x); half.add(y)
        }
        val out = ArrayList<Float>()
        for (i in 0..samples) { out.add(half[2 * i]); out.add(half[2 * i + 1]) }
        for (i in samples - 1 downTo 1) { out.add(-half[2 * i]); out.add(half[2 * i + 1]) }
        val arr = out.toFloatArray()
        for (i in 0 until arr.size / 2) arr[2 * i + 1] -= 0.08f
        return arr
    }

    private fun chaikin(p: FloatArray, iterations: Int): FloatArray {
        var cur = p
        repeat(iterations) {
            val n = cur.size / 2
            val nxt = FloatArray(n * 4)
            for (i in 0 until n) {
                val j = (i + 1) % n
                val x0 = cur[2 * i]; val y0 = cur[2 * i + 1]
                val x1 = cur[2 * j]; val y1 = cur[2 * j + 1]
                nxt[4 * i] = 0.75f * x0 + 0.25f * x1
                nxt[4 * i + 1] = 0.75f * y0 + 0.25f * y1
                nxt[4 * i + 2] = 0.25f * x0 + 0.75f * x1
                nxt[4 * i + 3] = 0.25f * y0 + 0.75f * y1
            }
            cur = nxt
        }
        return cur
    }
}
