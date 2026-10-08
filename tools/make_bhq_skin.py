"""Generate BHQ (Cambodia Bodyguard Unit) skins derived from SEM's PMC texture.

BHQ look: ALL-BLACK outfit (pure neutral grayscale, no green/yellow cast),
black technical helmet, Cambodian flag patch on the right shoulder. Three head variants: full balaclava, skull mask, sunglasses.

UV regions decoded from UnitModelDefinitions (texOffs):
  head cube base  : (0,0) 32x16  front face (8,8)-(16,16) = visible face
  head overlay    : (32,0) helmet layer, has face-hole alpha (keep it)
  head bottom     : (16,0) 8x8 neck (stays skin)
  body front      : (20,20) 8x12 chest (vest overlay is transparent webbing)
  right arm tops  : (44,16) base / (44,32) pad -> shoulder flag
  hands (skin)    : (48,16)+(40,48) base, (48,32)+(56,48) overlay bottoms

Skin protection is REGION-based, not color-based: looser color rules let
dark khaki (159,127,51) survive as yellow patches on the uniform.
"""
import os
from PIL import Image

SEM = r"C:\Users\Poseidon\Documents\GitHub\Simple-Enemy-Mod-Public"
SRC = os.path.join(
    SEM, "src/main/resources/assets/simpleenemymod/textures/entity/pmc_unit",
    "pmc_unit_default.png")
# skins ship inside the jar (assets/) - previews go to docs/ for browsing
OUT_DIR = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
        "..", "src/main/resources/assets/sem_classsystem/textures/entity/bhq_unit"))
PREVIEW_DIR = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs"))

MASK_BLACK = (14, 14, 16)
BLUE, RED, WHITE = (10, 34, 84), (186, 30, 40), (235, 235, 235)

# regions where skin tones are KEPT: face, neck, hands (base + overlay arms)
SKIN_REGIONS = [
    (8, 8, 16, 16),     # face
    (16, 0, 24, 8),     # neck (head bottom face)
    (48, 16, 52, 20),   # right hand (base arm bottom)
    (40, 48, 44, 52),   # left hand (base arm bottom)
    (48, 32, 52, 36),   # right hand (overlay arm bottom)
    (56, 48, 60, 52),   # left hand (overlay arm bottom)
]


def in_skin_region(x, y):
    return any(x0 <= x < x1 and y0 <= y < y1 for x0, y0, x1, y1 in SKIN_REGIONS)


def is_skin(r, g, b):
    # true skin is distinctly red-dominant
    return r > 120 and (r - g) >= 25 and (r - b) > 40


def recolor(x, y, r, g, b):
    """Pure-neutral grayscale crush: no hue survives, so no green/yellow.
    Skin kept only inside the protected regions (face/neck/hands)."""
    if in_skin_region(x, y) and is_skin(r, g, b):
        return r, g, b
    l = (r + g + b) / 3
    k = 0.55 if l < 60 else (0.28 if l < 200 else 0.35)
    v = int(l * k)
    return v, v, v


def fill(im, x0, y0, x1, y1, rgb):
    px = im.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            if px[x, y][3] > 0:
                px[x, y] = rgb + (255,)


def put(im, x, y, rgb):
    im.putpixel((x, y), rgb + (255,))


def paint_helmet(im):
    """Head overlay (32,0)-(64,16) -> matte black, keep the face-hole alpha."""
    px = im.load()
    for y in range(0, 16):
        for x in range(32, 64):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            l = (r + g + b) / 3
            k = 0.30 if l > 60 else 0.55
            v = int(l * k)
            px[x, y] = (v, v, v, a)


def paint_balaclava(im, skull):
    """Full black balaclava: whole head cube + neck go black (ears too).
    Eyes/skull print on the front face."""
    for x0, y0 in [(0, 8), (8, 0), (16, 0), (16, 8), (24, 8)]:
        fill(im, x0, y0, x0 + 8, y0 + 8, MASK_BLACK)
    # front face
    fill(im, 8, 8, 16, 16, MASK_BLACK)
    if not skull:
        put(im, 9, 12, WHITE)
        put(im, 14, 12, WHITE)
        return
    # skull print: 2x1 sockets, nose, alternating teeth
    for x in (9, 10, 13, 14):
        put(im, x, 12, WHITE)
    put(im, 11, 13, WHITE)
    for x in (9, 11, 13, 15):
        put(im, x, 14, WHITE)


def paint_sunglasses(im):
    """No mask: face + ears keep skin, black aviator band across the eyes."""
    for x in range(8, 16):
        put(im, x, 11, MASK_BLACK)
        put(im, x, 12, MASK_BLACK)
    # white glint on the right lens
    put(im, 14, 11, (200, 200, 205))
    # bridge hint on the left lens edge
    put(im, 9, 12, (60, 60, 64))


