# -*- coding: utf-8 -*-
"""生成回归用的真实内容 .mpkg 夹具（任务卡 T1.3 / T4.3）。

交接包里没有附带真实 .mpkg（原始 5.mpkg 未随包提供），所以这里用**真实素材**重新封装：

  fixtures/wallpaper_static.mpkg  PKGM0019
      textures/main.tex          ← sample/texture.tex 原样（真实 3840x2160 ETC2_RGBA8）
      materials/masks/depth_mask.tex  ← 干扰项，验证 ImageRanker 不会选到遮罩
      materials/presets/fireflies.json、project.json、scene.json
      preview.jpg                ← sample/preview.jpg 原样
  fixtures/wallpaper_video.mpkg   PKGM0018
      video/loop.mp4             ← ffmpeg 生成的真实 H.264 MP4（可播放）
      preview.jpg、project.json
  fixtures/wallpaper_codeonly.mpkg  只有 json/脚本的纯代码壁纸（应报「没有可提取内容」）

用法: python scripts/make-fixtures.py <项目根> <输出目录>
"""
import json
import os
import shutil
import struct
import subprocess
import sys


def le32(n):
    return struct.pack("<i", n)


def i32s(b):
    return le32(len(b)) + b


def build_pkg(magic, entries):
    """按 PKGV/PKGM 共用容器布局打包：magicLength+magic, entryCount, N*(path,off,len), data。"""
    header = i32s(magic.encode("ascii"))
    header += le32(len(entries))
    data = bytearray()
    cursor = 0
    for path, blob in entries:
        header += i32s(path.encode("utf-8"))
        header += le32(cursor)
        header += le32(len(blob))
        data += blob
        cursor += len(blob)
    return bytes(header) + bytes(data)


def raw_rgba_tex(width, height, seed=7):
    """一张小的 RGBA8888 干扰纹理（TEXB0003，format=0）。"""
    px = bytearray()
    for y in range(height):
        for x in range(width):
            px += bytes(((x * 37 + seed) % 256, (y * 53 + seed) % 256, 128, 255))
    out = b"TEXV0005\x00" + b"TEXI0001\x00"
    out += le32(0) + le32(0)
    out += le32(width) + le32(height) + le32(width) + le32(height) + le32(0)
    out += b"TEXB0003\x00" + le32(1) + le32(-1) + le32(1)
    out += le32(width) + le32(height)
    out += le32(0) + le32(len(px)) + le32(len(px))
    return out + bytes(px)


def make_mp4(target, seconds=2, size="320x240"):
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        print("  警告: 未找到 ffmpeg，跳过视频夹具")
        return False
    cmd = [
        ffmpeg, "-y", "-loglevel", "error",
        "-f", "lavfi", "-i", f"testsrc=size={size}:rate=12:duration={seconds}",
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-movflags", "+faststart",
        target,
    ]
    subprocess.run(cmd, check=True)
    return True


def main():
    root = os.path.abspath(sys.argv[1])
    outdir = os.path.abspath(sys.argv[2])
    os.makedirs(outdir, exist_ok=True)

    sample = os.path.join(root, "sample")
    texture = open(os.path.join(sample, "texture.tex"), "rb").read()
    preview = open(os.path.join(sample, "preview.jpg"), "rb").read()

    project = json.dumps({"type": "scene", "title": "回归夹具", "file": "scene.json"},
                         ensure_ascii=False).encode("utf-8")
    scene = json.dumps({"camera": {"eye": [0, 0, 1]}, "objects": []},
                       ensure_ascii=False).encode("utf-8")
    fireflies = json.dumps({"emitter": {"rate": 4}}, ensure_ascii=False).encode("utf-8")

    # --- 静态壁纸：真实 4K 纹理 + 干扰项 ---
    static_entries = [
        ("materials/presets/fireflies.json", fireflies),
        ("materials/masks/depth_mask.tex", raw_rgba_tex(64, 64)),        # 干扰：应被打分淘汰
        ("textures/preview_small.tex", raw_rgba_tex(32, 32, seed=11)),   # 干扰：名字含 preview
        ("textures/main.tex", texture),                                  # 真主图（3840x2160）
        ("project.json", project),
        ("scene.json", scene),
        ("preview.jpg", preview),
    ]
    static_path = os.path.join(outdir, "wallpaper_static.mpkg")
    with open(static_path, "wb") as f:
        f.write(build_pkg("PKGM0019", static_entries))
    print("  wallpaper_static.mpkg   %d 字节  %d 个条目" % (os.path.getsize(static_path), len(static_entries)))

    # --- 视频壁纸：真实可播放 MP4 ---
    video_dir = os.path.join(outdir, "_video")
    os.makedirs(video_dir, exist_ok=True)
    mp4_path = os.path.join(video_dir, "loop.mp4")
    if make_mp4(mp4_path):
        mp4 = open(mp4_path, "rb").read()
        video_entries = [
            ("video/loop.mp4", mp4),
            ("project.json", project),
            ("preview.jpg", preview),
        ]
        video_path = os.path.join(outdir, "wallpaper_video.mpkg")
        with open(video_path, "wb") as f:
            f.write(build_pkg("PKGM0018", video_entries))
        print("  wallpaper_video.mpkg    %d 字节  mp4=%d 字节" % (os.path.getsize(video_path), len(mp4)))
        # 同时留一份裸 mp4，供导出后做 SHA256 字节一致性比对
        shutil.copyfile(mp4_path, os.path.join(outdir, "expected_video.mp4"))

    # --- 纯代码壁纸：没有任何可提取内容 ---
    code_entries = [
        ("project.json", project),
        ("scene.json", scene),
        ("scripts/script.js", b"export function init() {}"),
        ("shaders/effect.frag", b"void main() {}"),
    ]
    code_path = os.path.join(outdir, "wallpaper_codeonly.mpkg")
    with open(code_path, "wb") as f:
        f.write(build_pkg("PKGM0019", code_entries))
    print("  wallpaper_codeonly.mpkg %d 字节" % os.path.getsize(code_path))


if __name__ == "__main__":
    main()
