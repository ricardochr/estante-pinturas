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

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "catalog.json")
REPORT = os.path.join(ROOT, "tools", "catalog-report.md")
CACHE = os.path.join(HERE, "color-cache.json")
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
    if "medium" in s or "thinner" in s or "reducer" in s or "retarder" in s: return "Médium"
    if "contrast" in s or "speedpaint" in s or "xpress" in s or "1-step" in s or "instant" in s or "quick gen" in s: return "Contraste"
    if "wash" in s or "shade" in s or "tone" in s.split(): return "Wash"
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
                  + fetch_wc("https://ak-interactive.com", 151796),
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
    if p.get("images"):
        src = p["images"][0]["src"]
        return src + ("&" if "?" in src else "?") + "width=160"
    return None


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
        img = None
        if p.get("images"):
            img = p["images"][0].get("thumbnail") or p["images"][0].get("src")
        out.append(dict(brand="Vallejo", line=line, name=name, code=sku, type=t, img=img))
    return out


def parse_ak(prods):
    out, seen = [], set()
    for p in prods:
        sku = (p.get("sku") or "").strip().upper()
        if not re.match(r"^AK1[17]\d{3}$", sku) or sku in seen:
            continue
        cats = [html.unescape(c["name"]) for c in p.get("categories", [])]
        # Cada producto existe en español e inglés; se usa la ficha en inglés
        if not any(c in ("AK product", "a.k. Campaings", "3GEN ACRYLICS", "Acrylics paints for modeling") for c in cats):
            continue
        raw = html.unescape(p["name"]).strip()
        if re.search(r"\bSET\b|GUIDE|\[PDF\]", raw, re.I):
            continue
        seen.add(sku)
        parts = [x.strip() for x in re.split(r"\s+[–-]\s+", raw)]
        name = titlecase(parts[0])
        suffix = parts[1].upper() if len(parts) > 1 else ""
        if sku.startswith("AK17"):
            line = "Quick Gen"
        else:
            line = "3rd Gen" + (" " + titlecase(suffix.replace("COLOR", "").replace("3RD GEN", "").replace("3GEN", "").strip()) if suffix else "")
            line = line.strip()
        t = guess_type(name, line)
        if sku.startswith("AK17") and t == "Opaca": t = "Contraste"
        img = None
        if p.get("images"):
            img = p["images"][0].get("thumbnail") or p["images"][0].get("src")
        out.append(dict(brand="AK Interactive", line=line, name=name, code=sku, type=t, img=img))
    return out


def parse_kaleido(prods):
    out, seen = [], set()
    for p in prods:
        m = re.match(r"^(K\d{3})\s+(.+)$", p["title"].strip())
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
        out.append(dict(brand="Kaleido Colorworks", line=line, name=name, code=code, type=guess_type(name, line), img=shopify_img(p)))
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
            img = None
            if v.get("image_id"):
                for im in p.get("images", []):
                    if im["id"] == v["image_id"]:
                        img = im["src"] + "?width=160"
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

    # 3) Colores faltantes: de la foto del producto
    missing = [r for r in rows if not r.get("hex")]
    print(f"Pinturas sin color conocido: {len(missing)}")
    if missing and args.fetch:
        sys.path.insert(0, HERE)
        from imgcolor import color_from_image
        done = 0
        for r in missing:
            img = r.get("img")
            if not img:
                continue
            if img in cache:
                r["hex"] = cache[img]; continue
            data = get(img, tries=2)
            hx = color_from_image(data, r["name"]) if data else None
            if hx:
                cache[img] = hx
                r["hex"] = hx
            done += 1
            if done % 100 == 0:
                print(f"  colores calculados: {done}")
                json.dump(cache, open(CACHE, "w"), indent=0, sort_keys=True)
        json.dump(cache, open(CACHE, "w"), indent=0, sort_keys=True)
    else:
        for r in missing:
            if r.get("img") in cache:
                r["hex"] = cache[r["img"]]

    final = []
    for r in rows:
        hx = r.get("hex") or "#888888"
        t = r.get("type") if r.get("type") in TYPES else guess_type(r["name"], r["line"])
        final.append([r["brand"], r["line"], r["name"], r["code"], hx, t])
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
    open(REPORT, "w", encoding="utf-8").write("\n".join(lines))
    print(f"catalog.json: {len(final)} pinturas")
    for b, n in sorted(by_brand.items(), key=lambda t: -t[1]):
        print(f"  {b}: {n}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--fetch", action="store_true")
    ap.add_argument("--raw")
    ap.add_argument("--save-raw")
    ap.add_argument("--paintdex", default="/tmp/paintdex/data")
    ap.add_argument("--miniature-paints", default="/tmp/miniature-paints")
    build(ap.parse_args())
