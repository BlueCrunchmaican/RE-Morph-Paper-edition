"""Builds the resource pack's arm forms (the Arsenal) from the client's geometry dump.

Players without the client mod see an arm form as the plain tool of its tier. A player whose game has RE:Morph's resource
pack sees it as the living arm: black static and red tendrils, with the animation the mod itself draws as far as an item
model can show it. This script writes that pack content; it never touches the head pack's folders (gen_head_pack.py's)
or assets/minecraft/atlases/items.json, which already includes every texture under textures/item/ of any namespace.

- resourcepack/assets/morphreborn/items/arm/<form>.json, one item definition per form (and arm/lantern_lure.json, the
  lantern's lure as the server's item display shows it). Each has hand_animation_on_swap false, so a change of the
  stack's custom_model_data (the forming steps the server sends) never plays the equip bob, and a top-level select on
  display_context: the icon in the gui, on the ground, in frames and on shelves; the first-person composite (the forearm
  and fist, then the tool); the third-person composite (the sleeve over the arm, then the tool). The tool is picked by
  tier (the third custom_model_data string: iron, diamond, netherite) and then by what the stack says:
    * a forming float in custom_model_data[0] (1 + progress while the arm forms, retracts or melts): five forming models;
    * otherwise the biomass bar: damage over 0.75, 0.90 and 0.98 of the maximum give the thinning, starving and failing
      models;
    * otherwise the writhing: a model with the parts that never move, and over it one of the writhe keyframes, picked by
      the compass property with no target (a damped random walk that starts at a different place for each holder), so the
      tendrils never move in step. The keyframes are cut from the mod's loop (96 ticks).
  The bow also reads using_item and use_duration (its draw: five poses, two alternating at full draw), and the whip
  fishing_rod/cast (the lash gathered up while the line is out).
  The spear, mace, trident, crossbow and claws (2026-11-16) are built the same way, with the base poses, and the use states
  vanilla's items have as item models (2026-11-16, the backlog's resource pack item):
    * the crossbow reads the stack's charged state (select on charge_type: the server charges the arm with a marker arrow
      while the bolt is whole, so an arrow or a rocket is the loaded look, nothing the empty groove's, each with its own body,
      writhe keyframes, low stages and icon); an empty one being charged by the game (using_item) reads crossbow/pull with
      vanilla's thresholds (0.58, 1.0) and shows the bolt coming out of the stock in three stages (the dump's pull0..2, the
      geometry's regrow at 35, 65 and 90 percent). It is held as vanilla holds a crossbow (the game's item/crossbow.json display
      block, the grip frame at the box's middle, an arm shell of its own for the pistol grip: the game poses the arm of a loaded or
      charging crossbow forward, and the sword's hold would stand it on end);
    * the trident reads using_item (the throw drawn: the game raises the arm). First person: the same hold with the fork opened
      (pullg). Third person: the raised pose's own models, drawn with the game's trident_throwing block (pullt, in the frame
      the geometry's hold fix gives it) and an arm shell for that block (sleeve/t<n>_raised);
    * the spear, mace and claws have none: vanilla's spear, mace and shears have no use-state item model (the spear's charge is
      the game's own arm and item animation, done for any stack with a kinetic weapon, whatever its model), so the base poses are
      the whole of them.
  Their first-person pose is a rescaled tool with a forearm of its own, not an overlay on the body, so first person is the
  body alone for them (the fp model is left out when most of its quads are not the body's).
- resourcepack/assets/morphreborn/models/item/arm/**: the models. Each has its own display (no vanilla parent), so a pack
  that reskins vanilla items can't skew it.
- resourcepack/assets/morphreborn/textures/item/arm/: animated static (static.png from morph skin, static_red.png from
  the red skin, static_glow.png from the glow skin: 32 frames of 64x64 cut at the offsets the biomass bar's static
  jumps to, two ticks a frame), veins.png (24 frames, a swelling that runs along the tendril into the hand, interpolated)
  and runes.png (16 frames, glyphs that pulse, on the glowing plates).
- resourcepack/assets/morphreborn/textures/skin/morphskin_dissolve.png and morphskin_glow.png, copied from the client mod.

The geometry is the mod's own: ArmDump (a dev tool, also run headless by fabric-client's ArmDumpTest) runs every form's
geometry into quads, per tier and pose, and this script fits boxes to them: a loft's segments and a form's pieces are
merged, cheapest first, into rotated boxes (one box is one block-model element, and a model keeps to a budget of them).
Everything is placed exactly where the mod's own item path puts it: grip space G sits at the sword sprite's grip pixel
and +Y runs along the diagonal (the bow has its own grip), and the display transforms are vanilla's handheld ones.

Usage: py -3 -I tools/gen_arm_pack.py [dump-dir]        (default: fabric-client/run/arm-dump)
       py -3 -I tools/gen_arm_pack.py --check            checks what was written (sizes, frames, definitions)
Make the dump first: MORPHREBORN_DUMPARMS=<dir> with `gradlew test --tests '*ArmDumpTest*'` (no game needed), or
/morphclient dumparms in a dev client. Needs Pillow and numpy.
"""
import hashlib
import heapq
import json
import math
import os
import shutil
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DUMP = os.path.join(ROOT, "fabric-client", "run", "arm-dump")
PACK = os.path.join(ROOT, "resourcepack", "assets", "morphreborn")
SKIN_SRC = os.path.join(ROOT, "fabric-client", "src", "main", "resources", "assets", "morphreborn", "textures", "skin")
NS = "morphreborn"

FORMS = ["katana", "axe", "bow", "whip", "sickle", "shovel", "pickaxe", "spear", "mace", "trident", "crossbow", "claws"]
TIERS = {1: "iron", 2: "diamond", 3: "netherite"}
FRAMES = 12  # writhe keyframes in the dump
LOOP = 96
# the forming models: custom_model_data float 0 is 1 + progress, so a stack with no float (0) is the finished arm
FORM_THRESHOLDS = [1.0, 1.2, 1.4, 1.6, 1.8]
LOW_THRESHOLDS = [0.75, 0.90, 0.98]
BOW_USE = [0.2, 0.45, 0.7, 0.95]  # seconds into the draw (use_duration scale 0.05)

# Element budgets per model. A block model may have more, but every element is bytes in the pack and a quad the client
# draws for every arm in sight, so they stay small. The body and each writhe frame are drawn together.
BUDGET = {"body": 60, "frame": 8, "form": 14, "low": 26, "icon": 26, "fp": 20, "pull": 44, "cast": 44, "sleeve": 22, "lure": 22}

