package com.ricardochr.estantepinturas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** Segmentación de sujeto de ML Kit: confianza de figura (0–1) por píxel. null si no está disponible. */
object SubjectCutout {
    fun segment(bmp: Bitmap): FloatArray? {
        return try {
        val seg = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
        try {
            val res = Tasks.await(seg.process(InputImage.fromBitmap(bmp, 0)), 20, TimeUnit.SECONDS)
            val buf = res.foregroundConfidenceMask ?: return null
            buf.rewind()
            if (buf.remaining() != bmp.width * bmp.height) return null
            FloatArray(buf.remaining()).also { buf.get(it) }
        } finally {
            seg.close()
        }
    } catch (e: Exception) {
        // El módulo se descarga con los servicios de Google la primera vez; mientras tanto se usa la profundidad
        null
        }
    }
}

/**
 * Fotos de "Luz y sombra" guardadas para no recalcular: la foto, su mapa de profundidad y el recorte
 * hecho a mano, por proyecto (o "general") y lado.
 */
object LightPhotoStore {
    private fun dir(context: Context, key: String) = File(File(context.filesDir, "luz"), key.replace(Regex("[^A-Za-z0-9_-]"), "_")).apply { mkdirs() }

    fun save(context: Context, key: String, side: PhotoSide, photo: Bitmap, depth: DepthMap, userMask: BooleanArray?) {
        val d = dir(context, key)
        File(d, "${side.name}.jpg").outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        DataOutputStream(File(d, "${side.name}.depth").outputStream().buffered()).use { o ->
            o.writeInt(depth.w); o.writeInt(depth.h)
            for (v in depth.d) o.writeFloat(v)
        }
        saveMask(context, key, side, userMask)
    }

    fun saveMask(context: Context, key: String, side: PhotoSide, userMask: BooleanArray?) {
        val f = File(dir(context, key), "${side.name}.mask")
        if (userMask == null) { f.delete(); return }
        DataOutputStream(f.outputStream().buffered()).use { o ->
            o.writeInt(userMask.size)
            val bits = java.util.BitSet(userMask.size)
            for (i in userMask.indices) if (userMask[i]) bits.set(i)
            val bytes = bits.toByteArray()
            o.writeInt(bytes.size); o.write(bytes)
        }
    }

    fun remove(context: Context, key: String, side: PhotoSide) {
        val d = dir(context, key)
        listOf("jpg", "depth", "mask").forEach { File(d, "${side.name}.$it").delete() }
    }

    class Saved(val photo: Bitmap, val depth: DepthMap, val userMask: BooleanArray?)

    fun load(context: Context, key: String): Map<PhotoSide, Saved> {
        val d = dir(context, key)
        val out = HashMap<PhotoSide, Saved>()
        for (side in PhotoSide.entries) {
            try {
                val jpg = File(d, "${side.name}.jpg")
                val dep = File(d, "${side.name}.depth")
                if (!jpg.exists() || !dep.exists()) continue
                val bmp = BitmapFactory.decodeFile(jpg.absolutePath) ?: continue
                val depth = DataInputStream(dep.inputStream().buffered()).use { i ->
                    val w = i.readInt(); val h = i.readInt()
                    DepthMap(w, h, FloatArray(w * h) { i.readFloat() })
                }
                val mf = File(d, "${side.name}.mask")
                val mask = if (mf.exists()) DataInputStream(mf.inputStream().buffered()).use { i ->
                    val n = i.readInt()
                    val bytes = ByteArray(i.readInt()); i.readFully(bytes)
                    val bits = java.util.BitSet.valueOf(bytes)
                    BooleanArray(n) { bits[it] }
                } else null
                out[side] = Saved(bmp, depth, mask?.takeIf { it.size == bmp.width * bmp.height })
            } catch (_: Exception) {
            }
        }
        return out
    }
}
