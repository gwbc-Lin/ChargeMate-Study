"""Generate raster launcher icons as a fallback next to the adaptive icon.

Why: MIUI / HyperOS's icon loader falls back to the system default icon (green
Android robot) for our adaptive icon, while Settings shows it correctly. A
raster PNG per density makes sure every icon surface has a real, branded icon
even if the adaptive XML path is not taken.

Run with the bundled Python (Pillow), from the project root:
    python tools/gen_launcher_icons.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

MINT = (143, 227, 203, 255)
PINK = (241, 191, 213, 255)
PINK_SOFT = (231, 169, 194, 255)
DARK = (36, 28, 54, 255)
BG = (23, 19, 36, 255)
BG2 = (34, 26, 56, 255)
BG3 = (43, 33, 68, 255)

DENSITIES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}


def ellipse(d, cx, cy, rx, ry, fill):
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=fill)


def draw_icon(size, rounded=True):
    img = Image.new('RGBA', (size, size), BG)
    d = ImageDraw.Draw(img)
    s = size / 108.0
    ellipse(d, 54 * s, 54 * s, 38 * s, 38 * s, BG2)
    ellipse(d, 54 * s, 54 * s, 29 * s, 29 * s, BG3)
    for cx in (42, 66):
        ellipse(d, cx * s, 33 * s, 8 * s, 13 * s, PINK)
    ellipse(d, 54 * s, 62 * s, 24 * s, 24 * s, MINT)
    for cx in (46.7, 61.3):
        ellipse(d, cx * s, 58 * s, 3.2 * s, 3.2 * s, DARK)
    for cx in (39.6, 68.4):
        ellipse(d, cx * s, 68 * s, 3.6 * s, 3.6 * s, PINK_SOFT)
    for cx in (49.7, 58.3):
        ellipse(d, cx * s, 67.2 * s, 1.3 * s, 1.3 * s, DARK)
    d.arc([50.2 * s, 68.0 * s, 57.8 * s, 72.0 * s], start=10, end=170,
          fill=DARK, width=max(1, int(1.1 * s)))
    bolt = [(83.4, 18.4), (77.0, 29.2), (80.6, 29.2), (77.0, 38.0),
            (84.6, 26.6), (81.2, 26.6), (83.4, 18.4)]
    d.polygon([(x * s, y * s) for x, y in bolt], fill=PINK)

    mask = Image.new('L', (size, size), 0)
    md = ImageDraw.Draw(mask)
    if rounded:
        md.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.22), fill=255)
    else:
        md.ellipse([0, 0, size - 1, size - 1], fill=255)
    out = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def main():
    root = Path(__file__).resolve().parents[1]
    res = root / 'app/src/main/res'
    for name, px in DENSITIES.items():
        folder = res / f'mipmap-{name}'
        folder.mkdir(parents=True, exist_ok=True)
        draw_icon(px).save(folder / 'ic_launcher.png')
        draw_icon(px, rounded=False).save(folder / 'ic_launcher_round.png')
        print(f'{folder.relative_to(root)}: {px}x{px}')


if __name__ == '__main__':
    main()
