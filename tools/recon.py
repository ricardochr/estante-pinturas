import json, re, time, urllib.request, html
def get(u):
    r = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0"})
    return urllib.request.urlopen(r, timeout=60).read()
# Kaleido
items=[]
for p in range(1,40):
    d=json.loads(get(f"https://www.gaahleri.com/products.json?limit=250&page={p}"))["products"]
    if not d: break
    items+=d
print("GAAHLERI", len(items))
for p in items:
    t=p["title"]
    if re.match(r"^K", t) or "kaleido" in t.lower():
        print("K|", t, "|", ",".join(p.get("tags",[]))[:200])
# AK: search store for washes
def wc(q, extra=""):
    out=[]
    for p in range(1,30):
        d=json.loads(get(f"https://ak-interactive.com/wp-json/wc/store/v1/products?per_page=100&page={p}&search={q}{extra}"))
        if not d: break
        out+=d; time.sleep(0.5)
    return out
for q in ["wash","AK193","metallic","ink","filter"]:
    for p in wc(q):
        print("AK|", q, "|", p.get("sku"), "|", html.unescape(p["name"]), "|", ";".join(html.unescape(c["name"]) for c in p.get("categories",[])))
