"""Arma app/src/main/assets/catalog.json con los datos más recientes.

Fuentes:
  * Tiendas oficiales (Monument/Pro Acryl, The Army Painter, Scale75, Vallejo, AK Interactive,
    Gaahleri/Kaleido Colorworks) y Hobbyist Haven para DSPIAE: nombres, códigos y líneas actuales.
  * paintdex (MIT, 2026) y miniature-paints (MIT, 2025): colores hex y marcas sin tienda consultable.
  * Para pinturas nuevas sin hex conocido, el color se estima de la foto del producto
    (tools/imgcolor.py) y se guarda en tools/color-cache.json para no recalcularlo.

Uso en GitHub Actions:  python3 tools/update_catalog.py --fetch
Uso local con datos ya descargados:  python3 tools/update_catalog.py --raw /ruta/raw
"""
import argparse
import gzip
import html
import json
import os
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.request
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "catalog.json")
SETS_OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sets.json")
REPORT = os.path.join(ROOT, "tools", "catalog-report.md")
SETS_REPORT = os.path.join(ROOT, "tools", "sets-report.md")
CACHE = os.path.join(HERE, "color-cache-v3.json")  # v3: candidatos por imagen
OVERRIDES = os.path.join(HERE, "color-overrides.json")  # correcciones manuales: "Marca|Código": "#RRGGBB"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

TYPES = ["Opaca", "Metálica", "Wash", "Contraste", "Tinta", "Aerógrafo", "Imprimación", "Barniz", "Médium", "Otro"]


# ---------------------------------------------------------------- utilidades

def norm(s):
    s = unicodedata.normalize("NFD", s or "")
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    return re.sub(r"[^a-z0-9]", "", s.lower())


def code_key(s):
    return re.sub(r"[^A-Z0-9]", "", (s or "").upper())


BRAND_KEYS = {
    "proacryl": "Pro Acryl", "monument": "Pro Acryl", "monumenthobbies": "Pro Acryl",
    "armypainter": "The Army Painter", "thearmypainter": "The Army Painter",
    "warhammer": "Citadel", "citadel": "Citadel", "citadelcolour": "Citadel",
    "akinteractive": "AK Interactive", "scale75": "Scale75", "vallejo": "Vallejo",
    "greenstuffworld": "Green Stuff World", "ammobymig": "Ammo by Mig", "mig": "Ammo by Mig",
    "p3": "P3", "tamiya": "Tamiya", "duncanrhodes": "Duncan Rhodes",
}


def titlecase(s):
    s = s.strip()
    if s.isupper() or s.islower():
        s = " ".join(w if re.match(r"^[IVX]+$|^[A-Z]{1,3}\d", w) else w.capitalize() for w in s.lower().split())
        s = re.sub(r"\b(Iii|Ii|Iv|Vi|Vii)\b", lambda m: m.group(0).upper(), s)
    return s


def guess_type(name, line):
    s = (name + " " + line).lower()
    if "primer" in s or "prime " in s or s.startswith("prime") or "imprim" in s: return "Imprimación"
    if "varnish" in s or "barniz" in s: return "Barniz"
    # "Medium Fleshtone", "Medium Grey"… son colores; "Metallic Medium", "Glaze Medium" son auxiliares
    if re.search(r"\b(thinner|reducer|retarder)\b", s) or ("medium" in s and not re.match(r"\s*medium\s+\w", name.lower())): return "Médium"
    if "contrast" in s or "speedpaint" in s or "xpress" in s or "1-step" in s or "instant" in s or "quick gen" in s: return "Contraste"
    # "Skin Tone", "Fleshtone" son colores de piel, no lavados (como "Soft Tone" o "Dark Tone")
    if "wash" in s or "shade" in s or ("tone" in s.split() and not re.search(r"skin|flesh", s)): return "Wash"
    if re.search(r"\bink\b|tinta|transparent|glaze", s): return "Tinta"
    if re.search(r"\b(metal|metallic|metallics|gold|silver|bronze|copper|brass|steel|iron|chrome|gunmetal|aluminium|aluminum|pewter)\b|alchemy", s) and "oxide" not in s:
        return "Metálica"
    if re.search(r"\bair\b", s) and "air war" not in s: return "Aerógrafo"
    return "Opaca"


def get(url, tries=3, headers=None):
    h = {"User-Agent": UA, "Accept": "application/json,*/*", "Accept-Language": "en"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, headers=h)
    for i in range(tries):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503, 504) and i < tries - 1:
                time.sleep(8 * (i + 1)); continue
            print(f"  ! {e.code} {url}", file=sys.stderr)
            return None
        except Exception as e:
            if i < tries - 1:
                time.sleep(5); continue
            print(f"  ! {e} {url}", file=sys.stderr)
            return None


# ---------------------------------------------------------------- descarga

def fetch_shopify(host, path=""):
    items = []
    for p in range(1, 80):
        b = get(f"https://{host}{path}/products.json?limit=250&page={p}")
        if not b: break
        prods = json.loads(b).get("products", [])
        if not prods: break
        items += prods
        time.sleep(0.7)
    print(f"  {host}{path}: {len(items)} productos")
    return items


def fetch_wc(base, category=None):
    items = []
    for p in range(1, 200):
        url = f"{base}/wp-json/wc/store/v1/products?per_page=100&page={p}"
        if category: url += f"&category={category}"
        b = get(url)
        if not b: break
        prods = json.loads(b)
        if not prods: break
        items += prods
        time.sleep(0.7)
    print(f"  {base} cat={category}: {len(items)} productos")
    return items


def fetch_wc_search(base, terms):
    """Productos de una tienda WooCommerce por búsqueda (sin repetir)."""
    out, ids = [], set()
    for q in terms:
        for p in range(1, 40):
            b = get(f"{base}/wp-json/wc/store/v1/products?per_page=100&page={p}&search={q}")
            if not b: break
            prods = json.loads(b)
            if not prods: break
            for x in prods:
                if x["id"] not in ids:
                    ids.add(x["id"]); out.append(x)
            time.sleep(0.7)
    print(f"  {base} búsqueda {terms}: {len(out)} productos")
    return out


GSW_CATEGORIES = ["122-acrylic-paints", "126-metallic-acrylic-paints", "129-acrylic-inks", "153-chameleon-acrylic-paints",
                  "187-fluorescent-acrylic-paints", "259-military-acrylic-paints", "264-effect-paints", "123-paint-sets"]


def fetch_gsw():
    """Green Stuff World (PrestaShop): listado de cada categoría en JSON (?from-xhr), página por página."""
    out, ids = [], set()
    hdr = {"Accept": "application/json, text/javascript, */*; q=0.01", "X-Requested-With": "XMLHttpRequest"}
    for cat in GSW_CATEGORIES:
        for page in range(1, 60):
            b = get(f"https://www.greenstuffworld.com/en/{cat}?page={page}&from-xhr", headers=hdr)
            if not b:
                break
            try:
                d = json.loads(b)
            except Exception:
                break
            prods = d.get("products") or []
            for p in prods:
                if p.get("id_product") not in ids:
                    ids.add(p.get("id_product"))
                    p["_cat"] = cat
                    out.append(p)
            pg = d.get("pagination") or {}
            if not prods or page >= int(pg.get("pages_count") or 1):
                break
            time.sleep(0.7)
    print(f"  greenstuffworld.com: {len(out)} productos")
    return out


# Tiendas minoristas que venden muchas marcas: sirven para saber qué trae cada set de las marcas
# cuya tienda oficial no se puede leer (Citadel, Ammo, Mission Models, Reaper…) o que ya no lo listan.
RETAIL_SHOPIFY = ["hobbyisthaven.com", "www.gapgames.com.au", "www.goblingaming.co.uk", "www.thecombatcompany.com",
                  "goodgames.com.au", "www.gameology.com.au", "www.tistaminis.com"]
