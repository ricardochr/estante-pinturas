import json, re, urllib.request, html
def get(u):
    r = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0", "Accept-Language": "en"})
    try: return urllib.request.urlopen(r, timeout=60).read().decode("utf-8", "replace")
    except Exception as e: return "ERR " + str(e)
def clean(t): return re.sub(r"\s+"," ",html.unescape(re.sub(r"<[^>]+>"," ",t or "")))
items=[]
for p in range(1,40):
    b=get(f"https://www.gaahleri.com/products.json?limit=250&page={p}")
    if b.startswith("ERR"): print(b); break
    d=json.loads(b)["products"]
    if not d: break
    items+=d
for p in items:
    t=p["title"]
    if re.search(r"kaleido", t + " " + " ".join(p.get("tags",[])), re.I) and re.search(r"set|bundle|kit|collection", t, re.I):
        v=p.get("variants") or []
        print("KS|", t, "|", [x.get("title") for x in v][:6], "|", [x.get("sku") for x in v][:6], "|", clean(p.get("body_html"))[:1200])
