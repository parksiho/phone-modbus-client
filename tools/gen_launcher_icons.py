#!/usr/bin/env python3
"""Nocturne 런처 아이콘과 Play 그래픽을 다시 만든다.

색은 app/.../ui/N.java 의 BG(#161826), ACCENT_900(#2B2741),
NEUTRAL_200(#E4E7F5), ACCENT_400(#B5ABFC) 이다.
표식은 현장 계측기 단자(플러그 두 핀)와 오른쪽 연결 호다.

  python3 tools/gen_launcher_icons.py

Pillow 가 필요하다. python3 -m pip install pillow
"""
from __future__ import annotations

import math
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    raise SystemExit("Pillow 가 필요합니다. python3 -m pip install pillow")

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
GRAPHICS = ROOT / "play-store" / "graphics"
FONT_MED = ROOT / "app" / "src" / "main" / "assets" / "fonts" / "inter_500.ttf"
FONT_REG = ROOT / "app" / "src" / "main" / "assets" / "fonts" / "inter_400.ttf"
FONT_KO = Path("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc")

# N.java
BG = (0x16, 0x18, 0x26, 255)
GLOW = (0x2B, 0x27, 0x41, 255)
PLUG = (0xE4, 0xE7, 0xF5, 255)       # NEUTRAL_200
ARC = (0xB5, 0xAB, 0xFC, 255)        # ACCENT_400
TITLE = (0xE9, 0xE9, 0xED, 255)
MUTED = (0x93, 0x97, 0xAB, 255)
WHITE = (255, 255, 255, 255)

# 108dp 적응형 아이콘 좌표. 원형 마스크(중심 반경 33) 안에 들어간다.
BODY = (30.0, 40.0, 22.0, 28.0, 6.0)  # x, y, w, h, r
PRONGS = ((50.0, 46.0, 8.0, 5.0, 1.6), (50.0, 57.0, 8.0, 5.0, 1.6))
ARC_C = (64.0, 54.0)
ARCS = ((10.0, -52.0, 52.0), (17.0, -40.0, 40.0))  # r, a0, a1 (0=동쪽, 시계방향)
STROKE = 4.6
GLOW_R = 40.0

LEGACY = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def rrect_path(x, y, w, h, r) -> str:
    return (
        f"M{x + r:.2f},{y:.2f} H{x + w - r:.2f} "
        f"A{r:.2f},{r:.2f} 0 0 1 {x + w:.2f},{y + r:.2f} "
        f"V{y + h - r:.2f} A{r:.2f},{r:.2f} 0 0 1 {x + w - r:.2f},{y + h:.2f} "
        f"H{x + r:.2f} A{r:.2f},{r:.2f} 0 0 1 {x:.2f},{y + h - r:.2f} "
        f"V{y + r:.2f} A{r:.2f},{r:.2f} 0 0 1 {x + r:.2f},{y:.2f} Z"
    )


def arc_path(cx, cy, r, a0, a1) -> str:
    x0 = cx + r * math.cos(math.radians(a0))
    y0 = cy + r * math.sin(math.radians(a0))
    x1 = cx + r * math.cos(math.radians(a1))
    y1 = cy + r * math.sin(math.radians(a1))
    large = 1 if abs(a1 - a0) % 360 > 180 else 0
    return f"M{x0:.2f},{y0:.2f} A{r:.2f},{r:.2f} 0 {large} 1 {x1:.2f},{y1:.2f}"


def circle_path(cx, cy, r) -> str:
    return (
        f"M{cx - r:.2f},{cy:.2f} "
        f"A{r:.2f},{r:.2f} 0 1 1 {cx + r:.2f},{cy:.2f} "
        f"A{r:.2f},{r:.2f} 0 1 1 {cx - r:.2f},{cy:.2f} Z"
    )


def draw_glyph(draw: ImageDraw.ImageDraw, scale: float, plug, arc) -> None:
    x, y, w, h, r = BODY
    draw.rounded_rectangle(
        (x * scale, y * scale, (x + w) * scale, (y + h) * scale),
        radius=r * scale,
        fill=plug,
    )
    for px, py, pw, ph, pr in PRONGS:
        draw.rounded_rectangle(
            (px * scale, py * scale, (px + pw) * scale, (py + ph) * scale),
            radius=pr * scale,
            fill=plug,
        )
    cx, cy = ARC_C
    for radius, a0, a1 in ARCS:
        box = (
            (cx - radius) * scale,
            (cy - radius) * scale,
            (cx + radius) * scale,
            (cy + radius) * scale,
        )
        draw.arc(box, start=a0, end=a1, fill=arc, width=max(1, round(STROKE * scale)))


def render_icon(size: int, *, glyph_only: bool = False, mono: bool = False) -> Image.Image:
    ss = 4
    canvas = size * ss
    scale = canvas / 108.0
    img = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    if not glyph_only:
        draw.rectangle((0, 0, canvas, canvas), fill=BG)
        cx = cy = canvas / 2
        gr = GLOW_R * scale
        draw.ellipse((cx - gr, cy - gr, cx + gr, cy + gr), fill=GLOW)
    plug = WHITE if mono else PLUG
    arc = WHITE if mono else ARC
    draw_glyph(draw, scale, plug, arc)
    return img.resize((size, size), Image.Resampling.LANCZOS)


