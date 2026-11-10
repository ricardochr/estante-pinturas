#!/usr/bin/env python3
"""Prepara el código para traducirse.

El código de la app está escrito en español. Este script:
  1. Envuelve en tr("…") los textos que se arman con variables ("Tienes $n de $m" -> tr("Tienes {} de {}", n, m))
     y los trozos de frases concatenadas con variables.
  2. Lista todas las claves (textos en español) que necesitan traducción en tools/i18n/keys.json.

Los textos fijos que se muestran tal cual no se tocan: los traduce el componente Text de la app
al dibujarlos (búsqueda exacta en el diccionario). Es idempotente: lo ya envuelto en tr(...) se respeta.

Uso:  python3 tools/i18n_wrap.py [--write]
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "ricardochr", "estantepinturas")
OUT = os.path.join(ROOT, "tools", "i18n", "keys.json")
SKIP_FILES = {"I18n.kt", "Prefs.kt", "AppText.kt"}

LETTERS = re.compile(r"[A-Za-zÁÉÍÓÚÜáéíóúüñÑ]{2,}")
SPANISH_HINT = re.compile(r"[ÁÉÍÓÚáéíóúñÑ¿¡]")
# Lo que hay justo antes de un literal y lo vuelve "técnico" (no es texto para la persona)
BEFORE_SKIP = re.compile(
    r"(==|!=|\bin|\bis|!in)\s*$|"
    r"(\b(?:tr|trData|Regex|contains|startsWith|endsWith|removePrefix|removeSuffix|substringAfter|substringBefore|split|replace|"
    r"indexOf|getString|optString|optInt|optLong|optBoolean|optDouble|optJSONArray|optJSONObject|getJSONArray|getJSONObject|has|isNull|"
    r"put|putExtra|putString|putBoolean|getSharedPreferences|getBoolean|SimpleDateFormat|format|File|Font|GoogleFont|parse|Intent|"
    r"setType|getUriForFile|open|require|check|error|IllegalStateException|IllegalArgumentException|Exception|Locale|Builder|"
    r"launch|d|w|e|i|valueOf|lookup|trimEnd|trimStart|trim|padStart|joinToString|toRegex|getIdentifier|createTempFile)\s*\(\s*)$|"
    r"(\b(?:label|key|name|contentType|type|action|mimeType|testTag|route)\s*=\s*)$|"
    r"(@\w+(?:\([^)]*)?\s*)$|\bconst\s+val\s+\w+\s*=\s*$"
)
AFTER_SKIP = re.compile(r"^\s*(->|\.toRegex\(|\bin\b|!in\b|(?:\s*,\s*\"[^\"\n]*\")*\s*->)")


class Lit:
    """Un literal de texto: partes fijas y expresiones ${…}."""

    def __init__(self, start, end, parts, raw):
        self.start, self.end, self.parts, self.raw = start, end, parts, raw

    @property
    def static(self):
        return all(isinstance(p, str) for p in self.parts)

    def key(self):
        return "".join(p if isinstance(p, str) else "{}" for p in self.parts)

    def text(self):
        return "".join(p for p in self.parts if isinstance(p, str))


def unescape(s):
    return (s.replace("\\n", "\n").replace("\\t", "\t").replace('\\"', '"').replace("\\$", "$")
            .replace("\\'", "'").replace("\\\\", "\\"))


def scan(code):
    """Devuelve los literales de primer nivel del código (sin los que están dentro de comentarios).
    Entre los comentarios "// i18n:off" y "// i18n:on" no se toca nada (palabras de búsqueda, datos…)."""
    lits = []
    i, n = 0, len(code)
    while i < n:
        c = code[i]
        if code.startswith("// i18n:off", i):
            j = code.find("// i18n:on", i)
            i = n if j < 0 else j
        elif code.startswith("//", i):
            j = code.find("\n", i)
            i = n if j < 0 else j
        elif code.startswith("/*", i):
            j = code.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif code.startswith('"""', i):
            j = code.find('"""', i + 3)
            i = n if j < 0 else j + 3
        elif c == "'":
            j = i + 1
            while j < n and code[j] != "'":
                j += 2 if code[j] == "\\" else 1
            i = j + 1
        elif c == '"':
            lit, i = read_literal(code, i)
            lits.append(lit)
        else:
            i += 1
    return lits


