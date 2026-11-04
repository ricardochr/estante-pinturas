import json, re, urllib.request, html
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
for p in items:
    if re.match(r"^741\d\d$|^74\.1\d\d$", (p.get("sku") or "")) or re.search(r"fantasy.?pro|flesh set", html.unescape(p["name"]), re.I):
        print("V741|", html.unescape(p["name"]), "|", p.get("sku"), "|", clean(p.get("short_description"))[:300], "|D|", clean(p.get("description"))[:300])
hosts = ["latinoamericahobby.com","www.latinoamericahobby.com","hobbyisthaven.com","www.gapgames.com.au","www.frontlinehobbies.com.au","www.miniaturemarket.com","www.elementgames.co.uk","www.waylandgames.co.uk","www.goblingaming.co.uk","www.thecombatcompany.com","www.jadlam.co.uk","www.hobbyrepublic.co.uk","www.mightyape.co.nz","www.tistaminis.com","www.paintwithpearl.com","www.monumenthobbies.com","www.4ground.co.uk","www.modelersite.com","www.kingdomofgames.com.au","www.atomicgames.com.au","goodgames.com.au","www.gameology.com.au","www.firestormgames.co.uk","www.dicedrop.com","minis.tools","www.miniaturepaintersupply.com","www.paintedpixels.com","www.redgrassgames.com","www.thepaintroom.com","www.hobbyshopcentral.com","www.hobbylinc.com","plasticoldiers.com","www.scalemodelshop.co.uk","www.modelshop.co.uk"]
for h in hosts:
    a = get(f"https://{h}/products.json?limit=3", 30)
    s_ok = a.startswith("{") and '"products"' in a
    w = get(f"https://{h}/wp-json/wc/store/v1/products?per_page=2", 30)
    w_ok = w.strip().startswith("[")
    print("HOST|", h, "| shopify:", s_ok, "| woo:", w_ok)
