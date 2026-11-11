"""Qué color se espera según el nombre de una pintura, para validar y corregir el catálogo.

Cada palabra de color define un rango en CIELAB (tono en grados, croma y luminosidad) y un color
típico para cuando la foto del producto no sirve. Si el nombre trae varias palabras de color
("Dark Green Brown"), basta con que el color cumpla alguna.
"""
import math
import re

# palabra: (tono_min, tono_max, croma_min, croma_max, L_min, L_max, hex_típico)
# Tono = ángulo de (a*, b*) en grados. None = no importa.
_N = None
EXPECT = {
    # rojos y rosas
    "red": (350, 50, 25, 200, 15, 70, "#B52A2A"), "crimson": (340, 40, 25, 200, 15, 60, "#9E1B2B"),
    "scarlet": (355, 50, 35, 200, 25, 70, "#D02A1E"), "blood": (345, 50, 20, 200, 10, 55, "#7E1A1A"),
    "ruby": (340, 45, 25, 200, 15, 60, "#9B1B30"), "burgundy": (330, 50, 12, 200, 8, 45, "#5E1A2C"),
    "maroon": (330, 50, 10, 200, 8, 45, "#5A1E22"), "pink": (300, 50, 12, 200, 45, 95, "#E58AA8"),
    "rose": (310, 45, 12, 200, 30, 90, "#C76A84"), "magenta": (300, 360, 25, 200, 25, 75, "#B5306E"),
    # naranjas, cafés
    "orange": (35, 80, 30, 200, 35, 85, "#E07020"), "rust": (30, 70, 18, 200, 20, 60, "#8E3E1E"),
    "brown": (25, 95, 6, 60, 10, 55, "#6B4A32"), "umber": (30, 95, 5, 50, 8, 50, "#4E3826"),
    "sienna": (35, 75, 15, 70, 20, 60, "#8A4A2A"), "leather": (35, 80, 12, 60, 20, 60, "#7A4E2E"),
    "chocolate": (30, 80, 6, 45, 10, 40, "#4A3022"), "wood": (40, 95, 10, 60, 20, 65, "#7E5A3A"),
    "copper": (35, 75, 15, 70, 25, 70, "#A0603A"), "bronze": (50, 95, 10, 60, 25, 65, "#8A6A3A"),
    "sepia": (45, 90, 8, 50, 15, 50, "#5E4630"), "grime": (50, 100, 3, 35, 15, 50, "#4E4434"),
    "filth": (50, 100, 3, 40, 15, 50, "#4E4232"), "dirtveil": (50, 100, 3, 40, 20, 60, "#6A5A44"),
    "woodgrain": (40, 95, 10, 60, 20, 60, "#6E4E30"), "hazel": (50, 90, 12, 60, 30, 65, "#8E6A3E"),
    "rotwood": (40, 95, 6, 50, 15, 50, "#5A4430"), "carmine": (350, 40, 30, 200, 15, 55, "#960018"),
    "amber": (55, 90, 30, 150, 40, 85, "#D08A1A"),
    # amarillos, ocres, beiges
    "yellow": (65, 110, 30, 200, 55, 100, "#E8C21A"), "gold": (60, 100, 15, 120, 40, 90, "#C09A3A"),
    "ochre": (60, 95, 25, 120, 35, 80, "#B88A2E"), "ocher": (60, 95, 25, 120, 35, 80, "#B88A2E"),
    "mustard": (70, 100, 30, 120, 45, 80, "#C2A02A"), "sand": (60, 100, 8, 45, 55, 92, "#C8B48C"),
    "beige": (55, 100, 5, 40, 60, 95, "#D2C2A2"), "taupe": (45, 105, 2, 22, 35, 75, "#A89C8E"),
    "khaki": (75, 110, 8, 50, 45, 85, "#B4A878"), "bone": (65, 105, 4, 35, 70, 97, "#DCD2B4"),
    "ivory": (70, 110, 3, 30, 82, 100, "#EEE8D2"), "cream": (70, 110, 4, 35, 82, 100, "#EFE4C4"),
    "buff": (60, 100, 8, 40, 65, 92, "#D8BE96"), "flesh": (35, 85, 10, 60, 45, 90, "#E0A882"),
    "skin": (35, 85, 8, 60, 30, 90, "#D29A7A"), "tan": (55, 90, 12, 50, 45, 80, "#B48E64"),
    # verdes
    "green": (95, 200, 12, 200, 10, 90, "#3A8A3A"), "olive": (85, 125, 8, 70, 20, 65, "#6A6A30"),
    "jade": (140, 210, 12, 120, 30, 80, "#3A9A7A"), "emerald": (140, 200, 20, 150, 25, 70, "#1E8A5A"),
    "moss": (95, 135, 10, 70, 20, 65, "#5A6A30"), "lime": (95, 130, 35, 200, 55, 95, "#9ACD32"),
    # azules y cianes
    "verdigris": (150, 220, 10, 80, 35, 75, "#4E9A88"),
        "teal": (175, 235, 10, 120, 20, 70, "#1E7A80"), "turquoise": (170, 230, 15, 150, 40, 85, "#30B0B0"),
    "cyan": (190, 240, 15, 150, 45, 95, "#30B8D8"), "blue": (210, 320, 12, 200, 10, 85, "#2A5AB0"),
    "navy": (230, 310, 8, 120, 5, 40, "#1E2A55"), "ultramarine": (260, 315, 25, 200, 15, 55, "#2A3A9A"),
    "cobalt": (240, 310, 25, 200, 20, 60, "#2A4AA8"), "sky": (210, 280, 10, 120, 55, 95, "#8AB8E0"),
    # morados
    "purple": (280, 345, 15, 200, 10, 65, "#6A2A80"), "violet": (280, 340, 15, 200, 10, 70, "#6A3A9A"),
    "plum": (300, 350, 10, 120, 10, 50, "#5A2A4A"), "lilac": (280, 340, 8, 80, 55, 92, "#B8A0D0"),
    "lavender": (270, 330, 6, 70, 60, 95, "#C0B0DA"),
    # neutros
    "black": (_N, _N, 0, 22, 0, 32, "#1C1C1C"), "white": (_N, _N, 0, 18, 78, 100, "#F2F2F0"),
    "grey": (_N, _N, 0, 22, 12, 88, "#808080"), "gray": (_N, _N, 0, 22, 12, 88, "#808080"),
    "slate": (_N, _N, 0, 25, 20, 65, "#5A6470"), "ash": (_N, _N, 0, 18, 40, 85, "#A8A8A2"),
    "silver": (_N, _N, 0, 20, 50, 95, "#B8BCC0"), "steel": (_N, _N, 0, 22, 30, 80, "#8A9096"),
"obsidian": (_N, _N, 0, 25, 0, 30, "#1E1C22"),
        "gunmetal": (_N, _N, 0, 20, 15, 55, "#4A4E54"), "charcoal": (_N, _N, 0, 15, 8, 35, "#33343A"),
}