# Item-model space: pixels, the block's centre at (8, 8, 8). The sword sprite's grip pixel, and the bow's.
GRIP = (3.5, 3.5, 8.0)
BOW_GRIP = (5.5, 11.5, 8.0)
LIMIT_LO, LIMIT_HI = -15.5, 31.5  # block-model elements may not leave -16..32
MIN_THICK = 0.25  # a flat piece still gets this much body, so both its faces and its edges show

# display blocks: vanilla's own numbers (handheld.json, bow.json, generated.json)
DISPLAY_HAND = {
    "thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 4, 0.5], "scale": [0.85, 0.85, 0.85]},
    "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 4, 0.5], "scale": [0.85, 0.85, 0.85]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
}
DISPLAY_BOW = dict(DISPLAY_HAND)
DISPLAY_BOW["thirdperson_righthand"] = {"rotation": [-80, 260, -40], "translation": [-1, -2, 2.5], "scale": [0.9, 0.9, 0.9]}
DISPLAY_BOW["thirdperson_lefthand"] = {"rotation": [-80, -280, 40], "translation": [-1, -2, 2.5], "scale": [0.9, 0.9, 0.9]}
# The crossbow is held as vanilla holds one (2026-11-16, the sheath's pistol grip: SheathGeometry.grip): the game poses the arm of a loaded or charging
# crossbow forward, which a sword's hold would turn into a crossbow standing on end. Vanilla's own item/crossbow.json right-hand blocks;
# the grip frame is the box's middle. The left-hand blocks are the right ones PRE-FLIPPED, as DISPLAY_HAND's are (2026-11-16): the game's
# left-hand fix (ItemTransform.apply) negates a left block's x translation and its y and z rotation, so a left block with those negated lands
# exactly where the right one does, which is what the tool models, the grip and the arm shell (sleeve_place inverts the right block only) are
# fitted for. Vanilla's own left values are another pose and would turn the weapon off the arm. First person keeps its x translation raw, as
# handheld's does (the fix then puts it on the other side of the screen).
DISPLAY_CROSSBOW = dict(DISPLAY_HAND)
DISPLAY_CROSSBOW["thirdperson_righthand"] = {"rotation": [-90, 0, -60], "translation": [2, 0.1, -3], "scale": [0.9, 0.9, 0.9]}
DISPLAY_CROSSBOW["thirdperson_lefthand"] = {"rotation": [-90, 0, 60], "translation": [-2, 0.1, -3], "scale": [0.9, 0.9, 0.9]}
DISPLAY_CROSSBOW["firstperson_righthand"] = {"rotation": [-90, 0, -55], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]}
DISPLAY_CROSSBOW["firstperson_lefthand"] = {"rotation": [-90, 0, 55], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]}
CROSSBOW_GRIP = (8.0, 8.0)
# The trident's raised pose (a throw drawn, third person): the game's own trident_throwing block, which is what the geometry's raised hold fix
# is made for (ArmTransforms.TRIDENT_THROW_TP_*). Everything else (first person, the gui) is handheld's, which is what the hand-held model uses.
DISPLAY_RAISED = dict(DISPLAY_HAND)
DISPLAY_RAISED["thirdperson_righthand"] = {"rotation": [0, 90, 180], "translation": [8, -17, 9], "scale": [1, 1, 1]}
# pre-flipped like the crossbow's (the left-hand fix negates x translation and y, z rotation): the throw and the arm shell are fitted for the right
# hand, and vanilla's own left block (tz -7) is another pose
DISPLAY_RAISED["thirdperson_lefthand"] = {"rotation": [0, -90, -180], "translation": [-8, -17, 9], "scale": [1, 1, 1]}
TRIDENT_GRIP = (8.0, 8.0)  # ArmTransforms.Set.TRIDENT's grip frame: the middle of the box
DISPLAY_ICON = {
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
    "fixed": {"rotation": [0, 180, 0], "scale": [1, 1, 1]},
}


# ---------------------------------------------------------------------------------------------------------------- math