RETAIL_WC = ["https://www.scalemodelshop.co.uk", "https://www.redgrassgames.com"]
RETAIL_SET = re.compile(r"\b(set|sets|bundle|collection|box|kit|case|pack|triad)\b", re.I)
RETAIL_PAINT = re.compile(r"paint|colou?r|acrylic|wash|shade|speedpaint|contrast|xpress|ink|metallic|warpaint|game color|model color|primer", re.I)


def fetch_retail_sets():
    """Solo los productos que parecen sets de pintura, con su marca (vendor) y descripción."""
    out = []
    for host in RETAIL_SHOPIFY:
        n = 0
        for page in range(1, 120):
            b = get(f"https://{host}/products.json?limit=250&page={page}")
            if not b:
                break
            try:
                prods = json.loads(b).get("products", [])
            except Exception:
                break
            if not prods:
                break
            for p in prods:
                t = p.get("title") or ""
                if RETAIL_SET.search(t) and RETAIL_PAINT.search(t + " " + (p.get("product_type") or "")):
                    out.append({"title": t, "vendor": p.get("vendor") or "", "sku": ((p.get("variants") or [{}])[0].get("sku") or ""),
                                "text": p.get("body_html") or "", "src": host})
                    n += 1
            time.sleep(0.5)
        print(f"  {host}: {n} sets de pintura")
    for base in RETAIL_WC:
        n = 0
        for page in range(1, 200):
            b = get(f"{base}/wp-json/wc/store/v1/products?per_page=100&page={page}&search=set")
            if not b:
                break
            try:
                prods = json.loads(b)
            except Exception:
                break
            if not prods:
                break
            for p in prods:
                t = html.unescape(p.get("name") or "")
                if RETAIL_SET.search(t) and RETAIL_PAINT.search(t):
                    brands = " ".join(x.get("name", "") for x in (p.get("brands") or []))
                    out.append({"title": t, "vendor": brands, "sku": p.get("sku") or "",
                                "text": (p.get("description") or "") + " " + (p.get("short_description") or ""), "src": base})
                    n += 1
            time.sleep(0.5)
        print(f"  {base}: {n} sets de pintura")
    return out


def load_sources(args):
    if args.fetch:
        print("Descargando tiendas…")
        src = {
            "monument": fetch_shopify("monumenthobbies.com"),
            "armypainter": fetch_shopify("thearmypainter.com"),
            "scale75": fetch_shopify("scale75.com"),
            "gaahleri": fetch_shopify("www.gaahleri.com"),
            "vallejo": fetch_wc("https://acrylicosvallejo.com/en"),
            "ak": fetch_wc("https://ak-interactive.com", 1538) + fetch_wc("https://ak-interactive.com", 1410)
                  + fetch_wc("https://ak-interactive.com", 151796)
                  + fetch_wc("https://ak-interactive.com", 938) + fetch_wc("https://ak-interactive.com", 101810)
                  + fetch_wc_search("https://ak-interactive.com", ["acrylic wash", "wash", "ink", "set"]),
        }
        ds = []
        for h in ["dspiae-50ml-pre-mixed-acrylic-paint", "dspiae-50ml-pre-mixed-metallic-acrylic-paints"]:
            b = get(f"https://hobbyisthaven.com/products/{h}.json")
            if b: ds.append(json.loads(b)["product"])
        src["dspiae"] = ds
        src["gsw"] = fetch_gsw()
        src["turbodork"] = fetch_shopify("turbodork.com")
        src["retail"] = fetch_retail_sets()
        if args.save_raw:
            os.makedirs(args.save_raw, exist_ok=True)
            for k, v in src.items():
                with gzip.open(os.path.join(args.save_raw, k + ".json.gz"), "wt", encoding="utf-8") as f:
                    json.dump(v, f)
        return src
    src = {}
    for k in ["monument", "armypainter", "scale75", "gaahleri", "vallejo", "ak", "dspiae", "gsw", "turbodork", "retail"]:
        fn = os.path.join(args.raw, k + ".json.gz")
        src[k] = json.load(gzip.open(fn, "rt", encoding="utf-8")) if os.path.exists(fn) else []
    return src


# ---------------------------------------------------------------- parsers de tiendas
# Cada parser devuelve dicts: brand, line, name, code, type, img (url de imagen), hex (opcional)

def shopify_img(p):
    """Hasta 4 imágenes del producto (la primera suele ser el bote; otras, muestras de color)."""
    out = []
    for im in (p.get("images") or [])[:4]:
        src = im["src"]
        out.append(src + ("&" if "?" in src else "?") + "width=240")
    return out


def parse_monument(prods):
    out = []
    for p in prods:
        tags = [t.lower() for t in p.get("tags", [])]
        title = p["title"].strip()
        sku = (p["variants"][0].get("sku") or "").strip()
        if "pro acryl paints" not in tags and "weathering pigments" not in tags:
            continue
        if re.search(r"\bset\b|bundle|kit\b|collection|case|sampler|basing texture", title, re.I) or "SET" in sku.upper():
            continue
        if not sku:
            continue
        if "1-step paints" in tags: line = "1-Step"
        elif "expert acrylics" in tags: line = "Expert Acrylics"
        elif "amp colors" in tags: line = "AMP Colors"
        elif "primer" in tags or "PRIME" in title.upper().split(): line = "Prime"
        elif "weathering pigments" in tags: line = "Weathering Pigments"
        elif re.match(r"S\d+\s*-", title) or "signatureseries" in tags: line = "Signature Series"
        elif "fluorescents" in tags: line = "Fluorescent"
        elif "transparent" in tags: line = "Transparent"
        elif "pro acryl washes" in tags: line = "Wash"
        elif "mediums" in tags: line = "Medium"
        else: line = "Pro Acryl"
        name = title
        m = re.match(r"^(?:PRO Acryl |Pro Acryl )?(?:1-Step|Expert Acrylics|AMP Colors|PRIME|Weathering Pigments)\s+\w+\s*-\s*(.+)$", name, re.I)
        if m:
            name = m.group(1)
        else:
            name = re.sub(r"^(S\d+|F\d+|\d{3})\s*-\s*", "", name)
            name = re.sub(r"^(PRO Acryl|Pro Acryl)\s+", "", name)
        name = name.strip(" -")
        t = guess_type(name, line)
        if line == "Prime": t = "Imprimación"
        if line == "Weathering Pigments": t = "Otro"
        if line == "1-Step": t = "Contraste"
        if "mediums" in tags: t = "Médium"
        if "varnish" in name.lower(): t = "Barniz"
        if "metallics" in tags: t = "Metálica"
        out.append(dict(brand="Pro Acryl", line=line, name=name, code=sku, type=t, img=shopify_img(p)))
    return out


AP_LINES = [
    (r"^Warpaints Fanatic Metallic", "Warpaints Fanatic Metallic"), (r"^Warpaints Fanatic Wash", "Warpaints Fanatic Wash"),
    (r"^Warpaints Fanatic Effects", "Warpaints Fanatic Effects"), (r"^Warpaints Fanatic", "Warpaints Fanatic"),
    (r"^Warpaints Air Metallics", "Warpaints Air Metallics"), (r"^Warpaints Air Fluorescent", "Warpaints Air Fluorescent"),
    (r"^Warpaints Air", "Warpaints Air"), (r"^Speedpaint Marker", "Speedpaint Marker"), (r"^Speedpaint", "Speedpaint 2.0"),
    (r"^Colour Primer", "Colour Primer"), (r"^Historical", "Warpaints Historical"),
    (r"^John Blanche Masterclass", "John Blanche Masterclass"), (r"^Warpaints Effects", "Warpaints Effects"),
]