# Palabras que cambian la lectura o no indican color
_SKIP_NAME = re.compile(r"\b(wash|varnish|medium|primer|thinner|glaze|shade|ink|tone|clear|transparent|metal|metallic)\b", re.I)


def hex_to_lab(h):
    h = h.lstrip("#")
    rgb = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    lin = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in rgb]
    x = (lin[0] * 0.4124 + lin[1] * 0.3576 + lin[2] * 0.1805) / 0.95047
    y = lin[0] * 0.2126 + lin[1] * 0.7152 + lin[2] * 0.0722
    z = (lin[0] * 0.0193 + lin[1] * 0.1192 + lin[2] * 0.9505) / 1.08883
    f = lambda t: t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116
    fx, fy, fz = f(x), f(y), f(z)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def _hue_in(h, lo, hi):
    return lo <= h <= hi if lo <= hi else (h >= lo or h <= hi)


# "Flesh"/"skin" en nombres de fantasía no dicen el color: "Waaagh! Flesh" y "Orc Skin" son verdes,
# "Frozen Flesh" gris, "Ebony Flesh" café oscuro
_FANTASY_SKIN = re.compile(r"\b(orc|ork|goblin|troll|waaagh|greenskin|zombie|ghoul|undead|daemon|demon|dragon|lizard|kraken|"
                           r"frozen|ghost|alien|forest|ebony|african|dark|shadow|nurgle|plague|corpse|dead|rotting|vampire|"
                           r"drow|elf|dwarf|ogre|gnoblar|surfer|sylvan|swamp|toad|frog|snake|serpent|reptile|deep)\b")
# Códigos FS/RAL/BS: el número manda, no la palabra ("Duck Egg Blue FS 35622" es casi gris)
_STANDARD_CODE = re.compile(r"\b(fs|ral|bs|bs381c|rlm|ana|munsell)\s?\d", re.I)


def expectations(name):
    """Palabras de color reconocidas en el nombre, en orden."""
    low = name.lower()
    words = re.findall(r"[a-z]+", low)
    found = [w for w in words if w in EXPECT]
    if _FANTASY_SKIN.search(low):
        found = [w for w in found if w not in ("flesh", "skin")]
    if _STANDARD_CODE.search(low) or re.search(r"\b(duck egg|pastel|faded|underside|light|pale)\b", low):
        # Colores militares estandarizados o muy aclarados: el nombre da la familia pero no la saturación
        found = [w for w in found if EXPECT[w][0] is None]
    return found


def satisfies(hexv, word):
    lo, hi, cmin, cmax, lmin, lmax, _ = EXPECT[word]
    L, a, b = hex_to_lab(hexv)
    c = math.hypot(a, b)
    h = math.degrees(math.atan2(b, a)) % 360
    if not (cmin <= c <= cmax and lmin - 5 <= L <= lmax + 5):
        return False
    if lo is None or c < 6:
        return lo is None
    return _hue_in(h, lo, hi)


def plausible(hexv, name, ptype=""):
    """True si el color no contradice el nombre (o el nombre no dice nada de color)."""
    if not hexv or hexv.upper() == "#888888":
        return False
    words = expectations(name)
    if not words:
        return True
    # "Metal", "wash", etc. cambian el aspecto: ser tolerante con luminosidad
    return any(satisfies(hexv, w) for w in words)


def typical(name):
    """Color típico según la última palabra de color del nombre (la que suele mandar en inglés)."""
    words = expectations(name)
    if not words:
        return None
    return EXPECT[words[-1]][6]
