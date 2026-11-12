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
 */

object DepthModel {
    private const val URL_MODEL = "https://github.com/ricardochr/estante-pinturas/releases/download/model-depth-v1/depth-v2-small-q8.onnx"
    const val SIZE_MB = 27
    private const val MIN_BYTES = 20_000_000L

    private fun file(context: Context) = File(File(context.filesDir, "models").apply { mkdirs() }, "depth-v2-small-q8.onnx")

    fun isReady(context: Context): Boolean = file(context).let { it.exists() && it.length() > MIN_BYTES }

    /** Descarga el modelo una sola vez. Devuelve false si falla. */
    fun download(context: Context, progress: (Float) -> Unit): Boolean {
        val dest = file(context)
        val part = File(dest.parentFile, dest.name + ".part")
        return try {
            var url = URL(URL_MODEL)
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
                if (code != 200) { conn.disconnect(); return false }
                break
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (SIZE_MB * 1_000_000L)
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
            if (part.length() < MIN_BYTES) { part.delete(); return false }
            part.renameTo(dest)
        } catch (e: Exception) {
            part.delete()
            false
        }
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Synchronized
    private fun session(context: Context): OrtSession {
        session?.let { return it }
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(max(1, min(4, Runtime.getRuntime().availableProcessors())))
        }
        val s = e.createSession(file(context).absolutePath, opts)
        env = e
        session = s
        return s
    }

