#!/usr/bin/env python3
"""Arma app/src/main/assets/i18n-en.json a partir de tools/i18n/en.json (español -> inglés)
y avisa qué claves de tools/i18n/keys.json siguen sin traducción."""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
en = json.load(open(os.path.join(ROOT, "tools", "i18n", "en.json"), encoding="utf-8"))
keys = json.load(open(os.path.join(ROOT, "tools", "i18n", "keys.json"), encoding="utf-8"))
out = {k: v for k, v in en.items() if v and v != k}
for k, v in out.items():
    if k.count("{}") != v.count("{}") and "{0}" not in v:
        print("OJO, distinto número de {}:", repr(k))
with open(os.path.join(ROOT, "app", "src", "main", "assets", "i18n-en.json"), "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
missing = [k for k in keys if k not in en]
print(f"{len(out)} traducciones; {len(missing)} claves sin traducir (se muestran en español)")
for k in missing:
    print("  ", json.dumps(k, ensure_ascii=False)[:120])

# --check: falla (para CI) si quedan textos sin traducir
if "--check" in sys.argv and missing:
    sys.exit(f"Hay {len(missing)} textos sin traducción en tools/i18n/en.json")
