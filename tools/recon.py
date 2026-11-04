import json, re, urllib.request, html, gzip
def get(u):
    r = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) Chrome/120", "Accept-Language": "en", "Accept": "application/json,*/*"})
    try:
        b = urllib.request.urlopen(r, timeout=40).read()
        try: b = gzip.decompress(b)
        except Exception: pass
        return b.decode("utf-8", "replace")
    except Exception as e: return "ERR " + str(e)[:80]
hosts = ["ammo.mig.com","www.migjimenez.com","migjimenez.com","missionmodelspaints.com","www.missionmodelspaints.com","duncanrhodes.com","www.duncanrhodespaintingacademy.com","shop.turbodork.com","turbodork.com","www.kimerakolors.com","kimerakolors.com","www.coatdarms.co.uk","wargamesfoundry.com","www.warcolours.com","warcolours.com","steamforged.com","www.reapermini.com","uk.humbrol.com","www.humbrol.com","www.games-workshop.com","www.tamiyausa.com","www.mrhobby.net","greenstuffworld.com","www.dspiae.com","ammobymig.com"]
for h in hosts:
    a = get(f"https://{h}/products.json?limit=5")
    ok_s = a.startswith("{") and '"products"' in a
    w = get(f"https://{h}/wp-json/wc/store/v1/products?per_page=2")
    ok_w = w.strip().startswith("[")
    print("HOST|", h, "| shopify:", ok_s, (a[:80] if not ok_s else ""), "| woo:", ok_w, (w[:80] if not ok_w else ""))
# Vallejo sets
items=[]
for p in range(1,200):
    b=get(f"https://acrylicosvallejo.com/en/wp-json/wc/store/v1/products?per_page=100&page={p}")
    if not b.startswith("["): break
    d=json.loads(b)
    if not d: break
    items+=d
print("VALLEJO", len(items))
def clean(t): return re.sub(r"\s+"," ",html.unescape(re.sub(r"<[^>]+>"," ",t or "")))
for p in items:
    t=html.unescape(p["name"])
    if re.search(r"nocturna|squidmar", t, re.I) and re.search(r"set|box|collection|kit", t, re.I):
        print("VS|", t, "|", p.get("sku"), "|", [c["name"] for c in p.get("categories",[])], "|", clean(p.get("description"))[:900], "|SHORT|", clean(p.get("short_description"))[:400])
