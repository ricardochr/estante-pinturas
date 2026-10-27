"""Color aproximado de una pintura a partir de la foto o render del producto.

Cada marca fotografía distinto, así que se mira una zona concreta de la imagen:
  * Kaleido Colorworks: el cuadro de muestra arriba a la derecha.
  * Resto: el cuerpo del bote (mitad inferior), sin la tapa negra de arriba.
Dentro de la zona se agrupan colores y se elige el grupo con color (saturado) más grande.
Si la pintura es neutra (blanco, negro, gris, plata), se usa el nombre para elegir el grupo.
"""
import colorsys
import io

from PIL import Image

REGIONS = {
    "Kaleido Colorworks": (0.60, 0.02, 0.73, 0.15),
    "default": (0.25, 0.45, 0.75, 0.95),
}
NEUTRAL_WORDS = ["white", "black", "grey", "gray", "silver", "steel", "aluminium", "aluminum", "iron", "gunmetal",
                 "chrome", "blanco", "negro", "gris", "plata", "graphite", "carbon", "slate", "ash"]


def _region_pixels(img, box):
    img = img.convert("RGBA")
    img.thumbnail((200, 200))
    w, h = img.size
    x0, y0, x1, y1 = int(box[0] * w), int(box[1] * h), max(int(box[2] * w), int(box[0] * w) + 1), max(int(box[3] * h), int(box[1] * h) + 1)
    out = []
    for y in range(y0, y1):
        for x in range(x0, x1):
            r, g, b, a = img.getpixel((x, y))
            if a >= 200:
                out.append((r, g, b))
    return out


def _clusters(px, step=20):
    groups = {}
    for r, g, b in px:
        k = (r // step, g // step, b // step)
        s = groups.setdefault(k, [0, 0, 0, 0])
        s[0] += r; s[1] += g; s[2] += b; s[3] += 1
    res = []
    for s in groups.values():
        n = s[3]
        r, g, b = s[0] / n, s[1] / n, s[2] / n
        h, l, sat = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
        res.append(dict(rgb=(r, g, b), n=n, l=l, s=sat))
    return res


def _hex(rgb):
    r, g, b = rgb
    return "#%02X%02X%02X" % (int(round(r)), int(round(g)), int(round(b)))


def color_from_image(data: bytes, name: str = "", brand: str = "") -> str | None:
    hx = _color_from_image(data, name, brand)
    if hx and ("white" in name.lower() or "blanco" in name.lower()):
        r, g, b = int(hx[1:3], 16), int(hx[3:5], 16), int(hx[5:7], 16)
        if (r + g + b) / 3 < 220:
            k = 220 - (r + g + b) / 3
            hx = _hex((min(255, r + k), min(255, g + k), min(255, b + k)))
    return hx


def _color_from_image(data: bytes, name: str = "", brand: str = "") -> str | None:
    try:
        img = Image.open(io.BytesIO(data))
    except Exception:
        return None
    box = REGIONS.get(brand, REGIONS["default"])
    px = _region_pixels(img, box)
    if len(px) < 20:
        return None
    if brand == "Kaleido Colorworks":
        # Cuadro de un solo color sobre fondo casi blanco: se toman los píxeles que no son fondo
        bg = max(px, key=lambda p: px.count(p)) if len(px) < 400 else px[0]
        sw = [p for p in px if abs(p[0] - bg[0]) + abs(p[1] - bg[1]) + abs(p[2] - bg[2]) > 30]
        if len(sw) < 15:
            return "#F2F2F2"  # muestra blanca
        sw.sort(key=lambda p: sum(p))
        return _hex(sw[len(sw) // 2])

    lname = name.lower()
    neutral = any(w in lname.split() or w in lname for w in NEUTRAL_WORDS)
    cl = _clusters(px)
    total = sum(c["n"] for c in cl)

    if not neutral:
        colored = [c for c in cl if 0.06 < c["l"] < 0.93 and (c["s"] >= 0.22 or (c["l"] < 0.35 and c["s"] >= 0.12))]
        if colored:
            # Une grupos vecinos del dominante para un promedio más estable
            best = max(colored, key=lambda c: c["n"] * (0.6 + c["s"]))
            if best["n"] >= 0.015 * total:
                br, bg, bb = best["rgb"]
                near = [c for c in colored if abs(c["rgb"][0] - br) + abs(c["rgb"][1] - bg) + abs(c["rgb"][2] - bb) < 60]
                n = sum(c["n"] for c in near)
                return _hex(tuple(sum(c["rgb"][i] * c["n"] for c in near) / n for i in range(3)))

    # Neutros: elegir por el nombre
    greys = [c for c in cl if c["s"] < 0.22]
    if not greys:
        greys = cl
    if "white" in lname or "blanco" in lname:
        light = [c for c in greys if c["l"] > 0.75] or greys
        c = max(light, key=lambda c: c["n"])
        return _hex(c["rgb"])
    if "black" in lname or "negro" in lname:
        dark = [c for c in greys if c["l"] < 0.25] or greys
        c = max(dark, key=lambda c: c["n"])
        return _hex(c["rgb"])
    # Gris o metálico: el gris intermedio más frecuente (ni blanco de etiqueta ni negro de texto)
    mid = [c for c in greys if 0.15 < c["l"] < 0.85] or greys
    c = max(mid, key=lambda c: c["n"])
    return _hex(c["rgb"])
