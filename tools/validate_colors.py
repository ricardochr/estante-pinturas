#!/usr/bin/env python3
"""Valida el color de cada pintura del catálogo contra fuentes independientes.

Para cada pintura se juntan los colores que dan los datos abiertos (paintdex y miniature-paints,
buscando por código y si no por nombre) y se comparan con el color del catálogo (ΔE2000):

  - coincide:      todas las fuentes están a menos de 10 de diferencia
  - discrepa:      alguna fuente está a 10–20: probablemente el mismo color con distinta medición
  - contradice:    alguna fuente está a más de 20: una de las dos está mal (código cruzado, foto equivocada…)
  - sin_contraste: solo hay una fuente o ninguna (no se puede confirmar)

Además se revisa que el color no contradiga el nombre ("Red" que sale azul…).

Uso:  python3 tools/validate_colors.py --paintdex DIR/data --miniature-paints DIR [--write-flags]
      --write-flags marca en catalog.json con calidad "d" (dudoso) las que se contradicen.
"""
import argparse
import json
import math
import os
import re
import sys
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
from colornames import hex_to_lab, plausible, expectations  # noqa: E402
import update_catalog as uc  # noqa: E402

CATALOG = os.path.join(ROOT, "app", "src", "main", "assets", "catalog.json")
REPORT = os.path.join(HERE, "colors-report.md")


def de2000(l1, l2):
    L1, a1, b1 = l1
    L2, a2, b2 = l2
    C1, C2 = math.hypot(a1, b1), math.hypot(a2, b2)
    Cm = (C1 + C2) / 2
    G = 0.5 * (1 - math.sqrt(Cm ** 7 / (Cm ** 7 + 25 ** 7)))
    a1p, a2p = a1 * (1 + G), a2 * (1 + G)
    C1p, C2p = math.hypot(a1p, b1), math.hypot(a2p, b2)
    h1p = math.degrees(math.atan2(b1, a1p)) % 360
    h2p = math.degrees(math.atan2(b2, a2p)) % 360
    dLp, dCp = L2 - L1, C2p - C1p
    dhp = 0 if C1p * C2p == 0 else (h2p - h1p if abs(h2p - h1p) <= 180 else h2p - h1p - 360 if h2p > h1p else h2p - h1p + 360)
    dHp = 2 * math.sqrt(C1p * C2p) * math.sin(math.radians(dhp / 2))
    Lm, Cmp = (L1 + L2) / 2, (C1p + C2p) / 2
    if C1p * C2p == 0:
        hm = h1p + h2p
    elif abs(h1p - h2p) <= 180:
        hm = (h1p + h2p) / 2
    else:
        hm = (h1p + h2p + 360) / 2 if h1p + h2p < 360 else (h1p + h2p - 360) / 2
    T = 1 - 0.17 * math.cos(math.radians(hm - 30)) + 0.24 * math.cos(math.radians(2 * hm)) \
        + 0.32 * math.cos(math.radians(3 * hm + 6)) - 0.20 * math.cos(math.radians(4 * hm - 63))
    SL = 1 + 0.015 * (Lm - 50) ** 2 / math.sqrt(20 + (Lm - 50) ** 2)
    SC = 1 + 0.045 * Cmp
    SH = 1 + 0.015 * Cmp * T
    dth = 30 * math.exp(-(((hm - 275) / 25) ** 2))
    RC = 2 * math.sqrt(Cmp ** 7 / (Cmp ** 7 + 25 ** 7))
    RT = -math.sin(math.radians(2 * dth)) * RC
    return math.sqrt((dLp / SL) ** 2 + (dCp / SC) ** 2 + (dHp / SH) ** 2 + RT * (dCp / SC) * (dHp / SH))


