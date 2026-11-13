#!/usr/bin/env python3
"""Agrega a tools/color-overrides.json los colores medidos que exporta la app (Configuración > Colores
medidos). Solo trae los de confianza alta; si una clave ya existe, se reemplaza y se avisa.

Uso:  python3 tools/merge_overrides.py color-overrides-medidos.json
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEST = os.path.join(HERE, "color-overrides.json")

new = json.load(open(sys.argv[1], encoding="utf-8"))
cur = json.load(open(DEST, encoding="utf-8")) if os.path.exists(DEST) else {}
added = changed = 0
for k, v in new.items():
    if not re.match(r"^#[0-9A-Fa-f]{6}$", v) or "|" not in k:
        print("Ignorado (formato):", k, v)
        continue
    v = v.upper()
    if k in cur and cur[k] != v:
        print(f"Cambia {k}: {cur[k]} -> {v}")
        changed += 1
    elif k not in cur:
        added += 1
    cur[k] = v
json.dump(dict(sorted(cur.items())), open(DEST, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(f"Agregados {added}, cambiados {changed}. Total {len(cur)}.")
