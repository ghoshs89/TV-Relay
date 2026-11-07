"""Builds Relay's launcher icon, monochrome icon, TV banner and legacy PNGs from one geometry.

Usage: python3 -I tools/icon/build_icon.py <SpaceGrotesk-Bold.ttf> app/src/main/res <preview dir>

Needs fontTools, uharfbuzz and rsvg-convert. The font (SIL OFL) comes from the npm package
@fontsource/space-grotesk (files/space-grotesk-latin-700-normal.woff, converted to TTF with
fontTools). Only glyph outlines end up in the app, never the font file.
"""
import subprocess
import sys
from pathlib import Path

import uharfbuzz as hb
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

FONT, RES, PREVIEW = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
PREVIEW.mkdir(parents=True, exist_ok=True)

BG = "#0B1220"
INK = "#E8EEF7"
ACCENT = "#3DDC97"
LIVE = "#FF5A4E"

# ---- Mark geometry, in the 72-unit visible area of an adaptive icon ----
FRAME = "M19,19 H53 A5,5 0 0 1 58,24 V44 A5,5 0 0 1 53,49 H19 A5,5 0 0 1 14,44 V24 A5,5 0 0 1 19,19 Z"
STAND = "M29.75,52 H42.25 A1.75,1.75 0 0 1 42.25,55.5 H29.75 A1.75,1.75 0 0 1 29.75,52 Z"
PLAY = "M31.5,27.5 L44,34 L31.5,40.5 Z"
DOT = "M49.5,19 A5.5,5.5 0 1 0 60.5,19 A5.5,5.5 0 1 0 49.5,19 Z"


def mark_svg(dot=True, mono=False):
    ink = "#FFFFFF" if mono else INK
    acc = "#FFFFFF" if mono else ACCENT
    s = (
        f'<path d="{FRAME}" fill="none" stroke="{ink}" stroke-width="4"/>'
        f'<path d="{STAND}" fill="{ink}"/>'
        f'<path d="{PLAY}" fill="{acc}" stroke="{acc}" stroke-width="3" stroke-linejoin="round"/>'
    )
    if dot:
        s += f'<path d="{DOT}" fill="{LIVE}" stroke="{BG}" stroke-width="2.5"/>'
    return s


def mark_vector(dot=True, mono=False):
    ink = "#FFFFFFFF" if mono else "#FF" + INK[1:]
    acc = "#FFFFFFFF" if mono else "#FF" + ACCENT[1:]
    s = (
        f'        <path android:pathData="{FRAME}" android:strokeColor="{ink}" android:strokeWidth="4" />\n'
        f'        <path android:pathData="{STAND}" android:fillColor="{ink}" />\n'
        f'        <path android:pathData="{PLAY}" android:fillColor="{acc}" android:strokeColor="{acc}"\n'
        f'            android:strokeWidth="3" android:strokeLineJoin="round" />\n'
    )
    if dot:
        s += (
            f'        <path android:pathData="{DOT}" android:fillColor="#FF{LIVE[1:]}"\n'
            f'            android:strokeColor="#FF{BG[1:]}" android:strokeWidth="2.5" />\n'
        )
    return s


HEADER = '<?xml version="1.0" encoding="utf-8"?>\n'


