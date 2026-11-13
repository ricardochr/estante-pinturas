package com.ricardochr.estantepinturas

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Carga de modelos 3D (STL binario o de texto, OBJ, o esos mismos dentro de un ZIP) para "Luz y sombra".
 *
 * Los archivos de miniaturas pueden pesar cientos de MB. Nunca se cargan completos: se leen en dos
 * pasadas (medidas y luego triángulos). Si tienen más triángulos de los que el teléfono mueve con
 * fluidez, se simplifican mientras se leen agrupando los vértices en una cuadrícula fina: cada celda
 * se vuelve un vértice (posición promedio) y los triángulos que quedan aplastados se descartan.
 * El resultado se guarda en la app para que la próxima vez abra al instante.
 */

/** Malla lista para dibujar: Y hacia arriba, centrada y escalada para que su lado mayor mida 2. */
class MeshData(
    val name: String,
    val positions: FloatArray,
    val normals: FloatArray,
    /** Concavidad por vértice: positiva en huecos y pliegues (reciben menos luz ambiental). */
    val cavity: FloatArray,
    val indices: IntArray,
    /** Triángulos del archivo original, antes de simplificar. */
    val sourceTriangles: Long,
    /** Calidad con que se abrió ([MeshQuality.name]); vacío si no se sabe. */
    val quality: String = "",
    /** De dónde salió, para reabrirlo con otra calidad: uri del archivo, o "zip:uri#pieza|pieza". */
    val sourceUri: String = "",
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
}

/** Modelo guardado en la app. */
data class SavedMesh(
    val file: File,
    val name: String,
    val triangles: Int,
    val sourceTriangles: Long,
    val quality: String = "",
    val sourceUri: String = "",
)

/** Pieza dentro de un ZIP. */
data class ZipPart(val name: String, val size: Long)

class MeshLoadException(message: String) : Exception(message)

/** Calidad al abrir un modelo: triángulos que se dibujan como máximo. Con más, la malla se simplifica. */
enum class MeshQuality(val label: String, val triangles: Int) {
    NORMAL("Normal", 800_000),
    ALTA("Alta", 2_000_000),
    MAXIMA("Máxima", 4_000_000),
}

// ---------------------------------------------------------------- utilidades

private class FloatList(cap: Int = 1024) {
    var data = FloatArray(cap)
    var size = 0
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    fun toArray(): FloatArray = data.copyOf(size)
}

private class IntList(cap: Int = 1024) {
    var data = IntArray(cap)
    var size = 0
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    operator fun get(i: Int) = data[i]
    fun toArray(): IntArray = data.copyOf(size)
}

