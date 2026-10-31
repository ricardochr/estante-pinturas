package com.ricardochr.estantepinturas

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Pintura reconocida en la foto de una gaveta, con su posición relativa (0–1). */
data class DetectedPaint(val paint: Paint, val x: Float, val y: Float, val owned: Boolean)

/** Bloque de texto leído en la foto y su centro en píxeles de la imagen original. */
class TextBlockAt(val lines: List<String>, val x: Float, val y: Float)

/**
 * Lee el texto de la foto en las cuatro orientaciones, porque en una gaveta los botes acostados
 * tienen la etiqueta de lado. Devuelve los centros convertidos a la orientación original.
 * Llamar fuera del hilo principal.
 */
fun readTextBlocks(bmp: Bitmap): List<TextBlockAt> {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val w = bmp.width.toFloat()
    val h = bmp.height.toFloat()
    val out = ArrayList<TextBlockAt>()
    try {
        for (deg in intArrayOf(0, 90, 180, 270)) {
            val img = if (deg == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
            val result = try {
                Tasks.await(recognizer.process(InputImage.fromBitmap(img, 0)))
            } catch (e: Exception) {
                null
            } ?: continue
            for (block in result.textBlocks) {
                val box = block.boundingBox ?: continue
                val cx = box.exactCenterX()
                val cy = box.exactCenterY()
                // Volver del sistema girado al original (giro horario de deg grados)
                val (x, y) = when (deg) {
                    90 -> cy to (h - cx)
                    180 -> (w - cx) to (h - cy)
                    270 -> (w - cy) to cx
                    else -> cx to cy
                }
                out.add(TextBlockAt(block.lines.map { it.text }, x, y))
            }
            if (img !== bmp) img.recycle()
        }
    } finally {
        recognizer.close()
    }
    return out
}
