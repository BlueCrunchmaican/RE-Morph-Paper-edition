"""Builds the two extra skin textures the client mod's arm forms (the Arsenal) are drawn with.

- fabric-client/.../textures/skin/morphskin_dissolve.png: the dissolve mask. RGB white; its alpha is three octaves of
  smooth value noise (cells of about 32, 12 and 5 texels, weighted 0.55, 0.30 and 0.15), equalised so that every
  level between 0.02 and 0.98 is equally common. The game's dissolve render type drops each pixel whose vertex alpha
  is below the mask's alpha at the same texture spot, so an arm whose vertex alpha ramps down across a growing or
  melting front frays there into noise, and because the mask is read at the static's own texture coordinates, the
  fringe fizzes with every re-pattern. Equalising makes the fraying even across the ramp. The ends stay inside
  0.02..0.98 so that alpha 1 never erodes and alpha 0 always does.
- fabric-client/.../textures/skin/morphskin_glow.png: the morph skin's own noise (morphskin.png), remapped from its
  darkest to its brightest texel onto 0x7A1010..0xFF5A4A. The glowing parts are drawn with it at full brightness, so
  they glow by being bright (no emissive render type, which shader packs treat differently).

Both are 256x256 RGBA like the other skin textures and tile seamlessly (the skin textures repeat). The noise comes from
a fixed integer hash, so running this again writes the same files byte for byte. Only Python's standard library is
used. Nothing here is copied from anyone else's art.

Usage: py -3 -I tools/gen_arm_textures.py          writes both textures
       py -3 -I tools/gen_arm_textures.py --check  checks the written ones (size, RGBA, mask alpha range)
"""
import math
import os
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIN_DIR = os.path.join(ROOT, "fabric-client", "src", "main", "resources", "assets", "morphreborn", "textures", "skin")
SKIN = os.path.join(SKIN_DIR, "morphskin.png")
MASK_OUT = os.path.join(SKIN_DIR, "morphskin_dissolve.png")
GLOW_OUT = os.path.join(SKIN_DIR, "morphskin_glow.png")

SIZE = 256
# (cell size in texels, weight) per octave. A lattice must divide the image to tile, so each octave uses the whole
# number of cells nearest its size (8, 21 and 51 cells: 32, 12.2 and 5.0 texels).
OCTAVES = ((32, 0.55), (12, 0.30), (5, 0.15))
SEED = 0x41524D31  # "ARM1"
# The mask's alpha bytes: ceil(0.02 * 255) .. floor(0.98 * 255), so the levels stay strictly inside 0.02..0.98.
ALPHA_LO = math.ceil(0.02 * 255)
ALPHA_HI = math.floor(0.98 * 255)
GLOW_DARK = (0x7A, 0x10, 0x10)
GLOW_BRIGHT = (0xFF, 0x5A, 0x4A)


def hash01(x, y, seed):
    """A value in [0, 1) for lattice point (x, y): a 32-bit integer mix, the same on every machine and Python."""
    h = (x * 0x27D4EB2D) ^ (y * 0x165667B1) ^ (seed * 0x9E3779B9)
    h &= 0xFFFFFFFF
    h ^= h >> 15
    h = (h * 0x2C1B3C6D) & 0xFFFFFFFF
    h ^= h >> 12
    h = (h * 0x297A2D39) & 0xFFFFFFFF
    h ^= h >> 15
    return h / 4294967296.0


def catmull_rom(p0, p1, p2, p3, t):
    """The Catmull-Rom spline through p1 (t = 0) and p2 (t = 1)."""
    return p1 + 0.5 * t * (p2 - p0 + t * (2 * p0 - 5 * p1 + 4 * p2 - p3 + t * (3 * (p1 - p2) + p3 - p0)))


def value_noise(cells, seed):
    """One octave: a periodic lattice of `cells` x `cells` random values, smoothly interpolated over the image.

    The interpolation is bicubic (Catmull-Rom), not a smoothstep between neighbours: a smoothstep stops dead at every
    lattice point, and equalising the result turns those flat spots into a visible square grid. Each octave's lattice
    is also shifted by a seeded fraction of a cell, so the octaves' lattice lines never fall on the same texels.
    """
    lattice = [[hash01(i, j, seed) for i in range(cells)] for j in range(cells)]
    ox, oy = hash01(-1, 0, seed), hash01(0, -1, seed)
    out = [0.0] * (SIZE * SIZE)
    for y in range(SIZE):
        fy = (y + 0.5) * cells / SIZE + oy
        j0 = int(math.floor(fy))
        ty = fy - j0
        rows = [lattice[(j0 + d) % cells] for d in (-1, 0, 1, 2)]
        for x in range(SIZE):
            fx = (x + 0.5) * cells / SIZE + ox
            i0 = int(math.floor(fx))
            tx = fx - i0
            cols = [(i0 + d) % cells for d in (-1, 0, 1, 2)]
            across = [catmull_rom(*(row[c] for c in cols), tx) for row in rows]
            out[y * SIZE + x] = catmull_rom(*across, ty)
    return out