def parse_armypainter(prods):
    out = []
    for p in prods:
        title = p["title"].strip()
        sku = (p["variants"][0].get("sku") or "").strip().upper()
        if not re.match(r"^(WP[1234]\d{3}|AW[134]\d{3}|SM1\d{3}|CP3\d{3})[A-Z]*$", sku):
            continue
        if re.search(r"\bset\b|triad|combo|bundle|replacement", title, re.I):
            continue
        line = next((l for rx, l in AP_LINES if re.match(rx, title)), "Warpaints")
        name = re.split(r"[:,]\s*", title, maxsplit=1)[-1].strip()
        name = re.sub(r",\s*\d+\s*ml$", "", name)
        code = re.sub(r"[A-Z]+$", "", sku)
        t = guess_type(name, line)
        if line in ("Speedpaint 2.0", "Speedpaint Marker"): t = "Contraste" if "metallic" not in name.lower() else "Metálica"
        if line.startswith("Warpaints Air") and t == "Opaca": t = "Aerógrafo"
        if line == "Colour Primer": t = "Imprimación"
        if line == "Warpaints Fanatic Wash": t = "Wash"
        if "Metallic" in line: t = "Metálica"
        out.append(dict(brand="The Army Painter", line=line, name=name, code=code, type=t, img=shopify_img(p)))
    return out


S75_LINES = {
    "SC": "Scalecolor", "SDP": "Drop & Paint", "SART": "Artist", "SW": "Warfront", "SFG": "Fantasy & Games",
    "SIN": "Instant Colors", "SCG": "Scalecolor Games", "SFL": "Óleos", "SWE": "Soil Works", "SFX": "FX Fluor",
}


def parse_scale75(prods):
    out = []
    for p in prods:
        if p.get("product_type") not in ("Pintura individual",) and "lavado" not in [t.lower() for t in p.get("tags", [])]:
            continue
        sku = (p["variants"][0].get("sku") or "").strip().upper()
        m = re.match(r"^([A-Z]+)-?\d+", sku)
        if not m:
            continue
        pre = m.group(1)
        line = S75_LINES.get(pre, pre)
        name = titlecase(p["title"])
        t = guess_type(name, line)
        if pre == "SC" and re.search(r"alchemy|metal|gold|silver|steel|copper|bronze|brass", name, re.I): t = "Metálica"
        if pre == "SIN": t = "Contraste"
        if pre == "SWE": t = "Wash"
        out.append(dict(brand="Scale75", line=line, name=name, code=sku, type=t, img=shopify_img(p)))
    return out


VALLEJO_LINES = ["Model Color", "Model Air", "Game Color", "Game Air", "Xpress Color", "Mecha Color", "Premium Color",
                 "True Metallic Metal", "Squidmar Color", "Metal Color", "Liquid Metal", "Wash FX", "Weathering FX",
                 "Primers", "Hobby Paint", "Pigment FX", "Diorama FX", "Auxiliary Products", "Eccentric Colors – The Shifters"]


def parse_vallejo(prods):
    out = []
    for p in prods:
        sku = (p.get("sku") or "").strip()
        if not re.match(r"^\d{2}\.\d{3}$", sku):
            continue
        cats = [html.unescape(c["name"]) for c in p.get("categories", [])]
        if any("Sets" in c or c in ("Publications", "Sets") for c in cats):
            continue
        line = next((l for l in VALLEJO_LINES if l in cats), None)
        if not line:
            continue
        name = titlecase(html.unescape(p["name"]))
        t = guess_type(name, line)
        if line in ("Model Air", "Game Air") and t == "Opaca": t = "Aerógrafo"
        if line in ("True Metallic Metal", "Metal Color", "Liquid Metal"): t = "Metálica"
        if line == "Xpress Color": t = "Contraste"
        if line == "Primers": t = "Imprimación"
        if line == "Wash FX": t = "Wash"
        if line in ("Weathering FX", "Pigment FX", "Diorama FX"): t = "Otro"
        if line == "Auxiliary Products" and t == "Opaca": t = "Médium"
        img = [im.get("thumbnail") or im.get("src") for im in (p.get("images") or [])[:4]]
        out.append(dict(brand="Vallejo", line=line, name=name, code=sku, type=t, img=img))
    return out


# Líneas de AK fuera de 3rd Gen / Quick Gen / The Inks, según la categoría de la tienda (en orden de prioridad)
AK_CATEGORY_LINES = [
    ("GLOW FLUOR", "Glow Fluor (Enamel)", "Opaca"),
    ("XTREME METAL", "Xtreme Metal (Enamel)", "Metálica"),
    ("TRUE METAL COLORS", "True Metal (Wax)", "Metálica"),
    ("TRUE METAL", "True Metal (Wax)", "Metálica"),
    ("DUAL EXO", "Dual Exo", "Aerógrafo"),
    ("LIQUID PIGMENTS", "Liquid Pigments", "Otro"),
    ("OILS", "Oils", "Opaca"),
    ("ARTIST DENSE ACRYLIC PAINTS", "Artist Dense Acrylic", "Opaca"),
    ("GOUACHES", "Gouache", "Opaca"),
    ("FILTERS", "Enamel Filter", "Tinta"),
    ("PANEL LINERS", "Enamel Panel Liner", "Wash"),
    ("STREAKING PAINTS E N", "Enamel Effects", "Otro"),
    ("STREAKING PAINTS", "Enamel Effects", "Otro"),
    ("NATURE E N", "Enamel Effects", "Otro"),
    ("NATURE", "Enamel Effects", "Otro"),
    ("DEPOSITS", "Enamel Effects", "Otro"),
    ("SPECIALS E N", "Enamel Effects", "Otro"),
    ("SPECIALS", "Enamel Effects", "Otro"),
    ("NATURAL OXIDANTS", "Natural Oxidants", "Otro"),
    ("EFFECTS", "Enamel Effects", "Otro"),
    ("RC MILITARY", "Real Colors - Military", "Opaca"),
    ("RC STANDARD", "Real Colors - Standard", "Opaca"),
    ("RC CIVIL", "Real Colors - Civil", "Opaca"),
    ("PIGMENTS", "Pigments", "Otro"),
]
AK_NOT_PAINT = re.compile(r"\bSETS?\b|MARKER|PENCIL|GUIDE|\bBOOK|\[PDF\]|\bBOX\b|\bKIT\b|BRUSH|\(\d+\s?(?:X|UNITS|COLORS)", re.I)