def write(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


# ---- Adaptive icon layers (108 x 108; the mark sits in the centred 72 x 72) ----
for name, dot, mono in [("ic_launcher_foreground", True, False), ("ic_launcher_monochrome", False, True)]:
    comment = (
        "<!-- Relay launcher mark: a TV screen with a play button" +
        (" and an on-air dot. -->\n" if dot else ". Single colour for themed icons. -->\n")
    )
    write(RES / "drawable" / f"{name}.xml", HEADER + comment +
          '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
          '    android:width="108dp" android:height="108dp"\n'
          '    android:viewportWidth="108" android:viewportHeight="108">\n'
          '    <group android:translateX="18" android:translateY="18">\n' +
          mark_vector(dot, mono) +
          '    </group>\n</vector>\n')

write(RES / "values" / "ic_launcher_colors.xml", HEADER +
      f'<resources>\n    <color name="ic_launcher_background">{BG}</color>\n</resources>\n')

ADAPTIVE = (HEADER +
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
            '</adaptive-icon>\n')
write(RES / "mipmap-anydpi-v26" / "ic_launcher.xml", ADAPTIVE)
write(RES / "mipmap-anydpi-v26" / "ic_launcher_round.xml", ADAPTIVE)

# ---- Wordmark outlines (Space Grotesk Bold, OFL), shaped with HarfBuzz ----
font = TTFont(FONT)
upem = font["head"].unitsPerEm
cap = font["OS/2"].sCapHeight
blob = hb.Blob.from_file_path(str(FONT))
hbfont = hb.Font(hb.Face(blob))
buf = hb.Buffer()
buf.add_str("Relay")
buf.guess_segment_properties()
hb.shape(hbfont, buf, {"kern": True, "liga": True})
glyphset = font.getGlyphSet()
order = font.getGlyphOrder()

SIZE = 50.0  # px, as in the approved concept
scale = SIZE / upem
advance = sum(p.x_advance for p in buf.glyph_positions) * scale

# ---- Banner layout, 320 x 180 ----
# Visible extent of the mark in its 72-unit box (frame stroke to the on-air dot).
VX0, VX1, VY0, VY1 = 12.0, 61.75, 12.25, 55.5
mscale = 72.0 / (VY1 - VY0)  # mark stands 72 px tall
GAP = 22.0
mark_w = (VX1 - VX0) * mscale
left = (320 - (mark_w + GAP + advance)) / 2
mx = left - VX0 * mscale
my = 90 - (VY0 + VY1) / 2 * mscale
tx = left + mark_w + GAP
baseline = 90 + cap * scale / 2

pen = SVGPathPen(glyphset, ntos=lambda v: f"{v:.2f}".rstrip("0").rstrip("."))
x = 0
for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
    gname = order[info.codepoint]
    # Font units, y up -> banner px, y down.
    t = (scale, 0, 0, -scale, tx + (x + pos.x_offset) * scale, baseline - pos.y_offset * scale)
    glyphset[gname].draw(TransformPen(pen, t))
    x += pos.x_advance
WORD = pen.getCommands()

write(RES / "drawable" / "banner.xml", HEADER +
      "<!-- Android TV launcher banner: the Relay mark and wordmark (Space Grotesk Bold, as outlines). -->\n"
      '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
      '    android:width="320dp" android:height="180dp"\n'
      '    android:viewportWidth="320" android:viewportHeight="180">\n'
      f'    <path android:pathData="M0,0 H320 V180 H0 Z" android:fillColor="#FF{BG[1:]}" />\n'
      f'    <group android:translateX="{mx:.2f}" android:translateY="{my:.2f}"\n'
      f'        android:scaleX="{mscale:.4f}" android:scaleY="{mscale:.4f}">\n' +
      mark_vector(True, False).replace("        <", "        <") +
      '    </group>\n'
      f'    <path android:pathData="{WORD}" android:fillColor="#FF{INK[1:]}" />\n'
      '</vector>\n')

# ---- SVG twins for previews and legacy PNGs ----
def icon_svg(size, shape):
    clip = {"square": 'rx="16"', "round": 'rx="36"', "full": ""}[shape]
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 72 72">'
            f'<defs><clipPath id="c"><rect width="72" height="72" {clip}/></clipPath></defs>'
            f'<g clip-path="url(#c)"><rect width="72" height="72" fill="{BG}"/>{mark_svg()}</g></svg>')


banner_svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" viewBox="0 0 320 180">'
              f'<rect width="320" height="180" fill="{BG}"/>'
              f'<g transform="translate({mx:.2f} {my:.2f}) scale({mscale:.4f})">{mark_svg()}</g>'
              f'<path d="{WORD}" fill="{INK}"/></svg>')
mono_svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="288" height="288" viewBox="0 0 72 72">'
            f'<rect width="72" height="72" rx="36" fill="#3A4A3F"/>'
            + mark_svg(dot=False, mono=True).replace("#FFFFFF", "#D7F5DF") + '</svg>')

(PREVIEW / "banner.svg").write_text(banner_svg)
(PREVIEW / "mono.svg").write_text(mono_svg)


def render(svg_text, out: Path, size=None):
    out.parent.mkdir(parents=True, exist_ok=True)
    src = PREVIEW / (out.stem + ".src.svg")
    src.write_text(svg_text)
    cmd = ["rsvg-convert", str(src), "-o", str(out)]
    if size:
        cmd[1:1] = ["-w", str(size), "-h", str(size)]
    subprocess.run(cmd, check=True)


# Legacy launcher icons for Android 7.x (no adaptive icons there).
for dpi, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    render(icon_svg(px, "square"), RES / f"mipmap-{dpi}" / "ic_launcher.png", px)
    render(icon_svg(px, "round"), RES / f"mipmap-{dpi}" / "ic_launcher_round.png", px)

render(banner_svg, PREVIEW / "banner.png")
# The README header uses the same banner.
render(banner_svg, RES.resolve().parents[3] / "docs" / "banner.png")
render(mono_svg, PREVIEW / "mono.png")
for px in (24, 32, 48, 192):
    render(icon_svg(px, "square"), PREVIEW / f"icon_{px}.png", px)
render(icon_svg(192, "round"), PREVIEW / "icon_round_192.png", 192)
print(f"banner: mark at ({mx:.1f},{my:.1f}) text width {advance:.1f}, baseline {baseline:.1f}, cap {cap}/{upem}")
