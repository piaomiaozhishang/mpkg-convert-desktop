# -*- coding: utf-8 -*-
"""生成 T1.3 / T4.3 负向测试文件（乱数据 / 截断 / 错误魔数等 10 个）。

用法: python scripts/make-negative.py <输出目录> <项目根>
"""
import os
import random
import struct
import sys

out = sys.argv[1]
root = sys.argv[2]
os.makedirs(out, exist_ok=True)


def le32(n):
    return struct.pack("<i", n)


def i32s(b):
    return le32(len(b)) + b


def w(name, data):
    p = os.path.join(out, name)
    with open(p, "wb") as f:
        f.write(data)
    print("%-22s %8d bytes" % (name, len(data)))


random.seed(20261013)

# 1 乱数据
w("junk.mpkg", bytes(random.randrange(256) for _ in range(100000)))

# 2 截断的真实纹理（前 500 KB）
with open(os.path.join(root, "sample", "texture.tex"), "rb") as f:
    tex = f.read()
w("cut.tex", tex[:500000])

# 3 错误魔数（合法长度前缀 + XXXX）
w("badmagic.mpkg", le32(8) + b"XXXX0000" + le32(0))

# 4 极短文件
w("tiny.tex", b"TEXV0005\x00")

# 5 空文件
w("empty.mpkg", b"")

# 6 合法魔数 + 荒谬的条目数
w("badcount.pkg", i32s(b"PKGV0005") + le32(200000000))

# 7 错误的 TEX 魔数
w("badtexmagic.tex", b"TEXV9999\x00TEXI0001\x00" + b"\x00" * 32)

# 8 TEXF 动帧容器（已知边界：应跳过不崩）
texf = b"TEXV0005\x00" + b"TEXI0001\x00"
texf += le32(0) + le32(2) + le32(64) + le32(64) + le32(64) + le32(64) + le32(0)
texf += b"TEXF0001\x00" + le32(1) + b"\x00" * 16
w("texf.tex", texf)

# 9 合法魔数 + 条目表被截断（声称 3 个条目，实际没有）
w("truncpkg.mpkg", i32s(b"PKGM0018") + le32(3))

# 10 Format 12（ASTC/BC7）：可读头、不可解码
f12 = b"TEXV0005\x00" + b"TEXI0001\x00"
f12 += le32(12) + le32(0) + le32(8) + le32(8) + le32(8) + le32(8) + le32(0)
f12 += b"TEXB0003\x00" + le32(1) + le32(-1) + le32(1)
f12 += le32(8) + le32(8) + le32(0) + le32(64) + le32(64) + bytes(64)
w("format12.tex", f12)