def parse_ak(prods):
    out, seen = [], set()
    for p in prods:
        sku = (p.get("sku") or "").strip().upper()
        if sku in seen:
            continue
        cats = [html.unescape(c["name"]) for c in p.get("categories", [])]
        cat_set = {c.upper() for c in cats}
        raw = html.unescape(p["name"]).strip()
        # Cada producto existe en español e inglés; se usa la ficha en inglés
        if not any(c in ("AK product", "a.k. Campaings", "3GEN ACRYLICS", "Acrylics paints for modeling", "ACRYLIC WASHES",
                             "Scale Model Paints", "REAL COLORS") for c in cats):
            continue
        parts = [x.strip() for x in re.split(r"\s+[–-]\s+", raw)]
        # Líneas por categoría (Glow Fluor, Xtreme Metal, True Metal, efectos, filtros, óleos, Real Colors…)
        if not re.match(r"^AK(1[17]\d{3}|193\d{2}|160[0-5]\d)$", sku) and re.match(r"^(AK|RC|ABT)\w{3,6}$", sku) \
                and not sku.startswith("RCS") and not re.search(r"\bcolou?rs\b|\bset\b", raw, re.I) \
                and not AK_NOT_PAINT.search(raw):
            hit = next(((ln, tp) for key, ln, tp in AK_CATEGORY_LINES if key in cat_set), None)
            if hit:
                line, t = hit
                # "DUAL EXO 8A – TWINKLE PINK 60ml": el color va después del guion
                first = parts[1] if len(parts) > 1 and re.match(r"^DUAL EXO \d+[AB]$", parts[0], re.I) else parts[0]
                name = titlecase(re.sub(r"\s*\d+\s?ML\b.*$", "", first, flags=re.I).strip(" .-"))
                if t == "Opaca" and guess_type(name, "") == "Metálica":
                    t = "Metálica"
                if name:
                    seen.add(sku)
                    img = [im.get("thumbnail") or im.get("src") for im in (p.get("images") or [])[:4]]
                    out.append(dict(brand="AK Interactive", line=line, name=name, code=sku, type=t, img=img))
                continue
        # Sets, guías, marcadores y colecciones (no son una pintura)
        if re.search(r"\bSET\b|GUIDE|\[PDF\]|\(\d+ COLORS\)|\bBOX\b|MARKER|COLORS\b|TONES\b|SELECTION|\bVOL\b|MODELS\b|CREATURES", raw, re.I):
            continue
        img = [im.get("thumbnail") or im.get("src") for im in (p.get("images") or [])[:4]]
        if re.match(r"^AK193\d{2}$", sku):
            # Lavados acrílicos 3ª generación
            name = titlecase(re.sub(r"\s*[–-]\s*ACRYLIC WASH$", "", raw, flags=re.I))
            line, t = "Acrylic Wash", "Wash"
        elif re.match(r"^AK160[0-5]\d$", sku) and re.search(r"\bINK\b", raw, re.I):
            # The Inks
            name = titlecase(re.sub(r"\s*[–-]\s*INK$", "", raw, flags=re.I))
            line, t = "The Inks", "Tinta"
        elif re.match(r"^AK\d{3,5}$", sku) and ("AK INTERACTIVE WASHES" in cat_set or "WASHES" in cat_set) \
                and re.search(r"WASH", raw, re.I) and not re.match(r"^AK1[17]\d{3}$", sku):
            # Lavados de esmalte (modelismo y wargame)
            name = titlecase(re.sub(r"\s*\d+\s?ML$", "", re.sub(r"\s*[–-]\s*WARGAME SERIES$", "", raw, flags=re.I), flags=re.I))
            line, t = "Enamel Wash", "Wash"
        elif re.match(r"^AK1[17]\d{3}$", sku) and not sku.startswith("AK175"):
            if not any(c in ("AK product", "a.k. Campaings", "3GEN ACRYLICS", "Acrylics paints for modeling") for c in cats):
                continue
            if re.search(r"\(|\d+\s?ML\b", raw, re.I):
                continue
            name = titlecase(parts[0])
            suffix = parts[1].upper() if len(parts) > 1 else ""
            if sku.startswith("AK17"):
                line = "Quick Gen"
            else:
                line = "3rd Gen" + (" " + titlecase(suffix.replace("COLOR", "").replace("3RD GEN", "").replace("3GEN", "").strip()) if suffix else "")
                line = line.strip()
            t = guess_type(name, line)
            if re.search(r"washable", name, re.I): t = "Opaca"
            if sku.startswith("AK17") and t == "Opaca": t = "Contraste"
        else:
            continue
        seen.add(sku)
        out.append(dict(brand="AK Interactive", line=line, name=name, code=sku, type=t, img=img))
    return out


def parse_kaleido(prods):
    out, seen = [], set()
    for p in prods:
        m = re.match(r"^(K[CM]?\d{3})\s+(.+)$", p["title"].strip())
        if not m:
            continue
        code = m.group(1)
        if code in seen:
            continue
        seen.add(code)
        name = re.sub(r"\s*(20\s?ml|from Kaleido.*)$", "", m.group(2), flags=re.I)
        name = re.sub(r"\s*20\s?ml\b.*$", "", name, flags=re.I).strip()
        tags = [t.lower() for t in p.get("tags", [])]
        line = "Mecha Colors" if "mecha colors" in tags else "Auto Colors" if "auto colors" in tags else "Primary Colors" if "primary colors" in tags else "Kaleido"
        t = guess_type(name, line)
        if code.startswith("KM"):
            line, t = "Metal Colors", "Metálica"
        elif code.startswith("KC"):
            line, t = "Translucent Colors", "Tinta"
        elif re.match(r"^K3\d{2}$", code):
            # Auto Colors serie 300: metalizados y perlados
            line, t = "Auto Colors", "Metálica"
        out.append(dict(brand="Kaleido Colorworks", line=line, name=name, code=code, type=t, img=shopify_img(p)))
    return out


GSW_PREFIX = re.compile(
    r"^(acrylic colou?r|acrylic paint|metallic paint|metallic colou?r|dipping ink|chameleon(?: colorshift)?(?: paint)?|"
    r"fluor(?:escent)? metallic|fluor(?:escent)?(?: paint| colou?r)?|candy ink|intensity ink|wash ink|effect paint|paint)\s*[-–:]?\s*", re.I)


def parse_gsw(prods):
    out = []
    for p in prods:
        title = html.unescape(p.get("name") or "").strip()
        if p.get("_cat") == "123-paint-sets" or re.search(r"\bset\b|pack|kit\b|bundle|empty|bottle|dropper|brush", title, re.I):
            continue
        low = title.lower()
        if "dipping" in low: line, t = "Dipping Inks", "Wash"
        elif "intensity" in low: line, t = "Intensity Ink", "Tinta"
        elif "candy" in low: line, t = "Candy Ink Metallic", "Tinta"
        elif "wash ink" in low: line, t = "Wash Ink", "Wash"
        elif "chameleon" in low: line, t = "Chameleon Colorshift Metallic", "Metálica"
        elif "fluor" in low and "metallic" in low: line, t = "Fluor Metallic", "Metálica"
        elif "fluor" in low: line, t = "Fluor Colors", "Opaca"
        elif "metallic" in low or p.get("_cat") == "126-metallic-acrylic-paints": line, t = "Metallic Colors", "Metálica"
        elif p.get("_cat") == "264-effect-paints": line, t = "Effect Paints", "Otro"
        else: line, t = "Acrylic Colors", "Opaca"
        name = GSW_PREFIX.sub("", title)
        name = re.sub(r"\s*\d+\s?ml\b.*$", "", name, flags=re.I).strip(" -")
        if not name:
            continue
        name = titlecase(name)
        cover = p.get("cover") or {}
        img = [((cover.get("bySize") or {}).get("home_default") or {}).get("url") or (cover.get("medium") or {}).get("url")]
        out.append(dict(brand="Green Stuff World", line=line, name=name, code="", type=t, img=[i for i in img if i]))
    return out


def parse_dspiae(prods):
    out = []
    for p in prods:
        metallic = "metallic" in p["title"].lower()
        for v in p.get("variants", []):
            n = (v.get("title") or "").strip()
            if not n or n.lower().startswith("set ") or n == "Default Title" or "(" in n:
                continue
            n = n.replace("Si-fi", "Sci-Fi").replace("Desginer's", "Designer's").replace("MechanicGrayII", "Mechanic Gray II")
            code = ""
            m = re.match(r"^([A-Z]{2,4}-?\d{1,3})(?:\s+(.+))?$", n)
            if m:
                code = m.group(1)
                n = m.group(2) or ("Metallic " + code if metallic else code)
            img = []
            if v.get("image_id"):
                for im in p.get("images", []):
                    if im["id"] == v["image_id"]:
                        img = [im["src"] + "?width=240"]
            out.append(dict(brand="DSPIAE", line="50ml Pre-Mixed" + (" Metallic" if metallic else ""), name=n, code=code,
                            type="Metálica" if metallic else "Opaca", img=img))
    return out


# ---------------------------------------------------------------- conjuntos de datos abiertos

PAINTDEX_FILES = {
    "vallejo": "Vallejo", "army-painter": "The Army Painter", "scale-75": "Scale75", "ak-interactive": "AK Interactive",
    "warhammer": "Citadel", "green-stuff-world": "Green Stuff World", "mig": "Ammo by Mig", "p3": "P3",
    "tamiya": "Tamiya", "duncan-rhodes": "Duncan Rhodes",
}
PAINTDEX_TYPES = {"opaque": "Opaca", "contrast": "Contraste", "wash": "Wash", "glaze": "Tinta", "ink": "Tinta",
                  "primer": "Imprimación", "varnish": "Barniz", "medium": "Médium", "technical": "Otro"}
