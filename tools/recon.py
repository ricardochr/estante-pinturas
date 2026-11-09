import json, re, urllib.request, html, collections
def get(u):
    r = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0", "Accept-Language": "en"})
    try: return urllib.request.urlopen(r, timeout=60).read().decode("utf-8", "replace")
    except Exception as e: return "ERR " + str(e)
cats=[]
for p in range(1,20):
    b=get(f"https://ak-interactive.com/wp-json/wc/store/v1/products/categories?per_page=100&page={p}")
    if not b.startswith("["): print(b[:200]); break
    d=json.loads(b)
    if not d: break
    cats+=d
for c in sorted(cats, key=lambda c:-c.get("count",0)):
    if c.get("count",0)>=5: print("CAT|", c["id"], "|", html.unescape(c["name"]), "|", c.get("count"), "|parent", c.get("parent"))
b=get("https://ak-interactive.com/wp-json/wc/store/v1/products?search=AK12111")
for p in json.loads(b) if b.startswith("[") else []:
    print("P|", p.get("sku"), html.unescape(p["name"]), [html.unescape(c["name"]) for c in p["categories"]])
