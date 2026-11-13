package com.ricardochr.estantepinturas

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * "Luz y sombra" con fotos: un modelo de profundidad (Depth Anything V2 Small) estima qué tan cerca está
 * cada punto de la figura. Con eso se arma un relieve, se separa la figura del fondo y se ilumina con una
 * luz que se mueve: luz difusa, sombras proyectadas (recorriendo el relieve hacia la luz) y huecos.
 *
 * El relieve y las sombras se calculan a la resolución del modelo (son suaves) y se amplían con
 * interpolación; el recorte de la figura y el detalle fino del esculpido salen de la foto a resolución
 * completa, así el resultado se ve nítido.
 */

/** Variante del modelo: la rápida está comprimida; la precisa conserva todo el detalle y usa más resolución. */
enum class DepthVariant(val label: String, val file: String, val sizeMb: Int, val minBytes: Long, val input: Int) {
    RAPIDA("Rápida", "depth-v2-small-q8.onnx", 27, 20_000_000L, 518),
    PRECISA("Precisa", "depth-v2-small-fp32.onnx", 99, 80_000_000L, 672),
}

object DepthModel {
    private const val BASE = "https://github.com/ricardochr/estante-pinturas/releases/download/model-depth-v1/"

    private fun file(context: Context, v: DepthVariant) = File(File(context.filesDir, "models").apply { mkdirs() }, v.file)

    fun isReady(context: Context, v: DepthVariant): Boolean = file(context, v).let { it.exists() && it.length() > v.minBytes }

    /** Descarga el modelo una sola vez. Devuelve null si salió bien, o el motivo del fallo. */
    fun download(context: Context, v: DepthVariant, progress: (Float) -> Unit): String? {
        val dest = file(context, v)
        val part = File(dest.parentFile, dest.name + ".part")
        return try {
            var url = URL(BASE + v.file)
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                if (code in 300..399 && hops < 5) {
                    // i18n:off
                    url = URL(url, conn.getHeaderField("Location"))
                    // i18n:on
                    conn.disconnect()
                    hops++
                    continue
                }
                if (code != 200) { conn.disconnect(); return tr("HTTP {}", code) }
                break
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (v.sizeMb * 1_000_000L)
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            conn.disconnect()
            if (part.length() < v.minBytes) { part.delete(); return tr("descarga incompleta") }
            if (!part.renameTo(dest)) return tr("no se pudo guardar")
            null
        } catch (e: Exception) {
            part.delete()
            e.message ?: e.javaClass.simpleName
        }
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var loaded: DepthVariant? = null

    @Synchronized
    private fun session(context: Context, v: DepthVariant): OrtSession {
        if (loaded == v) session?.let { return it }
        session?.close()
        session = null
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(max(1, min(4, Runtime.getRuntime().availableProcessors())))
        }
        val s = e.createSession(file(context, v).absolutePath, opts)
        env = e
        session = s
        loaded = v
        return s
    }