MP_ONLY = {"Reaper": "Reaper", "Foundry": "Foundry", "MissionModels": "Mission Models", "KimeraKolors": "Kimera Kolors",
           "TurboDork": "Turbo Dork", "CoatDArmes": "Coat d'Arms", "Warcolours": "Warcolours", "MrHobby": "Mr. Hobby",
           "Humbrol": "Humbrol"}
# Tiendas cuyo listado ya trae la gama completa: no se agregan sobrantes de los datasets
# (en Scale75 son las mismas pinturas con otra ortografía).
STORE_IS_COMPLETE = {"Scale75", "Pro Acryl", "Kaleido Colorworks", "DSPIAE"}
MP_HEX_ONLY = {"Monument": "Pro Acryl", "Creature": "Pro Acryl"}


def load_paintdex(path):
    ranges = json.load(open(os.path.join(path, "ranges.json")))["ranges"]
    out = []
    for fn, brand in PAINTDEX_FILES.items():
        for x in json.load(open(os.path.join(path, "paints", fn + ".json"), encoding="utf-8")):
            line = (x.get("range") or "").strip().replace("  ", " ")
            info = ranges.get(x.get("brand"), {}).get(x.get("range"), {})
            t = PAINTDEX_TYPES.get(x.get("type"), "Opaca")
            if x.get("metallic"): t = "Metálica"
            elif t == "Opaca" and info.get("format") == "airbrush": t = "Aerógrafo"
            if x.get("discontinued"): line = (line + " (descontinuada)").strip()
            out.append(dict(brand=brand, line=line, name=x["name"].strip(), code=(x.get("code") or "").strip(),
                            type=t, hex=(x.get("hex") or "").upper(), src="paintdex"))
    return out


def load_miniature_paints(path, files):
    out = []
    for key, brand in files.items():
        fn = os.path.join(path, "paints", key + ".md")
        header = None
        for raw in open(fn, encoding="utf-8").read().splitlines():
            if not raw.startswith("|"):
                continue
            cells = [c.strip() for c in raw.strip().strip("|").split("|")]
            if header is None:
                header = [c.lower() for c in cells]; continue
            if set(raw) <= set("|-: ") or len(cells) != len(header):
                continue
            d = dict(zip(header, cells))
            name = d.get("name", "").strip()
            if not name:
                continue
            code = d.get("code", "")
            if code.lower() in ("null", "none", "-"): code = ""
            line = d.get("set", "")
            if line.isdigit(): line = ""
            m = re.search(r"#([0-9A-Fa-f]{6})", d.get("hex", ""))
            out.append(dict(brand=brand, line=line, name=name, code=code, type=guess_type(name, line),
                            hex=("#" + m.group(1).upper()) if m else "", src="miniature-paints"))
    return out


# ---------------------------------------------------------------- unión

def severe_mismatch(hexv, name):
    """Color medido que contradice claramente el nombre (p. ej. un 'Orange Ink' azul)."""
    import math
    from colornames import EXPECT, expectations, hex_to_lab
    words = expectations(name)
    if not words:
        return False
    L, a, b = hex_to_lab(hexv)
    c = math.hypot(a, b)
    h = math.degrees(math.atan2(b, a)) % 360
    for w in words:
        lo, hi, cmin, cmax, lmin, lmax, _ = EXPECT[w]
        if lo is None:
            if c <= cmax + 15:
                return False
            continue
        if c < 6:
            continue
        mid = ((lo + (hi + 360 if hi < lo else hi)) / 2) % 360
        diff = abs((h - mid + 180) % 360 - 180)
        half = ((hi - lo) % 360) / 2
        if diff <= half + 60:
            return False
    return True


def key_code(r):
    ck = code_key(r["code"])
    return (r["brand"], ck) if ck else None


def key_name(r):
    return (r["brand"], norm(r["name"]))


