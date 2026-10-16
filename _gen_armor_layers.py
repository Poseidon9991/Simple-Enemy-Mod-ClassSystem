# Generates vanilla-convention armor layer textures for SEMCS armor sets.
#
# SEM's GeckoArmorLayerImpl asks for
#   sem_classsystem:textures/models/armor/<itemname>_layer_1.png (head/chest/feet)
#   sem_classsystem:textures/models/armor/<itemname>_layer_2.png (legs)
# per piece - those files were never authored, so units wore empty/invisible
# armor. For every existing models/armor/<piece>.png we sample the matching
# item icon's dominant colour and paint a 64x32 layer texture: base fill +
# quilted shade (every 4px seam) + edge darkening, so the piece reads as
# padded tactical armour instead of a flat blob or missing texture.
import os
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                    "src", "main", "resources", "assets", "sem_classsystem",
                    "textures")
ARMOR = os.path.join(ROOT, "models", "armor")
ITEM = os.path.join(ROOT, "item")

LAYER2_PIECES = ("pants", "leggings")   # legs use the layer_2 convention


def dominant_color(path):
    """Average opaque pixel of the item icon -> (r, g, b)."""
    img = Image.open(path).convert("RGBA")
    px = img.load()
    r = g = b = n = 0
    for y in range(img.height):
        for x in range(img.width):
            p = px[x, y]
            if p[3] > 128:
                r += p[0]
                g += p[1]
                b += p[2]
                n += 1
    if n == 0:
        return (90, 90, 90)
    return (r // n, g // n, b // n)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def paint_layer(path, base):
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    px = img.load()
    dark = shade(base, 0.72)
    seam = shade(base, 0.55)
    light = shade(base, 1.12)
    for y in range(32):
        for x in range(64):
            px[x, y] = base + (255,)
            # quilted seams every 4 pixels read as padding at this size
            if x % 4 == 3 or y % 4 == 3:
                px[x, y] = seam + (255,)
            # top edge highlight / bottom edge shadow for cheap depth
            if y % 4 == 0:
                px[x, y] = light + (255,)
            if y >= 28 or x >= 60:
                px[x, y] = dark + (255,)
    img.save(path)


made = 0
skipped = []
for fn in sorted(os.listdir(ARMOR)):
    if not fn.endswith(".png") or "_layer_" in fn or fn == "semcs_empty.png":
        continue
    piece = fn[:-4]
    icon = os.path.join(ITEM, fn)
    base = dominant_color(icon) if os.path.exists(icon) else (90, 90, 90)
    layer = "layer_2" if piece.endswith(LAYER2_PIECES) else "layer_1"
    out = os.path.join(ARMOR, piece + "_" + layer + ".png")
    if os.path.exists(out):
        skipped.append(out)
        continue
    paint_layer(out, base)
    made += 1
    print("made", piece + "_" + layer + ".png", base)

print("done:", made, "created,", len(skipped), "already existed")
