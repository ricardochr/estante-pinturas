import json, re, urllib.request, html
def get(u):
    r = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0", "Accept-Language": "en"})
    try: return urllib.request.urlopen(r, timeout=60).read().decode("utf-8", "replace")
    except Exception as e: return "ERR " + str(e)
def shop(host):
    items=[]
    for p in range(1,40):
        b=get(f"https://{host}/products.json?limit=250&page={p}")
        if b.startswith("ERR"): break
        d=json.loads(b)["products"]
        if not d: break
        items+=d
    return items
def clean(t): return re.sub(r"\s+"," ",html.unescape(re.sub(r"<[^>]+>"," ",t or "")))
for p in shop("monumenthobbies.com"):
    if re.search(r"Base Set|Expert Acrylics Set # 1|Mega Set|Signature Series Set (3|4|6)|Adepti|Wizard", p["title"]):
        print("PA|", p["title"], "|", clean(p.get("body_html"))[:2500])
        print("PAV|", [v.get("title") for v in p.get("variants",[])][:5], [i.get("src","")[-60:] for i in p.get("images",[])][:3])
ap=shop("thearmypainter.com")
n=0
for p in ap:
    t=p["title"]
    if re.search(r"\bset\b|bundle|kit\b|collection", t, re.I) and not re.search(r"brush|miniatures|terrain", t, re.I):
        n+=1
        if n<=40: print("AP|", t, "|", clean(p.get("body_html"))[:600])
print("APN|", n)