def read_literal(code, i):
    start = i
    i += 1
    parts, buf = [], []
    n = len(code)
    while i < n:
        c = code[i]
        if c == "\\":
            buf.append(code[i:i + 2])
            i += 2
        elif c == '"':
            i += 1
            break
        elif c == "$" and i + 1 < n and code[i + 1] == "{":
            depth, j = 1, i + 2
            while j < n and depth:
                ch = code[j]
                if ch == '"':
                    _, j = read_literal(code, j)
                    continue
                if ch == "'":
                    j += 1
                    while j < n and code[j] != "'":
                        j += 2 if code[j] == "\\" else 1
                    j += 1
                    continue
                depth += ch == "{"
                depth -= ch == "}"
                j += 1
            if buf:
                parts.append(unescape("".join(buf)))
                buf = []
            parts.append(("expr", code[i + 2:j - 1]))
            i = j
        elif c == "$" and i + 1 < n and (code[i + 1].isalpha() or code[i + 1] == "_"):
            j = i + 1
            while j < n and (code[j].isalnum() or code[j] == "_"):
                j += 1
            if buf:
                parts.append(unescape("".join(buf)))
                buf = []
            parts.append(("expr", code[i + 1:j]))
            i = j
        else:
            buf.append(c)
            i += 1
    if buf:
        parts.append(unescape("".join(buf)))
    return Lit(start, i, parts, code[start:i]), i


def human(lit):
    """¿Es texto para la persona (y no una clave, formato o identificador)?"""
    t = lit.text()
    if not LETTERS.search(t):
        return False
    s = t.strip()
    if not lit.static and " " not in t and not SPANISH_HINT.search(s):
        return False  # "$id.jpg", "row-$i", "$n%"…
    if lit.static:
        if re.fullmatch(r"[\w.\-/#:%|+*=,;()\[\]{}<>@!?&'^$\\ ]*", s) and " " not in s and not SPANISH_HINT.search(s):
            # Una sola palabra sin acentos: solo cuenta si parece etiqueta ("Guardar"), no un identificador ("airbrush")
            return bool(re.fullmatch(r"[A-ZÁÉÍÓÚ][a-záéíóúñ]+…?", s))
    return True


