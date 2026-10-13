# -*- coding: utf-8 -*-
"""
pure_etc2.py -- dependency-free decoder for ETC1 / ETC2 RGB / EAC alpha / ETC2_RGBA8.

Why this file exists
--------------------
Wallpaper Engine packs the wallpaper image as a `.tex` whose pixel payload can be
`ETC2_RGBA8` (the format byte in the .tex header is 5). That value is NOT in the
format tables of RePKG / linux-wallpaperengine / most reverse-engineering notes.
Most environments therefore need a decoder. This file is a zero-dependency
reference so the pipeline can run anywhere (e.g. a bare Python, a CI box, or as
an oracle while porting to Android).

Block layout (ETC2_RGBA8 == GL_COMPRESSED_RGBA8_ETC2_EAC)
--------------------------------------------------------
Each 4x4 texel block is 16 bytes:
    bytes [0:8]  = 64-bit EAC alpha block
    bytes [8:16] = 64-bit ETC2 RGB block      (ETC1-compatible, plus T/H/planar)
Both halves are read as BIG-endian 64-bit integers (i.e. byte[0] is the MSB).

This is a faithful re-implementation of the decoding rules in the Khronos
OpenGL ES / Data Format specs (the same rules implemented by the well-known
`iOrange/etcdec` single-header C library, MIT/Unlicense, and by Android's DEQP
conformance suite `decompressETC2Block` / `decompressEAC8Block`):

  * ETC1 individual mode  : 4-bit R/G/B base colors per half, replicated 4->8 bit
  * ETC1 differential mode: 5-bit base + 3-bit signed delta, expanded 5->8 bit
  * ETC2 T mode           : two RGB444 base colors + 3-bit "distance" LUT
  * ETC2 H mode           : two RGB444 base colors + 3-bit distance (H variant)
  * ETC2 planar mode      : RGB676 corner colors, bilinear ramp
  * EAC alpha (8-bit)     : 8-bit base + 4-bit multiplier + 3-bit modifier table

Output of `decode_etc2_rgba8()` is byte-for-byte identical to what the popular
`texture2ddecoder` C extension (`decode_etc2a8`) produces -- this was verified
pixel-exact over every block of a real 3840x2160 wallpaper (see selftest.py).

NOTE on channel order: ETC2 native order is R,G,B,A. Wallpaper Engine's
`5.mpkg` internally stores those bytes so that a R<->B swap is required to match
the shipped `preview.jpg` (see extract_original.py). That swap is a property of
the *data*, not of the decoder, so it is applied by the caller.
"""

from __future__ import annotations

__all__ = ["decode_etc2_rgba8_block", "decode_etc2_rgba8", "decode_eac_alpha_block", "BLOCK"]

BLOCK = 16  # bytes per 4x4 block


# --------------------------------------------------------------------------- #
# tables
# --------------------------------------------------------------------------- #

# ETC1 / ETC2 RGB modifier table (8 tables x 4 modifiers).
# Index = 2-bit pixel selector. These are the *final* values to add to the base
# color (i.e. already include ETC1's per-table scaling).
_ETC_MOD = (
    (2, 8, -2, -8),
    (5, 17, -5, -17),
    (9, 29, -9, -29),
    (13, 42, -13, -42),
    (18, 60, -18, -60),
    (24, 80, -24, -80),
    (33, 106, -33, -106),
    (47, 183, -47, -183),
)

# "distance" table used by ETC2 T and H modes (3-bit index -> distance).
_ETC_DIST = (3, 6, 11, 16, 23, 32, 41, 64)

