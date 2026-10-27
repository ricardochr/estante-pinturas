"""Descarga completa (cruda) de tiendas oficiales a raw/*.json.gz para analizar."""
import gzip, json, os, re, time, urllib.request, urllib.error

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
OUT = "raw"
os.makedirs(OUT, exist_ok=True)
log = []

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json", "Accept-Language": "en"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.status, r.read(), dict(r.headers)
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503) and attempt < 2:
                time.sleep(10); continue
            return e.code, e.read()[:2000], {}
        except Exception as e:
            if attempt < 2:
                time.sleep(5); continue
            return 0, str(e).encode(), {}

def dump(name, items):
    with gzip.open(os.path.join(OUT, name + ".json.gz"), "wt", encoding="utf-8") as f:
        json.dump(items, f, ensure_ascii=False)
    log.append(f"{name}: {len(items)}")

def shopify(name, host):
    items = []
    for p in range(1, 60):
        st, body, _ = get(f"https://{host}/products.json?limit=250&page={p}")
        if st != 200:
            log.append(f"{name} page {p} status {st}"); break
        prods = json.loads(body).get("products", [])
        if not prods: break
        items += prods
        time.sleep(1)
    dump(name, items)

def wc(name, base):
    items = []
    for p in range(1, 80):
        st, body, h = get(f"{base}/wp-json/wc/store/v1/products?per_page=100&page={p}")
        if st != 200:
            log.append(f"{name} page {p} status {st}"); break
        prods = json.loads(body)
        if not prods: break
        items += prods
        time.sleep(1)
    dump(name, items)
    st, body, _ = get(f"{base}/wp-json/wc/store/v1/products/categories?per_page=100")
    if st == 200:
        dump(name + "_categories", json.loads(body))
    else:
        log.append(f"{name} categories status {st}")

shopify("monument", "monumenthobbies.com")
shopify("armypainter", "thearmypainter.com")
shopify("gaahleri", "www.gaahleri.com")
shopify("scale75", "scale75.com")
wc("vallejo", "https://acrylicosvallejo.com/en")
wc("ak", "https://ak-interactive.com")

with open(os.path.join(OUT, "_log.txt"), "w") as f:
    f.write("\n".join(log))
print("\n".join(log))