def kotlin_string(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t").replace("$", "\\$") + '"'


DATA = set()          # literales que el código compara o usa como valor de datos: nunca se envuelven
DATA_EXTRA = {"En progreso", "Terminado", "Proyecto"}
FUN_OPEN = re.compile(r"(\bfun\b[^{};=]*|\bget\(\)\s*|\binit\s*|\bset\([^)]*\)\s*)$")


def fun_ranges(code):
    """Rangos (inicio, fin) de los cuerpos de función: ahí los textos fijos también se envuelven en tr()."""
    ranges, stack = [], []
    i, n = 0, len(code)
    while i < n:
        c = code[i]
        if code.startswith("//", i):
            j = code.find("\n", i); i = n if j < 0 else j; continue
        if code.startswith("/*", i):
            j = code.find("*/", i + 2); i = n if j < 0 else j + 2; continue
        if code.startswith('"""', i):
            j = code.find('"""', i + 3); i = n if j < 0 else j + 3; continue
        if c == '"':
            _, i = read_literal(code, i); continue
        if c == "'":
            j = i + 1
            while j < n and code[j] != "'":
                j += 2 if code[j] == "\\" else 1
            i = j + 1; continue
        if c == "{":
            head = code[max(0, i - 400):i]
            # La firma puede traer paréntesis con valores por defecto: se mira desde el último "fun"
            k = head.rfind("fun ")
            is_fun = False
            if k >= 0:
                # Entre "fun" y esta llave solo puede haber llaves dentro de los paréntesis de la firma
                # (valores por defecto como "= {}"); una llave fuera de ellos es de otro bloque.
                depth, ok = 0, True
                for ch in head[k:]:
                    if ch == "(":
                        depth += 1
                    elif ch == ")":
                        depth -= 1
                    elif ch in "{}" and depth == 0:
                        ok = False
                        break
                is_fun = ok and depth == 0
            if re.search(r"\b(get\(\)|init)\s*$", head):
                is_fun = True
            stack.append((i, is_fun))
        elif c == "}" and stack:
            start, is_fun = stack.pop()
            if is_fun:
                ranges.append((start, i))
        i += 1
    return ranges


def collect_data(code):
    """Literales fijos que aparecen en comparaciones, when, contains()…: son datos."""
    for lit in scan(code):
        if not lit.static:
            continue
        before = code[max(0, lit.start - 160):lit.start]
        after = code[lit.end:lit.end + 120]
        if re.search(r"(==|!=|\bin|!in)\s*$", before) or re.match(r"\s*(->|(?:\s*,\s*\"[^\"\n]*\")*\s*->)", after) \
                or re.search(r"\b(contains|startsWith|endsWith|indexOf|listOf|setOf|mapOf)\s*\(\s*$", before) and re.match(r"\s*\)\s*(==|!=|->)", after):
            DATA.add(lit.text())
        if re.match(r"\s*(==|!=)", after):
            DATA.add(lit.text())


def process(code, keys, top=True, in_fun=False):
    """Reescribe [code] envolviendo lo necesario; agrega las claves a [keys]."""
    lits = scan(code)
    ranges = fun_ranges(code) if top else []

    def inside_fun(pos):
        return in_fun or any(a < pos < b for a, b in ranges)
    # Cadenas de concatenación: literal (+ literal)*
    out, pos = [], 0
    k = 0
    while k < len(lits):
        chain = [lits[k]]
        while k + 1 < len(lits) and re.fullmatch(r"\s*\+\s*", code[chain[-1].end:lits[k + 1].start]):
            k += 1
            chain.append(lits[k])
        k += 1
        before = code[max(0, chain[0].start - 160):chain[0].start]
        after = code[chain[-1].end:chain[-1].end + 120]
        if re.search(r"\btr(Data)?\(\s*$", before):
            # Ya está envuelto a mano: solo se anota la clave
            if chain[0].static:
                keys.add(chain[0].text())
            continue
        if BEFORE_SKIP.search(before) or AFTER_SKIP.match(after):
            continue
        joined_before = re.search(r"\+\s*$", before) is not None
        joined_after = re.match(r"\s*\+", after) is not None
        pure = all(l.static for l in chain) and not joined_before and not joined_after
        if pure:
            whole = Lit(chain[0].start, chain[-1].end, ["".join(l.text() for l in chain)], "")
            txt = whole.text()
            if human(whole):
                keys.add(whole.key())
                # Dentro de una función el texto fijo también pasa por tr(), salvo que sea un dato
                if inside_fun(chain[0].start) and txt not in DATA and len(chain) == 1:
                    out.append(code[pos:chain[0].start]); out.append("tr(" + chain[0].raw + ")"); pos = chain[0].end
                elif inside_fun(chain[0].start) and txt not in DATA:
                    out.append(code[pos:chain[0].start]); out.append("tr(" + kotlin_string(txt) + ")"); pos = chain[-1].end
            elif not top and LETTERS.search(txt) and txt not in DATA and re.fullmatch(r"[a-záéíóúñ ]+", txt.strip() or "-"):
                keys.add(txt)  # palabra suelta dentro de una plantilla ("parte"/"partes"): se traduce como valor
            continue
        for lit in chain:
            if not human(lit):
                # Aun así hay que traducir lo que haya dentro de sus ${…}
                if not lit.static:
                    new = rebuild_template(lit, keys)
                    if new != lit.raw:
                        out.append(code[pos:lit.start]); out.append(new); pos = lit.end
                continue
            keys.add(lit.key())
            args = [process(p[1], keys, False, inside_fun(lit.start)) for p in lit.parts if not isinstance(p, str)]
            call = "tr(" + ", ".join([kotlin_string(lit.key())] + args) + ")"
            out.append(code[pos:lit.start]); out.append(call); pos = lit.end
    out.append(code[pos:])
    return "".join(out)


def rebuild_template(lit, keys):
    """Literal que no se traduce pero que tiene expresiones: se procesan por dentro."""
    res = ['"']
    for p in lit.parts:
        if isinstance(p, str):
            res.append(kotlin_string(p)[1:-1])
        else:
            inner = process(p[1], keys, False, True)
            res.append("${" + inner + "}")
    res.append('"')
    new = "".join(res)
    # Si nada cambió por dentro, se deja el original tal cual (conserva $nombre sin llaves)
    return new if any(not isinstance(p, str) and process(p[1], set(), False, True) != p[1] for p in lit.parts) else lit.raw


def main():
    write = "--write" in sys.argv
    keys = set()
    changed = 0
    DATA.update(DATA_EXTRA)
    for base, _, files in os.walk(SRC):
        for f in files:
            if f.endswith(".kt"):
                collect_data(open(os.path.join(base, f), encoding="utf-8").read())
    for base, _, files in os.walk(SRC):
        for f in sorted(files):
            if not f.endswith(".kt") or f in SKIP_FILES:
                continue
            path = os.path.join(base, f)
            code = open(path, encoding="utf-8").read()
            new = process(code, keys)
            if new != code:
                changed += 1
                if write:
                    if "tr(" in new and "import com.ricardochr.estantepinturas.tr\n" not in new and "package com.ricardochr.estantepinturas.ui" in new:
                        new = re.sub(r"^(package [^\n]+\n)", r"\1\nimport com.ricardochr.estantepinturas.tr\n", new, count=1, flags=re.M)
                    open(path, "w", encoding="utf-8").write(new)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(sorted(keys), open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    print(f"{len(keys)} claves; {changed} archivos {'reescritos' if write else 'por reescribir'}")


if __name__ == "__main__":
    main()
