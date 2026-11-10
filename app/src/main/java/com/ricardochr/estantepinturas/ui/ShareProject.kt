package com.ricardochr.estantepinturas.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint as GPaint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.ricardochr.estantepinturas.MainViewModel
import com.ricardochr.estantepinturas.Project
import com.ricardochr.estantepinturas.tr
import java.io.File

/**
 * Arma una imagen del proyecto (la última foto de avance o la referencia, el nombre y las pinturas
 * usadas con su color) y abre el menú de compartir. Devuelve false si no se pudo.
 */
fun shareProject(context: Context, vm: MainViewModel, p: Project): Boolean {
    return try {
        val photoName = p.photos.lastOrNull()?.file ?: p.reference
        val photo = photoName?.let { BitmapFactory.decodeFile(vm.projectFile(p.id, it).absolutePath) }
        val paints = p.paints.mapNotNull { pp -> vm.byId[pp.paintId]?.let { it to pp.use } }.take(24)

        val w = 1080
        val pad = 48
        val photoH = if (photo != null) (photo.height * (w.toFloat() / photo.width)).toInt().coerceAtMost(1350) else 0
        val rowH = 76
        val cols = 2
        val rows = (paints.size + cols - 1) / cols
        val headerH = 170
        val h = photoH + headerH + rows * rowH + 110
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(22, 24, 39))

        if (photo != null) {
            // Recorte centrado si la foto es más alta que el espacio
            val srcH = (photoH * (photo.width.toFloat() / w)).toInt().coerceAtMost(photo.height)
            val top = (photo.height - srcH) / 2
            c.drawBitmap(photo, Rect(0, top, photo.width, top + srcH), Rect(0, 0, w, photoH), GPaint(GPaint.FILTER_BITMAP_FLAG))
        }
        val title = GPaint(GPaint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 64f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val small = GPaint(GPaint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = 30f }
        val name = GPaint(GPaint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        fun fit(text: String, paint: GPaint, max: Float): String {
            if (paint.measureText(text) <= max) return text
            var t = text
            while (t.length > 3 && paint.measureText("$t…") > max) t = t.dropLast(1)
            return "$t…"
        }
        var y = photoH + 90f
        c.drawText(fit(p.name, title, (w - 2 * pad).toFloat()), pad.toFloat(), y, title)
        y += 48f
        c.drawText(tr("{} pinturas", p.paints.size) + " · " + tr(p.status), pad.toFloat(), y, small)
        y += 40f

        val colW = (w - 2 * pad) / cols
        val fill = GPaint(GPaint.ANTI_ALIAS_FLAG)
        val ring = GPaint(GPaint.ANTI_ALIAS_FLAG).apply { style = GPaint.Style.STROKE; strokeWidth = 2f; color = Color.argb(90, 255, 255, 255) }
        paints.forEachIndexed { i, (paint, use) ->
            val x = pad + (i % cols) * colW
            val ry = y + (i / cols) * rowH
            fill.color = paint.argb.toInt()
            val box = RectF(x.toFloat(), ry + 8f, x + 54f, ry + 62f)
            c.drawRoundRect(box, 14f, 14f, fill)
            c.drawRoundRect(box, 14f, 14f, ring)
            val maxText = colW - 80f
            c.drawText(fit(paint.name, name, maxText), x + 70f, ry + 34f, name)
            c.drawText(fit(listOf(paint.brand, use).filter { it.isNotBlank() }.joinToString(" · "), small, maxText), x + 70f, ry + 66f, small)
        }
        val foot = GPaint(GPaint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); textSize = 28f; letterSpacing = 0.25f }
        c.drawText("GOTARIO", pad.toFloat(), h - 44f, foot)

        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "proyecto-compartir.jpg")
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, p.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, tr("Compartir proyecto")))
        true
    } catch (e: Throwable) {
        false
    }
}
