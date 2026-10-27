"""Genera app/src/main/assets/catalog.json a partir del repo miniature-paints (MIT, Rick Fleuren).
Uso: python3 tools/build_catalog.py <ruta a miniature-paints/paints> <salida catalog.json>
"""
import json, re, sys, pathlib

BRANDS = {
    "Vallejo": "Vallejo",
    "Monument": "Pro Acryl",
    "Creature": "Pro Acryl",
    "Citadel_Colour": "Citadel",
    "Army_Painter": "The Army Painter",
    "AK": "AK Interactive",
    "AKRC": "AK Real Colors",
    "Scale75": "Scale75",
    "Reaper": "Reaper",
    "GreenStuffWorld": "Green Stuff World",
    "KimeraKolors": "Kimera Kolors",
    "P3": "P3",
    "Tamiya": "Tamiya",
    "TurboDork": "Turbo Dork",
    "Foundry": "Foundry",
    "Mig": "Ammo by Mig",
    "MissionModels": "Mission Models",
    "Warcolours": "Warcolours",
    "CoatDArmes": "Coat d'Arms",
    "MrHobby": "Mr. Hobby",
    "Humbrol": "Humbrol",
}

def ptype(name, line):
    s = (name + " " + line).lower()
    if "primer" in s or "imprim" in s: return "Imprimación"
    if "varnish" in s or "barniz" in s or "matt medium" in s or "'ardcoat" in s or "lahmian medium" in s: return "Barniz"
    if "medium" in s or "thinner" in s: return "Médium"
    if "contrast" in s or "speedpaint" in s or "xpress" in s: return "Contraste"
    if "wash" in s or "shade" in s: return "Wash"
    if "ink" in s or "tinta" in s: return "Tinta"
    if any(w in s for w in ["metal", "gold", "silver", "bronze", "copper", "brass", "steel", "iron", "chrome", "oro", "plata", "gunmetal"]): return "Metálica"
    if re.search(r"\bair\b", s): return "Aerógrafo"
    return "Opaca"

def main(src, out):
    rows, seen = [], set()
    for key, brand in BRANDS.items():
        f = pathlib.Path(src) / f"{key}.md"
        header = None
        for raw in f.read_text(encoding="utf-8").splitlines():
            if not raw.startswith("|"): continue
            cells = [c.strip() for c in raw.strip().strip("|").split("|")]
            if header is None:
                header = [c.lower() for c in cells]; continue
            if set(raw) <= set("|-: "): continue
            if len(cells) != len(header): continue
            d = dict(zip(header, cells))
            name = d.get("name", "").strip()
            if not name: continue
            code = d.get("code", "")
            if code.lower() in ("null", "none", "-"): code = ""
            line = d.get("set", "")
            if line.isdigit(): line = ""
            if line.startswith("Monument Pro"):
                line = re.sub(r"^Monument Pro( Acrylic| Acryl)?", "", line).strip()
                line = {"": "Pro Acryl", "Paints": "Pro Acryl", "Transparent": "Transparent"}.get(line, line)
            m = re.search(r"#([0-9A-Fa-f]{6})", d.get("hex", ""))
            hexv = "#" + m.group(1).upper() if m else "#888888"
            k = (brand, line, name.lower(), code.lower())
            if k in seen: continue
            seen.add(k)
            rows.append([brand, line, name, code, hexv, ptype(name, line)])
    rows.sort(key=lambda r: (r[0], r[1], r[2]))
    pathlib.Path(out).parent.mkdir(parents=True, exist_ok=True)
    pathlib.Path(out).write_text(json.dumps(rows, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(len(rows), "pinturas")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