def dissolve_mask():
    """The mask's alpha bytes, row by row: the octaves summed, then equalised by rank onto ALPHA_LO..ALPHA_HI."""
    total = [0.0] * (SIZE * SIZE)
    for octave, (cell, weight) in enumerate(OCTAVES):
        cells = max(1, round(SIZE / cell))
        layer = value_noise(cells, SEED + octave * 7919)
        for i, v in enumerate(layer):
            total[i] += v * weight
    # histogram equalisation: each texel's level is its rank, so all levels are (nearly) equally common; ties are
    # broken by position, so the result is fully deterministic
    order = sorted(range(len(total)), key=lambda i: (total[i], i))
    levels = ALPHA_HI - ALPHA_LO + 1
    alpha = [0] * len(total)
    for rank, i in enumerate(order):
        alpha[i] = ALPHA_LO + rank * levels // len(total)
    return alpha


def read_png(path):
    """(width, height, channels, bytes) of an 8-bit, non-interlaced greyscale, RGB or RGBA PNG."""
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(path + " is not a PNG")
    pos, idat, header = 8, [], None
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat.append(body)
        elif kind == b"IEND":
            break
    width, height, depth, colour, _, _, interlace = header
    channels = {0: 1, 2: 3, 6: 4}.get(colour)
    if depth != 8 or channels is None or interlace != 0:
        raise ValueError(path + ": only 8-bit non-interlaced grey, RGB or RGBA PNGs are read")
    raw = zlib.decompress(b"".join(idat))
    stride = width * channels
    pixels = bytearray(height * stride)
    prev = bytearray(stride)
    for y in range(height):
        kind = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for x in range(stride):
            left = line[x - channels] if x >= channels else 0
            up = prev[x]
            corner = prev[x - channels] if x >= channels else 0
            if kind == 1:
                line[x] = (line[x] + left) & 0xFF
            elif kind == 2:
                line[x] = (line[x] + up) & 0xFF
            elif kind == 3:
                line[x] = (line[x] + (left + up) // 2) & 0xFF
            elif kind == 4:
                p = left + up - corner
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - corner)
                pred = left if pa <= pb and pa <= pc else up if pb <= pc else corner
                line[x] = (line[x] + pred) & 0xFF
        pixels[y * stride:(y + 1) * stride] = line
        prev = line
    return width, height, channels, bytes(pixels)


def write_png(path, width, height, rgba):
    """An 8-bit RGBA PNG with no filtering, compressed at the highest level."""
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    stride = width * 4
    raw = b"".join(b"\x00" + rgba[y * stride:(y + 1) * stride] for y in range(height))
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def build_mask():
    alpha = dissolve_mask()
    out = bytearray(SIZE * SIZE * 4)
    for i, a in enumerate(alpha):
        out[4 * i:4 * i + 4] = bytes((255, 255, 255, a))
    write_png(MASK_OUT, SIZE, SIZE, bytes(out))


def build_glow():
    width, height, channels, pixels = read_png(SKIN)
    if (width, height) != (SIZE, SIZE):
        raise ValueError("morphskin.png is %dx%d, expected %dx%d" % (width, height, SIZE, SIZE))
    grey = []
    for i in range(width * height):
        px = pixels[i * channels:i * channels + min(3, channels)]
        grey.append(sum(px) / len(px))
    lo, hi = min(grey), max(grey)
    span = (hi - lo) or 1.0
    out = bytearray(width * height * 4)
    for i, g in enumerate(grey):
        t = (g - lo) / span
        rgb = tuple(int(round(d + (b - d) * t)) for d, b in zip(GLOW_DARK, GLOW_BRIGHT))
        out[4 * i:4 * i + 4] = bytes(rgb + (255,))
    write_png(GLOW_OUT, width, height, bytes(out))


def check():
    ok = True
    for path in (MASK_OUT, GLOW_OUT):
        width, height, channels, pixels = read_png(path)
        alpha = pixels[3::4] if channels == 4 else b""
        good = (width, height, channels) == (SIZE, SIZE, 4)
        line = "%s: %dx%d, %d channels" % (os.path.basename(path), width, height, channels)
        if path == MASK_OUT and alpha:
            a_lo, a_hi = min(alpha) / 255.0, max(alpha) / 255.0
            rgb_white = all(v == 255 for i, v in enumerate(pixels) if i % 4 != 3)
            good &= 0.02 <= a_lo and a_hi <= 0.98 and rgb_white
            line += ", alpha %.4f..%.4f, %d levels, rgb %s" % (a_lo, a_hi, len(set(alpha)), "white" if rgb_white else "NOT white")
        elif alpha:
            line += ", alpha %d..%d, %d colours" % (min(alpha), max(alpha), len({pixels[i:i + 3] for i in range(0, len(pixels), 4)}))
        print(("ok   " if good else "FAIL ") + line)
        ok &= good
    return ok


def main(argv):
    if "--check" in argv:
        return 0 if check() else 1
    build_mask()
    build_glow()
    print("wrote " + os.path.relpath(MASK_OUT, ROOT))
    print("wrote " + os.path.relpath(GLOW_OUT, ROOT))
    return 0 if check() else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