def build(args):
    src = load_sources(args)
    store = {
        "Pro Acryl": parse_monument(src["monument"]),
        "The Army Painter": parse_armypainter(src["armypainter"]),
        "Scale75": parse_scale75(src["scale75"]),
        "Vallejo": parse_vallejo(src["vallejo"]),
        "AK Interactive": parse_ak(src["ak"]),
        "Kaleido Colorworks": parse_kaleido(src["gaahleri"]),
        "DSPIAE": parse_dspiae(src["dspiae"]),
        "Green Stuff World": parse_gsw(src.get("gsw", [])),
    }
    for b, rs in store.items():
        print(f"Tienda {b}: {len(rs)} pinturas")

    pdx = load_paintdex(args.paintdex)
    mp = load_miniature_paints(args.miniature_paints, MP_ONLY)
    mp_hex = load_miniature_paints(args.miniature_paints, MP_HEX_ONLY)
    mp_all_hex = load_miniature_paints(args.miniature_paints, {
        "Vallejo": "Vallejo", "Army_Painter": "The Army Painter", "Scale75": "Scale75", "AK": "AK Interactive",
        "AKRC": "AK Interactive", "Citadel_Colour": "Citadel"})

    # Índices de color conocidos (paintdex manda; miniature-paints completa)
    hex_by_code, hex_by_name = {}, {}
    for r in mp_all_hex + mp_hex + pdx:
        if not re.match(r"^#[0-9A-F]{6}$", r.get("hex", "")):
            continue
        kc = key_code(r)
        if kc:
            hex_by_code[kc] = r["hex"]
        else:
            # Solo pinturas sin código: evita prestar el color de otra línea con el mismo nombre
            hex_by_name[key_name(r)] = r["hex"]

    cache = json.load(open(CACHE)) if os.path.exists(CACHE) else {}
    rows, report_new = [], {}

    def add(r):
        rows.append(r)

    # 1) Marcas con tienda: lo de la tienda manda; se agregan las del dataset que la tienda ya no lista
    for brand, recs in store.items():
        seen_codes, seen_names, new = set(), set(), []
        for r in recs:
            kc, kn = key_code(r), key_name(r)
            if (kc and kc in seen_codes) or (not kc and kn in seen_names):
                continue
            hx = (kc and hex_by_code.get(kc)) or hex_by_name.get(kn)
            if not hx:
                new.append(r)
            r["hex"] = hx or ""
            if kc: seen_codes.add(kc)
            seen_names.add(kn)
            add(r)
        report_new[brand] = new
        if brand in STORE_IS_COMPLETE:
            continue
        for r in pdx + mp:
            if r["brand"] != brand:
                continue
            kc, kn = key_code(r), key_name(r)
            if (kc and kc in seen_codes) or kn in seen_names:
                continue
            if kc: seen_codes.add(kc)
            seen_names.add(kn)
            add(dict(r))

    # 2) Marcas sin tienda consultable: paintdex y miniature-paints
    store_brands = set(store)
    seen = set()
    for r in pdx + mp:
        if r["brand"] in store_brands:
            continue
        k = (r["brand"], norm(r["line"]), norm(r["name"]), code_key(r["code"]))
        if k in seen:
            continue
        seen.add(k)
        add(dict(r))

    # 3) Colores faltantes: de las fotos del producto, eligiendo el que coincide con el nombre
    sys.path.insert(0, HERE)
    from colornames import plausible, typical, expectations, hex_to_lab
    missing = [r for r in rows if not r.get("hex")]
    print(f"Pinturas sin color conocido: {len(missing)}")
    if missing and args.fetch:
        from imgcolor import color_candidates
        done = 0
        for r in missing:
            imgs = r.get("img") or []
            if isinstance(imgs, str):
                imgs = [imgs]
            options = []  # (hex, peso, plausible)
            for url in imgs:
                if url not in cache:
                    data = get(url, tries=2)
                    cache[url] = color_candidates(data, r["brand"]) if data else []
                    done += 1
                    if done % 100 == 0:
                        print(f"  imágenes analizadas: {done}")
                        json.dump(cache, open(CACHE, "w"), indent=0, sort_keys=True)
                for hx, share, primary in cache[url]:
                    options.append((hx, share + (0.5 if primary else 0), plausible(hx, r["name"])))
            r["_opts"] = options
            good = [o for o in options if o[2]]
            if good:
                r["hex"] = max(good, key=lambda o: o[1])[0]
                r["quality"] = "f"
            elif options and not expectations(r["name"]):
                r["hex"] = max(options, key=lambda o: o[1])[0]
                r["quality"] = "f"
        json.dump(cache, open(CACHE, "w"), indent=0, sort_keys=True)

    # 3b) Color del empaque: si el mismo color "medido de la foto" se repite en muchas pinturas
    #     distintas de una marca, es la etiqueta o el fondo, no la pintura.
    from collections import Counter
    rep = Counter((r["brand"], r["hex"]) for r in rows if r.get("quality") == "f")
    packaging = {k for k, n in rep.items() if n >= 4}
    if packaging:
        print(f"Colores de empaque descartados: {sorted(packaging)}")
    for r in rows:
        if r.get("quality") != "f" or (r["brand"], r["hex"]) not in packaging:
            continue
        opts = [o for o in r.get("_opts", []) if (r["brand"], o[0]) not in packaging]
        good = [o for o in opts if o[2]]
        if good:
            r["hex"] = max(good, key=lambda o: o[1])[0]
        elif opts and not expectations(r["name"]):
            r["hex"] = max(opts, key=lambda o: o[1])[0]
        else:
            r["hex"] = ""; r.pop("quality", None)
    for r in rows:
        r.pop("_opts", None)

    # 4) Validación: el color no debe contradecir el nombre
    overrides = json.load(open(OVERRIDES)) if os.path.exists(OVERRIDES) else {}
    fixed_name, kept_suspicious = 0, []
    for r in rows:
        key = f"{r['brand']}|{r['code'] or r['name']}"
        if key in overrides:
            r["hex"] = overrides[key]
            r["quality"] = ""
            continue
        hx = r.get("hex")
        if hx and plausible(hx, r["name"]):
            continue
        t = typical(r["name"])
        if not hx:
            if t:
                r["hex"] = t; r["quality"] = "n"; fixed_name += 1
            continue
        if t and (r.get("quality") == "f" or severe_mismatch(hx, r["name"])):
            r["hex"] = t; r["quality"] = "n"; fixed_name += 1
        elif t:
            kept_suspicious.append(r)
    print(f"Colores corregidos por nombre: {fixed_name}; dudosos sin cambiar (medidos): {len(kept_suspicious)}")

    final = []
    for r in rows:
        hx = r.get("hex") or "#888888"
        t = r.get("type") if r.get("type") in TYPES else guess_type(r["name"], r["line"])
        q = r.get("quality", "")
        if hx == "#888888":
            q = "n"
        row = [r["brand"], r["line"], r["name"], r["code"], hx, t]
        if q:
            row.append(q)  # "f" = estimado de la foto, "n" = típico según el nombre
        final.append(row)
    final.sort(key=lambda x: (x[0].lower(), x[1].lower(), x[2].lower()))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(final, f, ensure_ascii=False, separators=(",", ":"))

    # Reporte
    from collections import Counter, defaultdict
    by_brand = Counter(x[0] for x in final)
    by_line = defaultdict(Counter)
    for x in final:
        by_line[x[0]][x[1]] += 1
    lines = [f"# Catálogo de pinturas", "", f"Total: **{len(final)}** pinturas · generado {time.strftime('%Y-%m-%d')}", "",
             "| Marca | Pinturas | Fuente |", "|---|---:|---|"]
    for b, n in sorted(by_brand.items(), key=lambda t: -t[1]):
        lines.append(f"| {b} | {n} | {'tienda oficial + datos abiertos' if b in store else 'datos abiertos'} |")
    lines.append("")
    for b in sorted(store):
        lines.append(f"## {b}")
        lines.append("")
        for l, n in sorted(by_line[b].items(), key=lambda t: (-t[1], t[0])):
            lines.append(f"- {l or '(sin línea)'}: {n}")
        if report_new.get(b):
            lines.append(f"- Pinturas con color estimado de la foto: {len(report_new[b])}")
        lines.append("")
    q = Counter(x[6] if len(x) > 6 else "" for x in final)
    lines.append("## Calidad del color")
    lines.append("")
    lines.append(f"- Medido (datos abiertos o muestra oficial): {q['']}")
    lines.append(f"- Estimado de la foto del producto: {q['f']}")
    lines.append(f"- Típico según el nombre (aproximado): {q['n']}")
    lines.append("")
    open(REPORT, "w", encoding="utf-8").write("\n".join(lines))
    print(f"catalog.json: {len(final)} pinturas")
    for b, n in sorted(by_brand.items(), key=lambda t: -t[1]):
        print(f"  {b}: {n}")
    build_sets(src, final)


# ---------------------------------------------------------------- sets de pinturas

SET_SOURCES = {
    "monument": "Pro Acryl", "armypainter": "The Army Painter", "scale75": "Scale75",
    "gaahleri": "Kaleido Colorworks", "vallejo": "Vallejo", "ak": "AK Interactive", "gsw": "Green Stuff World",
    "turbodork": "Turbo Dork",
}
SET_TITLE = re.compile(r"\b(set|sets|bundle|collection|kit|box|range|case|pack)\b", re.I)
SET_EXCLUDE = re.compile(r"brush|pincel|rack|organi[sz]er|empty|palette|paleta|display|book|magazine|\[pdf\]|guide|airbrush|aer[oó]grafo|tool|herramienta|model kit|1/\d+|maqueta|figure|figura|terrain|basing|texture|decal|calcas|marker|rotulador|pencil|l[aá]pi[cz]|\bpens?\b|crayon|sticker|pegatina|gift card|tarjeta regalo|t-shirt|camiseta|mug|taza", re.I)
CODE_RES = [
    re.compile(r"\bMPA-?S?\d{2,3}\b|\bAMP-\d{3}\b|\bMPAP-\d{3}\b", re.I),     # Pro Acryl
    re.compile(r"\bWP\d{4}\b|\bAW\d{4}\b", re.I),                                # Army Painter
    re.compile(r"\bS(?:C|FG|CG|DP|IN|ART|WE)-\d{2,3}\b", re.I),                     # Scale75
    re.compile(r"\b7[0-7]\.\d{3}\b|\b2[68]\.\d{3}\b|\b6[29]\.\d{3}\b"),     # Vallejo
    re.compile(r"\bAK\d{4,5}\b", re.I),                                              # AK
    re.compile(r"\bK[CMP]?\d{3}\b"),                                                 # Kaleido
]


RETAIL_BRANDS = [
    (r"citadel|games ?workshop", "Citadel"), (r"vallejo", "Vallejo"), (r"army ?painter", "The Army Painter"),
    (r"\bak\b|ak[- ]interactive|ak3g|3rd gen", "AK Interactive"), (r"ammo|\bmig\b", "Ammo by Mig"),
    (r"mission models", "Mission Models"), (r"reaper", "Reaper"), (r"pro ?acryl|monument", "Pro Acryl"),
    (r"scale ?75", "Scale75"), (r"green ?stuff", "Green Stuff World"), (r"kimera", "Kimera Kolors"),
    (r"turbo ?dork", "Turbo Dork"), (r"foundry", "Foundry"), (r"tamiya", "Tamiya"), (r"mr\.? ?hobby|gsi creos|mr\.? ?color", "Mr. Hobby"),
    (r"humbrol", "Humbrol"), (r"two thin coats|duncan rhodes", "Duncan Rhodes"), (r"warcolou?rs", "Warcolours"),
    (r"coat d.?arms", "Coat d'Arms"), (r"\bp3\b|formula p3|privateer", "P3"), (r"kaleido|gaahleri", "Kaleido Colorworks"),
    (r"dspiae", "DSPIAE"),
]


