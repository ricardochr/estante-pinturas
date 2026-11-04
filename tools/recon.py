import json, re, urllib.request, html, gzip
def get(u, t=60):
    r = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) Chrome/120", "Accept-Language": "en", "Accept": "application/json,*/*"})
    try:
        b = urllib.request.urlopen(r, timeout=t).read()
        try: b = gzip.decompress(b)
        except Exception: pass
        return b.decode("utf-8", "replace")
    except Exception as e: return "ERR " + str(e)[:80]
def clean(t): return re.sub(r"\s+"," ",html.unescape(re.sub(r"<[^>]+>"," ",t or "")))
for h in ["www.missionmodels.com","missionmodels.com","coatdarms.co.uk","duncanrhodespaintingacademy.com","ammo.mig.com","www.ammobymig.com","shop.kimerakolors.com","www.kimera-kolors.com","www.thearmypainter.com"]:
    a = get(f"https://{h}/products.json?limit=5", 90)
    w = get(f"https://{h}/wp-json/wc/store/v1/products?per_page=2", 90)
    print("HOST|", h, "| shopify:", a.startswith("{") and '"products"' in a, a[:60] if not a.startswith("{") else "", "| woo:", w.strip().startswith("["), w[:60] if not w.strip().startswith("[") else "")
items=[]
for p in range(1,200):
    b=get(f"https://acrylicosvallejo.com/en/wp-json/wc/store/v1/products?per_page=100&page={p}")
    if not b.startswith("["): break
    d=json.loads(b)
    if not d: break
    items+=d
for p in items:
    t=html.unescape(p["name"])
    if re.search(r"nocturna|squidmar", t + " " + " ".join(c["name"] for c in p.get("categories",[])), re.I) and not re.search(r"^\d", p.get("sku") or "x"):
        print("VS|", t, "|", p.get("sku"), "|", [c["name"] for c in p.get("categories",[])][:4], "|", clean(p.get("description"))[:500], "|SHORT|", clean(p.get("short_description"))[:300])
for h in ["turbodork.com","wargamesfoundry.com","steamforged.com"]:
    its=[]
    for pg in range(1,30):
        b=get(f"https://{h}/products.json?limit=250&page={pg}")
        if not b.startswith("{"): break
        d=json.loads(b)["products"]
        if not d: break
        its+=d
    print("STORE|", h, len(its))
    n=0
    for p in its:
        if re.search(r"\bset\b|bundle|collection|kit\b|triad", p["title"], re.I):
            n+=1
            if n<=6: print("SS|", h, "|", p["title"], "|", clean(p.get("body_html"))[:500])
    print("SSN|", h, n)
