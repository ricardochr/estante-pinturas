"""Pinturas y sets agregados a mano (tools/extra-paints.json) y pinturas sueltas tomadas de minoristas.

Las tiendas oficiales retiran de su web productos que siguen en venta (AK dejó de listar Ochre Earth AK14010,
los sets de pigmentos líquidos…). Este módulo los agrega al catálogo sin duplicar lo que ya existe.

Uso directo (aplica la lista manual sobre los assets actuales, sin descargar nada):
    python3 tools/extras.py
"""
import html
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
EXTRA = os.path.join(HERE, "extra-paints.json")
CATALOG = os.path.join(ROOT, "app", "src", "main", "assets", "catalog.json")
SETS = os.path.join(ROOT, "app", "src", "main", "assets", "sets.json")


def ckey(code):
    """Código comparable: 'AK-14010', 'AKI 14010' y 'AK14010' son el mismo."""
    s = re.sub(r"[^A-Z0-9]", "", (code or "").upper())
    return re.sub(r"^AKI(?=\d)", "AK", s)


def load_extra(path=EXTRA):
    if not os.path.exists(path):
        return {"paints": [], "sets": []}
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    return {"paints": d.get("paints", []), "sets": d.get("sets", [])}


def apply_paints(final, extra):
    """final: filas [marca, línea, nombre, código, hex, tipo, (calidad)]. Agrega las que faltan y corrige el color
    de las que ya existen solo si su color era una estimación de la foto (calidad "f"); un color medido se respeta.
    Devuelve (agregadas, corregidas)."""
    idx = {(r[0], ckey(r[3])): i for i, r in enumerate(final) if r[3]}
    added = fixed = 0
    for p in extra:
        k = (p["brand"], ckey(p["code"]))
        hx = p.get("hex") or "#888888"
        q = p.get("quality", "n")
        if k in idx:
            r = final[idx[k]]
            if p.get("hex") and len(r) > 6 and r[6] == "f":
                r[4] = hx
                r[6] = q
                fixed += 1
            continue
        row = [p["brand"], p["line"], p["name"], p["code"], hx, p.get("type", "Otro")]
        if q:
            row.append(q)
        final.append(row)
        idx[k] = len(final) - 1
        added += 1
    final.sort(key=lambda x: (x[0].lower(), x[1].lower(), x[2].lower()))
    return added, fixed


def apply_sets(sets, final, extra):
    """Agrega los sets de la lista manual que no estén ya (mismo código o mismas pinturas)."""
    by_code = {(r[0], ckey(r[3])): r for r in final if r[3]}
    have_codes = {(s["brand"], ckey(s.get("code"))) for s in sets if s.get("code")}
    have_ids = {(s["brand"], frozenset(s["paints"])) for s in sets}
    added = 0
    for s in extra:
        rows = [by_code.get((s["brand"], ckey(c))) for c in s["paints"]]
        if any(r is None for r in rows):
            print(f"  set {s['name']}: faltan pinturas en el catálogo, no se agrega", file=sys.stderr)
            continue
        ids = [f"{r[0]}|{r[1]}|{r[2]}|{r[3]}" for r in rows]
        if (s["brand"], ckey(s["code"])) in have_codes or (s["brand"], frozenset(ids)) in have_ids:
            continue
        sets.append({"brand": s["brand"], "name": s["name"], "code": s["code"], "paints": ids,
                     "expected": len(ids), "status": "ok", "note": f"Trae {len(ids)} pinturas; lista revisada a mano",
                     "source": "Lista manual"})
        added += 1
    sets.sort(key=lambda x: (x["brand"], x["name"]))
    return added


# ------------------------------------------------------------- pinturas sueltas de minoristas (AK)

AK_CODE = re.compile(r"AK(?:I)?[- ]?(\d{3,5})\b", re.I)
BARE_CODE = re.compile(r"(?<![\d.,])(1\d{4})(?![\d.,]|\s?ml)", re.I)  # "AK Interactive 14010 Ochre Earth"
RETAIL_NOT_SINGLE = re.compile(r"\b(set|sets|bundle|collection|box|kit|case|pack|range|triad|book|guide|brush|marker|pencil|"
                               r"airbrush|primer\s+400|spray|thinner|cleaner|varnish|medium|learning)\b|\(\d+\s*(?:pcs|x|ref)", re.I)
