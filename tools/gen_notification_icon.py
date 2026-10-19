"""Generate raster PNG notification small icons.

MIUI / HyperOS does not reliably render a VectorDrawable as the notification
small icon: the notification keeps pointing at our drawable, but the shade and
status bar fall back to the system default (green Android robot). Raster PNGs
per density are used instead.

Run with the bundled Python (Pillow), from the project root:
    python tools/gen_notification_icon.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

WHITE = (255, 255, 255, 255)
# 24dp design, same geometry as the vector that used to live in
# res/drawable/ic_notification_pet.xml: two ears + round head + two eye holes.
DENSITIES = {
    'mdpi': 1,
    'hdpi': 1.5,
    'xhdpi': 2,
    'xxhdpi': 3,
    'xxxhdpi': 4,
}


def ellipse(d, cx, cy, rx, ry, fill):
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=fill)


def mask(size):
    s = size / 24.0
    m = Image.new('L', (size, size), 0)
    d = ImageDraw.Draw(m)
    for cx in (8.0, 16.0):
        ellipse(d, cx * s, 7.4 * s, 2.4 * s, 3.8 * s, 255)
    ellipse(d, 12.0 * s, 14.2 * s, 7.6 * s, 7.4 * s, 255)
    for cx in (11.3, 14.7):
        ellipse(d, cx * s, 13.4 * s, 1.3 * s, 1.3 * s, 0)
    return m


def icon(size):
    img = Image.new('RGBA', (size, size), WHITE[:3] + (0,))
    img.putalpha(mask(size))
    return img


def main():
    root = Path(__file__).resolve().parents[1]
    res = root / 'app/src/main/res'
    for name, scale in DENSITIES.items():
        px = max(16, round(24 * scale))
        target = res / f'drawable-{name}' / 'ic_notification_pet.png'
        target.parent.mkdir(parents=True, exist_ok=True)
        icon(px).save(target)
        print(f'{target.relative_to(root)}  {px}x{px}')


if __name__ == '__main__':
    main()
