package com.ricardochr.estantepinturas

import android.content.Context
import org.json.JSONObject

/**
 * Traducción de la interfaz. El código está escrito en español; cada texto visible pasa por [tr],
 * que lo deja igual en español o busca su traducción en assets/i18n-en.json.
 * Los nombres de pinturas, líneas y marcas nunca se traducen.
 */
object I18n {
    @Volatile private var en: Map<String, String> = emptyMap()

    /** Nombres propios (pinturas, líneas, marcas): si un texto es exactamente uno de estos, no se toca. */
    @Volatile var protectedNames: Set<String> = emptySet()

    fun load(context: Context) {
        if (en.isNotEmpty()) return
        try {
            val text = context.assets.open("i18n-en.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(text)
            val m = HashMap<String, String>(o.length() * 2)
            val keys = o.keys()
            while (keys.hasNext()) { val k = keys.next(); m[k] = o.getString(k) }
            en = m
        } catch (e: Exception) {
            en = emptyMap()
        }
    }

    fun lookup(key: String): String? = en[key]
}

/**
 * Texto en el idioma elegido. [key] es el texto en español, con {} donde van los valores:
 * tr("Tienes {} de {}", 3, 12). En la traducción los valores pueden ir en otro orden con {0}, {1}…
 */
fun tr(key: String, vararg args: Any?): String {
    val template = if (Prefs.lang == "es") key else (I18n.lookup(key) ?: key)
    if (args.isEmpty() || '{' !in template) return template
    val sb = StringBuilder(template.length + 16 * args.size)
    var next = 0
    var i = 0
    while (i < template.length) {
        val c = template[i]
        if (c == '{') {
            val close = template.indexOf('}', i)
            if (close == i + 1) {
                sb.append(args.getOrNull(next++)?.toString() ?: "")
                i = close + 1
                continue
            }
            if (close > i && close - i <= 3) {
                val n = template.substring(i + 1, close).toIntOrNull()
                if (n != null) {
                    sb.append(args.getOrNull(n)?.toString() ?: "")
                    i = close + 1
                    continue
                }
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

/**
 * Traduce un texto que viene de los datos (tipo de pintura, estado, rol…) si tiene traducción exacta.
 * Los nombres propios se devuelven tal cual.
 */
fun trData(text: String): String {
    if (Prefs.lang == "es" || text.isEmpty()) return text
    if (text in I18n.protectedNames) return text
    return I18n.lookup(text) ?: text
}
