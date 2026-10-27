"""Explora tiendas oficiales y guarda las respuestas crudas en raw/ para analizarlas."""
import json, os, re, time, urllib.request, urllib.error

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
OUT = "raw"
os.makedirs(OUT, exist_ok=True)
log = []

def get(url, accept="application/json,text/html;q=0.9,*/*;q=0.8"):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": accept, "Accept-Language": "en"})
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            return r.status, r.geturl(), r.read()
    except urllib.error.HTTPError as e:
        return e.code, url, e.read()[:2000]
    except Exception as e:
        return 0, url, str(e).encode()

def slug(u):
    return re.sub(r"[^a-zA-Z0-9]+", "_", u)[:120]

def save(url, status, final, body):
    fn = os.path.join(OUT, slug(url) + ".txt")
    with open(fn, "wb") as f:
        f.write(f"URL {url}\nFINAL {final}\nSTATUS {status}\nLEN {len(body)}\n\n".encode() + body[:6_000_000])
    log.append(f"{status}\t{len(body)}\t{url}\t-> {final}")

def shopify(host, maxpages=30, path=""):
    for p in range(1, maxpages + 1):
        url = f"https://{host}{path}/products.json?limit=250&page={p}"
        st, fin, body = get(url)
        save(url, st, fin, body)
        if st != 200:
            break
        try:
            if not json.loads(body).get("products"):
                break
        except Exception:
            break
        time.sleep(1)

SHOPIFY = [
    "monumenthobbies.com", "thearmypainter.com", "us.thearmypainter.com", "www.gaahleri.com",
    "scale75.com", "www.scale75.com", "shop.scale75.com", "ak-interactive.com", "www.ak-interactive.com",
    "dspiae.com", "www.dspiae.com", "dspiae.net", "kaleidocolorworks.com",
]
for h in SHOPIFY:
    shopify(h, maxpages=1)

PAGES = [
    "https://acrylicosvallejo.com/wp-json/wc/store/v1/products?per_page=100&page=1",
    "https://acrylicosvallejo.com/en/wp-json/wc/store/v1/products?per_page=100&page=1",
    "https://acrylicosvallejo.com/wp-json/wp/v2/product?per_page=100&page=1",
    "https://acrylicosvallejo.com/sitemap_index.xml",
    "https://acrylicosvallejo.com/en/",
    "https://www.scale75.com/",
    "https://scale75.com/",
    "https://ak-interactive.com/",
    "https://ak-interactive.com/sitemap.xml",
    "https://www.dspiae.com/",
    "https://hobbyisthaven.com/collections/dspiae/products.json?limit=250",
    "https://hobbyisthaven.com/products/dspiae-50ml-pre-mixed-acrylic-paint.json",
    "https://hobbyisthaven.com/products/dspiae-50ml-pre-mixed-metallic-acrylic-paints.json",
    "https://www.gaahleri.com/pages/introducing-kaleido-colorworks",
    "https://www.gaahleri.com/collections/kaleido-colorworks/products.json?limit=250",
    "https://www.warhammer.com/en-US/shop/Paints-Citadel-Colour",
]
for u in PAGES:
    st, fin, body = get(u)
    save(u, st, fin, body)
    time.sleep(1)

with open(os.path.join(OUT, "_log.txt"), "w") as f:
    f.write("\n".join(log))
print("\n".join(log))
