#!/usr/bin/env python3
"""Generate PNG launcher icons for Android 7.0 (API 24-25) fallback.

Adaptive icons (mipmap-anydpi-v26) only work on Android 8.0+. For
Android 7.0/7.1 (API 24/25, our minSdk), we need real PNGs in
mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi.

The icon: black background with neon green hex ring + "1" glyph —
matching the vector drawable version.
"""
from PIL import Image, ImageDraw
from pathlib import Path

RES_DIR = Path("/home/z/my-project/android/app/src/main/res")

# Standard Android launcher icon densities
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

NEON_GREEN = (0, 255, 156, 255)
BLACK = (0, 0, 0, 255)

def draw_icon(size: int) -> Image.Image:
    """Draw the NOID//MOBILE icon at the given size."""
    img = Image.new("RGBA", (size, size), BLACK)
    draw = ImageDraw.Draw(img)
    cx, cy = size / 2, size / 2
    # Hex ring: outer radius 0.45*size, inner radius 0.36*size
    outer_r = int(size * 0.45)
    inner_r = int(size * 0.36)
    # Draw hexagon as polygon (pointy-top)
    import math
    outer_pts = []
    inner_pts = []
    for i in range(6):
        angle = math.pi / 6 + i * math.pi / 3  # start at 30deg
        outer_pts.append((cx + outer_r * math.cos(angle),
                          cy + outer_r * math.sin(angle)))
        inner_pts.append((cx + inner_r * math.cos(angle),
                          cy + inner_r * math.sin(angle)))
    # Fill outer hex with neon green
    draw.polygon(outer_pts, fill=NEON_GREEN)
    # Cut out inner hex with black
    draw.polygon(inner_pts, fill=BLACK)
    # Draw "1" glyph — simple rectangle in the center
    bar_w = max(2, int(size * 0.06))
    bar_h = int(size * 0.32)
    bar_x = int(cx - bar_w / 2)
    bar_y = int(cy - bar_h / 2)
    draw.rectangle([bar_x, bar_y, bar_x + bar_w, bar_y + bar_h], fill=NEON_GREEN)
    # Small top serif
    serif_w = int(size * 0.16)
    serif_h = max(2, int(size * 0.05))
    draw.rectangle([int(cx - serif_w / 2), bar_y,
                    int(cx + serif_w / 2), bar_y + serif_h], fill=NEON_GREEN)
    return img

def main():
    for density, size in DENSITIES.items():
        out_dir = RES_DIR / density
        out_dir.mkdir(parents=True, exist_ok=True)
        img = draw_icon(size)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            img.save(out_dir / name, "PNG")
            print(f"  ✓ {density}/{name} ({size}x{size})")

if __name__ == "__main__":
    main()
