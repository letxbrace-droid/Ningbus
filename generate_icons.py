"""Generate PWA icons (192x192 and 512x512) as PNGs from an SVG template.

Run: python generate_icons.py
Requires: cairosvg  (pip install cairosvg)  OR  just uses the SVG fallbacks.
"""
import os
import sys

SVG_TEMPLATE = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="{size}" height="{size}">
  <rect width="100" height="100" rx="20" fill="#0f172a"/>
  <text x="50" y="62" font-size="54" text-anchor="middle" font-family="system-ui,sans-serif" fill="#6366f1">📈</text>
</svg>"""

ICONS_DIR = os.path.join(os.path.dirname(__file__), "docs", "icons")
os.makedirs(ICONS_DIR, exist_ok=True)

SIZES = [192, 512]

def generate_with_cairosvg():
    import cairosvg  # type: ignore
    for size in SIZES:
        svg = SVG_TEMPLATE.format(size=size).encode()
        out = os.path.join(ICONS_DIR, f"icon-{size}.png")
        cairosvg.svg2png(bytestring=svg, write_to=out, output_width=size, output_height=size)
        print(f"Generated {out}")

def generate_svg_fallbacks():
    """Write SVG files as fallbacks (renamed .png — browsers accept SVG via <img>)."""
    for size in SIZES:
        out = os.path.join(ICONS_DIR, f"icon-{size}.png")
        svg = SVG_TEMPLATE.format(size=size)
        # Write SVG content disguised as .png path — works for most PWA install prompts
        with open(out, "w", encoding="utf-8") as f:
            f.write(svg)
        print(f"Written SVG fallback → {out}")

if __name__ == "__main__":
    try:
        generate_with_cairosvg()
    except ImportError:
        print("cairosvg not available — writing SVG fallbacks")
        generate_svg_fallbacks()
    except Exception as e:
        print(f"Error: {e} — writing SVG fallbacks")
        generate_svg_fallbacks()