/** Tabla Long -> Int sin objetos intermedios (millones de vértices sin presión de memoria). */
private class LongIntMap(initial: Int = 1 shl 16) {
    private var keys = LongArray(initial) { EMPTY }
    private var vals = IntArray(initial)
    private var count = 0
    private fun slot(k: Long, mask: Int): Int {
        var h = k * -0x61c8864680b583ebL
        h = h xor (h ushr 29)
        return (h.toInt()) and mask
    }
    fun get(k: Long): Int {
        val mask = keys.size - 1
        var i = slot(k, mask)
        while (true) {
            val kk = keys[i]
            if (kk == EMPTY) return -1
            if (kk == k) return vals[i]
            i = (i + 1) and mask
        }
    }
    fun put(k: Long, v: Int) {
        if ((count + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var i = slot(k, mask)
        while (keys[i] != EMPTY && keys[i] != k) i = (i + 1) and mask
        if (keys[i] == EMPTY) count++
        keys[i] = k
        vals[i] = v
    }
    private fun grow() {
        val ok = keys
        val ov = vals
        keys = LongArray(ok.size * 2) { EMPTY }
        vals = IntArray(ok.size * 2)
        count = 0
        for (i in ok.indices) if (ok[i] != EMPTY) put(ok[i], ov[i])
    }
    companion object { const val EMPTY = Long.MIN_VALUE }
}

/** Cuenta los bytes leídos para mostrar el avance. */
private class CountingStream(input: InputStream, private val onBytes: (Long) -> Unit) : FilterInputStream(input) {
    private var total = 0L
    private var lastReport = 0L
    private fun add(n: Long) {
        if (n <= 0) return
        total += n
        if (total - lastReport > 1_000_000) { lastReport = total; onBytes(total) }
    }
    override fun read(): Int = super.read().also { if (it >= 0) add(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { add(it.toLong()) }
    override fun skip(n: Long): Long = super.skip(n).also { add(it) }
}

/** Abre un flujo nuevo cada vez: el archivo se lee en varias pasadas. */
internal class MeshSource(val name: String, val size: Long, val opener: () -> InputStream)

internal enum class MeshFormat { STL_BINARY, STL_ASCII, OBJ }

private class Bounds {
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    var triangles = 0L
    /** Superficie total (unidades del archivo al cuadrado); 0 si no se conoce (OBJ). */
    var area = 0.0
    fun addTriangle(v: FloatArray) {
        add(v[0], v[1], v[2]); add(v[3], v[4], v[5]); add(v[6], v[7], v[8])
        val ux = (v[3] - v[0]).toDouble(); val uy = (v[4] - v[1]).toDouble(); val uz = (v[5] - v[2]).toDouble()
        val wx = (v[6] - v[0]).toDouble(); val wy = (v[7] - v[1]).toDouble(); val wz = (v[8] - v[2]).toDouble()
        val cx = uy * wz - uz * wy; val cy = uz * wx - ux * wz; val cz = ux * wy - uy * wx
        val a = 0.5 * sqrt(cx * cx + cy * cy + cz * cz)
        if (!a.isNaN()) area += a
        triangles++
    }
    fun add(x: Float, y: Float, z: Float) {
        if (x.isNaN() || y.isNaN() || z.isNaN()) return
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
    }
    val valid: Boolean get() = minX <= maxX
    val maxDim: Float get() = max(maxX - minX, max(maxY - minY, maxZ - minZ))
}

/** Agrupa vértices por celda de la cuadrícula. Con celdas diminutas solo une los vértices repetidos. */
private class Clusterer(private val b: Bounds, cells: Long, expectedTris: Int) {
    private val n = cells.coerceIn(8, 1L shl 20)
    private val cell = (b.maxDim / n).coerceAtLeast(1e-9f) * 1.0001f
    private val map = LongIntMap()
    // Capacidad inicial según lo esperado: evita copias de arreglos enormes al crecer
    val sums = FloatList(max(1 shl 16, expectedTris / 2 * 3))
    val counts = IntList(max(1 shl 14, expectedTris / 2))
    val tris = IntList(max(1 shl 16, expectedTris * 3))

    fun vertex(x: Float, y: Float, z: Float): Int {
        val ix = ((x - b.minX) / cell).toLong().coerceIn(0, n - 1)
        val iy = ((y - b.minY) / cell).toLong().coerceIn(0, n - 1)
        val iz = ((z - b.minZ) / cell).toLong().coerceIn(0, n - 1)
        val key = ix + n * (iy + n * iz)
        var id = map.get(key)
        if (id < 0) {
            id = counts.size
            map.put(key, id)
            sums.add(0f); sums.add(0f); sums.add(0f)
            counts.add(0)
        }
        sums.data[id * 3] += x
        sums.data[id * 3 + 1] += y
        sums.data[id * 3 + 2] += z
        counts.data[id]++
        return id
    }

    fun tri(a: Int, b: Int, c: Int) {
        if (a == b || b == c || a == c) return
        tris.add(a); tris.add(b); tris.add(c)
    }
}

// ---------------------------------------------------------------- lectura

internal fun detect(src: MeshSource): MeshFormat {
    val lower = src.name.lowercase()
    if (lower.endsWith(".obj")) return MeshFormat.OBJ
    val head = ByteArray(512)
    var n = 0
    src.opener().use { s -> while (n < head.size) { val r = s.read(head, n, head.size - n); if (r <= 0) break; n += r } }
    if (n < 84) {
        val text = String(head, 0, n, Charsets.ISO_8859_1).trimStart()
        if (text.startsWith("solid")) return MeshFormat.STL_ASCII
        throw MeshLoadException(tr("El archivo está vacío o no es un modelo 3D."))
    }
    val count = ByteBuffer.wrap(head, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    val text = String(head, 0, n, Charsets.ISO_8859_1)
    val binarySizeMatches = src.size > 0 && src.size == 84 + 50 * count
    if (!binarySizeMatches && text.trimStart().startsWith("solid") && text.contains("facet")) return MeshFormat.STL_ASCII
    if (lower.endsWith(".stl") || binarySizeMatches || count > 0) return MeshFormat.STL_BINARY
    throw MeshLoadException(tr("Formato no reconocido. Usa STL, OBJ o un ZIP que los contenga."))
}

/** Recorre los triángulos de un STL binario. */
private fun readStlBinary(input: InputStream, onTri: (FloatArray) -> Unit) {
    val s = BufferedInputStream(input, 1 shl 20)
    val header = ByteArray(84)
    readFully(s, header, 84)
    val count = ByteBuffer.wrap(header, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    val chunkTris = 20_000
    val buf = ByteArray(chunkTris * 50)
    val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
    val v = FloatArray(9)
    var left = count
    while (left > 0) {
        val nTris = min(left, chunkTris.toLong()).toInt()
        val got = readUpTo(s, buf, nTris * 50)
        val full = got / 50
        for (t in 0 until full) {
            val base = t * 50 + 12
            for (k in 0 until 9) v[k] = bb.getFloat(base + k * 4)
            onTri(v)
        }
        if (got < nTris * 50) break // archivo cortado: se usa lo que hay
        left -= nTris
    }
}

private fun readFully(s: InputStream, b: ByteArray, len: Int) {
    if (readUpTo(s, b, len) < len) throw MeshLoadException(tr("El archivo está incompleto."))
}

private fun readUpTo(s: InputStream, b: ByteArray, len: Int): Int {
    var n = 0
    while (n < len) {
        val r = s.read(b, n, len - n)
        if (r <= 0) break
        n += r
    }
    return n
}

/** Recorre los triángulos de un STL de texto. */
private fun readStlAscii(input: InputStream, onTri: (FloatArray) -> Unit) {
    val r = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1), 1 shl 20)
    val v = FloatArray(9)
    var k = 0
    while (true) {
        val line = r.readLine() ?: break
        val t = line.trim()
        if (!t.startsWith("vertex")) continue
        val parts = t.split(WS)
        if (parts.size < 4) continue
        v[k * 3] = parts[1].toFloatOrNull() ?: 0f
        v[k * 3 + 1] = parts[2].toFloatOrNull() ?: 0f
        v[k * 3 + 2] = parts[3].toFloatOrNull() ?: 0f
        k++
        if (k == 3) { onTri(v); k = 0 }
    }
}

private val WS = Regex("\\s+")

/**
 * Recorre un OBJ: [onVertex] por cada "v" y [onFace] por cada triángulo (índices desde 0; los polígonos se
 * dividen en abanico).
 */
private fun readObj(input: InputStream, onVertex: (Float, Float, Float) -> Unit, onFace: (Int, Int, Int) -> Unit) {
    val r = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1), 1 shl 20)
    var vertices = 0
    val face = IntList(16)
    while (true) {
        val line = r.readLine() ?: break
        if (line.length < 2) continue
        if (line[0] == 'v' && line[1] == ' ') {
            val p = line.substring(2).trim().split(WS)
            if (p.size >= 3) {
                onVertex(p[0].toFloatOrNull() ?: 0f, p[1].toFloatOrNull() ?: 0f, p[2].toFloatOrNull() ?: 0f)
                vertices++
            }
        } else if (line[0] == 'f' && line[1] == ' ') {
            face.size = 0
            for (tok in line.substring(2).trim().split(WS)) {
                val s = tok.substringBefore('/').toIntOrNull() ?: continue
                face.add(if (s < 0) vertices + s else s - 1)
            }
            for (i in 1 until face.size - 1) onFace(face[0], face[i], face[i + 1])
        }
    }
}

// ---------------------------------------------------------------- carga completa

/** Malla en las coordenadas del archivo, antes de orientarla y escalarla. */
internal class RawMesh(val pos: FloatArray, val idx: IntArray, val sourceTris: Long)

/** Triángulos de entrada máximos para la simplificación por error cuádrico (memoria del teléfono). */
private const val QEM_MAX_INPUT = 2_500_000

/**
 * Lee el modelo en coordenadas del archivo. Si pasa de [budget] triángulos, primero se agrupan los
 * vértices en una cuadrícula fina (hasta el doble del presupuesto, para que quepa en memoria) y luego se
 * simplifica por error cuádrico hasta [budget], conservando filos y detalle. Con [qem] = false solo
 * se usa la cuadrícula (método anterior; también es el respaldo si falta memoria).
 */
internal fun readRaw(src: MeshSource, budget: Int, progress: (String, Float) -> Unit, qem: Boolean = true): RawMesh {
    val format = detect(src)
    val total = src.size.coerceAtLeast(1)
    fun counted(phase: String): InputStream = CountingStream(src.opener()) { progress(phase, (it.toFloat() / total).coerceIn(0f, 1f)) }

    // 1) Medidas y cantidad de triángulos
    val b = Bounds()
    var objVertices = 0
    when (format) {
        MeshFormat.STL_BINARY -> counted(tr("Midiendo")).use { s ->
            readStlBinary(s) { v -> b.addTriangle(v) }
        }
        MeshFormat.STL_ASCII -> counted(tr("Midiendo")).use { s ->
            readStlAscii(s) { v -> b.addTriangle(v) }
        }
        MeshFormat.OBJ -> counted(tr("Midiendo")).use { s ->
            readObj(s, { x, y, z -> b.add(x, y, z); objVertices++ }, { _, _, _ -> b.triangles++ })
        }
    }
    if (!b.valid || b.triangles == 0L) throw MeshLoadException(tr("No encontré triángulos en el archivo."))

    // 2) Triángulos. Si caben solo se unen los vértices repetidos (sin perder nada). Si no, el tamaño de
    // celda sale de la superficie real: cada vértice nuevo cubre más o menos celda² de superficie, y hay
    // cerca de la mitad de vértices que de triángulos.
    val inter = min(b.triangles, min(budget * 2L, QEM_MAX_INPUT.toLong())).toInt()
    val useQem = qem && b.triangles > budget && inter > budget * 1.1
    val gridBudget = if (useQem) inter else budget
    val targetVerts = gridBudget / 2.0 * 0.9
    var cells: Long = when {
        b.triangles <= gridBudget -> 1L shl 20
        b.area > 0 -> (b.maxDim / sqrt(1.6 * b.area / targetVerts)).toLong()
        else -> sqrt(targetVerts / 3.0).toLong()
    }
    val expected = min(b.triangles, (gridBudget * 1.2).toLong()).toInt()
    var cl: Clusterer
    var attempt = 0
    while (true) {
        cl = Clusterer(b, cells, expected)
        val c = cl
        val phase = if (attempt == 0) tr("Leyendo") else tr("Simplificando más")
        when (format) {
            MeshFormat.STL_BINARY -> counted(phase).use { s ->
                readStlBinary(s) { v -> c.tri(c.vertex(v[0], v[1], v[2]), c.vertex(v[3], v[4], v[5]), c.vertex(v[6], v[7], v[8])) }
            }
            MeshFormat.STL_ASCII -> counted(phase).use { s ->
                readStlAscii(s) { v -> c.tri(c.vertex(v[0], v[1], v[2]), c.vertex(v[3], v[4], v[5]), c.vertex(v[6], v[7], v[8])) }
            }
            MeshFormat.OBJ -> {
                val ids = IntArray(objVertices)
                var vi = 0
                counted(phase).use { s ->
                    readObj(s, { x, y, z -> if (vi < ids.size) ids[vi] = c.vertex(x, y, z); vi++ }, { i, j, k ->
                        if (i in ids.indices && j in ids.indices && k in ids.indices) c.tri(ids[i], ids[j], ids[k])
                    })
                }
            }
        }
        val got = c.tris.size / 3
        if (got <= gridBudget * 1.3 || attempt >= 3) break
        // Quedó más detallado de lo previsto: celdas más grandes y otra pasada
        cells = (cells * sqrt(gridBudget.toDouble() / got) * 0.95).toLong().coerceAtLeast(16)
        attempt++
    }
    if (cl.tris.size == 0) throw MeshLoadException(tr("El modelo quedó vacío al leerlo."))
    val nv = cl.counts.size
    var pos = FloatArray(nv * 3)
    for (i in 0 until nv) {
        val c = cl.counts.data[i].coerceAtLeast(1).toFloat()
        pos[i * 3] = cl.sums.data[i * 3] / c
        pos[i * 3 + 1] = cl.sums.data[i * 3 + 1] / c
        pos[i * 3 + 2] = cl.sums.data[i * 3 + 2] / c
    }
    var idx = if (cl.tris.data.size == cl.tris.size) cl.tris.data else cl.tris.toArray()
    if (useQem && idx.size / 3 > budget) {
        try {
            val simp = MeshSimplifier(pos, idx)
            pos = FloatArray(0); idx = IntArray(0) // libera memoria antes de simplificar
            val (p2, i2) = simp.simplify(budget) { progress(tr("Simplificando sin perder filos"), it) }
            pos = p2; idx = i2
        } catch (e: OutOfMemoryError) {
            return readRaw(src, budget, progress, qem = false)
        }
    }
    return RawMesh(pos, idx, b.triangles)
}

/** Une varias piezas (por ejemplo, las de un ZIP) en una sola malla. */
internal fun mergeRaw(parts: List<RawMesh>): RawMesh {
    val pos = FloatArray(parts.sumOf { it.pos.size })
    val idx = IntArray(parts.sumOf { it.idx.size })
    var po = 0
    var io = 0
    for (p in parts) {
        val base = po / 3
        System.arraycopy(p.pos, 0, pos, po, p.pos.size)
        for (k in p.idx.indices) idx[io + k] = p.idx[k] + base
        po += p.pos.size
        io += p.idx.size
    }
    return RawMesh(pos, idx, parts.sumOf { it.sourceTris })
}

/** Dirección "abajo" del archivo: 0 −X, 1 +X, 2 −Y, 3 +Y, 4 −Z, 5 +Z. */
internal fun detectDown(pos: FloatArray, idx: IntArray): Int {
    val mn = FloatArray(3) { Float.MAX_VALUE }
    val mx = FloatArray(3) { -Float.MAX_VALUE }
    for (i in 0 until pos.size / 3) for (a in 0 until 3) {
        val v = pos[i * 3 + a]
        if (v < mn[a]) mn[a] = v; if (v > mx[a]) mx[a] = v
    }
    val ext = maxOf(mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2]).coerceAtLeast(1e-9f)
    val area = DoubleArray(6)
    var totalArea = 0.0
    var t = 0
    while (t < idx.size) {
        val a = idx[t] * 3; val b = idx[t + 1] * 3; val c = idx[t + 2] * 3
        val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
        val wx = pos[c] - pos[a]; val wy = pos[c + 1] - pos[a + 1]; val wz = pos[c + 2] - pos[a + 2]
        val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        t += 3
        if (l <= 0f) continue
        val ar = l / 2.0
        totalArea += ar
        val n = floatArrayOf(nx / l, ny / l, nz / l)
        for (axis in 0 until 3) {
            val cen = (pos[a + axis] + pos[b + axis] + pos[c + axis]) / 3f
            // Cara plana que mira hacia afuera en el extremo del eje: la base de la peana
            if (n[axis] < -0.95f && cen <= mn[axis] + 0.02f * ext) area[axis * 2] += ar
            if (n[axis] > 0.95f && cen >= mx[axis] - 0.02f * ext) area[axis * 2 + 1] += ar
        }
    }
    val best = area.indices.maxByOrNull { area[it] } ?: 4
    // Sin una base clara se asume lo normal en impresión: Z hacia arriba
    return if (area[best] < totalArea * 0.004) 4 else best
}

/** Gira de las coordenadas del archivo a las de dibujo (Y arriba) según qué lado es la base. */
private fun orientPoint(down: Int, x: Float, y: Float, z: Float, out: FloatArray) {
    when (down) {
        0 -> { out[0] = y; out[1] = x; out[2] = -z }
        1 -> { out[0] = y; out[1] = -x; out[2] = z }
        2 -> { out[0] = x; out[1] = y; out[2] = z }
        3 -> { out[0] = x; out[1] = -y; out[2] = -z }
        5 -> { out[0] = x; out[1] = -z; out[2] = y }
        else -> { out[0] = x; out[1] = z; out[2] = -y } // Z arriba, frente hacia −Y
    }
}

/** Posiciones finales (Y arriba, centrado, lado mayor = 2), normales y concavidad. */
internal fun finish(name: String, raw: RawMesh, quality: String = "", sourceUri: String = ""): MeshData {
    val nv = raw.pos.size / 3
    val down = detectDown(raw.pos, raw.idx)
    val pos = FloatArray(nv * 3)
    val o = FloatArray(3)
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    for (i in 0 until nv) {
        orientPoint(down, raw.pos[i * 3], raw.pos[i * 3 + 1], raw.pos[i * 3 + 2], o)
        val px = o[0]; val py = o[1]; val pz = o[2]
        pos[i * 3] = px; pos[i * 3 + 1] = py; pos[i * 3 + 2] = pz
        if (px < minX) minX = px; if (px > maxX) maxX = px
        if (py < minY) minY = py; if (py > maxY) maxY = py
        if (pz < minZ) minZ = pz; if (pz > maxZ) maxZ = pz
    }
    val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2; val cz = (minZ + maxZ) / 2
    val s = 2f / max(maxX - minX, max(maxY - minY, maxZ - minZ)).coerceAtLeast(1e-9f)
    for (i in 0 until nv) {
        pos[i * 3] = (pos[i * 3] - cx) * s
        pos[i * 3 + 1] = (pos[i * 3 + 1] - cy) * s
        pos[i * 3 + 2] = (pos[i * 3 + 2] - cz) * s
    }
    // Las rotaciones usadas tienen determinante +1: el orden de los vértices (y el frente de las caras) se conserva
    val (nrm, cav) = normalsAndCavity(pos, raw.idx)
    return MeshData(name, pos, nrm, cav, raw.idx, raw.sourceTris, quality, sourceUri)
}

private fun normalsAndCavity(pos: FloatArray, idx: IntArray): Pair<FloatArray, FloatArray> {
    val nv = pos.size / 3
    val nrm = FloatArray(nv * 3)
    var t = 0
    while (t < idx.size) {
        val a = idx[t] * 3; val b = idx[t + 1] * 3; val c = idx[t + 2] * 3
        val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
        val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
        // Producto cruz sin normalizar: pesa por área
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        for (o in intArrayOf(a, b, c)) { nrm[o] += nx; nrm[o + 1] += ny; nrm[o + 2] += nz }
        t += 3
    }
    for (i in 0 until nv) {
        val l = sqrt(nrm[i * 3] * nrm[i * 3] + nrm[i * 3 + 1] * nrm[i * 3 + 1] + nrm[i * 3 + 2] * nrm[i * 3 + 2])
        if (l > 1e-12f) { nrm[i * 3] /= l; nrm[i * 3 + 1] /= l; nrm[i * 3 + 2] /= l } else nrm[i * 3 + 1] = 1f
    }
    // Concavidad: cuánto "suben" los vecinos sobre el plano tangente de cada vértice
    val acc = FloatArray(nv)
    val cnt = IntArray(nv)
    fun edge(a: Int, b: Int) {
        val dx = pos[b * 3] - pos[a * 3]; val dy = pos[b * 3 + 1] - pos[a * 3 + 1]; val dz = pos[b * 3 + 2] - pos[a * 3 + 2]
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        if (l < 1e-12f) return
        acc[a] += (nrm[a * 3] * dx + nrm[a * 3 + 1] * dy + nrm[a * 3 + 2] * dz) / l; cnt[a]++
        acc[b] += -(nrm[b * 3] * dx + nrm[b * 3 + 1] * dy + nrm[b * 3 + 2] * dz) / l; cnt[b]++
    }
    t = 0
    while (t < idx.size) {
        edge(idx[t], idx[t + 1]); edge(idx[t + 1], idx[t + 2]); edge(idx[t + 2], idx[t])
        t += 3
    }
    var cav = FloatArray(nv) { if (cnt[it] > 0) acc[it] / cnt[it] else 0f }
    // Suavizado sobre los vecinos para que el efecto cubra pliegues y no solo aristas sueltas
    repeat(3) {
        val sum = FloatArray(nv)
        val n = IntArray(nv)
        t = 0
        while (t < idx.size) {
            for (k in 0 until 3) {
                val a = idx[t + k]; val b = idx[t + (k + 1) % 3]
                sum[a] += cav[b]; n[a]++
                sum[b] += cav[a]; n[b]++
            }
            t += 3
        }
        cav = FloatArray(nv) { if (n[it] > 0) 0.4f * cav[it] + 0.6f * sum[it] / n[it] else cav[it] }
    }
    return nrm to cav
}

// ---------------------------------------------------------------- archivos, ZIP y caché

private fun displayName(context: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "modelo"
    var size = -1L
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    } catch (_: Exception) {
    }
    return name to size
}

