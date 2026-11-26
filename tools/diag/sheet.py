import re, sys, urllib.request
UA = {"User-Agent": "Mozilla/5.0"}
B = "https://docs.google.com/spreadsheets/d/e/2PACX-1vSjab8PTwZ9VaBpEVy3LGlDj3xeb65S3h8ADuCs4Cq5BGJ0UUORR0kP-tU4hwLjbu74iQanREhGCTx4"
html = urllib.request.urlopen(urllib.request.Request(B + "/pubhtml", headers=UA), timeout=60).read().decode("utf-8", "replace")
tabs = re.findall(r'\{name: "([^"]*)", pageUrl: "[^"]*gid=(\d+)', html) or re.findall(r'id="sheet-button-(\d+)"[^>]*>.*?>([^<]+)<', html)
print("TABS", tabs)
gids = {g for _, g in tabs} if tabs and not tabs[0][0].isdigit() else {t[0] for t in tabs}
gids.add("979186303")
for g in sorted(gids):
    try:
        csv = urllib.request.urlopen(urllib.request.Request(f"{B}/pub?gid={g}&single=true&output=csv", headers=UA), timeout=60).read().decode("utf-8", "replace")
    except Exception as e:
        print("ERR", g, e); continue
    open(f"sheet-{g}.csv", "w").write(csv)
    print(f"=== GID {g} ({len(csv)} bytes)")