# (patrón en el título, línea en el catálogo, tipo)
AK_RETAIL_LINES = [
    (r"liquid pigment", "Liquid Pigments", "Otro"),
    (r"pigment", "Pigments", "Otro"),
    (r"xtreme metal", "Xtreme Metal (Enamel)", "Metálica"),
    (r"true metal", "True Metal (Wax)", "Metálica"),
    (r"pane(?:l ?)?liner", "Enamel Panel Liner", "Wash"),
    (r"\bfilter\b", "Enamel Filter", "Tinta"),
    (r"streaking|weathering effect|\bnature effect|\bdeposits?\b", "Enamel Effects", "Otro"),
    (r"\bquick ?gen\b", "Quick Gen", "Contraste"),
    (r"\bthe inks?\b|\bink\b", "The Inks", "Tinta"),
    (r"\boils?\b|abteilung", "Oils", "Opaca"),
]
_STRIP = re.compile(r"\bak[- ]?interactive\b:?|\bak\b:?|\bwargame\b|\benamel\b|\bliquid\b|\bpigments?\b|\bseries\b|"
                    r"\b\d+\s?ml\b|\(.*?\)|\bbottle\b|\bacrylic\b|\bpaints?\b|\bcolou?rs?\b", re.I)


def _title_case(s):
    return " ".join(w[:1].upper() + w[1:].lower() if not re.match(r"^[IVX]+$", w) else w for w in s.split())


def parse_retail_ak(prods):
    """prods: dicts con title, vendor, sku, img. Devuelve filas dict(brand, line, name, code, type, img)."""
    out, seen = [], set()
    for p in prods:
        t = html.unescape(p.get("title") or "").strip()
        if not re.search(r"\bak\b|ak[- ]interactive", (p.get("vendor") or "") + " " + t, re.I):
            continue
        if RETAIL_NOT_SINGLE.search(t):
            continue
        m = AK_CODE.search((p.get("sku") or "") + " " + t) or BARE_CODE.search(t)
        if not m:
            continue
        code = "AK" + m.group(1)
        if code in seen:
            continue
        hit = next(((ln, tp) for rx, ln, tp in AK_RETAIL_LINES if re.search(rx, t, re.I)), None)
        if not hit:
            continue
        # El nombre va después del último separador ("… Liquid Pigments - Acid Yellow 35 ml", "Liquid Pigment: Dark Earth")
        # Si esa parte solo trae la línea o el código ("35ml Concrete – Liquid Pigment AK14006"), se usa la anterior
        name = ""
        for part in reversed(re.split(r"\s+[–-]\s+|:\s+", t)):
            name = BARE_CODE.sub(" ", AK_CODE.sub(" ", part))
            name = _STRIP.sub(" ", name)
            name = re.sub(r"\s+", " ", name).strip(" -–:.,")
            if len(name) >= 3 and not re.fullmatch(r"[\d\s]+", name):
                break
            name = ""
        if not name:
            continue
        seen.add(code)
        out.append(dict(brand="AK Interactive", line=hit[0], name=_title_case(name), code=code, type=hit[1],
                        img=p.get("img") or []))
    return out


def main():
    extra = load_extra()
    with open(CATALOG, encoding="utf-8") as f:
        final = json.load(f)
    with open(SETS, encoding="utf-8") as f:
        sets = json.load(f)
    a, fx = apply_paints(final, extra["paints"])
    s = apply_sets(sets, final, extra["sets"])
    with open(CATALOG, "w", encoding="utf-8") as f:
        json.dump(final, f, ensure_ascii=False, separators=(",", ":"))
    with open(SETS, "w", encoding="utf-8") as f:
        json.dump(sets, f, ensure_ascii=False, separators=(",", ":"))
    print(f"Pinturas agregadas: {a}; colores corregidos: {fx}; sets agregados: {s}")


if __name__ == "__main__":
    main()
