import json, re, urllib.request, html
def get(u, h=None):
    hd={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36", "Accept-Language": "en"}
    if h: hd.update(h)
    r = urllib.request.Request(u, headers=hd)
    try:
        return urllib.request.urlopen(r, timeout=60).read().decode("utf-8", "replace")
    except Exception as e:
        return "ERR " + str(e)
for u in ["https://www.greenstuffworld.com/en/122-acrylic-paints?from-xhr", "https://www.greenstuffworld.com/en/122-acrylic-paints?page=2&from-xhr"]:
    b=get(u, {"Accept":"application/json, text/javascript, */*; q=0.01","X-Requested-With":"XMLHttpRequest"})
    print("X|",u,"|",b[:200].replace("\n"," "))
    try:
        d=json.loads(b)
        print("KEYS|",list(d.keys())[:30])
        prods=d.get("products",[])
        print("N|",len(prods), "pagination|", json.dumps(d.get("pagination",{}))[:400])
        for p in prods[:3]:
            print("P|", json.dumps({k:p.get(k) for k in ["id_product","name","reference","ean13","link","category","cover","attributes","price"]})[:1500])
    except Exception as e:
        print("NOJSON",e)
b=get("https://www.greenstuffworld.com/en/122-acrylic-paints")
names=re.findall(r'class="h3 product-title"[^>]*>\s*<a[^>]*>([^<]+)</a>', b)
print("HTML names", len(names), names[:20])
print("HTMLSNIP|", re.sub(r"\s+"," ",b[b.find('product-miniature'):b.find('product-miniature')+3000]))
