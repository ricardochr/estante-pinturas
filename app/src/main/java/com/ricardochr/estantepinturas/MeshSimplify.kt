package com.ricardochr.estantepinturas

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Simplificación por colapso de aristas con error cuádrico (Garland y Heckbert), en la variante
 * iterativa sin cola de prioridad de Sven Forstmann ("Fast Quadric Mesh Simplification", MIT).
 *
 * A diferencia de agrupar vértices en una cuadrícula, cada colapso pone el vértice nuevo en el punto
 * que menos se aparta de los planos originales: las aristas vivas (filos de armadura, pliegues
 * profundos) y los bordes se conservan y el detalle se quita primero donde la superficie es plana.
 *
 * Todo se guarda en arreglos planos (sin un objeto por vértice o triángulo) para que quepan millones.
 */
class MeshSimplifier(positions: FloatArray, indices: IntArray) {
    private var nv = positions.size / 3
    private var px = DoubleArray(nv) { positions[it * 3].toDouble() }
    private var py = DoubleArray(nv) { positions[it * 3 + 1].toDouble() }
    private var pz = DoubleArray(nv) { positions[it * 3 + 2].toDouble() }
    private var q = DoubleArray(nv * 10)
    private var tstart = IntArray(nv)
    private var tcount = IntArray(nv)
    private var border = BooleanArray(nv)

    private var nt = indices.size / 3
    private var tv = indices.copyOf()
    private var terr = FloatArray(nt * 4)
    private var tdel = BooleanArray(nt)
    private var tdirty = BooleanArray(nt)
    private var tn = FloatArray(nt * 3)

    /** Referencias vértice -> triángulo: tid * 4 + esquina. Crece con los colapsos. */
    private var refs = IntArray(nt * 3)
    private var refCount = 0

    private fun addRef(r: Int) {
        if (refCount == refs.size) refs = refs.copyOf(refs.size + refs.size / 2 + 16)
        refs[refCount++] = r
    }

    // ---------------------------------------------------------------- matrices simétricas

    private fun det(o: Int, m: DoubleArray, a11: Int, a12: Int, a13: Int, a21: Int, a22: Int, a23: Int, a31: Int, a32: Int, a33: Int): Double =
        m[o + a11] * m[o + a22] * m[o + a33] + m[o + a13] * m[o + a21] * m[o + a32] + m[o + a12] * m[o + a23] * m[o + a31] -
            m[o + a13] * m[o + a22] * m[o + a31] - m[o + a11] * m[o + a23] * m[o + a32] - m[o + a12] * m[o + a21] * m[o + a33]

    private val tmpQ = DoubleArray(10)

    private fun vertexError(m: DoubleArray, o: Int, x: Double, y: Double, z: Double): Double =
        m[o] * x * x + 2 * m[o + 1] * x * y + 2 * m[o + 2] * x * z + 2 * m[o + 3] * x + m[o + 4] * y * y +
            2 * m[o + 5] * y * z + 2 * m[o + 6] * y + m[o + 7] * z * z + 2 * m[o + 8] * z + m[o + 9]

    private val res = DoubleArray(3)

    /** Error de colapsar a1–a2 y punto óptimo en [res]. */
    private fun calculateError(a1: Int, a2: Int): Double {
        for (k in 0 until 10) tmpQ[k] = q[a1 * 10 + k] + q[a2 * 10 + k]
        val m = tmpQ
        val bord = border[a1] && border[a2]
        val d = det(0, m, 0, 1, 2, 1, 4, 5, 2, 5, 7)
        if (d != 0.0 && !bord) {
            res[0] = -1 / d * det(0, m, 1, 2, 3, 4, 5, 6, 5, 7, 8)
            res[1] = 1 / d * det(0, m, 0, 2, 3, 1, 5, 6, 2, 7, 8)
            res[2] = -1 / d * det(0, m, 0, 1, 3, 1, 4, 6, 2, 5, 8)
            return vertexError(m, 0, res[0], res[1], res[2])
        }
        val x1 = px[a1]; val y1 = py[a1]; val z1 = pz[a1]
        val x2 = px[a2]; val y2 = py[a2]; val z2 = pz[a2]
        val x3 = (x1 + x2) / 2; val y3 = (y1 + y2) / 2; val z3 = (z1 + z2) / 2
        val e1 = vertexError(m, 0, x1, y1, z1)
        val e2 = vertexError(m, 0, x2, y2, z2)
        val e3 = vertexError(m, 0, x3, y3, z3)
        val e = min(e1, min(e2, e3))
        when (e) {
            e1 -> { res[0] = x1; res[1] = y1; res[2] = z1 }
            e2 -> { res[0] = x2; res[1] = y2; res[2] = z2 }
            else -> { res[0] = x3; res[1] = y3; res[2] = z3 }
        }
        return e
    }

