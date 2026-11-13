package com.ricardochr.estantepinturas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Compara la simplificación anterior (cuadrícula) con la nueva (error cuádrico) sobre dos formas con
 * superficie conocida: una caja de aristas vivas y una esfera con relieve (como una superficie esculpida).
 */
class MeshSimplifyTest {
    private class Tris { val v = ArrayList<FloatArray>() ; fun add(a: FloatArray, b: FloatArray, c: FloatArray) { v.add(a + b + c) } }

    private fun stl(t: Tris): ByteArray {
        val bb = ByteBuffer.allocate(84 + 50 * t.v.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(80); bb.putInt(t.v.size)
        for (f in t.v) { repeat(3) { bb.putFloat(0f) }; f.forEach { bb.putFloat(it) }; bb.putShort(0) }
        return bb.array()
    }

    private fun load(bytes: ByteArray, budget: Int, qem: Boolean): RawMesh =
        readRaw(MeshSource("prueba.stl", bytes.size.toLong()) { ByteArrayInputStream(bytes) }, budget, { _, _ -> }, qem)

    /** Caja de 20×20×20 (mm) con cada cara dividida en n×n cuadros. */
    private fun box(n: Int): Tris {
        val t = Tris()
        val s = 10f
        fun face(o: FloatArray, u: FloatArray, w: FloatArray) {
            fun p(i: Int, j: Int) = FloatArray(3) { k -> o[k] + u[k] * i / n + w[k] * j / n }
            for (i in 0 until n) for (j in 0 until n) { t.add(p(i, j), p(i + 1, j), p(i + 1, j + 1)); t.add(p(i, j), p(i + 1, j + 1), p(i, j + 1)) }
        }
        val a = 2 * s
        face(floatArrayOf(-s, -s, -s), floatArrayOf(0f, a, 0f), floatArrayOf(a, 0f, 0f))
        face(floatArrayOf(-s, -s, s), floatArrayOf(a, 0f, 0f), floatArrayOf(0f, a, 0f))
        face(floatArrayOf(-s, -s, -s), floatArrayOf(a, 0f, 0f), floatArrayOf(0f, 0f, a))
        face(floatArrayOf(-s, s, -s), floatArrayOf(0f, 0f, a), floatArrayOf(a, 0f, 0f))
        face(floatArrayOf(-s, -s, -s), floatArrayOf(0f, 0f, a), floatArrayOf(0f, a, 0f))
        face(floatArrayOf(s, -s, -s), floatArrayOf(0f, a, 0f), floatArrayOf(0f, 0f, a))
        return t
    }

    private fun boxError(m: RawMesh): Double {
        // Distancia a la superficie de la caja = |max(|x|,|y|,|z|) − 10| (exacta fuera; buena aproximación cerca)
        fun d(x: Float, y: Float, z: Float) = abs(max(abs(x), max(abs(y), abs(z))) - 10.0)
        var sum = 0.0; var n = 0
        var t = 0
        while (t < m.idx.size) {
            val a = m.idx[t] * 3; val b = m.idx[t + 1] * 3; val c = m.idx[t + 2] * 3
            sum += d((m.pos[a] + m.pos[b] + m.pos[c]) / 3, (m.pos[a + 1] + m.pos[b + 1] + m.pos[c + 1]) / 3, (m.pos[a + 2] + m.pos[b + 2] + m.pos[c + 2]) / 3)
            n++; t += 3
        }
        return sum / n
    }

    private fun bumpR(th: Double, ph: Double) = 10.0 * (1 + 0.08 * sin(8 * th) * sin(8 * ph))

    /** Esfera de radio 10 con relieve en una cuadrícula de n×2n. */
    private fun bumpy(n: Int): Tris {
        val t = Tris()
        fun p(i: Int, j: Int): FloatArray {
            val th = Math.PI * i / n; val ph = 2 * Math.PI * j / (2 * n)
            val r = bumpR(th, ph)
            return floatArrayOf((r * sin(th) * cos(ph)).toFloat(), (r * sin(th) * sin(ph)).toFloat(), (r * cos(th)).toFloat())
        }
        for (i in 0 until n) for (j in 0 until 2 * n) {
            val a = p(i, j); val b = p(i + 1, j); val c = p(i + 1, j + 1); val d = p(i, j + 1)
            if (i > 0) t.add(a, b, d)
            if (i < n - 1) t.add(b, c, d)
        }
        return t
    }

    private fun bumpyError(m: RawMesh): Double {
        var sum = 0.0; var n = 0
        var t = 0
        while (t < m.idx.size) {
            val a = m.idx[t] * 3; val b = m.idx[t + 1] * 3; val c = m.idx[t + 2] * 3
            val x = (m.pos[a] + m.pos[b] + m.pos[c]) / 3.0
            val y = (m.pos[a + 1] + m.pos[b + 1] + m.pos[c + 1]) / 3.0
            val z = (m.pos[a + 2] + m.pos[b + 2] + m.pos[c + 2]) / 3.0
            val r = sqrt(x * x + y * y + z * z)
            val th = kotlin.math.acos((z / r).coerceIn(-1.0, 1.0)); val ph = atan2(y, x)
            sum += abs(r - bumpR(th, ph)); n++; t += 3
        }
        return sum / n
    }

    @Test fun cajaConservaAristas() {
        val bytes = stl(box(60))
        val budget = 6_000
        val grid = load(bytes, budget, qem = false)
        val q = load(bytes, budget, qem = true)
        val eg = boxError(grid); val eq = boxError(q)
        println("Caja 43,200 → ~$budget triángulos | cuadrícula: ${grid.idx.size / 3} tri, error medio ${"%.4f".format(eg)} mm | cuádrico: ${q.idx.size / 3} tri, error medio ${"%.4f".format(eq)} mm")
        assertTrue(q.idx.size / 3 <= budget)
        assertTrue("cuádrico $eq vs cuadrícula $eg", eq < eg * 0.5)
    }

    @Test fun relieveSeConservaMejor() {
        val bytes = stl(bumpy(180))
        val budget = 8_000
        val grid = load(bytes, budget, qem = false)
        val q = load(bytes, budget, qem = true)
        val eg = bumpyError(grid); val eq = bumpyError(q)
        println("Esfera con relieve ${bumpy(180).v.size} → ~$budget triángulos | cuadrícula: ${grid.idx.size / 3} tri, error medio ${"%.4f".format(eg)} mm | cuádrico: ${q.idx.size / 3} tri, error medio ${"%.4f".format(eq)} mm")
        assertTrue("cuádrico $eq vs cuadrícula $eg", eq < eg)
    }

    @Test fun sinSimplificarNoCambiaNada() {
        val bytes = stl(box(10))
        val m = load(bytes, 100_000, qem = true)
        assertEquals(1200, m.idx.size / 3)
        assertTrue(boxError(m) < 1e-4)
    }

    /** Peana (cilindro bajo) con una esfera encima; [up] = eje hacia arriba (2 = Z, 1 = Y). */
    private fun figure(up: Int): RawMesh {
        val t = Tris()
        fun pt(x: Double, y: Double, h: Double): FloatArray =
            if (up == 2) floatArrayOf(x.toFloat(), y.toFloat(), h.toFloat()) else floatArrayOf(x.toFloat(), h.toFloat(), (-y).toFloat())
        val n = 48
        for (i in 0 until n) {
            val a0 = 2 * Math.PI * i / n; val a1 = 2 * Math.PI * (i + 1) / n
            val c0 = cos(a0) * 10; val s0 = sin(a0) * 10; val c1 = cos(a1) * 10; val s1 = sin(a1) * 10
            t.add(pt(0.0, 0.0, 0.0), pt(c1, s1, 0.0), pt(c0, s0, 0.0)) // base, mira hacia abajo
            t.add(pt(0.0, 0.0, 2.0), pt(c0, s0, 2.0), pt(c1, s1, 2.0))
            t.add(pt(c0, s0, 0.0), pt(c1, s1, 0.0), pt(c1, s1, 2.0)); t.add(pt(c0, s0, 0.0), pt(c1, s1, 2.0), pt(c0, s0, 2.0))
        }
        val m = 24
        for (i in 0 until m) for (j in 0 until 2 * m) {
            fun sp(ii: Int, jj: Int): FloatArray {
                val th = Math.PI * ii / m; val ph = Math.PI * jj / m
                return pt(5 * sin(th) * cos(ph), 5 * sin(th) * sin(ph), 12 + 5 * cos(th))
            }
            t.add(sp(i, j), sp(i + 1, j), sp(i + 1, j + 1)); t.add(sp(i, j), sp(i + 1, j + 1), sp(i, j + 1))
        }
        val bytes = stl(t)
        return load(bytes, 1_000_000, qem = true)
    }

    @Test fun detectaLaBase() {
        val zUp = figure(2)
        assertEquals(4, detectDown(zUp.pos, zUp.idx))
        val yUp = figure(1)
        assertEquals(2, detectDown(yUp.pos, yUp.idx))
    }
}
