"""Generate Android adaptive-icon foreground PNGs from the AI-generated glyph.

Steps:
1. Open the source 1024x1024 glyph.
2. If it has an opaque white background (model ignored 'transparent'), knock the
   white out so the launcher background shows through the fingerprint ridges.
3. Crop to the glyph bounding box.
4. For every density, center the glyph on a transparent canvas sized by the
   108dp adaptive-icon grid, glyph taking ~62% of the canvas (safe zone).
5. Also render a full-bleed preview (gradient background + glyph) so the final
   look can be reviewed without installing.
"""
import os
from PIL import Image, ImageDraw

SRC = r"D:\Android\AndoridPorject\otherPorject\PcbuApk\app\design\icon\glyph_raw.png"
RES = r"D:\Android\AndoridPorject\otherPorject\PcbuApk\app\src\main\res"
OUT = r"D:\Android\AndoridPorject\otherPorject\PcbuApk\app\build\icon"

DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}
CONTENT_RATIO = 0.62  # glyph area relative to the adaptive-icon canvas

BG_TOP = (27, 30, 33)      # #1B1E21
BG_BOTTOM = (14, 16, 18)   # #0E1012


def knock_out_white(img):
    """Make near-white pixels fully transparent (keeps anti-aliased edges)."""
    alpha = img.getchannel("A")
    if alpha.getextrema()[0] < 250:
        # already has real transparency, trust it
        return img
    px = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if r > 242 and g > 242 and b > 242:
                px[x, y] = (r, g, b, 0)
    return img


def vertical_gradient(size, top, bottom):
    img = Image.new("RGB", size)
    dr = ImageDraw.Draw(img)
    w, h = size
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        dr.line([(0, y), (w, y)], fill=c)
    return img


def main():
    img = Image.open(SRC).convert("RGBA")
    img = knock_out_white(img)

    bbox = img.getbbox()
    if bbox is None:
        raise SystemExit("glyph is empty")
    glyph = img.crop(bbox)
    print("glyph bbox:", bbox, "size:", glyph.size)

    # --- adaptive icon foregrounds
    for dpi, px in DENSITIES.items():
        canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        target = int(px * CONTENT_RATIO)
        g = glyph.copy()
        g.thumbnail((target, target), Image.LANCZOS)
        off = ((px - g.width) // 2, (px - g.height) // 2)
        canvas.paste(g, off, g)
        outdir = os.path.join(RES, "mipmap-" + dpi)
        os.makedirs(outdir, exist_ok=True)
        out = os.path.join(outdir, "ic_launcher_foreground.png")
        canvas.save(out, optimize=True)
        print("wrote", out, canvas.size)

    # --- preview: gradient background + glyph, circle-masked like a launcher
    S = 1024
    bg = vertical_gradient((S, S), BG_TOP, BG_BOTTOM).convert("RGBA")
    target = int(S * CONTENT_RATIO)
    g = glyph.copy()
    g.thumbnail((target, target), Image.LANCZOS)
    bg.paste(g, ((S - g.width) // 2, (S - g.height) // 2), g)

    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, S, S), fill=255)
    round_img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    round_img.paste(bg, (0, 0), mask)
    round_img.save(os.path.join(OUT, "preview_round.png"))
    bg.save(os.path.join(OUT, "preview_square.png"))
    print("wrote previews")


if __name__ == "__main__":
    main()
