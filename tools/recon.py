import json, re, urllib.request, html, gzip
def get(u, t=60):
    r = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) Chrome/120", "Accept-Language": "en", "Accept": "application/json,*/*"})
    try: return urllib.request.urlopen(r, timeout=t).read().decode("utf-8", "replace")
    except Exception as e: return "ERR " + str(e)[:80]
def clean(t): return re.sub(r"\s+"," ",html.unescape(re.sub(r"<[^>]+>"," ",t or "")))
items=[]
for p in range(1,200):
    b=get(f"https://acrylicosvallejo.com/en/wp-json/wc/store/v1/products?per_page=100&page={p}")
    if not b.startswith("["): break
    d=json.loads(b)
    if not d: break
    items+=d
print("N", len(items))
for p in items:
    t=html.unescape(p["name"]); cats=" ".join(c["name"] for c in p.get("categories",[]))
    if re.search(r"nocturna|squidmar", t + " " + cats, re.I):
        print("VS|", t, "|", p.get("sku"), "|", cats[:120], "|", clean(p.get("description"))[:400], "|SHORT|", clean(p.get("short_description"))[:300])
for h in ["turbodork.com","wargamesfoundry.com","steamforged.com","thearmypainter.com"]:
    its=[]
    for pg in range(1,30):
        b=get(f"https://{h}/products.json?limit=250&page={pg}")
        if not b.startswith("{"): break
        d=json.loads(b)["products"]
        if not d: break
        its+=d
    for p in its:
        t=p["title"]
        if re.search(r"paint|colou?r|triad|speedpaint|warpaint|ink", t+" "+" ".join(p.get("tags",[])), re.I) and re.search(r"\bset\b|bundle|collection|kit\b|triad|box", t, re.I):
            print("SS|", h, "|", t, "|", clean(p.get("body_html"))[:700])
