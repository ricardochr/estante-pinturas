import json, re, time, urllib.request, html, sys
def get(u):
    r = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36", "Accept-Language": "en"})
    try:
        return urllib.request.urlopen(r, timeout=60).read().decode("utf-8", "replace")
    except Exception as e:
        return "ERR " + str(e)
# 1) Pro Acryl sets
items=[]
for p in range(1,20):
    b=get(f"https://monumenthobbies.com/products.json?limit=250&page={p}")
    if b.startswith("ERR"): print(b); break
    d=json.loads(b)["products"]
    if not d: break
    items+=d
for p in items:
    t=p["title"]
    if re.search(r"set|bundle|kit|collection", t, re.I):
        body=re.sub(r"<[^>]+>"," ",p.get("body_html") or "")
        body=re.sub(r"\s+"," ",html.unescape(body))
        print("PA|",t,"|",body[:700])
# 2) GSW
for u in ["https://www.greenstuffworld.com/en/","https://www.greenstuffworld.com/en/76-acrylic-paints","https://www.greenstuffworld.com/sitemap.xml","https://www.greenstuffworld.com/robots.txt","https://www.greenstuffworld.com/en/module/ps_facetedsearch/?q=paint"]:
    b=get(u)
    print("GSW|",u,"|",len(b),"|",b[:300].replace("\n"," "))
    links=sorted(set(re.findall(r'href="(https://www\.greenstuffworld\.com/en/[^"#?]+)"', b)))
    print("GSWLINKS|", len(links), " ".join(l for l in links if re.search(r"paint|acryl|ink|color|colour", l, re.I))[:3000])