def paint_flag(im, x, y, w=6):
    """Horizontal Cambodia flag patch: blue / red+temple / blue."""
    px = im.load()
    for i in range(w):
        px[x + i, y] = BLUE + (255,)
        px[x + i, y + 1] = RED + (255,)
        px[x + i, y + 2] = RED + (255,)
        px[x + i, y + 3] = BLUE + (255,)
    px[x + 2, y + 1] = WHITE + (255,)
    px[x + 3, y + 2] = WHITE + (255,)


def mini_flag(im, x, y):
    """4x3 shoulder-top flag: blue / red+white / blue."""
    px = im.load()
    for i in range(4):
        px[x + i, y] = BLUE + (255,)
        px[x + i, y + 1] = RED + (255,)
        px[x + i, y + 2] = BLUE + (255,)
    px[x + 1, y + 1] = WHITE + (255,)


def build(name, head_variant):
    im = Image.open(SRC).convert("RGBA")
    out = im.copy()
    src, dst = im.load(), out.load()
    for yy in range(im.height):
        for xx in range(im.width):
            r, g, b, a = src[xx, yy]
            if a == 0:
                continue
            nr, ng, nb = recolor(xx, yy, r, g, b)
            dst[xx, yy] = (min(nr, 255), min(ng, 255), min(nb, 255), a)

    paint_helmet(out)
    if head_variant == "balaclava":
        paint_balaclava(out, skull=False)
    elif head_variant == "skull":
        paint_balaclava(out, skull=True)
    else:  # sunglasses
        paint_sunglasses(out)

    # right-shoulder flags on arm top + pad top so it shows either way
    mini_flag(out, 44, 17)
    mini_flag(out, 44, 33)

    out_png = os.path.join(OUT_DIR, f"bhq_unit_{name}.png")
    out.save(out_png)
    out.resize((512, 512), Image.NEAREST).save(
        os.path.join(PREVIEW_DIR, f"bhq_unit_{name}_preview.png"))
    print("saved", out_png)


def make_block_textures():
    """BHQ recruit table textures: dark metal desk, like SEM's table but
    black tactical instead of green field-issue."""
    out = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
            "..", "src/main/resources/assets/sem_classsystem/textures/block"))
    os.makedirs(out, exist_ok=True)

    # top: dark desk surface + a light "contract paper" + tiny flag tab
    top = Image.new("RGBA", (16, 16), (26, 26, 30, 255))
    tp = top.load()
    for i in range(16):                 # edge bevel
        tp[i, 0] = tp[i, 15] = (14, 14, 17, 255)
        tp[0, i] = tp[15, i] = (14, 14, 17, 255)
    for y in range(4, 12):              # paper
        for x in range(5, 12):
            tp[x, y] = (214, 210, 200, 255)
    for x in range(6, 10):              # "writing" lines on the paper
        tp[x, 6] = (60, 60, 66, 255)
        tp[x, 8] = (60, 60, 66, 255)
    # mini cambodian flag on the desk corner
    for x in range(12, 15):
        tp[x, 3] = (10, 34, 84, 255)
        tp[x, 4] = (186, 30, 40, 255)
        tp[x, 5] = (10, 34, 84, 255)
    top.save(os.path.join(out, "bhq_recruit_table_top.png"))

    # side: dark metal panel with a red stripe (elite accent)
    side = Image.new("RGBA", (16, 16), (22, 22, 26, 255))
    sp = side.load()
    for i in range(16):
        sp[i, 0] = sp[i, 15] = (12, 12, 15, 255)
        sp[0, i] = sp[15, i] = (12, 12, 15, 255)
    for x in range(1, 15):
        sp[x, 3] = sp[x, 4] = (122, 20, 27, 255)   # horizontal red band
    side.save(os.path.join(out, "bhq_recruit_table_side.png"))

    # bottom: plain dark plate
    bottom = Image.new("RGBA", (16, 16), (16, 16, 19, 255))
    bottom.save(os.path.join(out, "bhq_recruit_table_bottom.png"))
    print("saved block textures in", out)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    build("mask", "balaclava")      # full black balaclava
    build("skull", "skull")         # skull mask
    build("shades", "sunglasses")   # sunglasses, no mask
    make_block_textures()
    # keep the old docs alias for browsing
    import shutil
    shutil.copyfile(os.path.join(OUT_DIR, "bhq_unit_mask.png"),
                    os.path.join(PREVIEW_DIR, "bhq_unit.png"))
    shutil.copyfile(os.path.join(PREVIEW_DIR, "bhq_unit_mask_preview.png"),
                    os.path.join(PREVIEW_DIR, "bhq_unit_preview.png"))


if __name__ == "__main__":
    main()
