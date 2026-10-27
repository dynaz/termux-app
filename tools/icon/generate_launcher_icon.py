#!/usr/bin/env python3
"""Regenerate the TermiNow launcher icons from master-logo.png. Run from the repo root."""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "app", "src", "main", "res")
MASTER = os.path.join(HERE, "master-logo.png")
NAVY = (0x0B, 0x14, 0x28, 255)

# Inset policy (see README.md)
FG_HEIGHT_FRAC = 0.519     # shield height within the 108dp adaptive layer (~56dp)
LEGACY_HEIGHT_FRAC = 0.60
STORE_HEIGHT_FRAC = 0.62
FG = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def lift_shield(path):
    """Return the shield as transparent RGBA, navy background removed, tightly cropped."""
    rgb = Image.open(path).convert("RGB")
    w, h = rgb.size
    work = rgb.copy()
    SENT = (255, 0, 255)
    for c in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]:
        ImageDraw.floodfill(work, c, SENT, thresh=72)
    rgba = Image.open(path).convert("RGBA")
    wl, al = work.load(), rgba.load()
    for y in range(h):
        for x in range(w):
            if wl[x, y] == SENT:
                r, g, b, a = al[x, y]
                al[x, y] = (r, g, b, 0)
    # 1px defringe of navy-ish edge pixels
    px = rgba.load()
    nav = (11, 20, 40)
    edge = [(x, y) for y in range(1, h - 1) for x in range(1, w - 1)
            if px[x, y][3] > 0 and max(abs(px[x, y][0] - nav[0]), abs(px[x, y][1] - nav[1]), abs(px[x, y][2] - nav[2])) < 55
            and (px[x - 1, y][3] == 0 or px[x + 1, y][3] == 0 or px[x, y - 1][3] == 0 or px[x, y + 1][3] == 0)]
    for x, y in edge:
        r, g, b, a = px[x, y]
        px[x, y] = (r, g, b, 0)
    return rgba.crop(rgba.getbbox())


def place(shield, size, height_frac, bg=None):
    im = Image.new("RGBA", (size, size), bg if bg else (0, 0, 0, 0))
    sw, sh = shield.size
    th = round(size * height_frac)
    tw = round(sw * th / sh)
    im.alpha_composite(shield.resize((tw, th), Image.LANCZOS), ((size - tw) // 2, (size - th) // 2))
    return im


def circle(im):
    s = im.size[0]
    m = Image.new("L", (s, s), 0)
    ImageDraw.Draw(m).ellipse((0, 0, s - 1, s - 1), fill=255)
    o = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    o.paste(im, (0, 0), m)
    return o


def main():
    shield = lift_shield(MASTER)
    for d, s in FG.items():
        place(shield, s, FG_HEIGHT_FRAC).save(os.path.join(RES, f"mipmap-{d}", "ic_launcher_foreground.png"))
    for d, s in LEGACY.items():
        base = place(shield, s, LEGACY_HEIGHT_FRAC, bg=NAVY)
        base.save(os.path.join(RES, f"mipmap-{d}", "ic_launcher.png"))
        circle(base).save(os.path.join(RES, f"mipmap-{d}", "ic_launcher_round.png"))
    place(shield, 512, STORE_HEIGHT_FRAC, bg=NAVY).convert("RGB").save(os.path.join(HERE, "play-icon-512.png"))
    print("done: mipmaps updated + tools/icon/play-icon-512.png (upload with play.py icon)")


if __name__ == "__main__":
    main()
