# Generates docs/banner.png in real Minecraft style:
#   - dirt block background (darkened)
#   - grass block strip along the bottom
#   - title rendered with MC's own ascii.png font glyphs (with MC text shadow)
#   - five MC inventory slots, each holding the real TaCZ gun HUD icon
#
# Run:  python tools/make_banner.py
# Deps: pip install pillow
#
# Textures are read straight from the game jar / TaCZ gunpack - nothing is
# committed except the finished PNG.

from PIL import Image
import io
import zipfile
from pathlib import Path

# ---- asset locations (adjust if paths move) --------------------------------
MC_JAR = Path(r"C:\Users\Poseidon\AppData\Roaming\.minecraft\versions\New zombie\New zombie.jar")
TACZ_HUD = Path(r"C:\Users\Poseidon\AppData\Roaming\.minecraft\versions\New zombie"
                r"\tacz\tacz_default_gun\assets\tacz\textures\gun\hud")
OUT = Path(__file__).resolve().parent.parent / "docs" / "banner.png"

# ---- content ----------------------------------------------------------------
TITLE = "CLASS SYSTEM"
SUBTITLE = "SIMPLE ENEMY MOD ADDON"
SLOTS = [  # (gun icon file, nickname label)
    ("uzi",      "BREACHER"),
    ("ak47",     "RIFLEMAN"),
    ("spas_12",  "SWEEPER"),
    ("ai_awp",   "CAMPER"),
    ("m249",     "SUPPRESSOR"),
]

W, H = 1200, 360
DIRT_SCALE = 4          # 16px dirt -> 64px tiles
GRASS_SCALE = 3         # 16px grass side -> 48px strip
TITLE_SCALE = 8         # 8px glyph -> 64px
SUB_SCALE = 3
LABEL_SCALE = 2
SLOT = 72               # MC 18px slot drawn at 4x
SLOT_GAP = 40

# ---- load textures -----------------------------------------------------------
jar = zipfile.ZipFile(MC_JAR)
dirt = Image.open(io.BytesIO(jar.read("assets/minecraft/textures/block/dirt.png"))).convert("RGBA")
grass = Image.open(io.BytesIO(jar.read("assets/minecraft/textures/block/grass_block_side.png"))).convert("RGBA")
font = Image.open(io.BytesIO(jar.read("assets/minecraft/textures/font/ascii.png"))).convert("RGBA")

# ---- pixel font helpers ------------------------------------------------------
# ascii.png: 16x16 grid of 8x8 glyphs; glyph (col=code%16, row=code//16)
def glyph(ch, scale, color):
    """8x8 glyph cell -> colorized scaled image, empty columns trimmed."""
    cell = font.crop(((ord(ch) % 16) * 8, (ord(ch) // 16) * 8,
                      (ord(ch) % 16) * 8 + 8, (ord(ch) // 16) * 8 + 8))
    alpha = cell.getchannel("A")
    box = alpha.getbbox()
    cell = cell.crop(box) if box else cell.crop((0, 0, 0, 8))
    out = Image.new("RGBA", cell.size, color)
    out.putalpha(cell.getchannel("A"))
    return out.resize((max(1, cell.width * scale), cell.height * scale), Image.NEAREST)

def text_size(text, scale):
    w = 0
    for ch in text:
        w += (5 * scale if ch == " " else glyph(ch, scale, (0, 0, 0, 0)).width + scale)
    return w - scale if w else 0

def draw_text(img, text, x, y, scale, color=(255, 255, 255, 255), shadow=True):
    """MC-style text: dark shadow offset by 1 logical pixel, then main text."""
    for ox, oy, col in ([(scale, scale, (63, 63, 63, 255))] if shadow else []) + [(0, 0, color)]:
        cx = x + ox
        for ch in text:
            if ch == " ":
                cx += 5 * scale
                continue
            g = glyph(ch, scale, col)
            img.paste(g, (int(cx), int(y + oy)), g)
            cx += g.width + scale

def draw_text_centered(img, text, cy, scale, color=(255, 255, 255, 255)):
    draw_text(img, text, (W - text_size(text, scale)) / 2, cy, scale, color)

def mc_slot(size):
    """Classic 18px inventory slot scaled: dark top-left, light bottom-right."""
    s = size // 18
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = im.load()
    for yy in range(size):
        for xx in range(size):
            top_or_left = xx < s or yy < s
            bot_or_right = xx >= size - s or yy >= size - s
            if top_or_left:
                px[xx, yy] = (55, 55, 55, 255)
            elif bot_or_right:
                px[xx, yy] = (255, 255, 255, 140)
            else:
                px[xx, yy] = (139, 139, 139, 255)
    return im

def tile(img, tex, scale, box):
    """Tile a texture, scaled by NEAREST, clipped to box=(x0,y0,x1,y1)."""
    t = tex.resize((tex.width * scale, tex.height * scale), Image.NEAREST)
    x0, y0, x1, y1 = box
    for yy in range(y0, y1, t.height):
        for xx in range(x0, x1, t.width):
            img.paste(t, (xx, yy), t)

# ---- compose -----------------------------------------------------------------
img = Image.new("RGBA", (W, H))

# dirt background, darkened to keep text readable
tile(img, dirt, DIRT_SCALE, (0, 0, W, H))
img = Image.composite(img, Image.new("RGBA", (W, H), (10, 8, 6, 255)),
                      Image.new("L", (W, H), 90))

# subtle darker band behind the title area
band = Image.new("RGBA", (W, 180), (0, 0, 0, 70))
img.paste(band, (0, 0), band)

# grass strip along the bottom (green top edge shows at the top of the strip)
gh = grass.height * GRASS_SCALE
tile(img, grass, GRASS_SCALE, (0, H - gh, W, H))

# title + subtitle
draw_text_centered(img, TITLE, 64, TITLE_SCALE)
draw_text_centered(img, SUBTITLE, 148, SUB_SCALE, (170, 170, 170, 255))

# five inventory slots with gun icons + nickname labels
row_w = len(SLOTS) * SLOT + (len(SLOTS) - 1) * SLOT_GAP
x = (W - row_w) / 2
slot_img = mc_slot(SLOT)
for icon, label in SLOTS:
    y = 196
    img.paste(slot_img, (int(x), y), slot_img)
    gun = Image.open(TACZ_HUD / f"{icon}.png").convert("RGBA")
    # fit inside the slot's inner area with a thin margin, keep aspect
    inner = SLOT - 10
    gscale = min(inner / gun.width, inner / gun.height)
    gun = gun.resize((max(1, int(gun.width * gscale)), max(1, int(gun.height * gscale))),
                     Image.NEAREST)
    img.paste(gun, (int(x + (SLOT - gun.width) / 2), int(y + (SLOT - gun.height) / 2)), gun)
    # nickname under the slot, MC white text
    draw_text(img, label, x + (SLOT - text_size(label, LABEL_SCALE)) / 2,
              y + SLOT + 10, LABEL_SCALE)
    x += SLOT + SLOT_GAP

img.convert("RGB").save(OUT)
print("wrote", OUT, img.size)