    /** Profundidad relativa (mayor = más cerca) a la resolución de entrada del modelo. */
    @Synchronized
    fun estimate(context: Context, v: DepthVariant, bmp: Bitmap): DepthMap {
        val s = session(context, v)
        val scale = v.input.toFloat() / max(bmp.width, bmp.height)
        fun dim(x: Int) = max(14 * 8, ((x * scale) / 14).toInt() * 14)
        val w = dim(bmp.width)
        val h = dim(bmp.height)
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()
        val fb = ByteBuffer.allocateDirect(3 * w * h * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (c in 0 until 3) {
            val shift = 16 - c * 8
            for (i in 0 until w * h) fb.put((((px[i] shr shift) and 0xFF) / 255f - mean[c]) / std[c])
        }
        fb.position(0)
        val e = env ?: OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(e, fb, longArrayOf(1, 3, h.toLong(), w.toLong())).use { input ->
            s.run(mapOf(s.inputNames.first() to input)).use { out ->
                val t = out.get(0) as OnnxTensor
                val shape = t.info.shape
                val oh = shape[shape.size - 2].toInt()
                val ow = shape[shape.size - 1].toInt()
                val buf = t.floatBuffer
                val d = FloatArray(ow * oh)
                buf.get(d)
                return DepthMap(ow, oh, d)
            }
        }
    }
}

class DepthMap(val w: Int, val h: Int, val d: FloatArray)

private val LIN = FloatArray(256) {
    val s = it / 255f
    if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
}

private fun enc(v: Float): Int {
    val c = v.coerceIn(0f, 1f)
    val s = if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
    return (s * 255f + 0.5f).toInt().coerceIn(0, 255)
}

/** Cómo se muestra la foto iluminada. */
enum class PhotoLook(val label: String) {
    ARCILLA("Arcilla"),
    FOTO("Sobre la foto"),
}

/** Lado desde el que se tomó la foto, visto desde la figura. [yaw]: dónde está la cámara (0 = frente). */
enum class PhotoSide(val label: String, val yaw: Float) {
    FRENTE("Frente", 0f),
    DERECHA("Su derecha", -90f),
    ESPALDA("Espalda", 180f),
    IZQUIERDA("Su izquierda", 90f),
}

/** Muestreo bilineal de una imagen de baja resolución sobre una de alta: índices y pesos por fila y columna. */
private class Upsampler(private val lw: Int, lh: Int, val hw: Int, val hh: Int) {
    val x0 = IntArray(hw); val x1 = IntArray(hw); val tx = FloatArray(hw)
    val y0 = IntArray(hh); val y1 = IntArray(hh); val ty = FloatArray(hh)
    init {
        for (x in 0 until hw) {
            val f = ((x + 0.5f) * lw / hw - 0.5f).coerceIn(0f, (lw - 1).toFloat())
            x0[x] = f.toInt(); x1[x] = min(lw - 1, x0[x] + 1); tx[x] = f - x0[x]
        }
        for (y in 0 until hh) {
            val f = ((y + 0.5f) * lh / hh - 0.5f).coerceIn(0f, (lh - 1).toFloat())
            y0[y] = f.toInt(); y1[y] = min(lh - 1, y0[y] + 1); ty[y] = f - y0[y]
        }
    }
    fun sample(src: FloatArray, x: Int, y: Int): Float {
        val a = src[y0[y] * lw + x0[x]]; val b = src[y0[y] * lw + x1[x]]
        val c = src[y1[y] * lw + x0[x]]; val d = src[y1[y] * lw + x1[x]]
        val top = a + (b - a) * tx[x]
        val bot = c + (d - c) * tx[x]
        return top + (bot - top) * ty[y]
    }
}

/**
 * Una foto lista para iluminar. El relieve, las normales, los huecos y las sombras se calculan a la
 * resolución del mapa de profundidad (w×h); el recorte y el detalle a la de la foto (W×H).
 */
class PhotoScene(
    val w: Int,
    val h: Int,
    private val photoLo: IntArray,
    private val depth: FloatArray,
    private val W: Int,
    private val H: Int,
    private val photoHi: IntArray,
) {
    var mask = BooleanArray(w * h); private set
    private var height = FloatArray(w * h)
    private var nx = FloatArray(w * h)
    private var ny = FloatArray(w * h)
    private var nz = FloatArray(w * h)
    private var ao = FloatArray(w * h)
    private var hMax = 0f
    private var prepared: Pair<Float, Float>? = null

    private val up = Upsampler(w, h, W, H)
    private var maskHi = BooleanArray(W * H)
    /** Detalle fino de la foto: luminancia / luminancia suavizada (1 = sin detalle). */
    private val detail: FloatArray by lazy { computeDetail() }

    companion object {
        fun from(bmp: Bitmap, dm: DepthMap): PhotoScene {
            val small = Bitmap.createScaledBitmap(bmp, dm.w, dm.h, true)
            val lo = IntArray(dm.w * dm.h)
            small.getPixels(lo, 0, dm.w, 0, 0, dm.w, dm.h)
            if (small !== bmp) small.recycle()
            val hi = IntArray(bmp.width * bmp.height)
            bmp.getPixels(hi, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return PhotoScene(dm.w, dm.h, lo, dm.d, bmp.width, bmp.height, hi)
        }
    }

    private fun lum(c: Int): Float = 0.2126f * LIN[(c shr 16) and 0xFF] + 0.7152f * LIN[(c shr 8) and 0xFF] + 0.0722f * LIN[c and 0xFF]

    private fun computeDetail(): FloatArray {
        val n = W * H
        val y = FloatArray(n) { lum(photoHi[it]) }
        val r = max(2, max(W, H) / 400)
        var b = boxBlurAll(y, W, H, r)
        b = boxBlurAll(b, W, H, r)
        return FloatArray(n) { (y[it] + 0.004f) / (b[it] + 0.004f) }
    }

    /**
     * Recorta la figura y arma el relieve.
     * @param cut ajuste del recorte (−1 incluye más, +1 recorta más).
     * @param relief volumen del relieve (0.2–1.5).
     */
    fun prepare(cut: Float, relief: Float) {
        if (prepared == cut to relief) return
        val n = w * h
        // Profundidad normalizada con percentiles para ignorar valores extremos
        val sorted = depth.copyOf().also { it.sort() }
        val lo = sorted[(n * 0.01).toInt()]
        val hi = sorted[min(n - 1, (n * 0.99).toInt())]
        val span = (hi - lo).coerceAtLeast(1e-6f)
        val dn = FloatArray(n) { ((depth[it] - lo) / span).coerceIn(0f, 1f) }

        // Umbral de Otsu entre figura (cerca) y fondo (lejos)
        val hist = IntArray(256)
        for (v in dn) hist[(v * 255).toInt()]++
        var sum = 0.0
        for (i in 0 until 256) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var tIdx = 128
        for (i in 0 until 256) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = n - wB
            if (wF == 0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; tIdx = i }
        }
        val thr = (tIdx / 255f + cut * 0.18f).coerceIn(0.02f, 0.95f)

        // Color del fondo: mediana del borde de la foto
        val border = ArrayList<Int>()
        for (x in 0 until w) { border.add(x); border.add((h - 1) * w + x) }
        for (y in 0 until h) { border.add(y * w); border.add(y * w + w - 1) }
        val br = border.map { (photoLo[it] shr 16) and 0xFF }.sorted()[border.size / 2]
        val bgG = border.map { (photoLo[it] shr 8) and 0xFF }.sorted()[border.size / 2]
        val bb = border.map { photoLo[it] and 0xFF }.sorted()[border.size / 2]
        fun nearBg(c: Int): Boolean {
            val dr = ((c shr 16) and 0xFF) - br
            val dg = ((c shr 8) and 0xFF) - bgG
            val db = (c and 0xFF) - bb
            return dr * dr + dg * dg + db * db < 38 * 38
        }

        // Fondo: lo conectado al borde con color parecido y que no esté claramente cerca
        val bg = BooleanArray(n)
        val queue = IntArray(n)
        var qh = 0
        var qt = 0
        for (i in border) if (!bg[i] && nearBg(photoLo[i]) && dn[i] < thr + 0.15f) { bg[i] = true; queue[qt++] = i }
        while (qh < qt) {
            val i = queue[qh++]
            val x = i % w
            val y = i / w
            fun visit(j: Int) { if (!bg[j] && nearBg(photoLo[j]) && dn[j] < thr + 0.15f) { bg[j] = true; queue[qt++] = j } }
            if (x > 0) visit(i - 1)
            if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w)
            if (y < h - 1) visit(i + w)
        }
        val fg = BooleanArray(n) { dn[it] >= thr && !bg[it] }

        // Solo las partes grandes (la figura y su peana), sin manchas sueltas
        val label = IntArray(n) { -1 }
        val sizes = ArrayList<Int>()
        for (s in 0 until n) {
            if (!fg[s] || label[s] >= 0) continue
            val id = sizes.size
            var cnt = 0
            qh = 0; qt = 0
            queue[qt++] = s; label[s] = id
            while (qh < qt) {
                val i = queue[qh++]
                cnt++
                val x = i % w
                val y = i / w
                fun visit(j: Int) { if (fg[j] && label[j] < 0) { label[j] = id; queue[qt++] = j } }
                if (x > 0) visit(i - 1)
                if (x < w - 1) visit(i + 1)
                if (y > 0) visit(i - w)
                if (y < h - 1) visit(i + w)
            }
            sizes.add(cnt)
        }
        val biggest = sizes.maxOrNull() ?: 0
        val m = BooleanArray(n) { label[it] >= 0 && sizes[label[it]] >= biggest * 0.15 }
        mask = m

        // Medidas de la figura
        var minX = w; var maxX = 0; var minY = h; var maxY = 0
        var dMax = thr
        for (i in 0 until n) if (m[i]) {
            val x = i % w; val y = i / w
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (dn[i] > dMax) dMax = dn[i]
        }
        val figW = (maxX - minX + 1).coerceAtLeast(1)
        val figH = (maxY - minY + 1).coerceAtLeast(1)
        val figSize = max(figW, figH).toFloat()

        // Distancia al borde de la silueta (chaflán 3-4) para redondear los contornos
        val inf = 1_000_000
        val dist = IntArray(n) { if (m[it]) inf else 0 }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!m[i]) continue
            var v = dist[i]
            v = if (x > 0) min(v, dist[i - 1] + 3) else min(v, 3)
            v = if (y > 0) min(v, dist[i - w] + 3) else min(v, 3)
            if (x > 0 && y > 0) v = min(v, dist[i - w - 1] + 4)
            if (x < w - 1 && y > 0) v = min(v, dist[i - w + 1] + 4)
            dist[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (!m[i]) continue
            var v = dist[i]
            v = if (x < w - 1) min(v, dist[i + 1] + 3) else min(v, 3)
            v = if (y < h - 1) min(v, dist[i + w] + 3) else min(v, 3)
            if (x < w - 1 && y < h - 1) v = min(v, dist[i + w + 1] + 4)
            if (x > 0 && y < h - 1) v = min(v, dist[i + w - 1] + 4)
            dist[i] = v
        }

