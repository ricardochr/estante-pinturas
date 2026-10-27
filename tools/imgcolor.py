"""Color aproximado de una pintura a partir de la foto o render del producto.

Toma los píxeles del producto (sin fondo transparente ni blanco), agrupa colores y elige el
grupo con más peso de saturación. Si casi no hay color, la pintura es neutra (blanco/gris/negro)
y se usa la luminosidad del grupo más grande de la zona central.
"""
import colorsys
import io

from PIL import Image


def _pixels(img):
    img = img.convert("RGBA")
    img.thumbnail((96, 96))
    w, h = img.size
    out = []
    for y in range(h):
        for x in range(w):
            r, g, b, a = img.getpixel((x, y))
            if a < 200:
                continue
            if r > 238 and g > 238 and b > 238:
                continue  # fondo blanco
            # Más peso al centro, donde suele estar la muestra de color
            cx = 1 - abs(x - w / 2) / (w / 2)
            cy = 1 - abs(y - h / 2) / (h / 2)
            out.append((r, g, b, 0.3 + cx * cy))
    return out


def _quantize(px, step=24):
    groups = {}
    for r, g, b, wgt in px:
        k = (r // step, g // step, b // step)
        s = groups.setdefault(k, [0.0, 0.0, 0.0, 0.0])
        s[0] += r * wgt
        s[1] += g * wgt
        s[2] += b * wgt
        s[3] += wgt
    res = []
    for s in groups.values():
        n = s[3]
        res.append(((s[0] / n, s[1] / n, s[2] / n), n))
    return res


def color_from_image(data: bytes, name: str = "") -> str | None:
    try:
        img = Image.open(io.BytesIO(data))
    except Exception:
        return None
    px = _pixels(img)
    if len(px) < 30:
        return None
    groups = _quantize(px)
    total = sum(n for _, n in groups)
    lname = name.lower()
    neutral_name = any(w in lname for w in ["white", "black", "grey", "gray", "silver", "steel", "aluminium", "aluminum",
                                            "iron", "gunmetal", "chrome", "blanco", "negro", "gris", "plata"])
    best, best_score = None, 0.0
    for (r, g, b), n in groups:
        h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
        if l < 0.08 or l > 0.95:
            continue
        score = (n / total) * (s ** 1.5)
        if score > best_score:
            best, best_score = (r, g, b), score
    if best is None or best_score < 0.02 or neutral_name:
        # Neutro: grupo más grande con poca saturación
        neutrals = []
        for (r, g, b), n in groups:
            h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
            if s < 0.25:
                neutrals.append(((r, g, b), n))
        if neutrals and (neutral_name or best is None or best_score < 0.02):
            (r, g, b), _ = max(neutrals, key=lambda t: t[1])
            if "white" in lname or "blanco" in lname:
                r = g = b = max(225, (r + g + b) / 3)
            elif "black" in lname or "negro" in lname:
                r = g = b = min(40, (r + g + b) / 3)
            best = (r, g, b)
    if best is None:
        return None
    r, g, b = best
    return "#%02X%02X%02X" % (int(r), int(g), int(b))
