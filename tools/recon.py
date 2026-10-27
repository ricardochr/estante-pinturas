"""Descarga imágenes de muestra de productos para calibrar la estimación de color."""
import json, os, re, time, urllib.request
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36"
OUT = "raw"
os.makedirs(OUT, exist_ok=True)
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=60) as r: return r.read()
    except Exception as e:
        print("!", url, e); return None
def shop(host, pages=8):
    items=[]
    for p in range(1,pages+1):
        b=get(f"https://{host}/products.json?limit=250&page={p}")
        if not b: break
        x=json.loads(b).get("products",[])
        if not x: break
        items+=x
    return items
picks=[]
mon=shop("monumenthobbies.com")
for rx in [r"1-Step 502", r"1-Step 500", r"1-Step 523", r"AMP Colors 017", r"AMP Colors 005", r"Expert Acrylics 003", r"Expert Acrylics 001", r"S49", r"075 - Pro Acryl"]:
    p=next((p for p in mon if re.search(rx,p["title"])),None)
    if p: picks.append(("mon",p["title"],[i["src"] for i in p["images"][:3]]))
g=shop("www.gaahleri.com",4)
for rx in [r"^K212", r"^K202", r"^K003", r"^K115", r"^K301"]:
    p=next((p for p in g if re.search(rx,p["title"])),None)
    if p: picks.append(("kal",p["title"],[i["src"] for i in p["images"][:3]]))
for h in ["dspiae-50ml-pre-mixed-acrylic-paint"]:
    b=get(f"https://hobbyisthaven.com/products/{h}.json")
    if b:
        pr=json.loads(b)["product"]
        picks.append(("dsp",pr["title"],[i["src"] for i in pr["images"][:6]]))
sc=shop("scale75.com",6)
for rx in ["^VIOLET$","^ARROGANCE WHITE$","^ROTTEN PUS$","^SAP GREEN$"]:
    p=next((p for p in sc if re.search(rx,p["title"])),None)
    if p: picks.append(("s75",p["title"],[i["src"] for i in p["images"][:3]]))
ap=shop("thearmypainter.com",5)
for rx in ["Fanatic: Moldy Wine","Speedpaint: Burnished Red","Speedpaint Marker: Slaughter Red"]:
    p=next((p for p in ap if rx in p["title"]),None)
    if p: picks.append(("ap",p["title"],[i["src"] for i in p["images"][:3]]))
idx=[]
for i,(k,t,srcs) in enumerate(picks):
    for j,s in enumerate(srcs):
        u=s+("&" if "?" in s else "?")+"width=300"
        b=get(u)
        if b:
            fn=f"{OUT}/{k}_{i:02d}_{j}.png" if ".png" in s.lower() else f"{OUT}/{k}_{i:02d}_{j}.jpg"
            open(fn,"wb").write(b); idx.append(f"{fn}\t{t}\t{s}")
open(f"{OUT}/_index.txt","w").write("\n".join(idx))
print("\n".join(idx))