# EAC alpha modifier table (16 tables x 8 modifiers), selected by a 4-bit
# table index; the entry is multiplied by the 4-bit multiplier.
_EAC_MOD = (
    (-3, -6, -9, -15, 2, 5, 8, 14),
    (-3, -7, -10, -13, 2, 6, 9, 12),
    (-2, -5, -8, -13, 1, 4, 7, 12),
    (-2, -4, -6, -13, 1, 3, 5, 12),
    (-3, -6, -8, -12, 2, 5, 7, 11),
    (-3, -7, -9, -11, 2, 6, 8, 10),
    (-4, -7, -8, -11, 3, 6, 7, 10),
    (-3, -5, -8, -11, 2, 4, 7, 10),
    (-2, -6, -8, -10, 1, 5, 7, 9),
    (-2, -5, -8, -10, 1, 4, 7, 9),
    (-2, -4, -8, -10, 1, 3, 7, 9),
    (-2, -5, -7, -10, 1, 4, 6, 9),
    (-3, -4, -7, -10, 2, 3, 6, 9),
    (-1, -2, -3, -10, 0, 1, 2, 9),
    (-4, -6, -8, -9, 3, 5, 7, 8),
    (-3, -5, -7, -9, 2, 4, 6, 8),
)


def _clamp255(v: int) -> int:
    return 0 if v < 0 else (255 if v > 255 else v)


def _sign_extend(val: int, bits: int) -> int:
    """two's-complement sign extension of a `bits`-wide field."""
    m = 1 << (bits - 1)
    return (val ^ m) - m


# --------------------------------------------------------------------------- #
# ETC2 RGB half
# --------------------------------------------------------------------------- #

def _rgb_block(b: bytes):
    """Decode the 8-byte ETC2 RGB half -> list of 16 (r,g,b), row-major (idx = row*4+col)."""
    blk = int.from_bytes(b, "big")

    flip = (blk >> 32) & 0x1
    diff = (blk >> 33) & 0x1

    if diff == 0:
        # ---- individual mode: two RGB444 base colors ----
        r0 = (blk >> 60) & 0xF
        r1 = (blk >> 56) & 0xF
        g0 = (blk >> 52) & 0xF
        g1 = (blk >> 48) & 0xF
        b0 = (blk >> 44) & 0xF
        b1 = (blk >> 40) & 0xF
        # 4 -> 8 bit expansion by bit replication
        col0 = ((r0 << 4) | r0, (g0 << 4) | g0, (b0 << 4) | b0)
        col1 = ((r1 << 4) | r1, (g1 << 4) | g1, (b1 << 4) | b1)
        return _paint_legacy(blk, flip, col0, col1)

    # ---- differential mode candidate: 5-bit base + 3-bit signed delta ----
    r0 = (blk >> 59) & 0x1F
    g0 = (blk >> 51) & 0x1F
    b0 = (blk >> 43) & 0x1F
    r1 = r0 + _sign_extend((blk >> 56) & 0x7, 3)
    g1 = g0 + _sign_extend((blk >> 48) & 0x7, 3)
    b1 = b0 + _sign_extend((blk >> 40) & 0x7, 3)

    if not (0 <= r1 <= 31):
        return _rgb_mode_t(blk)
    if not (0 <= g1 <= 31):
        return _rgb_mode_h(blk)
    if not (0 <= b1 <= 31):
        return _rgb_mode_planar(blk)

    # ---- differential mode: 5 -> 8 bit expansion ----
    def e5(v):  # replicate top 3 bits into low 3 bits
        return (v << 3) | (v >> 2)

    col0 = (e5(r0), e5(g0), e5(b0))
    col1 = (e5(r1), e5(g1), e5(b1))
    return _paint_legacy(blk, flip, col0, col1)