        // Relieve: profundidad del modelo + borde redondeado
        val rim = figSize * 0.045f
        val depthScale = figW * 0.45f * relief
        val hgt = FloatArray(n)
        for (i in 0 until n) if (m[i]) {
            val d = dist[i] / 3f
            val dd = min(d, rim)
            val inflate = sqrt((2 * rim * dd - dd * dd).coerceAtLeast(0f))
            hgt[i] = ((dn[i] - thr) / (dMax - thr).coerceAtLeast(1e-3f)).coerceIn(0f, 1f) * depthScale + inflate * 0.8f
        }
        val hs = boxBlur(hgt, m, 1)
        height = hs
        hMax = hs.maxOrNull() ?: 0f

        // Normales (X derecha, Y arriba, Z hacia la cámara)
        val nX = FloatArray(n); val nY = FloatArray(n); val nZ = FloatArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!m[i]) continue
            val l = if (x > 0 && m[i - 1]) hs[i - 1] else hs[i]
            val r = if (x < w - 1 && m[i + 1]) hs[i + 1] else hs[i]
            val u = if (y > 0 && m[i - w]) hs[i - w] else hs[i]
            val d = if (y < h - 1 && m[i + w]) hs[i + w] else hs[i]
            val ax = -(r - l) / 2f; val ay = (d - u) / 2f; val az = 1f
            val len = sqrt(ax * ax + ay * ay + az * az)
            nX[i] = ax / len; nY[i] = ay / len; nZ[i] = az / len
        }
        // Se extienden 2 píxeles hacia el fondo para que al ampliar el borde no se mezcle con ceros
        val filled = m.copyOf()
        repeat(2) {
            val add = ArrayList<Int>()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (filled[i]) continue
                var sx = 0f; var sy = 0f; var sz = 0f; var c = 0
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
                    if (j >= 0 && filled[j]) { sx += nX[j]; sy += nY[j]; sz += nZ[j]; c++ }
                }
                if (c > 0) { nX[i] = sx / c; nY[i] = sy / c; nZ[i] = sz / c; add.add(i) }
            }
            for (i in add) filled[i] = true
        }
        nx = nX; ny = nY; nz = nZ

        // Huecos: puntos más bajos que su alrededor
        val r = max(2, (figSize * 0.025f).toInt())
        var around = boxBlur(hs, m, r)
        around = boxBlur(around, m, r)
        val scale = (figSize * 0.012f).coerceAtLeast(0.5f)
        ao = FloatArray(n) { if (m[it]) ((around[it] - hs[it]) / scale).coerceIn(0f, 1f) else 0f }

        // Recorte a resolución completa: lo claramente dentro o fuera sale de la máscara ampliada; en el
        // borde decide el color de la foto (fondo o no) junto con la profundidad
        val mf = FloatArray(n) { if (m[it]) 1f else 0f }
        val hiMask = BooleanArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            val v = up.sample(mf, x, y)
            val i = y * W + x
            hiMask[i] = when {
                v > 0.85f -> true
                v < 0.15f -> false
                else -> up.sample(dn, x, y) >= thr - 0.05f && !nearBg(photoHi[i])
            }
        }
        maskHi = hiMask
        prepared = cut to relief
    }

    /** Desenfoque de caja separable solo dentro de la figura. */
    private fun boxBlur(src: FloatArray, m: BooleanArray, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            var s = 0f; var c = 0
            val row = y * w
            for (x in -r until w + r) {
                val xin = x + r
                if (xin in 0 until w && m[row + xin]) { s += src[row + xin]; c++ }
                val xout = x - r - 1
                if (xout in 0 until w && m[row + xout]) { s -= src[row + xout]; c-- }
                if (x in 0 until w) tmp[row + x] = if (m[row + x] && c > 0) s / c else src[row + x]
            }
        }
        for (x in 0 until w) {
            var s = 0f; var c = 0
            for (y in -r until h + r) {
                val yin = y + r
                if (yin in 0 until h && m[yin * w + x]) { s += tmp[yin * w + x]; c++ }
                val yout = y - r - 1
                if (yout in 0 until h && m[yout * w + x]) { s -= tmp[yout * w + x]; c-- }
                if (y in 0 until h) out[y * w + x] = if (m[y * w + x] && c > 0) s / c else tmp[y * w + x]
            }
        }
        return out
    }

    /**
     * Ilumina la foto a resolución completa.
     * @param light parámetros de la luz en el sistema de la figura.
     * @param side lado desde el que se tomó esta foto (gira la luz al sistema de la cámara).
     * @param detailAmount cuánto del detalle fino de la foto se suma (0–1).
     */
    fun render(light: LightParams, side: PhotoSide, look: PhotoLook, detailAmount: Float, cancelled: () -> Boolean = { false }): Bitmap? {
        val n = w * h
        val lw = light.direction()
        val phi = Math.toRadians(side.yaw.toDouble())
        val c = kotlin.math.cos(phi).toFloat()
        val s = kotlin.math.sin(phi).toFloat()
        val lx = lw[0] * c - lw[2] * s
        val ly = lw[1]
        val lz = lw[0] * s + lw[2] * c

        // Sombras proyectadas a baja resolución: desde cada punto se avanza hacia la luz por el relieve
        val shadow = FloatArray(n) { 1f }
        val lxy = sqrt(lx * lx + ly * ly)
        if (lxy > 0.03f) {
            val dx = lx / lxy
            val dy = -ly / lxy // en la imagen Y crece hacia abajo
            val slope = lz / lxy
            val bias = 0.6f
            val maxSteps = max(w, h)
            for (y in 0 until h) {
                if (cancelled()) return null
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!mask[i]) continue
                    val h0 = height[i]
                    var t = 1.5f
                    while (t < maxSteps) {
                        val rayH = h0 + slope * t
                        if (rayH > hMax + 1f) break
                        val qx = (x + dx * t).toInt()
                        val qy = (y + dy * t).toInt()
                        if (qx < 0 || qy < 0 || qx >= w || qy >= h) break
                        val j = qy * w + qx
                        if (mask[j] && height[j] > rayH + bias) { shadow[i] = 0f; break }
                        t += if (t < 12f) 1f else 1.5f
                    }
                }
            }
        }

        val out = IntArray(W * H)
        val zones = light.zones
        val th = zoneThresholds(if (zones == 3) 3 else 5)
        val zc = zoneColors(if (zones == 3) 3 else 5)
        val bgColor = 0xFF1C1D22.toInt()
        val det = if (detailAmount > 0.01f && look == PhotoLook.ARCILLA) detail else null
        for (y in 0 until H) {
            if (cancelled()) return null
            for (x in 0 until W) {
                val i = y * W + x
                if (!maskHi[i]) { out[i] = bgColor; continue }
                var ax = up.sample(nx, x, y); var ay = up.sample(ny, x, y); var az = up.sample(nz, x, y)
                val len = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-4f)
                ax /= len; ay /= len; az /= len
                val ndl = ax * lx + ay * ly + az * lz
                val diff = max(ndl, 0f)
                val sh = if (ndl > 0f) up.sample(shadow, x, y) else 0f
                val occl = (1f - light.cavity * up.sample(ao, x, y) * 0.75f).coerceIn(0.25f, 1f)
                val sky = 0.65f + 0.35f * ay
                val direct = (1f - light.ambient) * diff * sh
                val amb = light.ambient * occl * sky
                if (zones > 0) {
                    val intensity = direct + amb * 1.4f
                    var z = 0
                    while (z < th.size && intensity >= th[z]) z++
                    out[i] = zc[z]
                    continue
                }
                val p = photoHi[i]
                val ar: Float; val ag: Float; val ab: Float
                if (look == PhotoLook.FOTO) {
                    ar = LIN[(p shr 16) and 0xFF]; ag = LIN[(p shr 8) and 0xFF]; ab = LIN[p and 0xFF]
                } else {
                    val dt = if (det != null) det[i].coerceIn(0.45f, 1.8f).pow(detailAmount) else 1f
                    ar = 0.62f * dt; ag = 0.62f * dt; ab = 0.63f * dt
                }
                val k = if (look == PhotoLook.FOTO) 1.9f else 1.25f
                fun tone(albedo: Float, lc: Float): Int {
                    val v = albedo * (lc * direct + amb) * k
                    return enc(v / (1f + 0.25f * v))
                }
                out[i] = (0xFF shl 24) or (tone(ar, light.color[0]) shl 16) or (tone(ag, light.color[1]) shl 8) or tone(ab, light.color[2])
            }
        }
        return Bitmap.createBitmap(out, W, H, Bitmap.Config.ARGB_8888)
    }

    /** La foto original recortada (para comparar). */
    fun original(): Bitmap {
        val out = IntArray(W * H) { if (maskHi[it]) photoHi[it] else 0xFF1C1D22.toInt() }
        return Bitmap.createBitmap(out, W, H, Bitmap.Config.ARGB_8888)
    }
}

/** Desenfoque de caja separable sobre toda la imagen. */
private fun boxBlurAll(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
    val tmp = FloatArray(w * h)
    val out = FloatArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        var s = 0f; var c = 0
        for (x in -r until w + r) {
            val xin = x + r
            if (xin in 0 until w) { s += src[row + xin]; c++ }
            val xout = x - r - 1
            if (xout in 0 until w) { s -= src[row + xout]; c-- }
            if (x in 0 until w) tmp[row + x] = s / c.coerceAtLeast(1)
        }
    }
    for (x in 0 until w) {
        var s = 0f; var c = 0
        for (y in -r until h + r) {
            val yin = y + r
            if (yin in 0 until h) { s += tmp[yin * w + x]; c++ }
            val yout = y - r - 1
            if (yout in 0 until h) { s -= tmp[yout * w + x]; c-- }
            if (y in 0 until h) out[y * w + x] = s / c.coerceAtLeast(1)
        }
    }
    return out
}