    // ---------------------------------------------------------------- pasos

    /** ¿Colapsar i0 hacia p voltearía algún triángulo vecino? Marca en [deleted] los que desaparecen. */
    private fun flipped(x: Double, y: Double, z: Double, i0: Int, i1: Int, deleted: BooleanArray): Boolean {
        for (k in 0 until tcount[i0]) {
            val r = refs[tstart[i0] + k]
            val t = r ushr 2
            if (tdel[t]) continue
            val s = r and 3
            val id1 = tv[t * 3 + (s + 1) % 3]
            val id2 = tv[t * 3 + (s + 2) % 3]
            if (id1 == i1 || id2 == i1) { deleted[k] = true; continue }
            var d1x = px[id1] - x; var d1y = py[id1] - y; var d1z = pz[id1] - z
            var l = sqrt(d1x * d1x + d1y * d1y + d1z * d1z); if (l > 0) { d1x /= l; d1y /= l; d1z /= l }
            var d2x = px[id2] - x; var d2y = py[id2] - y; var d2z = pz[id2] - z
            l = sqrt(d2x * d2x + d2y * d2y + d2z * d2z); if (l > 0) { d2x /= l; d2y /= l; d2z /= l }
            if (abs(d1x * d2x + d1y * d2y + d1z * d2z) > 0.999) return true
            var nx = d1y * d2z - d1z * d2y; var ny = d1z * d2x - d1x * d2z; var nz = d1x * d2y - d1y * d2x
            l = sqrt(nx * nx + ny * ny + nz * nz); if (l > 0) { nx /= l; ny /= l; nz /= l }
            deleted[k] = false
            if (nx * tn[t * 3] + ny * tn[t * 3 + 1] + nz * tn[t * 3 + 2] < 0.2) return true
        }
        return false
    }

    private var deletedTriangles = 0

    private fun updateTriangles(i0: Int, v: Int, deleted: BooleanArray) {
        for (k in 0 until tcount[v]) {
            val r = refs[tstart[v] + k]
            val t = r ushr 2
            if (tdel[t]) continue
            if (deleted[k]) { tdel[t] = true; deletedTriangles++; continue }
            tv[t * 3 + (r and 3)] = i0
            tdirty[t] = true
            val e0 = calculateError(tv[t * 3], tv[t * 3 + 1])
            val e1 = calculateError(tv[t * 3 + 1], tv[t * 3 + 2])
            val e2 = calculateError(tv[t * 3 + 2], tv[t * 3])
            terr[t * 4] = e0.toFloat(); terr[t * 4 + 1] = e1.toFloat(); terr[t * 4 + 2] = e2.toFloat()
            terr[t * 4 + 3] = min(e0, min(e1, e2)).toFloat()
            addRef(r)
        }
    }