def opaque(img: Image.Image) -> Image.Image:
    base = Image.new("RGBA", img.size, BG)
    out = Image.alpha_composite(base, img.convert("RGBA"))
    alpha = out.getchannel("A")
    if alpha.getextrema() != (255, 255):
        raise SystemExit(f"투명 픽셀이 남았습니다: {alpha.getextrema()}")
    return out


def feature_graphic() -> Image.Image:
    w, h = 1024 * 2, 500 * 2
    img = Image.new("RGBA", (w, h), BG)
    draw = ImageDraw.Draw(img)
    # 아이콘 PNG 자체가 같은 배경색이라 사각형 경계가 보이지 않는다.
    icon = render_icon(560)
    img.alpha_composite(icon, (96, (h - 560) // 2))

    med = ImageFont.truetype(str(FONT_MED), 120)
    reg = ImageFont.truetype(str(FONT_REG), 36)
    ko_font = ImageFont.truetype(str(FONT_KO), 52) if FONT_KO.is_file() else reg
    x = 760
    draw.text((x, 280), "USB-C   ·   MODBUS TCP", font=reg, fill=ARC)
    draw.text((x, 340), "Modbus Checker", font=med, fill=TITLE)
    draw.text((x, 490), "모드버스 체커", font=ko_font, fill=MUTED)
    draw.text((x, 580), "Equipment link, off the phone network.", font=reg, fill=MUTED)
    return opaque(img).resize((1024, 500), Image.Resampling.LANCZOS)


def svg_document(mono: bool) -> str:
    plug = "#FFFFFF" if mono else "#E4E7F5"
    arc = "#FFFFFF" if mono else "#B5ABFC"
    body = rrect_path(*BODY)
    prongs = " ".join(rrect_path(*p) for p in PRONGS)
    arcs = " ".join(arc_path(*ARC_C, r, a0, a1) for r, a0, a1 in ARCS)
    bg = ""
    if not mono:
        bg = (
            '<rect width="108" height="108" fill="#161826"/>'
            f'<path d="{circle_path(54, 54, GLOW_R)}" fill="#2B2741"/>'
        )
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">
  {bg}
  <path d="{body}" fill="{plug}"/>
  <path d="{prongs}" fill="{plug}"/>
  <path d="{arcs}" fill="none" stroke="{arc}" stroke-width="{STROKE}" stroke-linecap="round"/>
</svg>
'''


def vector_xml(paths: list[tuple[str, str]], strokes: list[tuple[str, str]] | None = None) -> str:
    body = []
    for color, data in paths:
        body.append(f'    <path android:fillColor="{color}" android:pathData="{data}"/>')
    for color, data in strokes or []:
        body.append(
            "    <path "
            f'android:fillColor="#00000000" android:strokeColor="{color}" '
            f'android:strokeWidth="{STROKE}" android:strokeLineCap="round" '
            f'android:pathData="{data}"/>'
        )
    inner = "\n".join(body)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{inner}
</vector>
'''


def adaptive_xml() -> str:
    return '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def main() -> None:
    plug_paths = " ".join([rrect_path(*BODY), *(rrect_path(*p) for p in PRONGS)])
    arc_paths = " ".join(arc_path(*ARC_C, r, a0, a1) for r, a0, a1 in ARCS)
    bg_paths = [("#161826", "M0,0H108V108H0Z"), ("#2B2741", circle_path(54, 54, GLOW_R))]

    write(RES / "drawable" / "ic_launcher_background.xml", vector_xml(bg_paths))
    write(
        RES / "drawable" / "ic_launcher_foreground.xml",
        vector_xml([("#E4E7F5", plug_paths)], [("#B5ABFC", arc_paths)]),
    )
    write(
        RES / "drawable" / "ic_launcher_monochrome.xml",
        vector_xml([("#FFFFFF", plug_paths)], [("#FFFFFF", arc_paths)]),
    )
    anydpi = RES / "mipmap-anydpi-v26"
    write(anydpi / "ic_launcher.xml", adaptive_xml())
    write(anydpi / "ic_launcher_round.xml", adaptive_xml())

    for folder, px in LEGACY.items():
        icon = opaque(render_icon(px))
        out = RES / folder
        out.mkdir(parents=True, exist_ok=True)
        icon.save(out / "ic_launcher.png", "PNG")
        icon.save(out / "ic_launcher_round.png", "PNG")

    GRAPHICS.mkdir(parents=True, exist_ok=True)
    write(GRAPHICS / "icon.svg", svg_document(False))
    write(GRAPHICS / "icon-monochrome.svg", svg_document(True))
    opaque(render_icon(512)).save(GRAPHICS / "play-icon-512.png", "PNG")
    feature_graphic().save(GRAPHICS / "feature-graphic-1024x500.png", "PNG")
    print("icons written")


if __name__ == "__main__":
    main()