def index(records):
    by_code, by_name = defaultdict(list), defaultdict(list)
    for r in records:
        if not re.match(r"^#[0-9A-F]{6}$", r.get("hex", "")):
            continue
        kc = uc.key_code(r)
        if kc:
            by_code[kc].append(r["hex"])
        by_name[uc.key_name(r)].append(r["hex"])
    return by_code, by_name


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--paintdex", required=True)
    ap.add_argument("--miniature-paints", required=True)
    ap.add_argument("--write-flags", action="store_true")
    args = ap.parse_args()

    pdx = uc.load_paintdex(args.paintdex)
    mp_files = dict(uc.MP_ONLY)
    mp_files.update(uc.MP_HEX_ONLY)
    mp_files.update({"Vallejo": "Vallejo", "Army_Painter": "The Army Painter", "Scale75": "Scale75", "AK": "AK Interactive",
                     "AKRC": "AK Interactive", "Citadel_Colour": "Citadel", "Tamiya": "Tamiya", "GreenStuffWorld": "Green Stuff World",
                     "Mig": "Ammo by Mig", "P3": "P3", "Duncan": "Duncan Rhodes"})
    mp = uc.load_miniature_paints(args.miniature_paints, {k: v for k, v in mp_files.items()
                                                          if os.path.exists(os.path.join(args.miniature_paints, "paints", k + ".md"))})
    sources = {"paintdex": index(pdx), "miniature-paints": index(mp)}

    cat = json.load(open(CATALOG, encoding="utf-8"))
    status = Counter()
    by_brand = defaultdict(Counter)
    problems = []      # (ΔE, fila, colores de las fuentes)
    name_bad = []
    against_all = []   # el color del catálogo no se parece a NINGUNA fuente
    unconfirmed = set()
    for row in cat:
        brand, line, name, code, hx = row[0], row[1], row[2], row[3], row[4]
        q = row[6] if len(row) > 6 else ""
        r = {"brand": brand, "name": name, "code": code}
        found = {}
        for sname, (bc, bn) in sources.items():
            kc = uc.key_code(r)
            vals = (bc.get(kc) if kc else None) or bn.get(uc.key_name(r))
            if vals:
                found[sname] = vals[0]
        if hx.upper() == "#888888":
            st = "sin_color"
        else:
            lab = hex_to_lab(hx)
            diffs = {s: de2000(lab, hex_to_lab(v)) for s, v in found.items()}
            # Una fuente que es la misma de la que salió el color no confirma nada: hace falta una segunda
            independent = [d for d in diffs.values()]
            if len(found) == 0 or (len(found) == 1 and min(independent) < 0.5):
                st = "sin_contraste"
            else:
                worst = max(independent)
                st = "coincide" if worst < 10 else "discrepa" if worst < 20 else "contradice"
                if st == "contradice":
                    problems.append((worst, row, found))
                if min(independent) > 20:
                    against_all.append(row)
            if st == "sin_contraste":
                unconfirmed.add((brand, line, name, code))
            if expectations(name) and not plausible(hx, name):
                name_bad.append(row)
        status[st] += 1
        by_brand[brand][st] += 1

    flagged = 0
    if args.write_flags:
        # Dudosas: el color no se parece a ninguna fuente, o contradice el nombre y nadie más lo confirma
        bad = {(r[0], r[1], r[2], r[3]) for r in against_all}
        bad |= {(r[0], r[1], r[2], r[3]) for r in name_bad if (r[0], r[1], r[2], r[3]) in unconfirmed}
        for row in cat:
            if (row[0], row[1], row[2], row[3]) in bad:
                while len(row) < 7:
                    row.append("")
                if row[6] in ("", "f"):
                    row[6] = "d"
                    flagged += 1
        with open(CATALOG, "w", encoding="utf-8") as f:
            json.dump(cat, f, ensure_ascii=False, separators=(",", ":"))

    lines = ["# Validación de colores", "",
             f"Pinturas: {len(cat)}. Comparadas con paintdex y miniature-paints (ΔE2000).", "",
             "| Estado | Pinturas |", "|---|---:|"]
    labels = {"coincide": "Coincide con otra fuente (<10)", "discrepa": "Diferencia moderada (10–20)",
              "contradice": "Se contradice (>20)", "sin_contraste": "Sin segunda fuente", "sin_color": "Sin color"}
    for k in ["coincide", "discrepa", "contradice", "sin_contraste", "sin_color"]:
        lines.append(f"| {labels[k]} | {status[k]} |")
    lines += ["", f"Color que contradice el nombre: {len(name_bad)}",
              f"No se parece a ninguna fuente: {len(against_all)}", f"Marcadas como dudosas en la app: {flagged}", "",
              "## Por marca", "", "| Marca | Coincide | Moderada | Contradice | Sin 2.ª fuente | Sin color |", "|---|---:|---:|---:|---:|---:|"]
    for b in sorted(by_brand):
        c = by_brand[b]
        lines.append(f"| {b} | {c['coincide']} | {c['discrepa']} | {c['contradice']} | {c['sin_contraste']} | {c['sin_color']} |")
    lines += ["", "## Se contradicen (las 200 peores)", ""]
    for d, row, found in sorted(problems, key=lambda p: -p[0])[:200]:
        lines.append(f"- **{row[0]} — {row[2]}** ({row[3] or 's/c'}, {row[1]}): catálogo {row[4]} · " +
                     ", ".join(f"{s} {v}" for s, v in found.items()) + f" · ΔE {d:.0f}")
    lines += ["", "## Color que contradice el nombre", ""]
    for row in name_bad[:300]:
        lines.append(f"- {row[0]} — {row[2]} ({row[3] or 's/c'}): {row[4]}")
    open(REPORT, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("\n".join(lines[:12]))


if __name__ == "__main__":
    main()