private fun isModelName(n: String): Boolean { val l = n.lowercase(); return l.endsWith(".stl") || l.endsWith(".obj") }

/** Si el archivo es un ZIP, lo copia a la app y devuelve sus piezas STL/OBJ (mayor primero). Si no, null. */
fun zipPartsOf(context: Context, uri: Uri): Pair<File, List<ZipPart>>? {
    val (name, _) = displayName(context, uri)
    val magic = ByteArray(4)
    context.contentResolver.openInputStream(uri)?.use { readUpTo(it, magic, 4) } ?: throw MeshLoadException(tr("No pude abrir el archivo."))
    val isZip = name.lowercase().endsWith(".zip") || (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() && magic[2].toInt() == 3 && magic[3].toInt() == 4)
    if (!isZip) return null
    val dir = File(context.cacheDir, "zip").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val f = File(dir, name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._ -]"), "_").take(60).ifBlank { "modelo" } + ".zip")
    context.contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it, 1 shl 20) } }
    val parts = ZipFile(f).use { z ->
        z.entries().asSequence().filter { !it.isDirectory && isModelName(it.name) && !it.name.contains("__MACOSX") }
            .map { ZipPart(it.name, it.size) }.sortedByDescending { it.size }.toList()
    }
    if (parts.isEmpty()) throw MeshLoadException(tr("El ZIP no tiene archivos STL ni OBJ."))
    return f to parts
}