def _paint_legacy(blk, flip, col0, col1):
    """Fill a 4x4 block from the two base colors + per-half modifier table."""
    cw0 = (blk >> 37) & 0x7
    cw1 = (blk >> 34) & 0x7
    mod0 = _ETC_MOD[cw0]
    mod1 = _ETC_MOD[cw1]

    out = [None] * 16
    for row in range(4):
        for col in range(4):
            # subblock: flip==0 -> left/right (2 wide x 4 tall); flip==1 -> top/bottom
            sub = (col >> 1) if flip == 0 else (row >> 1)
            # 2-bit selector: LSB at bit m, MSB at bit m+16, with m = row + col*4
            m = row + (col << 2)
            idx = (((blk >> (m + 16)) & 1) << 1) | ((blk >> m) & 1)
            base, mod = (col0, mod0) if sub == 0 else (col1, mod1)
            d = mod[idx]
            out[row * 4 + col] = (_clamp255(base[0] + d),
                                  _clamp255(base[1] + d),
                                  _clamp255(base[2] + d))
    return out


def _rgb_mode_t(blk):
    ra = (blk >> 59) & 0x3
    rb = (blk >> 56) & 0x3
    g0 = (blk >> 52) & 0xF
    b0 = (blk >> 48) & 0xF
    r1 = (blk >> 44) & 0xF
    g1 = (blk >> 40) & 0xF
    b1 = (blk >> 36) & 0xF
    da = (blk >> 34) & 0x3
    db = (blk >> 32) & 0x1

    r0 = (ra << 2) | rb
    base0 = ((r0 << 4) | r0, (g0 << 4) | g0, (b0 << 4) | b0)
    base1 = ((r1 << 4) | r1, (g1 << 4) | g1, (b1 << 4) | b1)

    dist = _ETC_DIST[(da << 1) | db]
    paint = (
        base0,
        tuple(_clamp255(c + dist) for c in base1),
        base1,
        tuple(_clamp255(c - dist) for c in base1),
    )
    return _paint_indexed(blk, paint)


def _rgb_mode_h(blk):
    r0 = (blk >> 59) & 0xF
    ga = (blk >> 56) & 0x7
    gb = (blk >> 52) & 0x1
    ba = (blk >> 51) & 0x1
    bb = (blk >> 47) & 0x7
    r1 = (blk >> 43) & 0xF
    g1 = (blk >> 39) & 0xF
    b1 = (blk >> 35) & 0xF
    da = (blk >> 34) & 0x1
    db = (blk >> 32) & 0x1

    g0 = (ga << 1) | gb
    b0 = (ba << 3) | bb
    base0 = ((r0 << 4) | r0, (g0 << 4) | g0, (b0 << 4) | b0)
    base1 = ((r1 << 4) | r1, (g1 << 4) | g1, (b1 << 4) | b1)

    cmp_ = 1 if (base0[0] << 16 | base0[1] << 8 | base0[2]) >= \
                (base1[0] << 16 | base1[1] << 8 | base1[2]) else 0
    dist = _ETC_DIST[(da << 2) | (db << 1) | cmp_]
    paint = (
        tuple(_clamp255(c + dist) for c in base0),
        tuple(_clamp255(c - dist) for c in base0),
        tuple(_clamp255(c + dist) for c in base1),
        tuple(_clamp255(c - dist) for c in base1),
    )
    return _paint_indexed(blk, paint)


def _rgb_mode_planar(blk):
    ro = (blk >> 57) & 0x3F
    go1 = (blk >> 56) & 0x01
    go2 = (blk >> 49) & 0x3F
    bo1 = (blk >> 48) & 0x01
    bo2 = (blk >> 43) & 0x03
    bo3 = (blk >> 39) & 0x07
    rh1 = (blk >> 34) & 0x1F
    rh2 = (blk >> 32) & 0x01
    gh = (blk >> 25) & 0x7F
    bh = (blk >> 19) & 0x3F
    rv = (blk >> 13) & 0x3F
    gv = (blk >> 6) & 0x7F
    bv = (blk >> 0) & 0x3F

    go = (go1 << 6) | go2
    bo = (bo1 << 5) | (bo2 << 3) | bo3
    rh = (rh1 << 1) | rh2

    # 6 -> 8 bit and 7 -> 8 bit expansion by bit replication
    ro = (ro << 2) | (ro >> 4)
    rh = (rh << 2) | (rh >> 4)
    rv = (rv << 2) | (rv >> 4)
    go = (go << 1) | (go >> 6)
    gh = (gh << 1) | (gh >> 6)
    gv = (gv << 1) | (gv >> 6)
    bo = (bo << 2) | (bo >> 4)
    bh = (bh << 2) | (bh >> 4)
    bv = (bv << 2) | (bv >> 4)

    out = [None] * 16
    for row in range(4):
        for col in range(4):
            # R(x,y) = x*(RH-RO)/4 + y*(RV-RO)/4 + RO   (x=col, y=row), fixed point
            r = (col * (rh - ro) + row * (rv - ro) + (ro << 2) + 2) >> 2
            g = (col * (gh - go) + row * (gv - go) + (go << 2) + 2) >> 2
            b = (col * (bh - bo) + row * (bv - bo) + (bo << 2) + 2) >> 2
            out[row * 4 + col] = (_clamp255(r), _clamp255(g), _clamp255(b))
    return out