    private fun computeNormal(t: Int) {
        val a = tv[t * 3]; val b = tv[t * 3 + 1]; val c = tv[t * 3 + 2]
        val ux = px[b] - px[a]; val uy = py[b] - py[a]; val uz = pz[b] - pz[a]
        val wx = px[c] - px[a]; val wy = py[c] - py[a]; val wz = pz[c] - pz[a]
        var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l > 0) { nx /= l; ny /= l; nz /= l }
        tn[t * 3] = nx.toFloat(); tn[t * 3 + 1] = ny.toFloat(); tn[t * 3 + 2] = nz.toFloat()
    }

    private fun updateMesh(iteration: Int) {
        if (iteration > 0) {
            // Quita los triángulos borrados
            var dst = 0
            for (i in 0 until nt) {
                if (tdel[i]) continue
                if (dst != i) {
                    tv[dst * 3] = tv[i * 3]; tv[dst * 3 + 1] = tv[i * 3 + 1]; tv[dst * 3 + 2] = tv[i * 3 + 2]
                    for (k in 0 until 4) terr[dst * 4 + k] = terr[i * 4 + k]
                    tn[dst * 3] = tn[i * 3]; tn[dst * 3 + 1] = tn[i * 3 + 1]; tn[dst * 3 + 2] = tn[i * 3 + 2]
                }
                tdel[dst] = false
                tdirty[dst] = false
                dst++
            }
            nt = dst
            deletedTriangles = 0
        }
        if (iteration == 0) {
            java.util.Arrays.fill(q, 0.0)
            for (t in 0 until nt) {
                computeNormal(t)
                val nx = tn[t * 3].toDouble(); val ny = tn[t * 3 + 1].toDouble(); val nz = tn[t * 3 + 2].toDouble()
                val a = tv[t * 3]
                val d = -(nx * px[a] + ny * py[a] + nz * pz[a])
                for (j in 0 until 3) {
                    val o = tv[t * 3 + j] * 10
                    q[o] += nx * nx; q[o + 1] += nx * ny; q[o + 2] += nx * nz; q[o + 3] += nx * d
                    q[o + 4] += ny * ny; q[o + 5] += ny * nz; q[o + 6] += ny * d
                    q[o + 7] += nz * nz; q[o + 8] += nz * d; q[o + 9] += d * d
                }
            }
            for (t in 0 until nt) {
                val e0 = calculateError(tv[t * 3], tv[t * 3 + 1])
                val e1 = calculateError(tv[t * 3 + 1], tv[t * 3 + 2])
                val e2 = calculateError(tv[t * 3 + 2], tv[t * 3])
                terr[t * 4] = e0.toFloat(); terr[t * 4 + 1] = e1.toFloat(); terr[t * 4 + 2] = e2.toFloat()
                terr[t * 4 + 3] = min(e0, min(e1, e2)).toFloat()
            }
        }
        // Referencias vértice -> triángulos
        java.util.Arrays.fill(tcount, 0, nv, 0)
        for (i in 0 until nt * 3) tcount[tv[i]]++
        var acc = 0
        for (v in 0 until nv) { tstart[v] = acc; acc += tcount[v]; tcount[v] = 0 }
        if (refs.size < nt * 3) refs = IntArray(nt * 3)
        refCount = nt * 3
        for (t in 0 until nt) for (j in 0 until 3) {
            val v = tv[t * 3 + j]
            refs[tstart[v] + tcount[v]] = t * 4 + j
            tcount[v]++
        }
        if (iteration == 0) {
            // Bordes: aristas que pertenecen a un solo triángulo
            val vcount = IntArray(64)
            val vids = IntArray(64)
            for (v in 0 until nv) {
                var n = 0
                for (k in 0 until tcount[v]) {
                    val t = refs[tstart[v] + k] ushr 2
                    for (j in 0 until 3) {
                        val id = tv[t * 3 + j]
                        var o = 0
                        while (o < n && vids[o] != id) o++
                        if (o == n) {
                            if (n == vids.size) break
                            vids[n] = id; vcount[n] = 1; n++
                        } else vcount[o]++
                    }
                }
                for (o in 0 until n) if (vcount[o] == 1) { border[vids[o]] = true }
            }
        }
    }

    /**
     * Simplifica hasta [target] triángulos. [aggressiveness] más alto = más rápido y menos fino.
     * [progress] recibe 0–1.
     */
    fun simplify(target: Int, aggressiveness: Double = 7.0, progress: (Float) -> Unit = {}): Pair<FloatArray, IntArray> {
        val start = nt
        if (nt > target) {
            deletedTriangles = 0
            var deleted0 = BooleanArray(64)
            var deleted1 = BooleanArray(64)
            for (iteration in 0 until 100) {
                if (nt - deletedTriangles <= target) break
                if (iteration % 5 == 0) updateMesh(iteration)
                java.util.Arrays.fill(tdirty, 0, nt, false)
                val threshold = 1e-9 * (iteration + 3.0).pow(aggressiveness) * scale2
                for (t in 0 until nt) {
                    if (terr[t * 4 + 3] > threshold || tdel[t] || tdirty[t]) continue
                    for (j in 0 until 3) {
                        if (terr[t * 4 + j] >= threshold) continue
                        val i0 = tv[t * 3 + j]
                        val i1 = tv[t * 3 + (j + 1) % 3]
                        if (border[i0] != border[i1]) continue
                        calculateError(i0, i1)
                        val x = res[0]; val y = res[1]; val z = res[2]
                        if (deleted0.size < tcount[i0]) deleted0 = BooleanArray(tcount[i0] * 2)
                        if (deleted1.size < tcount[i1]) deleted1 = BooleanArray(tcount[i1] * 2)
                        if (flipped(x, y, z, i0, i1, deleted0)) continue
                        if (flipped(x, y, z, i1, i0, deleted1)) continue
                        px[i0] = x; py[i0] = y; pz[i0] = z
                        for (k in 0 until 10) q[i0 * 10 + k] += q[i1 * 10 + k]
                        val ts = refCount
                        updateTriangles(i0, i0, deleted0)
                        updateTriangles(i0, i1, deleted1)
                        val tc = refCount - ts
                        if (tc <= tcount[i0]) {
                            System.arraycopy(refs, ts, refs, tstart[i0], tc)
                        } else {
                            tstart[i0] = ts
                        }
                        tcount[i0] = tc
                        // Normales de los triángulos que cambiaron, para la prueba de volteo siguiente
                        for (k in 0 until tc) computeNormal(refs[tstart[i0] + k] ushr 2)
                        break
                    }
                    if (nt - deletedTriangles <= target) break
                }
                progress(((start - (nt - deletedTriangles)).toFloat() / (start - target).coerceAtLeast(1)).coerceIn(0f, 1f))
            }
        }
        return compact()
    }

    /** Escala del error: el umbral se ajusta al tamaño del modelo (mm, cm o unidades sueltas). */
    private val scale2: Double by lazy {
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        var minZ = Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (i in 0 until nv) {
            if (px[i] < minX) minX = px[i]; if (px[i] > maxX) maxX = px[i]
            if (py[i] < minY) minY = py[i]; if (py[i] > maxY) maxY = py[i]
            if (pz[i] < minZ) minZ = pz[i]; if (pz[i] > maxZ) maxZ = pz[i]
        }
        val d = maxOf(maxX - minX, maxY - minY, maxZ - minZ).coerceAtLeast(1e-9) / 30.0
        d * d
    }

    private fun compact(): Pair<FloatArray, IntArray> {
        val used = IntArray(nv) { -1 }
        var tris = 0
        for (t in 0 until nt) if (!tdel[t]) tris++
        val idx = IntArray(tris * 3)
        var k = 0
        var nNew = 0
        for (t in 0 until nt) {
            if (tdel[t]) continue
            for (j in 0 until 3) {
                val v = tv[t * 3 + j]
                if (used[v] < 0) used[v] = nNew++
                idx[k++] = used[v]
            }
        }
        val pos = FloatArray(nNew * 3)
        for (v in 0 until nv) {
            val n = used[v]
            if (n < 0) continue
            pos[n * 3] = px[v].toFloat(); pos[n * 3 + 1] = py[v].toFloat(); pos[n * 3 + 2] = pz[v].toFloat()
        }
        return pos to idx
    }
}