/** Carga un STL u OBJ suelto. */
fun loadMeshFromUri(context: Context, uri: Uri, quality: MeshQuality, progress: (String, Float) -> Unit): MeshData {
    val (name, size) = displayName(context, uri)
    val src = MeshSource(name, size) { context.contentResolver.openInputStream(uri) ?: throw MeshLoadException(tr("No pude abrir el archivo.")) }
    return finish(name, readRaw(src, quality.triangles, progress), quality.name, uri.toString())
}

/**
 * Carga una o varias piezas de un ZIP ya copiado con [zipPartsOf] y las une. El presupuesto de
 * triángulos se reparte según el tamaño de cada pieza.
 */
fun loadMeshFromZip(zip: File, parts: List<ZipPart>, quality: MeshQuality, sourceUri: String, progress: (String, Float) -> Unit): MeshData {
    if (parts.isEmpty()) throw MeshLoadException(tr("No elegiste ninguna pieza."))
    val z = ZipFile(zip)
    try {
        val totalSize = parts.sumOf { it.size.coerceAtLeast(1) }.toDouble()
        val raws = parts.mapIndexed { i, part ->
            val entry = z.getEntry(part.name) ?: throw MeshLoadException(tr("No encontré la pieza en el ZIP."))
            val src = MeshSource(part.name.substringAfterLast('/'), entry.size) { z.getInputStream(entry) }
            val share = (quality.triangles * part.size.coerceAtLeast(1) / totalSize).toInt().coerceAtLeast(20_000)
            readRaw(src, share, { phase, p -> progress(if (parts.size > 1) tr("Pieza {} de {}: {}", i + 1, parts.size, phase) else phase, p) })
        }
        val name = if (parts.size == 1) parts[0].name.substringAfterLast('/')
        else tr("{} ({} piezas)", zip.nameWithoutExtension.takeIf { it != "modelo" } ?: parts[0].name.substringAfterLast('/').substringBeforeLast('.'), parts.size)
        val src = "zip:$sourceUri#" + parts.joinToString("|") { it.name }
        return finish(name, if (raws.size == 1) raws[0] else mergeRaw(raws), quality.name, src)
    } finally {
        z.close()
    }
}