def _paint_indexed(blk, paint):
    """Fill a 4x4 block from a 4-entry palette indexed by the per-pixel 2-bit selector."""
    out = [None] * 16
    for row in range(4):
        for col in range(4):
            m = row + (col << 2)
            idx = (((blk >> (m + 16)) & 1) << 1) | ((blk >> m) & 1)
            out[row * 4 + col] = paint[idx]
    return out


# --------------------------------------------------------------------------- #
# EAC alpha half
# --------------------------------------------------------------------------- #

def decode_eac_alpha_block(b: bytes):
    """Decode the 8-byte EAC alpha half -> list of 16 ints (0..255), row-major."""
    blk = int.from_bytes(b, "big")
    base = (blk >> 56) & 0xFF
    mult = (blk >> 52) & 0xF
    mods = _EAC_MOD[(blk >> 48) & 0xF]

    out = [0] * 16
    for row in range(4):
        for col in range(4):
            # 3-bit index; pixel (row,col) sits at 3-bit slot k = 15 - (col*4 + row)
            k = 15 - ((col << 2) + row)
            idx = (blk >> (k * 3)) & 0x7
            out[row * 4 + col] = _clamp255(base + mods[idx] * mult)
    return out


# --------------------------------------------------------------------------- #
# public API
# --------------------------------------------------------------------------- #

def decode_etc2_rgba8_block(block16: bytes):
    """Decode one 16-byte ETC2_RGBA8 block -> list of 16 (r,g,b,a), row-major."""
    rgb = _rgb_block(bytes(block16[8:16]))
    alpha = decode_eac_alpha_block(bytes(block16[0:8]))
    return [rgb[i] + (alpha[i],) for i in range(16)]


def decode_etc2_rgba8(data: bytes, width: int, height: int) -> bytearray:
    """Decode a full ETC2_RGBA8 image (RGBA, row-major, bytes) of `width` x `height` pixels.

    `width` and `height` must be multiples of 4. Row/column order matches the
    source texture (row 0 = top).
    """
    if width % 4 or height % 4:
        raise ValueError("ETC2 dimensions must be multiples of 4")
    bw, bh = width >> 2, height >> 2
    need = bw * bh * BLOCK
    if len(data) < need:
        raise ValueError("pixel data too short: have %d, need %d" % (len(data), need))

    out = bytearray(width * height * 4)
    stride = width * 4
    p = 0
    for by in range(bh):
        for bx in range(bw):
            blk = data[p:p + BLOCK]
            p += BLOCK
            px = decode_etc2_rgba8_block(blk)
            # px is row-major, so all 64 bytes are already in image order within
            # the block; copy 16 bytes per row into the destination scanlines.
            rowbytes = bytes(v for pix in px for v in pix)
            base = (by * 4 * width + bx * 4) * 4
            for r in range(4):
                o = base + r * stride
                out[o:o + 16] = rowbytes[r * 16:(r + 1) * 16]
    return out