def rz(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def rx(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def ry(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def euler_xyz(x, y, z):
    """The game's ItemTransform rotation (JOML rotationXYZ: x, then y, then z about the moving axes = Rx . Ry . Rz)."""
    return rx(x) @ ry(y) @ rz(z)


def euler_zyx_from_matrix(m):
    """Element rotation {x, y, z} as the game applies it (rotationZYX: M = Rz . Ry . Rx), in degrees, from a rotation matrix."""
    sy = -m[2, 0]
    sy = max(-1.0, min(1.0, sy))
    y = math.asin(sy)
    if abs(sy) < 0.99999:
        x = math.atan2(m[2, 1], m[2, 2])
        z = math.atan2(m[1, 0], m[0, 0])
    else:  # looking straight along an axis: fold the twist into z
        x = 0.0
        z = math.atan2(-m[0, 1], m[1, 1])
    return [math.degrees(x), math.degrees(y), math.degrees(z)]


class Place:
    """A map from the dump's coordinates to item-model pixels: p -> origin + k * R p."""

    def __init__(self, origin, rot, k=1.0):
        self.origin = np.array(origin, float)
        self.rot = rot
        self.k = k

    def __call__(self, pts):
        return self.origin + self.k * (np.asarray(pts, float) @ self.rot.T)


def grip_place(bow):
    return Place(BOW_GRIP if bow else GRIP, rz(-45.0))


def grip_place_at(gx, gy):
    return Place((gx, gy, 8.0), rz(-45.0))


def gui_place(k, g):
    return Place((g, g, 8.0), rz(-45.0), k)


def sleeve_place(display=None):
    """Arm space A (px, the shoulder pivot, +Y down the arm) -> the item model of a hand-held item, third person, right hand
    (with the display block {display}, handheld's by default: the trident's raised pose has its own).

    The game draws a held item with the arm bone's pose, then Rx(-90), Ry(180), a move of (1, 2, -10)/16, then the item
    transform (move, turn, scale, then -0.5 for the block's middle). A point q of the model (px) is therefore at
    A = 16 * Rx(-90) Ry(180) ( off + t + R S (q/16 - 0.5) ) in arm space; this is its inverse."""
    d = (display or DISPLAY_HAND)["thirdperson_righthand"]
    rot = euler_xyz(*d["rotation"])
    s = d["scale"][0]
    t = np.array(d["translation"]) / 16.0
    off = np.array([1.0, 2.0, -10.0]) / 16.0
    hand = rx(-90) @ ry(180)

    class _Inverse(Place):
        def __call__(self, pts):
            a = np.asarray(pts, float) / 16.0
            v = a @ np.linalg.inv(hand).T - off - t
            v = v @ np.linalg.inv(rot).T / s
            return (v + 0.5) * 16.0

    return _Inverse((0, 0, 0), np.eye(3))


def lure_place(at):
    """The lure, drawn in arm space with world down along +Y, turned half a turn about X (so it hangs along -Y) and put
    with its rest point at the middle of the model."""
    turn = rx(180)
    centre = turn @ np.array(at, float)

    class _Lure(Place):
        def __call__(self, pts):
            return np.array([8.0, 8.0, 8.0]) + (np.asarray(pts, float) @ turn.T - centre)

    return _Lure((0, 0, 0), np.eye(3))


# ---------------------------------------------------------------------------------------------------------------- fit

def quad_area(q):
    return 0.5 * np.linalg.norm(np.cross(q[2] - q[0], q[3] - q[1]))


def obb(points):
    """A box round the points: (centre, rotation matrix with the longest axis second [local y], half sizes), PCA axes."""
    p = np.asarray(points, float)
    mean = p.mean(0)
    c = p - mean
    if len(p) < 3:
        axes = np.eye(3)
    else:
        w, v = np.linalg.eigh(c.T @ c)
        axes = v[:, ::-1]  # largest variance first
    best = None
    # PCA of a shell is rarely the tightest frame; try it and the same axes with the other cross-section turns
    for turn in (0.0, 45.0):
        a = axes.copy()
        if turn:
            ang = math.radians(turn)
            u, w2 = a[:, 1].copy(), a[:, 2].copy()
            a[:, 1] = u * math.cos(ang) + w2 * math.sin(ang)
            a[:, 2] = -u * math.sin(ang) + w2 * math.cos(ang)
        co = c @ a
        lo, hi = co.min(0), co.max(0)
        size = np.maximum(hi - lo, MIN_THICK)
        vol = size[0] * size[1] * size[2]
        if best is None or vol < best[0] - 1e-9:
            best = (vol, a, lo, hi)
    _, a, lo, hi = best
    size = np.maximum(hi - lo, MIN_THICK)
    centre_local = (lo + hi) / 2
    centre = mean + a @ centre_local
    # local y is the long axis: columns (cross, long, cross) so the long faces are the side faces
    cols = np.stack([a[:, 1], a[:, 0], a[:, 2]], axis=1)
    half = np.array([size[1], size[0], size[2]]) / 2
    if np.linalg.det(cols) < 0:
        cols[:, 2] = -cols[:, 2]
    return centre, cols, half


def obb_volume(points):
    p = np.asarray(points, float)
    if len(p) < 3:
        return MIN_THICK ** 3
    _, _, half = obb(p)
    return 8 * half[0] * half[1] * half[2]


class Run:
    """Consecutive quads of one layer that become one box."""

    def __init__(self, idx, layer, points):
        self.idx = idx
        self.layer = layer
        self.points = points
        self.vol = obb_volume(points)


def vertex_key(p):
    return (round(p[0] * 200), round(p[1] * 200), round(p[2] * 200))


def initial_runs(quads, layers):
    """The loft segments and the boxes of an emission order: consecutive quads that carry on from each other round a
    ring (each shares the previous one's edge), then the leftover single quads (caps) joined to a neighbour they touch."""
    n = len(quads)
    if n == 0:
        return []
    groups = []
    cur = [0]

    def same(a, b):
        return float(np.abs(a - b).max()) < 2e-3

    for i in range(1, n):
        a, b = quads[i - 1], quads[i]
        ccw = same(b[0], a[1]) and same(b[3], a[2])
        cw = same(b[1], a[0]) and same(b[2], a[3])
        if layers[i] == layers[i - 1] and (ccw or cw):
            cur.append(i)
        else:
            groups.append(cur)
            cur = [i]
    groups.append(cur)
    # fold single quads into a touching neighbour group (a box is two caps then a ring of four)
    out = []
    for g in groups:
        out.append(list(g))
    changed = True
    while changed:
        changed = False
        for k in range(len(out)):
            g = out[k]
            if len(g) != 1:
                continue
            keys = {vertex_key(p) for p in quads[g[0]]}
            for nb in (k + 1, k - 1):
                if 0 <= nb < len(out) and layers[out[nb][0]] == layers[g[0]]:
                    shared = sum(1 for i in out[nb] for p in quads[i] if vertex_key(p) in keys)
                    if shared >= 2:
                        out[nb] = sorted(out[nb] + g)
                        del out[k]
                        changed = True
                        break
            if changed:
                break
    runs = []
    for g in out:
        pts = np.unique(np.round(np.concatenate([quads[i] for i in g]), 3), axis=0)
        runs.append(Run(g, layers[g[0]], pts))
    return runs


def merge_runs(runs, budget):
    """Merges neighbouring runs of one layer, the cheapest merge (the box's extra volume) first, until the budget holds;
    then drops the least visible runs if merging alone can't get there."""
    runs = [r for r in runs]
    # a linked list with a heap of merge costs, entries stale when either side changed
    nxt = {i: i + 1 for i in range(len(runs) - 1)}
    prv = {i + 1: i for i in range(len(runs) - 1)}
    alive = set(range(len(runs)))
    version = {i: 0 for i in alive}
    heap = []

    def push(a, b):
        ra, rb = runs[a], runs[b]
        if ra.layer != rb.layer:
            return
        pts = np.concatenate([ra.points, rb.points])
        # the extra volume the merged box wastes: a straight run of a loft merges for nothing, a bend or a taper costs
        excess = obb_volume(pts) - ra.vol - rb.vol
        # (and dearer the more of the new box is wasted, so thin curved strands stay in several boxes)
        cost = excess * (1.0 + 2.5 * max(0.0, excess) / (ra.vol + rb.vol + 0.3))
        heapq.heappush(heap, (cost, a, b, version[a], version[b]))

    for a, b in nxt.items():
        push(a, b)
    count = len(alive)
    while count > budget and heap:
        cost, a, b, va, vb = heapq.heappop(heap)
        if a not in alive or b not in alive or version[a] != va or version[b] != vb or nxt.get(a) != b:
            continue
        ra, rb = runs[a], runs[b]
        pts = np.unique(np.concatenate([ra.points, rb.points]), axis=0)
        merged = Run(ra.idx + rb.idx, ra.layer, pts)
        runs.append(merged)
        m = len(runs) - 1
        alive.discard(a)
        alive.discard(b)
        alive.add(m)
        version[m] = 0
        count -= 1
        p, n = prv.get(a), nxt.get(b)
        if p is not None:
            nxt[p] = m
            prv[m] = p
            push(p, m)
        if n is not None:
            prv[n] = m
            nxt[m] = n
            push(m, n)
    out = [runs[i] for i in sorted(alive, key=lambda i: min(runs[i].idx))]
    if len(out) > budget:
        out.sort(key=lambda r: -importance(r))
        out = sorted(out[:budget], key=lambda r: min(r.idx))
    return out


def importance(run):
    _, _, half = obb(run.points)
    return float(half[1] * (half[0] + half[2]) * 2 + half.max() * 0.2) * (1.15 if run.layer != 0 else 1.0)


# ------------------------------------------------------------------------------------------------------------- elements

DIRS = {"east": (0, 1), "west": (0, -1), "up": (1, 1), "down": (1, -1), "south": (2, 1), "north": (2, -1)}


def hash01(*parts):
    h = hashlib.blake2b("|".join(str(p) for p in parts).encode(), digest_size=8).digest()
    return int.from_bytes(h, "little") / float(1 << 64)


def element_from(centre, cols, half, layer, tag, vein_ok=True):
    """One block-model element for a box: from/to round the centre, turned about it. The faces map onto the textures."""
    c = np.asarray(centre, float)
    h = np.asarray(half, float)
    frm = c - h
    to = c + h
    rot = euler_zyx_from_matrix(cols)
    thin_long = h[1] >= 2.2 * max(h[0], h[2]) and h[1] >= 1.6 and max(h[0], h[2]) <= 0.9
    plate = layer == 2 and min(h) * 2 <= 0.9 and sorted(h)[1] * 2 >= 2.4
    if layer == 0:
        tex = "s"
    elif layer == 1:
        tex = "v" if thin_long and vein_ok else "r"
    else:
        tex = "g" if not plate else "x"
    faces = {}
    tiny_caps = 4 * h[0] * h[2] < 1.0  # an end this small is a speck; the hole it would leave can't be seen
    for name, (axis, sign) in DIRS.items():
        if tiny_caps and axis == 1:
            continue
        a, b = [i for i in range(3) if i != axis]
        # the face's own two sizes, in px: a box's face is as wide as the other two axes
        du, dv = 2 * h[a], 2 * h[b]
        if axis == 1:  # up/down: u across x, v along z
            du, dv = 2 * h[0], 2 * h[2]
        elif axis == 0:  # east/west: u along z, v along y
            du, dv = 2 * h[2], 2 * h[1]
        else:  # north/south: u along x, v along y
            du, dv = 2 * h[0], 2 * h[1]
        if tex == "v":
            # the whole frame runs along the strand, so the swelling travels its length
            u0 = hash01(tag, name, "u") * 12.0
            uw = min(4.0, max(du, 0.5) * 2.0)
            uv = [round(u0, 1), 0, round(u0 + uw, 1), 16]
        else:
            w = min(16.0, max(du, 0.4))
            t = min(16.0, max(dv, 0.4))
            u0 = hash01(tag, name, "u") * (16.0 - w)
            v0 = hash01(tag, name, "v") * (16.0 - t)
            uv = [round(u0, 1), round(v0, 1), round(u0 + w, 1), round(v0 + t, 1)]
            if tex == "x":
                uv = [round(u0, 1), round(v0, 1), round(u0 + min(w, 8.0), 1), round(v0 + min(t, 8.0), 1)]
        faces[name] = {"uv": uv, "texture": "#" + tex}
    el = {"from": [round(float(v), 2) for v in frm], "to": [round(float(v), 2) for v in to]}
    if any(abs(r) > 0.05 for r in rot):
        el["rotation"] = {"origin": [round(float(v), 2) for v in c], "x": round(rot[0], 1), "y": round(rot[1], 1), "z": round(rot[2], 1)}
    if layer == 2:
        el["light_emission"] = 15
    el["faces"] = faces
    return el


def fit_elements(runs, place, tag):
    """Boxes for runs (whose points are already in item-model pixels; place is kept for the callers' symmetry)."""
    out = []
    for n, r in enumerate(runs):
        centre, cols, half = obb(r.points)
        out.append(element_from(centre, cols, half, r.layer, f"{tag}/{n}"))
    return out


def corners_of_runs(runs, quads_model):
    return [np.unique(np.round(np.concatenate([quads_model[i] for i in r.idx]), 3), axis=0) for r in runs]


# ---------------------------------------------------------------------------------------------------------------- dump

def load_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def pose_arrays(pose):
    q = np.array(pose["quads"], float).reshape(-1, 13)
    layers = q[:, 0].astype(int)
    quads = q[:, 1:].reshape(-1, 4, 3)
    ok = np.array([quad_area(x) > 1e-4 for x in quads], dtype=bool)
    return quads[ok], layers[ok]


TEXTURES = {
    "s": NS + ":item/arm/static",
    "r": NS + ":item/arm/static_red",
    "g": NS + ":item/arm/static_glow",
    "v": NS + ":item/arm/veins",
    "x": NS + ":item/arm/runes",
    "particle": NS + ":item/arm/static",
}
# The display blocks live in four parent models of the pack's own (never a vanilla one: a pack that reskins vanilla items
# can't skew them), which also name the textures; a model is then only its elements.
PARENTS = {}


def parent_for(display, gui_light):
    key = json.dumps(display, sort_keys=True) + str(gui_light)
    if key not in PARENTS:
        name = ("hand" if display is DISPLAY_HAND else "hand_bow" if display is DISPLAY_BOW else "icon" if display is DISPLAY_ICON
                else "raised" if display is DISPLAY_RAISED else "crossbow" if display is DISPLAY_CROSSBOW else "lure")
        m = {}
        if gui_light:
            m["gui_light"] = gui_light
        m["textures"] = TEXTURES
        m["display"] = display
        PARENTS[key] = (name, m)
    return NS + ":item/arm/" + PARENTS[key][0]


def write_parents():
    for name, m in PARENTS.values():
        write_model(name, m)


def model_json(elements, display, gui_light=None):
    m = {"parent": parent_for(display, gui_light)}
    m["elements"] = elements
    return m


# model id -> the factor its elements were shrunk by to stay inside the element limits (the definition grows it back)
SHRUNK = {}


def write_model(rel, model):
    path = os.path.join(PACK, "models", "item", "arm", rel + ".json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(model, f, separators=(",", ":"))


def emit_model(rel, elements, display, gui_light=None):
    """Writes a model, shrinking it about its middle if an element would leave -16..32 (a pre-rotation coordinate counts).
    Returns the model's id; the item definition grows a shrunk one back with a transformation."""
    if not elements:
        return None
    k = clamp_check(elements)
    if k < 1.0:
        elements = scaled(elements, k)
        SHRUNK[NS + ":item/arm/" + rel] = k
    write_model(rel, model_json(elements, display, gui_light))
    return NS + ":item/arm/" + rel


def clamp_check(elements):
    """Scale about the middle (8, 8, 8) if anything is out of the element limits; returns the factor (1 when fine)."""
    far = 0.0
    for e in elements:
        for key in ("from", "to"):
            for v in e[key]:
                far = max(far, abs(v - 8))
    limit = 23.4
    return 1.0 if far <= limit else limit / far


def scaled(elements, k):
    if k == 1.0:
        return elements
    out = json.loads(json.dumps(elements))
    for e in out:
        for key in ("from", "to"):
            e[key] = [round(8 + (v - 8) * k, 3) for v in e[key]]
        if "rotation" in e:
            e["rotation"]["origin"] = [round(8 + (v - 8) * k, 3) for v in e["rotation"]["origin"]]
    return out


# ------------------------------------------------------------------------------------------------------------- building

class Builder:
    def __init__(self, form, root):
        self.form = form
        self.root = root
        self.bow = bool(root.get("bow"))
        self.display = DISPLAY_BOW if self.bow else DISPLAY_CROSSBOW if form == "crossbow" else DISPLAY_HAND
        self.place = grip_place_at(*CROSSBOW_GRIP) if form == "crossbow" else grip_place(self.bow)
        self.names = {}  # (tier, kind) -> model id
        self.count = {}

    def poses(self, tier):
        t = next(t for t in self.root["tiers"] if t["tier"] == tier)
        return {p["id"]: p for p in t["poses"]}

    def model_id(self, rel):
        return NS + ":item/arm/" + rel

    def emit(self, rel, elements, display, kind, gui_light=None):
        self.count[kind] = self.count.get(kind, 0) + len(elements)
        return emit_model(rel, elements, display, gui_light)

    def full(self, tier, pose_id, budget_key, rel, place=None, display=None, gui_light=None):
        """One model of a whole pose (every element of it fitted afresh)."""
        poses = self.poses(tier)
        pl = place or self.place
        q, lay = pose_arrays(poses[pose_id])
        qm = pl(q.reshape(-1, 3)).reshape(-1, 4, 3)
        runs = merge_runs(initial_runs(qm, lay), BUDGET[budget_key])
        return self.emit(rel, fit_elements(runs, pl, f"{self.form}{tier}{pose_id}"), display or self.display, budget_key, gui_light)

    def build_state(self, tier, p=""):
        """The models a form's resting look is made of: the body and the writhe keyframes, the three low-biomass stages and the icon.
        {p} is the dump's pose-id prefix and the model names' infix: "" for the form as it is, "e_" for the crossbow with its groove empty
        (the same look with the bolt out of the weapon, which the stack's charged state picks, 2026-11-16)."""
        poses = self.poses(tier)
        f = self.form
        out = {}

        # --- the writhe: cluster once on frame 0, then see which runs move
        frames = []
        for k in range(FRAMES):
            q, lay = pose_arrays(poses[p + "w%02d" % k])
            frames.append((q, lay))
        same_topology = all(len(fr[0]) == len(frames[0][0]) and (fr[1] == frames[0][1]).all() for fr in frames)
        q0, l0 = frames[0]
        qm0 = self.place(q0.reshape(-1, 3)).reshape(-1, 4, 3)
        if same_topology:
            runs = merge_runs(initial_runs(qm0, l0), BUDGET["body"] + 6 * BUDGET["frame"])
            moves = []
            allq = [self.place(fr[0].reshape(-1, 3)).reshape(-1, 4, 3) for fr in frames]
            for r in runs:
                lo = min(float(np.abs(allq[k][r.idx] - allq[0][r.idx]).max()) for k in range(1, FRAMES))
                dev = max(float(np.abs(allq[k][r.idx] - allq[0][r.idx]).max()) for k in range(1, FRAMES))
                moves.append(dev > 0.22)
            static = [r for r, m in zip(runs, moves) if not m]
            moving = [r for r, m in zip(runs, moves) if m]
            # keep the moving part within its frame budget by letting the cheapest moving runs go static (their frame-0 place)
            if len(moving) > BUDGET["frame"]:
                moving.sort(key=lambda r: -importance(r))
                static += moving[BUDGET["frame"]:]
                moving = sorted(moving[:BUDGET["frame"]], key=lambda r: min(r.idx))
            static.sort(key=lambda r: min(r.idx))
            static = merge_runs(static, BUDGET["body"])
            body_el = fit_elements(static, self.place, f"{f}{tier}{p}b")
            out["body"] = self.emit(f"{f}/t{tier}_{p}body", body_el, self.display, "body")
            out["w"] = []
            for k in range(FRAMES):
                if not moving:
                    out["w"].append(None)
                    continue
                el = []
                for n, r in enumerate(moving):
                    pts = allq[k][r.idx].reshape(-1, 3)
                    pts = np.unique(np.round(pts, 3), axis=0)
                    c, cols, h = obb(pts)
                    el.append(element_from(c, cols, h, r.layer, f"{f}{tier}{p}m{n}"))
                out["w"].append(self.emit(f"{f}/t{tier}_{p}w{k:02d}", el, self.display, "frame"))
        else:
            runs = merge_runs(initial_runs(qm0, l0), BUDGET["body"])
            out["body"] = self.emit(f"{f}/t{tier}_{p}body", fit_elements(runs, self.place, f"{f}{tier}{p}b"), self.display, "body")
            out["w"] = [None] * FRAMES

        # --- full models for the other poses
        out["low"] = [self.full(tier, f"{p}low{n}", "low", f"{f}/t{tier}_{p}low{n}") for n in (1, 2, 3)]
        out["icon"] = self.full(tier, p + "gui", "icon", f"{f}/t{tier}_{p}icon", gui_place(self.root["guiK"], self.root["guiG"]), DISPLAY_ICON, "front")
        return out

    def build_tier(self, tier):
        poses = self.poses(tier)
        f = self.form
        out = self.build_state(tier)
        out["form"] = [self.full(tier, f"f{n}", "form", f"{f}/t{tier}_f{n}") for n in range(5)]
        q0, l0 = pose_arrays(poses["w00"])
        # the forearm and fist: what the first-person pose has over the third person's w00
        qf, lf = pose_arrays(poses["fp"])
        key0 = {tuple(np.round(q.reshape(-1), 2)) for q in q0}
        extra = [i for i, q in enumerate(qf) if tuple(np.round(q.reshape(-1), 2)) not in key0]
        qe, le = qf[extra], lf[extra]
        if len(extra) > len(qf) // 2:
            # not an overlay: the whole tool is drawn differently in first person (the new forms scale it, with a forearm and fist of their
            # own, 2026-11-16), so laying it over the body would show two tools; first person is the body alone, as since 8.0.0
            qe, le = qe[:0], le[:0]
        qem = self.place(qe.reshape(-1, 3)).reshape(-1, 4, 3)
        runs = merge_runs(initial_runs(qem, le), BUDGET["fp"])
        out["fp"] = self.emit(f"{f}/t{tier}_fp", fit_elements(runs, self.place, f"{f}{tier}fp"), self.display, "fp")
        if self.bow:
            out["pull"] = [self.full(tier, f"pull{n}", "pull", f"{f}/t{tier}_pull{n}") for n in range(7)]
        if f == "whip":
            out["cast"] = self.full(tier, "cast", "cast", f"{f}/t{tier}_cast")
        if f == "crossbow":
            # the stack's charged state picks between the loaded look (everything above) and the empty groove's; a vanilla pull
            # (the game charging an empty crossbow) shows the bolt coming out of the stock, three stages like vanilla's three pulling models
            out["empty"] = self.build_state(tier, "e_")
            out["empty"]["form"] = [self.full(tier, f"e_f{n}", "form", f"{f}/t{tier}_e_f{n}") for n in range(5)]
            out["pull"] = [self.full(tier, f"pull{n}", "pull", f"{f}/t{tier}_pull{n}") for n in range(3)]
        if f == "trident":
            # a throw drawn (the use animation): in first person the same hold with the prongs spread; in third person the game raises the
            # arm and the model is drawn with the throwing display block, in the frame the geometry's own hold fix gives (grip space of the
            # trident's box, the middle of it)
            out["drawn"] = self.full(tier, "pullg", "pull", f"{f}/t{tier}_drawn")
            out["raised"] = self.full(tier, "pullt", "pull", f"{f}/t{tier}_raised", grip_place_at(*TRIDENT_GRIP), DISPLAY_RAISED)
        return out


# ---------------------------------------------------------------------------------------------------------------- items

def model(m):
    if m is None:
        return {"type": "minecraft:empty"}
    out = {"type": "minecraft:model", "model": m}
    k = SHRUNK.get(m)
    if k:
        t = 0.5 - 0.5 / k
        out["transformation"] = {"translation": [round(t, 5)] * 3, "left_rotation": [0, 0, 0, 1], "scale": [round(1 / k, 5)] * 3,
                                 "right_rotation": [0, 0, 0, 1]}
    return out


def select_tier(cases, fallback=None):
    out = {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 2,
           "cases": [{"when": TIERS[t], "model": cases[t]} for t in sorted(cases)]}
    out["fallback"] = fallback if fallback is not None else cases[2]
    return out


def writhe_dispatch(w):
    frames = [m for m in w]
    if not any(frames):
        return {"type": "minecraft:empty"}
    entries = [{"threshold": float(k), "model": model(frames[k])} for k in range(1, FRAMES) if frames[k]]
    return {"type": "minecraft:range_dispatch", "property": "minecraft:compass", "target": "none", "scale": float(FRAMES),
            "entries": entries, "fallback": model(frames[0]) if frames[0] else {"type": "minecraft:empty"}}


def forming_chain(b, state):
    """The finished arm's look by what the stack says: the forming float, else the biomass bar, else the writhe. {state} is the models
    of one resting look (build_state's: body, w, low, icon); the forming models are common to all of them, except the crossbow's empty
    groove, which has its own (state["form"], from the e_f poses: an arm forming right after a shot must not show a bolt it then loses)."""
    resting = {"type": "minecraft:composite", "models": [model(state["body"]), writhe_dispatch(state["w"])]}
    low = {"type": "minecraft:range_dispatch", "property": "minecraft:damage", "normalize": True,
           "entries": [{"threshold": th, "model": model(m)} for th, m in zip(LOW_THRESHOLDS, state["low"])], "fallback": resting}
    return {"type": "minecraft:range_dispatch", "property": "minecraft:custom_model_data", "index": 0,
            "entries": [{"threshold": th, "model": model(m)} for th, m in zip(FORM_THRESHOLDS, state.get("form", b["form"]))], "fallback": low}


def charge_select(b, loaded, empty):
    """The crossbow's charged state (what the vanilla crossbow's own definition reads): a loaded crossbow (an arrow or a rocket in it:
    the server charges the arm with one marker arrow) shows the {loaded} look, an empty one the {empty} look, and while the game is
    charging an empty one (a vanilla pull; the arm is never really loaded by the player) the bolt comes out of the stock in three
    stages, picked by the same crossbow/pull property and thresholds as vanilla's three pulling models."""
    pull = {"type": "minecraft:range_dispatch", "property": "minecraft:crossbow/pull",
            "entries": [{"threshold": 0.58, "model": model(b["pull"][1])}, {"threshold": 1.0, "model": model(b["pull"][2])}],
            "fallback": model(b["pull"][0])}
    return {"type": "minecraft:select", "property": "minecraft:charge_type",
            "cases": [{"when": "arrow", "model": loaded}, {"when": "rocket", "model": loaded}],
            "fallback": {"type": "minecraft:condition", "property": "minecraft:using_item", "on_true": pull, "on_false": empty}}


def body_for_tier(b, bow, whip, crossbow=False):
    forming = forming_chain(b, b)
    if crossbow:
        return charge_select(b, forming, forming_chain(b, b["empty"]))
    if whip:
        forming = {"type": "minecraft:condition", "property": "minecraft:fishing_rod/cast", "on_true": model(b["cast"]), "on_false": forming}
    if bow:
        pulls = b["pull"]
        cycle = {"type": "minecraft:range_dispatch", "property": "minecraft:use_cycle", "period": 4.0,
                 "entries": [{"threshold": 2.0, "model": model(pulls[6])}], "fallback": model(pulls[5])}
        draw = {"type": "minecraft:range_dispatch", "property": "minecraft:use_duration", "scale": 0.05,
                "entries": [{"threshold": th, "model": model(pulls[n + 1])} for n, th in enumerate(BOW_USE)] + [{"threshold": 1.0, "model": cycle}],
                "fallback": model(pulls[0])}
        forming = {"type": "minecraft:condition", "property": "minecraft:using_item", "on_true": draw, "on_false": forming}
    return forming


def item_definition(built, bow, whip, crossbow=False, trident=False):
    sleeves = {t: model(sleeve_id(t, "crossbow_" if crossbow else "")) for t in TIERS}
    icon = select_tier({t: model(built[t]["icon"]) for t in TIERS})
    if crossbow:
        # an icon shows what the stack is charged with too (the mod's own icon has no stack and shows it loaded: it is the one that cannot tell)
        icon = {"type": "minecraft:select", "property": "minecraft:charge_type",
                "cases": [{"when": "arrow", "model": icon}, {"when": "rocket", "model": icon}],
                "fallback": select_tier({t: model(built[t]["empty"]["icon"]) for t in TIERS})}
    bodies = {t: body_for_tier(built[t], bow, whip, crossbow) for t in TIERS}
    body = select_tier(bodies)
    # first person: the forearm and fist over the body; since 8.0.0 first person is the tool alone (the client draws no arm
    # there), so with no forearm models it is just the body
    if all(built[t]["fp"] for t in TIERS):
        fp = {"type": "minecraft:composite", "models": [select_tier({t: model(built[t]["fp"]) for t in TIERS}), body]}
    else:
        fp = body
    tp = {"type": "minecraft:composite", "models": [select_tier(sleeves), body]}
    if trident:
        # a throw drawn (the use animation, which every viewer sees as the raised arm): first person keeps its hold with the fork opened,
        # third person is the raised pose's own models (the arm shell and the fork in the throwing display block's frame)
        using = {"type": "minecraft:condition", "property": "minecraft:using_item"}
        fp = dict(using, on_true=select_tier({t: model(built[t]["drawn"]) for t in TIERS}), on_false=fp)
        raised_sleeves = {t: model(sleeve_id(t) + "_raised") for t in TIERS}
        raised = {"type": "minecraft:composite", "models": [select_tier(raised_sleeves), select_tier({t: model(built[t]["raised"]) for t in TIERS})]}
        tp = dict(using, on_true=raised, on_false=tp)
    return {"hand_animation_on_swap": False,
            "model": {"type": "minecraft:select", "property": "minecraft:display_context",
                      "cases": [{"when": ["gui", "ground", "fixed", "on_shelf"], "model": icon},
                                {"when": ["firstperson_righthand", "firstperson_lefthand"], "model": fp}],
                      "fallback": tp}}


def sleeve_id(tier, kind=""):
    return NS + ":item/arm/sleeve/%st%d" % (kind, tier)


def write_item(name, definition):
    path = os.path.join(PACK, "items", "arm", name + ".json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(definition, f, separators=(",", ":"))


# ---------------------------------------------------------------------------------------------------------------- the rest

def build_sleeves(dump):
    root = load_json(os.path.join(dump, "_sleeve.json"))
    place = sleeve_place()
    # the same shell in the trident's raised pose: the arm is lifted and the item is drawn with another display block, so the
    # shell's place in the model is the inverse of that block's chain (a throw drawn is the only time it is used)
    raised = sleeve_place(DISPLAY_RAISED)
    for t in root["tiers"]:
        q, lay = pose_arrays(t["poses"][0])
        qm = place(q.reshape(-1, 3)).reshape(-1, 4, 3)
        runs = merge_runs(initial_runs(qm, lay), BUDGET["sleeve"])
        emit_model(f"sleeve/t{t['tier']}", fit_elements(runs, place, f"sleeve{t['tier']}"), DISPLAY_HAND)
        qr = raised(q.reshape(-1, 3)).reshape(-1, 4, 3)
        runs = merge_runs(initial_runs(qr, lay), BUDGET["sleeve"])
        emit_model(f"sleeve/t{t['tier']}_raised", fit_elements(runs, raised, f"sleeveR{t['tier']}"), DISPLAY_RAISED)


def build_crossbow_sleeves(dump):
    """The crossbow's arm shell: the claw closes round the pistol grip (not the sword's hold), and the model is drawn with the crossbow's own display block."""
    path = os.path.join(dump, "_sleeve_crossbow.json")
    # no silent skip (2026-11-16): the crossbow's definition names sleeve/crossbow_t1..3, so a dump without this file (one made before the
    # crossbow, or a partial one) must stop here, as every other missing pose does, and not ship a definition whose models were never written
    root = load_json(path)
    place = sleeve_place(DISPLAY_CROSSBOW)
    for t in root["tiers"]:
        q, lay = pose_arrays(t["poses"][0])
        qm = place(q.reshape(-1, 3)).reshape(-1, 4, 3)
        runs = merge_runs(initial_runs(qm, lay), BUDGET["sleeve"])
        emit_model(f"sleeve/crossbow_t{t['tier']}", fit_elements(runs, place, f"sleeveX{t['tier']}"), DISPLAY_CROSSBOW)


def build_lure(dump):
    path = os.path.join(dump, "_lure.json")
    if not os.path.isfile(path):
        return None
    root = load_json(path)
    at = root["at"]
    place = lure_place(at)
    tier = next(t for t in root["tiers"] if t["tier"] == 2)
    ids = []
    for k, p in enumerate(tier["poses"]):
        q, lay = pose_arrays(p)
        # only what is near the lure itself: the stalk and the clutch belong to the arm
        c = q.mean(1)
        near = np.linalg.norm(c - np.array(at), axis=1) < 7.5
        q, lay = q[near], lay[near]
        if len(q) == 0:
            continue
        qm = place(q.reshape(-1, 3)).reshape(-1, 4, 3)
        runs = merge_runs(initial_runs(qm, lay), BUDGET["lure"])
        display = {"none": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}
        ids.append(emit_model(f"lantern/lure_{k}", fit_elements(runs, place, f"lure{k}"), display))
    if not ids:
        return None
    entries = [{"threshold": float(k), "model": model(ids[k])} for k in range(1, len(ids))]
    definition = {"hand_animation_on_swap": False,
                  "model": {"type": "minecraft:range_dispatch", "property": "minecraft:compass", "target": "none", "scale": float(len(ids)),
                            "entries": entries, "fallback": model(ids[0])}}
    write_item("lantern_lure", definition)
    return ids


# -------------------------------------------------------------------------------------------------------------- textures

FRAME = 64
STATIC_FRAMES = 32
VEIN_FRAMES = 24
RUNE_FRAMES = 16


def skin(name):
    return Image.open(os.path.join(SKIN_SRC, name)).convert("RGBA")


def crop_wrapped(img, x, y, size):
    a = np.asarray(img)
    h, w = a.shape[:2]
    ys = (np.arange(size) + y) % h
    xs = (np.arange(size) + x) % w
    return a[np.ix_(ys, xs)]


def static_strip(src):
    """The biomass bar's static: every two ticks it jumps its window over the 64-unit sheet by (13, 29) units, 4 texels a
    unit; a frame here is a 64-texel window (16 units, the whole frame of an item model) at those offsets."""
    frames = []
    for k in range(STATIC_FRAMES):
        bu, bv = (k * 13) & 63, (k * 29) % 50
        frames.append(crop_wrapped(src, bu * 4, bv * 4, FRAME))
    return np.concatenate(frames, axis=0)


def vein_strip():
    """Dark red static with thin veins along v that swell: a bulge runs down the frame (into the hand) once a loop, as the
    frenzy tendrils' swelling does (1 + 0.8 * exp(-d^2 / 0.006) about a point that travels along them)."""
    base = np.asarray(skin("morphskin_red.png"))
    frames = []
    rng = np.random.default_rng(0x56454E)
    cols = [0.12, 0.34, 0.58, 0.82] + rng.uniform(-0.04, 0.04, 4)
    phase = rng.uniform(0, 6.28, 4)
    yy = np.linspace(0, 1, FRAME)[:, None]
    xx = np.linspace(0, 1, FRAME)[None, :]
    for f in range(VEIN_FRAMES):
        t = f / VEIN_FRAMES
        img = crop_wrapped(Image.fromarray(base), 0, 0, FRAME).astype(float) * 0.5
        pulse = 1.0 - t
        swell = 1.0 + 0.8 * np.exp(-((yy - pulse) ** 2) / 0.006)
        for c, p in zip(cols, phase):
            centre = c + 0.035 * np.sin(5.0 * yy + p)
            width = 0.012 * swell
            line = np.exp(-((xx - centre) ** 2) / (2 * width ** 2))
            img[..., 0] += 200 * line * (swell * 0.7)
            img[..., 1] += 22 * line
            img[..., 2] += 16 * line
        img[..., 3] = 255
        frames.append(np.clip(img, 0, 255).astype(np.uint8))
    return np.concatenate(frames, axis=0)


def rune_strip():
    """A dark plate with engraved glyphs (strokes and bars from a fixed hash) that glow brighter and dimmer."""
    rng = np.random.default_rng(0x52554E)
    glyph = np.zeros((FRAME, FRAME))
    for _ in range(22):
        if rng.random() < 0.5:
            y = int(rng.integers(4, FRAME - 4))
            x0 = int(rng.integers(2, FRAME - 18))
            glyph[y:y + 2, x0:x0 + int(rng.integers(6, 18))] = 1
        else:
            x = int(rng.integers(4, FRAME - 4))
            y0 = int(rng.integers(2, FRAME - 18))
            glyph[y0:y0 + int(rng.integers(6, 18)), x:x + 2] = 1
    glyph[::8, :] *= 0.0
    base = np.asarray(skin("morphskin_glow.png"))
    plate = crop_wrapped(Image.fromarray(base), 64, 64, FRAME).astype(float) * 0.35
    frames = []
    for f in range(RUNE_FRAMES):
        pulse = 0.55 + 0.45 * math.sin(2 * math.pi * f / RUNE_FRAMES)
        img = plate.copy()
        img[..., 0] += glyph * (150 + 100 * pulse)
        img[..., 1] += glyph * (20 + 30 * pulse)
        img[..., 2] += glyph * (10 + 20 * pulse)
        img[..., 3] = 255
        frames.append(np.clip(img, 0, 255).astype(np.uint8))
    return np.concatenate(frames, axis=0)


def write_png(rel, arr, frametime, interpolate=False):
    path = os.path.join(PACK, "textures", "item", "arm", rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(arr).save(path, optimize=True)
    meta = {"animation": {"frametime": frametime, "width": FRAME, "height": FRAME}}
    if interpolate:
        meta["animation"]["interpolate"] = True
    with open(path + ".mcmeta", "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=1)


def build_textures():
    write_png("static.png", static_strip(skin("morphskin.png")), 2)
    write_png("static_red.png", static_strip(skin("morphskin_red.png")), 2)
    write_png("static_glow.png", static_strip(skin("morphskin_glow.png")), 2)
    write_png("veins.png", vein_strip(), 2, True)
    write_png("runes.png", rune_strip(), 4, True)
    skin_dir = os.path.join(PACK, "textures", "skin")
    os.makedirs(skin_dir, exist_ok=True)
    for name in ("morphskin_dissolve.png", "morphskin_glow.png"):
        shutil.copyfile(os.path.join(SKIN_SRC, name), os.path.join(skin_dir, name))


# ------------------------------------------------------------------------------------------------------------------ main

def clean():
    for sub in ("items/arm", "models/item/arm", "textures/item/arm"):
        shutil.rmtree(os.path.join(PACK, *sub.split("/")), ignore_errors=True)


def main(dump):
    clean()
    PARENTS.clear()
    build_textures()
    build_sleeves(dump)
    forms = os.environ.get("ARMPACK_FORMS", ",".join(FORMS)).split(",")
    if "crossbow" in forms and os.path.isfile(os.path.join(dump, "crossbow.json")):
        build_crossbow_sleeves(dump)  # raises when the dump has the crossbow but not its shell (a dump from before the crossbow's pistol grip)
    for form in forms:
        path = os.path.join(dump, form + ".json")
        if not os.path.isfile(path):
            print(f"{form}: no geometry in the dump, skipped")
            continue
        root = load_json(path)
        b = Builder(form, root)
        built = {t: b.build_tier(t) for t in TIERS}
        write_item(form, item_definition(built, b.bow, form == "whip", form == "crossbow", form == "trident"))
        print(f"{form}: {b.count}")
    ids = build_lure(dump)
    print("lure:", "none" if not ids else f"{len(ids)} frames")
    write_parents()


def check():
    from collections import Counter
    bad = 0
    base = os.path.join(PACK, "textures", "item", "arm")
    for name in sorted(os.listdir(base)):
        if not name.endswith(".png"):
            continue
        img = Image.open(os.path.join(base, name))
        meta = load_json(os.path.join(base, name + ".mcmeta"))["animation"]
        w, h = meta.get("width", img.width), meta.get("height", img.height)
        ok = img.width % w == 0 and img.height % h == 0
        print(name, img.size, "frames", (img.height // h) * (img.width // w), "ok" if ok else "BAD")
        bad += 0 if ok else 1
    sizes = Counter()
    for dirpath, _, files in os.walk(os.path.join(PACK, "models", "item", "arm")):
        for f in files:
            sizes[os.path.basename(dirpath)] += os.path.getsize(os.path.join(dirpath, f))
    print("model bytes by folder:", dict(sizes), "total", sum(sizes.values()))
    return bad


if __name__ == "__main__":
    if "--check" in sys.argv:
        sys.exit(1 if check() else 0)
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    main(args[0] if args else DUMP)
