# -*- coding: utf-8 -*-
"""生成应用图标（任务卡 T4.1）。

设计：圆角方块 + 主色渐变底 + 白色「下载箭头 + 托盘」字形，呼应导航栏图标。
输出：多尺寸 PNG（窗口图标与 Linux 打包）、.ico（Windows jpackage）、.icns（macOS）。

用法: python scripts/make-icons.py <输出目录>
"""
import os
import sys

from PIL import Image, ImageDraw

# 与 ui/theme 的「经典蓝」主色保持一致
ACCENT_TOP = (32, 118, 196)
ACCENT_BOTTOM = (17, 82, 143)
WHITE = (255, 255, 255, 255)

SIZES = [16, 24, 32, 48, 64, 128, 256, 512]


def rounded_mask(size, radius_ratio=0.22):
    mask = Image.new("L", (size * 4, size * 4), 0)
    d = ImageDraw.Draw(mask)
    r = int(size * 4 * radius_ratio)
    d.rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=r, fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def gradient(size):
    g = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / max(1, size - 1)
        g.putpixel((0, y), tuple(
            int(ACCENT_TOP[i] + (ACCENT_BOTTOM[i] - ACCENT_TOP[i]) * t) for i in range(3)
        ))
    return g.resize((size, size), Image.NEAREST)


def draw_glyph(img, size):
    """在超采样画布上画字形，再缩回来，得到平滑边缘。"""
    s = size * 4
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    stroke = max(1, int(s * 0.075))
    cx = s / 2

    # 向下箭头
    top = s * 0.24
    bottom = s * 0.56
    d.line([(cx, top), (cx, bottom)], fill=WHITE, width=stroke)
    head = s * 0.085
    d.line([(cx - head, bottom - head), (cx, bottom)], fill=WHITE, width=stroke)
    d.line([(cx + head, bottom - head), (cx, bottom)], fill=WHITE, width=stroke)

    # 托盘
    ty = s * 0.70
    d.line([(s * 0.27, ty - s * 0.05), (s * 0.27, ty), (s * 0.73, ty), (s * 0.73, ty - s * 0.05)],
           fill=WHITE, width=stroke, joint="curve")

    layer = layer.resize((size, size), Image.LANCZOS)
    img.alpha_composite(layer)


def build(size):
    # 发布资源以用户提供的封面为准，重新生成时不能恢复成旧版箭头。
    source = os.path.join(os.path.dirname(__file__), "..", "composeApp", "src", "main", "resources", "icons", "brand-source.png")
    if os.path.isfile(source):
        with Image.open(source) as artwork:
            return artwork.convert("RGBA").resize((size, size), Image.LANCZOS)
    base = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    grad = gradient(size).convert("RGBA")
    base.paste(grad, (0, 0), rounded_mask(size))
    draw_glyph(base, size)
    return base


def main():
    outdir = os.path.abspath(sys.argv[1])
    os.makedirs(outdir, exist_ok=True)
    images = {}
    for size in SIZES:
        img = build(size)
        images[size] = img
        img.save(os.path.join(outdir, f"app_{size}.png"))
    images[256].save(os.path.join(outdir, "app.ico"), format="ICO",
                     sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
    try:
        images[512].save(os.path.join(outdir, "app.icns"), format="ICNS")
    except Exception as exc:  # pragma: no cover - 取决于 Pillow 构建
        print("  icns 生成失败（可忽略，macOS 打包时再补）:", exc)
    print("icons ->", outdir)
    for name in sorted(os.listdir(outdir)):
        print("  %-14s %d bytes" % (name, os.path.getsize(os.path.join(outdir, name))))


if __name__ == "__main__":
    main()