/** Vuelve a abrir el archivo original de un modelo guardado con otra calidad. */
fun reopenMesh(context: Context, saved: SavedMesh, quality: MeshQuality, progress: (String, Float) -> Unit): MeshData {
    val src = saved.sourceUri
    if (src.isBlank()) throw MeshLoadException(tr("Este modelo se guardó con una versión anterior: ábrelo de nuevo desde el archivo."))
    try {
        if (src.startsWith("zip:")) {
            val uri = Uri.parse(src.removePrefix("zip:").substringBefore('#'))
            val names = src.substringAfter('#').split('|').toSet()
            val (zip, parts) = zipPartsOf(context, uri) ?: throw MeshLoadException(tr("El archivo original ya no está disponible."))
            return loadMeshFromZip(zip, parts.filter { it.name in names }, quality, uri.toString(), progress)
        }
        return loadMeshFromUri(context, Uri.parse(src), quality, progress)
    } catch (e: SecurityException) {
        throw MeshLoadException(tr("El archivo original ya no está disponible."))
    } catch (e: java.io.FileNotFoundException) {
        throw MeshLoadException(tr("El archivo original ya no está disponible."))
    }
}

private fun meshDir(context: Context) = File(context.filesDir, "modelos").apply { mkdirs() }

private const val MAGIC = 0x474D5348 // "GMSH": primera versión, sin calidad ni origen
private const val MAGIC2 = 0x474D5332 // "GMS2"