    /** Profundidad relativa (mayor = más cerca) a la resolución de entrada del modelo (≤ 518 px por lado). */
    @Synchronized
    fun estimate(context: Context, bmp: Bitmap): DepthMap {
        val s = session(context)
        val scale = 518f / max(bmp.width, bmp.height)
        fun dim(v: Int) = max(14 * 8, ((v * scale) / 14).toInt() * 14)
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

/**
 * Una foto lista para iluminar: color de la foto, figura recortada y relieve, a la resolución del mapa
 * de profundidad.
 */
class PhotoScene(val w: Int, val h: Int, private val photo: IntArray, private val depth: FloatArray) {
    var mask = BooleanArray(w * h); private set
    private var height = FloatArray(w * h)
    private var nx = FloatArray(w * h)
    private var ny = FloatArray(w * h)
    private var nz = FloatArray(w * h)
    private var ao = FloatArray(w * h)
    private var hMax = 0f
    private var prepared: Pair<Float, Float>? = null

    companion object {
        fun from(bmp: Bitmap, dm: DepthMap): PhotoScene {
            val small = Bitmap.createScaledBitmap(bmp, dm.w, dm.h, true)
            val px = IntArray(dm.w * dm.h)
            small.getPixels(px, 0, dm.w, 0, 0, dm.w, dm.h)
            if (small !== bmp) small.recycle()
            return PhotoScene(dm.w, dm.h, px, dm.d)
        }
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

        // Fondo: lo conectado al borde con color parecido al del borde y que no esté claramente cerca
        val bg = BooleanArray(n)
        val border = ArrayList<Int>()
        for (x in 0 until w) { border.add(x); border.add((h - 1) * w + x) }
        for (y in 0 until h) { border.add(y * w); border.add(y * w + w - 1) }
        val br = border.map { (photo[it] shr 16) and 0xFF }.sorted()[border.size / 2]
        val bgG = border.map { (photo[it] shr 8) and 0xFF }.sorted()[border.size / 2]
        val bb = border.map { photo[it] and 0xFF }.sorted()[border.size / 2]
        fun nearBg(i: Int): Boolean {
            val c = photo[i]
            val dr = ((c shr 16) and 0xFF) - br
            val dg = ((c shr 8) and 0xFF) - bgG
            val db = (c and 0xFF) - bb
            return dr * dr + dg * dg + db * db < 38 * 38
        }
        val queue = IntArray(n)
        var qh = 0
        var qt = 0
        for (i in border) if (!bg[i] && nearBg(i) && dn[i] < thr + 0.15f) { bg[i] = true; queue[qt++] = i }
        while (qh < qt) {
            val i = queue[qh++]
            val x = i % w
            val y = i / w
            fun visit(j: Int) { if (!bg[j] && nearBg(j) && dn[j] < thr + 0.15f) { bg[j] = true; queue[qt++] = j } }
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
            if (x > 0) v = min(v, dist[i - 1] + 3) else v = min(v, 3)
            if (y > 0) v = min(v, dist[i - w] + 3) else v = min(v, 3)
            if (x > 0 && y > 0) v = min(v, dist[i - w - 1] + 4)
            if (x < w - 1 && y > 0) v = min(v, dist[i - w + 1] + 4)
            dist[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (!m[i]) continue
            var v = dist[i]
            if (x < w - 1) v = min(v, dist[i + 1] + 3) else v = min(v, 3)
            if (y < h - 1) v = min(v, dist[i + w] + 3) else v = min(v, 3)
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
        var hs = boxBlur(hgt, m, 1)
        hs = boxBlur(hs, m, 1)
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
            val gx = (r - l) / 2f
            val gy = (d - u) / 2f
            val ax = -gx; val ay = gy; val az = 1f
            val len = sqrt(ax * ax + ay * ay + az * az)
            nX[i] = ax / len; nY[i] = ay / len; nZ[i] = az / len
        }
        nx = nX; ny = nY; nz = nZ

        // Huecos: puntos más bajos que su alrededor
        val r = max(2, (figSize * 0.025f).toInt())
        var around = boxBlur(hs, m, r)
        around = boxBlur(around, m, r)
        val scale = (figSize * 0.012f).coerceAtLeast(0.5f)
        ao = FloatArray(n) { if (m[it]) ((around[it] - hs[it]) / scale).coerceIn(0f, 1f) else 0f }
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
     * Ilumina la foto.
     * @param light parámetros de la luz en el sistema de la figura.
     * @param side lado desde el que se tomó esta foto (gira la luz al sistema de la cámara).
     */
    fun render(light: LightParams, side: PhotoSide, look: PhotoLook, cancelled: () -> Boolean = { false }): Bitmap? {
        val n = w * h
        val lw = light.direction()
        val phi = Math.toRadians(side.yaw.toDouble())
        val c = kotlin.math.cos(phi).toFloat()
        val s = kotlin.math.sin(phi).toFloat()
        val lx = lw[0] * c - lw[2] * s
        val ly = lw[1]
        val lz = lw[0] * s + lw[2] * c

        // Sombras proyectadas: desde cada punto se avanza hacia la luz por el relieve
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
            // Borde de sombra suave
            val soft = FloatArray(n)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (!mask[i]) continue
                var sum = 0f; var cnt = 0
                for (oy in -1..1) for (ox in -1..1) {
                    val xx = x + ox; val yy = y + oy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val j = yy * w + xx
                    if (!mask[j]) continue
                    sum += shadow[j]; cnt++
                }
                soft[i] = if (cnt > 0) sum / cnt else shadow[i]
            }
            for (i in 0 until n) if (mask[i]) shadow[i] = soft[i]
        }

        val out = IntArray(n)
        val zones = light.zones
        val th = zoneThresholds(if (zones == 3) 3 else 5)
        val zc = zoneColors(if (zones == 3) 3 else 5)
        val bgColor = 0xFF1C1D22.toInt()
        for (i in 0 until n) {
            if (!mask[i]) { out[i] = bgColor; continue }
            val ndl = nx[i] * lx + ny[i] * ly + nz[i] * lz
            val diff = max(ndl, 0f)
            val sh = if (ndl > 0f) shadow[i] else 0f
            val occl = (1f - light.cavity * ao[i] * 0.75f).coerceIn(0.25f, 1f)
            val sky = 0.65f + 0.35f * ny[i]
            val direct = (1f - light.ambient) * diff * sh
            val amb = light.ambient * occl * sky
            if (zones > 0) {
                val intensity = direct + amb * 1.4f
                var z = 0
                while (z < th.size && intensity >= th[z]) z++
                out[i] = zc[z]
                continue
            }
            val p = photo[i]
            val (ar, ag, ab) = when (look) {
                PhotoLook.ARCILLA -> Triple(0.62f, 0.62f, 0.63f)
                PhotoLook.FOTO -> Triple(LIN[(p shr 16) and 0xFF], LIN[(p shr 8) and 0xFF], LIN[p and 0xFF])
            }
            val k = if (look == PhotoLook.FOTO) 1.9f else 1.25f
            fun tone(albedo: Float, lc: Float): Int {
                val v = albedo * (lc * direct + amb) * k
                return enc(v / (1f + 0.25f * v))
            }
            out[i] = (0xFF shl 24) or (tone(ar, light.color[0]) shl 16) or (tone(ag, light.color[1]) shl 8) or tone(ab, light.color[2])
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** La foto original recortada (para comparar). */
    fun original(): Bitmap {
        val out = IntArray(w * h) { if (mask[it]) photo[it] else 0xFF1C1D22.toInt() }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
