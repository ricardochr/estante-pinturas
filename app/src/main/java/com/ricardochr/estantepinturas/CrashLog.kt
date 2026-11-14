package com.ricardochr.estantepinturas

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Si la app se cierra por un error, guarda el detalle para mostrarlo al abrirla de nuevo
 * (y poder copiarlo y enviarlo).
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "ultimo-cierre.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                // i18n:off
                file(app).writeText("Gotario ${BuildConfig.VERSION_NAME} · ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\nHilo: ${t.name}\n\n$sw")
                // i18n:on
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** El detalle del último cierre (y lo borra), o null si no hubo. */
    fun take(context: Context): String? {
        val f = file(context)
        if (!f.exists()) return null
        val s = f.readText()
        f.delete()
        return s
    }
}