private fun ByteBuffer.putStr(s: String): ByteBuffer { val b = s.toByteArray(Charsets.UTF_8); putInt(b.size); put(b); return this }
private fun ByteBuffer.getStr(): String { val b = ByteArray(int); get(b); return String(b, Charsets.UTF_8) }

/** Guarda la malla preparada para abrirla al instante la próxima vez. */
fun saveMesh(context: Context, m: MeshData): File {
    val safe = m.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._ -]"), "_").take(60).ifBlank { "modelo" }
    val f = File(meshDir(context), "$safe.gmesh")
    val strs = listOf(m.name, m.quality, m.sourceUri).sumOf { 4 + it.toByteArray(Charsets.UTF_8).size }
    val bytes = 4 + strs + 8 + 4 + 4 + (m.positions.size + m.normals.size + m.cavity.size) * 4 + m.indices.size * 4
    val bb = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
    bb.putInt(MAGIC2).putStr(m.name).putStr(m.quality).putStr(m.sourceUri)
    bb.putLong(m.sourceTriangles).putInt(m.vertexCount).putInt(m.indices.size)
    bb.asFloatBuffer().put(m.positions); bb.position(bb.position() + m.positions.size * 4)
    bb.asFloatBuffer().put(m.normals); bb.position(bb.position() + m.normals.size * 4)
    bb.asFloatBuffer().put(m.cavity); bb.position(bb.position() + m.cavity.size * 4)
    bb.asIntBuffer().put(m.indices); bb.position(bb.position() + m.indices.size * 4)
    bb.flip()
    RandomAccessFile(f, "rw").use { raf -> raf.setLength(0); raf.channel.write(bb) }
    return f
}