def retail_brand(vendor, title):
    for txt in (vendor, title):
        for rx, b in RETAIL_BRANDS:
            if txt and re.search(rx, txt, re.I):
                return b
    return None


def clean_retail_title(title, brand):
    """Quita del título de un minorista la marca, tamaños y avisos ("(runout sale)", "17mL acrylic"…)."""
    t = re.sub(r"\((?:[^)]*sale|x?\d+)\)|\b\d+\s?ml\b|acrylic paint set|acrylic|\brunout\b", " ", title, flags=re.I)
    for rx, b in RETAIL_BRANDS:
        if b == brand:
            t = re.sub(r"^\s*(?:%s)[\s:–-]*" % rx, "", t, flags=re.I)
    t = re.sub(r"^\s*[-–:]\s*|\s*[-–:]\s*$", "", re.sub(r"\s+", " ", t)).strip()
    return t or title


def _codes(prefix, a, b):
    return [f"{prefix}{i:03d}" for i in range(a, b + 1)]


# Kaleido no lista los colores en la descripción de sus sets, pero cada set corresponde a un tramo
# de su numeración (los "B" son la segunda mitad de cada serie). El orden importa: lo específico primero.
KALEIDO_SET_RULES = [
    (r"12 (matt primary|primary matt)", _codes("K", 37, 48)),
    (r"12 (translucent|clear)", _codes("KC", 1, 12)),
    (r"12 metal", _codes("KM", 1, 12)),
    (r"24 primary colou?rs set b", _codes("K", 25, 48)),
    (r"24 primary", _codes("K", 1, 24)),
    (r"12 primary", _codes("K", 1, 12)),
    (r"24 mecha colou?rs set b", _codes("K", 125, 148)),
    (r"24 mecha", _codes("K", 101, 124)),
    (r"24 auto colou?rs set b", _codes("K", 209, 220) + _codes("K", 317, 328)),
    (r"24 auto", _codes("K", 201, 208) + _codes("K", 301, 316)),
]


_NUM_WORDS = {"two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10,
              "eleven": 11, "twelve": 12, "fifteen": 15, "sixteen": 16, "twenty": 20, "twenty four": 24, "thirty": 30}
_COUNT_UNIT = r"(?:x\s*)?(?:[a-z]+\s){0,2}?(?:colou?rs?|paints?|pots?|bottles?|botes?|colores|pinturas|pcs|pieces|piezas|units?|unidades|dropper|jars?|tarros?|inks?|washes|lavados|metallics?|shades|tonos)\b"


def expected_count(title, text):
    """Cuántas pinturas dice traer el set ("12 Colors", "Set of 8", "(x6)", "Contains 16 paints"…); None si no lo dice."""
    def scan(t, strict):
        t = " " + re.sub(r"\s+", " ", t.lower().replace("-", " ")) + " "
        for w, n in sorted(_NUM_WORDS.items(), key=lambda x: -len(x[0])):
            t = re.sub(rf"\b{w}\b", str(n), t)
        pats = [rf"\b(\d{{1,2}})\s*{_COUNT_UNIT}", r"\bset of (\d{1,2})\b", r"\bpack of (\d{1,2})\b", r"\bjuego de (\d{1,2})\b"]
        if strict:  # solo en el título: "(8)", "x8", "8 set"
            pats += [r"\((?:x\s?)?(\d{1,2})\)", r"\bx\s?(\d{1,2})\b", r"\b(\d{1,2})\s?(?:set|pack|kit)\b"]
        for rx in pats:
            for m in re.finditer(rx, t):
                n = int(m.group(1))
                after = t[m.end():m.end() + 4]
                if 2 <= n <= 60 and not re.match(r"\s?ml\b", after):
                    return n
        return None
    return scan(title, True) or scan(text[:1500], False)


def _strip_html(t):
    t = re.sub(r"<[^>]+>", " ", t or "")
    return re.sub(r"\s+", " ", html.unescape(t))


def _set_items(key, prods):
    """(título, sku, texto) de los productos que parecen sets."""
    out = []
    for p in prods:
        if key in ("ak", "vallejo", "gsw"):
            title = html.unescape(p.get("name") or p.get("title") or "")
            sku = (p.get("sku") or p.get("reference") or "").strip()
            text = _strip_html((p.get("description") or "") + " " + (p.get("short_description") or "") + " " + (p.get("description_short") or ""))
        else:
            title = p.get("title") or ""
            sku = ((p.get("variants") or [{}])[0].get("sku") or "").strip()
            text = _strip_html(p.get("body_html"))
        if not SET_TITLE.search(title) or SET_EXCLUDE.search(title):
            continue
        # AK publica cada producto en inglés y en español: se usa el que está en inglés
        if key == "ak" and re.search(r"[áéíóúñ]|\bSET DE\b|LAVADOS|COLORES", title, re.I):
            continue
        out.append((title.strip(), sku, text))
    # Mismo producto publicado en dos idiomas (mismo SKU): se queda el título en inglés
    spanish = re.compile(r"\b(SET DE|CAMUFLAJE|COLORES|ENANOS|HUMANOS|ORCOS|ELFOS|LAVADOS|PINTURAS|COLOR REAL|Y|CON|PARA|DEL|LOS|LAS)\b|[áéíóúñ]", re.I)
    best = {}
    for t in out:
        k = t[1] or t[0]
        if k not in best or (spanish.search(best[k][0]) and not spanish.search(t[0])):
            best[k] = t
    return [t for t in best.values() if not (key == "ak" and spanish.search(t[0]))]


