"""Diagnóstico: qué categorías y productos de la tienda AK faltan en el catálogo."""
import json, sys, time, urllib.parse, urllib.request
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0"
B = "https://ak-interactive.com/wp-json/wc/store/v1"
def get(u):
    for _ in range(3):
        try:
            return json.loads(urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": UA}), timeout=60).read())
        except Exception as e:
            print("ERR", u, e); time.sleep(3)
    return []
cats = []
for p in range(1, 20):
    r = get(f"{B}/products/categories?per_page=100&page={p}")
    if not r: break
    cats += r
print("CATEGORIAS", len(cats))
for c in cats:
    print(f"CAT {c['id']}\t{c.get('parent')}\t{c.get('count')}\t{c['name']}")
for q in ["AK14010", "AK14034", "liquid pigment", "ochre earth", "AK140"]:
    r = get(f"{B}/products?per_page=100&search={urllib.parse.quote(q)}")
    print("BUSQUEDA", q, len(r))
    for x in r:
        print("  ", x.get("sku"), "|", x["name"], "|", [ (c["id"], c["name"]) for c in x.get("categories", [])])
total = 0
for p in range(1, 200):
    r = get(f"{B}/products?per_page=100&page={p}")
    if not r: break
    total += len(r)
print("TOTAL PRODUCTOS TIENDA", total)