private class MeshHeader(val name: String, val quality: String, val sourceUri: String, val src: Long, val nv: Int, val ni: Int)

private fun readHeader(bb: ByteBuffer): MeshHeader? = when (bb.int) {
    MAGIC -> { val n = bb.getStr(); MeshHeader(n, "", "", bb.long, bb.int, bb.int) }
    MAGIC2 -> { val n = bb.getStr(); val q = bb.getStr(); val u = bb.getStr(); MeshHeader(n, q, u, bb.long, bb.int, bb.int) }
    else -> null
}

fun loadSavedMesh(f: File): MeshData {
    RandomAccessFile(f, "r").use { raf ->
        val bb = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
        val h = readHeader(bb) ?: throw MeshLoadException(tr("Modelo guardado dañado."))
        val pos = FloatArray(h.nv * 3); bb.asFloatBuffer().get(pos); bb.position(bb.position() + pos.size * 4)
        val nrm = FloatArray(h.nv * 3); bb.asFloatBuffer().get(nrm); bb.position(bb.position() + nrm.size * 4)
        val cav = FloatArray(h.nv); bb.asFloatBuffer().get(cav); bb.position(bb.position() + cav.size * 4)
        val idx = IntArray(h.ni); bb.asIntBuffer().get(idx)
        return MeshData(h.name, pos, nrm, cav, idx, h.src, h.quality, h.sourceUri)
    }
}

/** Lee solo el encabezado de los modelos guardados (más reciente primero). */
fun savedMeshes(context: Context): List<SavedMesh> =
    meshDir(context).listFiles { f -> f.name.endsWith(".gmesh") }.orEmpty().sortedByDescending { it.lastModified() }.mapNotNull { f ->
        try {
            RandomAccessFile(f, "r").use { raf ->
                val bb = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, min(raf.length(), 16384L)).order(ByteOrder.LITTLE_ENDIAN)
                val h = readHeader(bb) ?: return@mapNotNull null
                SavedMesh(f, h.name, h.ni / 3, h.src, h.quality, h.sourceUri)
            }
        } catch (_: Exception) {
            null
        }
    }
