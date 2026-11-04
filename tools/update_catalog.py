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


def get(url, tries=3):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json,*/*", "Accept-Language": "en"})
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
                  + fetch_wc_search("https://ak-interactive.com", ["acrylic wash", "wash", "ink", "set"]),
        }
        ds = []
        for h in ["dspiae-50ml-pre-mixed-acrylic-paint", "dspiae-50ml-pre-mixed-metallic-acrylic-paints"]:
            b = get(f"https://hobbyisthaven.com/products/{h}.json")
            if b: ds.append(json.loads(b)["product"])
        src["dspiae"] = ds
        if args.save_raw:
            os.makedirs(args.save_raw, exist_ok=True)
            for k, v in src.items():
                with gzip.open(os.path.join(args.save_raw, k + ".json.gz"), "wt", encoding="utf-8") as f:
                    json.dump(v, f)
        return src
    src = {}
    for k in ["monument", "armypainter", "scale75", "gaahleri", "vallejo", "ak", "dspiae"]:
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
        if not any(c in ("AK product", "a.k. Campaings", "3GEN ACRYLICS", "Acrylics paints for modeling", "ACRYLIC WASHES") for c in cats):
            continue
        # Sets, guías, marcadores y colecciones (no son una pintura)
        if re.search(r"\bSET\b|GUIDE|\[PDF\]|\(\d+ COLORS\)|\bBOX\b|MARKER|COLORS\b|TONES\b|SELECTION|\bVOL\b|MODELS\b|CREATURES", raw, re.I):
            continue
        parts = [x.strip() for x in re.split(r"\s+[–-]\s+", raw)]
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
}
SET_TITLE = re.compile(r"\b(set|sets|bundle|collection|kit|box|range|case|pack)\b", re.I)
SET_EXCLUDE = re.compile(r"brush|pincel|rack|organi[sz]er|empty|palette|paleta|display|book|magazine|\[pdf\]|guide|airbrush|aer[oó]grafo|tool|herramienta|model kit|1/\d+|maqueta|figure|figura|terrain|base[s]? set|texture|decal|calcas", re.I)
CODE_RES = [
    re.compile(r"\bMPA-?S?\d{2,3}\b|\bAMP-\d{3}\b|\bMPAP-\d{3}\b", re.I),     # Pro Acryl
    re.compile(r"\bWP\d{4}\b|\bAW\d{4}\b", re.I),                                # Army Painter
    re.compile(r"\bS(?:C|FG|CG|DP|IN|ART|WE)-\d{2,3}\b", re.I),                     # Scale75
    re.compile(r"\b7[0-7]\.\d{3}\b|\b2[68]\.\d{3}\b|\b6[29]\.\d{3}\b"),     # Vallejo
    re.compile(r"\bAK\d{4,5}\b", re.I),                                              # AK
    re.compile(r"\bK[CMP]?\d{3}\b"),                                                 # Kaleido
]


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
    return out


def build_sets(src, final):
    by_brand = defaultdict(list)
    for r in final:
        by_brand[r[0]].append(r)
    sets, seen = [], set()
    for key, brand in SET_SOURCES.items():
        rows = by_brand.get(brand, [])
        if not rows or not src.get(key):
            continue
        by_code = {}
        by_name = defaultdict(list)
        for r in rows:
            if r[3]:
                by_code.setdefault(code_key(r[3]), r)
            by_name[norm(r[2])].append(r)
        names = sorted((n for n in by_name if len(n) >= 4), key=len, reverse=True)
        for title, sku, text in _set_items(key, src[key]):
            found = []
            # 1) por código
            for rx in CODE_RES:
                for m in rx.findall(text):
                    r = by_code.get(code_key(m))
                    if r and r not in found:
                        found.append(r)
            # 2) por nombre, si los códigos no alcanzan: nombres largos primero y sin reutilizar el texto
            if len(found) < 3:
                low = " " + re.sub(r"[^a-z0-9]+", " ", unicodedata.normalize("NFD", text.lower()).encode("ascii", "ignore").decode()) + " "
                compact_title = norm(title)
                items = {" ".join(re.findall(r"[a-z0-9]+", unicodedata.normalize("NFD", x.lower()).encode("ascii", "ignore").decode())).removeprefix("and ").strip()
                         for x in re.split(r"[,;:•\n·]|\band\b|\by\b", text, flags=re.I)}
                for n in names:
                    cands = by_name[n]
                    words = " ".join(re.findall(r"[a-z0-9]+", unicodedata.normalize("NFD", cands[0][2].lower()).encode("ascii", "ignore").decode()))
                    if len(words) < 4 or f" {words} " not in low:
                        continue
                    # Un nombre de una sola palabra ("Black", "Silver") solo cuenta si aparece como elemento
                    # completo de la lista ("…, Silver, …"), no dentro de otro nombre ("Black Brush-On Primer")
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
            if len(found) < 3:
                continue
            k = (brand, norm(title))
            if k in seen:
                continue
            seen.add(k)
            sets.append({
                "brand": brand, "name": title, "code": sku,
                "paints": [f"{r[0]}|{r[1]}|{r[2]}|{r[3]}" for r in found],
            })
    sets.sort(key=lambda x: (x["brand"], x["name"]))
    with open(SETS_OUT, "w", encoding="utf-8") as f:
        json.dump(sets, f, ensure_ascii=False, separators=(",", ":"))
    print(f"sets.json: {len(sets)} sets")
    for b, n in Counter(x["brand"] for x in sets).most_common():
        print(f"  {b}: {n}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--fetch", action="store_true")
    ap.add_argument("--raw")
    ap.add_argument("--save-raw")
    ap.add_argument("--paintdex", default="/tmp/paintdex/data")
    ap.add_argument("--miniature-paints", default="/tmp/miniature-paints")
    build(ap.parse_args())