def build_sets(src, final):
    by_brand = defaultdict(list)
    for r in final:
        by_brand[r[0]].append(r)
    sets, seen, pending, rejected = [], set(), [], []
    # Fuentes: tiendas oficiales primero; después los minoristas, agrupados por la marca de cada producto
    sources = [(key, brand, [t + ("Tienda oficial",) for t in _set_items(key, src.get(key) or [])]) for key, brand in SET_SOURCES.items()]
    retail_by_brand = defaultdict(list)
    for p in src.get("retail") or []:
        b = retail_brand(p["vendor"], p["title"])
        if b and not SET_EXCLUDE.search(p["title"]):
            host = re.sub(r"^https?://(www\.)?|/.*$", "", p.get("src") or "")
            retail_by_brand[b].append((html.unescape(p["title"]).strip(), p["sku"], _strip_html(p["text"]), host or "Minorista"))
    for b, items in retail_by_brand.items():
        sources.append(("retail", b, items))
    paint_sets_seen = set()
    for key, brand, set_items in sources:
        rows = by_brand.get(brand, [])
        if not rows or not set_items:
            continue
        by_code = {}
        by_name = defaultdict(list)
        for r in rows:
            if r[3]:
                by_code.setdefault(code_key(r[3]), r)
            by_name[norm(r[2])].append(r)
        names = sorted((n for n in by_name if len(n) >= 4), key=len, reverse=True)
        for title, sku, text, origin in set_items:
            found = []
            method = "código"
            # 1) por código
            for rx in CODE_RES:
                for m in rx.findall(text):
                    r = by_code.get(code_key(m))
                    if r and r not in found:
                        found.append(r)
            def asc(t):
                return unicodedata.normalize("NFD", t.lower()).encode("ascii", "ignore").decode()

            def words_of(t):
                return " ".join(re.findall(r"[a-z0-9]+", asc(t)))

            if len(found) < 3:
                # El texto se normaliza conservando los separadores de la lista ("A, B y C" -> " a | b | c "),
                # para que un nombre no se arme con el final de uno y el inicio del siguiente
                low = " " + " | ".join(words_of(x) for x in re.split(r"[,;•\n·]|\s[-–]\s|\band\b|\by\b", text, flags=re.I)) + " "
                low = re.sub(r"\s+", " ", low)
                items = {re.sub(r"^(colou?rs? |paints? )?(include[sd]?|included|incluye) ", "", words_of(x)).strip()
                         for x in re.split(r"[,;•\n·]|\s[-–]\s|\band\b|\by\b", text, flags=re.I)}
                title_w = " " + words_of(title) + " "
                compact_title = norm(title)
                # 2a) Pinturas "de artista" (Signature Series): el set las nombra sin el prefijo del artista
                #     ("Bright Green" = "Rogue Hobbies Bright Green"); solo si el artista está en el título del set
                #     También "Fluorescents Set: Red, Green…" = "Fluorescent Red", "Fluorescent Green"…
                for r in rows:
                    if r in found:
                        continue
                    w = words_of(r[2]).split()
                    line_w = words_of(r[1])
                    for k in (2, 1):
                        if len(w) <= k:
                            continue
                        prefix, rest = " ".join(w[:k]), " ".join(w[k:])
                        if "signature" not in r[1].lower() and prefix not in line_w:
                            continue
                        if (f" {prefix} " in title_w or f" {prefix}s " in title_w) and f" {rest} " in low:
                            found.append(r)
                            low = low.replace(f" {rest} ", " | ", 1)
                            break
                # 2b) Por nombre: los más largos primero y sin reutilizar el texto
                for n in names:
                    cands = by_name[n]
                    words = words_of(cands[0][2])
                    if len(words) < 4 or f" {words} " not in low:
                        continue
                    # Un nombre de una sola palabra ("Black", "Silver") solo cuenta si es un elemento
                    # completo de la lista ("…, Silver, …"), no parte de otro nombre ("Black Brush-On Primer")
                    if " " not in words and words not in items:
                        continue
                    low = low.replace(f" {words} ", " | ", 1)
                    pick = next((c for c in cands if norm(c[1]) and norm(c[1]) in compact_title), None)
                    if pick is None and found:
                        lines = Counter(f[1] for f in found)
                        pick = max(cands, key=lambda c: lines.get(c[1], 0))
                    pick = pick or cands[0]
                    if pick not in found:
                        found.append(pick)
                        method = "nombre"
            # 3) Kaleido: sets por tramo de numeración
            if len(found) < 3 and brand == "Kaleido Colorworks":
                for rx, codes in KALEIDO_SET_RULES:
                    if re.search(rx, title, re.I):
                        found = [by_code[code_key(c)] for c in codes if code_key(c) in by_code]
                        method = "numeración"
                        break
            # 4) Sets que se arman con otros sets ("todos los colores del Base Set, Expansion 1…"): se resuelven al final
            if len(found) < 3:
                if len(found) == 0 or not re.search(r"base set|intro set|metallic set|expansion", text, re.I):
                    rejected.append((brand, title, origin, f"solo {len(found)} pinturas reconocidas"))
                refs = re.findall(r"(base set|intro set|metallic set|expansion(?: set)? ?#? ?\d)", text, re.I)
                if refs:
                    pending.append((brand, title, sku, [norm(x).replace("set", "") for x in refs], origin))
                continue
            # Validación: lo que dice el set que trae contra lo que se reconoció
            exp = expected_count(title, text)
            if key == "retail":
                title = clean_retail_title(title, brand)
            status, note = validate_set(len(found), exp, method, key)
            if status is None:
                rejected.append((brand, title, origin, note))
                continue
            k = (brand, norm(title))
            ids = frozenset(f"{r[0]}|{r[1]}|{r[2]}|{r[3]}" for r in found)
            pk = (brand, ids)
            # El mismo set publicado por la tienda oficial y por minoristas (o por varios minoristas) va una sola vez:
            # mismo nombre, mismas pinturas, o casi las mismas (≥75 % en común) en la misma marca
            if k in seen or pk in paint_sets_seen:
                continue
            if any(b == brand and len(ids & other) >= 0.75 * max(len(ids), len(other)) for b, other in paint_sets_seen):
                continue
            seen.add(k)
            paint_sets_seen.add(pk)
            sets.append({
                "brand": brand, "name": title, "code": sku,
                "paints": [f"{r[0]}|{r[1]}|{r[2]}|{r[3]}" for r in found],
                "expected": exp or 0, "status": status, "note": note, "source": origin,
            })
    for brand, title, sku, refs, origin in pending:
        paints = []
        for ref in refs:
            for st in sets:
                if st["brand"] == brand and ref in norm(st["name"]).replace("set", "").replace("proacryl", ""):
                    paints += [x for x in st["paints"] if x not in paints]
                    break
        if len(paints) >= 3 and (brand, norm(title)) not in seen:
            seen.add((brand, norm(title)))
            sets.append({"brand": brand, "name": title, "code": sku, "paints": paints, "expected": 0,
                         "status": "ok", "note": "Reúne los colores de otros sets de la marca", "source": origin})
    sets.sort(key=lambda x: (x["brand"], x["name"]))
    with open(SETS_OUT, "w", encoding="utf-8") as f:
        json.dump(sets, f, ensure_ascii=False, separators=(",", ":"))
    print(f"sets.json: {len(sets)} sets")
    for b, n in Counter(x["brand"] for x in sets).most_common():
        print(f"  {b}: {n}")
    write_sets_report(sets, rejected)


def validate_set(found, expected, method, key):
    """(estado, nota) del set; estado None = se descarta.
    ok: coincide con lo que anuncia · parcial: faltan algunas · revisar: no se pudo confirmar del todo."""
    if expected:
        if found == expected:
            return "ok", f"Trae {expected} pinturas; se reconocieron todas"
        if found > expected:
            # Más de las anunciadas: el texto nombra colores que no vienen en el set (sugerencias, "también te puede interesar"…)
            if found > expected * 1.5 + 1:
                return None, f"anuncia {expected} pero se reconocieron {found}: el texto mezcla otros productos"
            return "revisar", f"Anuncia {expected} pinturas y se reconocieron {found}: puede incluir alguna que no viene"
        if found >= expected * 0.75:
            return "parcial", f"Trae {expected} pinturas; se reconocieron {found} (faltan {expected - found} en el catálogo)"
        if found >= expected * 0.5:
            return "revisar", f"Trae {expected} pinturas; solo se reconocieron {found}"
        return None, f"anuncia {expected} pero solo se reconocieron {found}"
    if method == "numeración":
        return "revisar", "Kaleido no publica la lista: se dedujo por la numeración de la serie"
    if method == "nombre" and found < 4:
        return None, f"solo {found} nombres reconocidos y el set no dice cuántas trae"
    if key == "retail":
        return "revisar", "Solo aparece en tiendas minoristas y no dice cuántas pinturas trae"
    return "ok", "Lista tomada de la tienda oficial"


def write_sets_report(sets, rejected):
    st = Counter(x["status"] for x in sets)
    lines = ["# Validación de sets", "",
             f"Sets publicados: {len(sets)} — ok {st['ok']}, parcial {st['parcial']}, revisar {st['revisar']}. Descartados: {len(rejected)}.", ""]
    lines += ["## Por revisar o parciales", ""]
    for x in sets:
        if x["status"] != "ok":
            lines.append(f"- **{x['brand']} — {x['name']}** ({len(x['paints'])}/{x['expected'] or '?'}, {x['source']}): {x['note']}")
    lines += ["", "## Descartados", ""]
    for b, t, o, why in sorted(rejected):
        lines.append(f"- {b} — {t} ({o}): {why}")
    open(SETS_REPORT, "w", encoding="utf-8").write("\n".join(lines) + "\n")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--fetch", action="store_true")
    ap.add_argument("--raw")
    ap.add_argument("--save-raw")
    ap.add_argument("--paintdex", default="/tmp/paintdex/data")
    ap.add_argument("--miniature-paints", default="/tmp/miniature-paints")
    build(ap.parse_args())
