# Generates medic spawn-egg textures + squad whistle icon for sem_classsystem.
# Medic eggs reuse the exact vanilla egg silhouette: we take an existing
# same-faction class egg, split pixels into base (dominant colour) vs spots,
# then repaint the spots in medical red keeping their shading.
from PIL import Image
import os
from collections import Counter

TEX = "src/main/resources/assets/sem_classsystem/textures/item"
MODEL = "src/main/resources/assets/sem_classsystem/models/item"

MEDIC = (214, 58, 58)  # 0xD63A3A medical red cross

def lum(c):
    return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]

def recolor_spots(src_path, dst_path):
    im = Image.open(src_path).convert("RGBA")
    px = im.load()
    opaque = [px[x, y] for y in range(16) for x in range(16) if px[x, y][3] > 0]
    base = Counter((c[0], c[1], c[2]) for c in opaque).most_common(1)[0][0]
    spots = [c for c in opaque if (c[0]-base[0])**2 + (c[1]-base[1])**2 + (c[2]-base[2])**2 > 900]
    mean_spot_lum = sum(lum(c) for c in spots) / max(1, len(spots))
    for y in range(16):
        for x in range(16):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            if (r-base[0])**2 + (g-base[1])**2 + (b-base[2])**2 > 900:
                s = lum((r, g, b)) / mean_spot_lum if mean_spot_lum else 1.0
                px[x, y] = (min(255, int(MEDIC[0]*s)),
                            min(255, int(MEDIC[1]*s)),
                            min(255, int(MEDIC[2]*s)), a)
    im.save(dst_path)
    print("wrote", dst_path)

for faction in ("ru", "us", "pmc", "bhq"):
    recolor_spots(f"{TEX}/{faction}_rifle_spawn_egg.png",
                  f"{TEX}/{faction}_medic_spawn_egg.png")

# Squad whistle: simple flat 16x16 icon - steel pea whistle, side view.
im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
px = im.load()
steel = (168, 174, 182)
dark = (108, 114, 122)
light = (208, 214, 222)
ring = (70, 74, 80)

def rect(x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[x, y] = (*c, 255)

# whistle body (rounded barrel, wider at left)
rect(3, 7, 11, 10, steel)
rect(2, 8, 2, 9, steel)
rect(3, 6, 9, 6, steel)          # top curve
rect(3, 11, 9, 11, dark)         # bottom shade
rect(3, 6, 9, 6, light)          # top highlight
# mouthpiece tapering to the right
rect(11, 8, 14, 9, steel)
px[15, 8] = (*light, 255); px[15, 9] = (*dark, 255)
# pea hole
px[5, 8] = (30, 32, 36, 255); px[6, 8] = (30, 32, 36, 255)
px[5, 9] = (30, 32, 36, 255); px[6, 9] = (30, 32, 36, 255)
# lanyard ring at the back
px[1, 8] = (*ring, 255); px[1, 9] = (*ring, 255)
px[2, 7] = (*ring, 255); px[2, 10] = (*ring, 255)
im.save(f"{TEX}/squad_whistle.png")
print("wrote", f"{TEX}/squad_whistle.png")

# Item models
for faction in ("ru", "us", "pmc", "bhq"):
    name = f"{faction}_medic_spawn_egg"
    with open(f"{MODEL}/{name}.json", "w") as f:
        f.write('{\n  "parent": "minecraft:item/generated",\n  "textures": {\n'
                f'    "layer0": "sem_classsystem:item/{name}"\n  }}\n}}\n')
with open(f"{MODEL}/squad_whistle.json", "w") as f:
    f.write('{\n  "parent": "minecraft:item/generated",\n  "textures": {\n'
            '    "layer0": "sem_classsystem:item/squad_whistle"\n  }\n}\n')
print("models written")
