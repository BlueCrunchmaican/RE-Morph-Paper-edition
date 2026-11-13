"""Builds the multimorph head models from the client's head dump.

Players without the client mod see a multimorph as the main body (Lib's Disguises) with the borrowed mob's head on
it: an item display showing that head as an item model from RE:Morph's resource pack. This script writes:

- resourcepack/assets/morphreborn/models/head/<mob>.json and items/head/<mob>.json: one head model per mob, made
  from the game's own model geometry and textures (nothing of Mojang's is copied into the pack; the models only
  point at the game's textures);
- models/ears/<mob>.json (and its item): for the animals that lend their ears and tail instead of their head (wolf,
  fox, cat...), the ears alone;
- models/part/<mob>/<part>.json (and their items): what a creature lends besides or instead of a head, as the client
  mod grafts it: each wing, tail, fin, rod and stinger on its own, around the point where it joins the body (a bat's
  wings, a cod's fins, a blaze's rods, a wolf's tail, the ender dragon's wings and tail);
- resourcepack/assets/minecraft/atlases/items.json: adds those textures to the item atlas, so item models can use them;
- models/skin/*: the morph skin (the mod's textures, copied into textures/skin) as a box and as a tendril segment, for
  the transformations, absorbing and tendrils players without the mod see as the mod draws them;
- paper-plugin/src/main/resources/morph-boxes.json: every model as boxes (middle, size, turn), for those effects;
- paper-plugin/src/main/resources/morph-heads.json: for the plugin, where each body's neck and passenger seat are,
  and how big each head is drawn, each body's torso and whole box (where borrowed parts go), where ears sit, and
  where each part joins its own body. The ender dragon has parts only (no head model, no neck).

A creature that has babies gets all of the above for its baby too, under <mob>_baby (head/wolf_baby,
part/wolf_baby/tail, "minecraft:wolf_baby"): 26.3 draws babies with models and textures of their own, in proportions
of their own.

The input is the client's dump: run the dev client with MORPHREBORN_DUMPHEADS=1 (or /morphclient dumpheads in a dev
client), which writes fabric-client/run/morph-heads.json.

Usage: py -3 -I tools/gen_head_pack.py [morph-heads.json]
"""
import json
import math
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DUMP = os.path.join(ROOT, "fabric-client", "run", "morph-heads.json")
PACK = os.path.join(ROOT, "resourcepack")
PLUGIN_DATA = os.path.join(ROOT, "paper-plugin", "src", "main", "resources", "morph-heads.json")
PLUGIN_BOXES = os.path.join(ROOT, "paper-plugin", "src", "main", "resources", "morph-boxes.json")
# Block-model elements must stay within -16..32; heads that reach further are drawn smaller in the model and the
# plugin makes up for it with the display's scale.
LIMIT = 23.9


def mobs():
    out = []
    for line in open(os.path.join(ROOT, "docs", "ABILITIES.md"), encoding="utf-8"):
        m = re.match(r"\| ([a-z][a-z ]*?) \|", line)
        if m and m.group(1) != "mob":
            out.append("minecraft:" + m.group(1).replace(" ", "_"))
    return out


def fit(elements):
    """The factor that brings every coordinate within LIMIT of the centre (8, 8, 8), and the elements scaled by it."""
    far = 0.0
    for e in elements:
        for key in ("from", "to"):
            far = max(far, *(abs(v - 8) for v in e[key]))
    k = 1.0 if far <= LIMIT else LIMIT / far
    if k == 1.0:
        return k, elements
    scaled = []
    for e in elements:
        e = json.loads(json.dumps(e))
        for key in ("from", "to"):
            e[key] = [round(8 + (v - 8) * k, 4) for v in e[key]]
        if "rotation" in e:
            e["rotation"]["origin"] = [round(8 + (v - 8) * k, 4) for v in e["rotation"]["origin"]]
        scaled.append(e)
    return k, scaled


def clamp_uv(elements):
    """Mob models may sample past their texture's edge (the game wraps around); item models refuse that."""
    for e in elements:
        for face in e["faces"].values():
            face["uv"] = [min(16.0, max(0.0, v)) for v in face["uv"]]


# A flat element's two faces, the face the game draws its first UV pair at for each, and the axis of the UV that runs
# the other way on the opposite face: FaceBakery puts east's first pair at max z and west's at min z, north's at max x
# and south's at min x (so u is mirrored), up's at min z and down's at max z (so v is).
FLAT = {0: ("east", "west", 0), 1: ("up", "down", 1), 2: ("south", "north", 0)}
# Pixels a flat element's backing faces sit behind its own faces (no z-fighting where both sides are painted)
BACKING = 0.01


def double_sided(elements):
    """Mobs are drawn without back-face culling, items with it. A flat element (a bat's or vex's wing, a fin, an ear)
    has two faces on one plane and the game shows each from both sides: often only one is painted (a vex's wing), so as
    an item it vanished from the other side. Each flat element gets backing faces: on each side, just behind its own
    face, the opposite face's texture, mapped to the same points (as the game shows it from behind)."""
    out = []
    for e in elements:
        out.append(e)
        size = [e["to"][i] - e["from"][i] for i in range(3)]
        axis = next((i for i in range(3) if abs(size[i]) < 1e-3), None)
        if axis is None:
            continue
        plus, minus, flip = FLAT[axis]
        c = e["from"][axis]
        for face, other, shift in ((plus, minus, -BACKING), (minus, plus, BACKING)):
            if other not in e["faces"]:
                continue
            uv = list(e["faces"][other]["uv"])
            if flip == 0:
                uv[0], uv[2] = uv[2], uv[0]
            else:
                uv[1], uv[3] = uv[3], uv[1]
            b = {"from": list(e["from"]), "to": list(e["to"])}
            b["from"][axis] = b["to"][axis] = round(c + shift, 4)
            if "rotation" in e:
                b["rotation"] = e["rotation"]
            b["faces"] = {face: {"uv": uv, "texture": "#t"}}
            out.append(b)
    return out


def write_model(models_dir, items_dir, kind, name, texture, elements):
    """name may be a path (part/<mob>/<part>): its folders are made."""
    model = {"textures": {"t": texture, "particle": texture}, "elements": double_sided(elements)}
    for d in (models_dir, items_dir):
        os.makedirs(os.path.dirname(os.path.join(d, name + ".json")), exist_ok=True)
    json.dump(model, open(os.path.join(models_dir, name + ".json"), "w", encoding="utf-8", newline="\n"), separators=(",", ":"))
    item = {"model": {"type": "minecraft:model", "model": "morphreborn:" + kind + "/" + name}}
    json.dump(item, open(os.path.join(items_dir, name + ".json"), "w", encoding="utf-8", newline="\n"), indent=1)


def turn(rotation):
    """An element's rotation as the game draws it (HeadDump writes them this way): about z, then y, then x."""
    rx, ry, rz = (math.radians(rotation.get(a, 0.0)) for a in ("x", "y", "z"))
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    # Rz * Ry * Rx
    return [[cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx],
            [sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx],
            [-sy, cy * sx, cy * cx]]


def quaternion(q):
    """A unit quaternion [x, y, z, w] as a matrix."""
    x, y, z, w = q
    return [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]


def corners(e):
    """An element's eight corners as drawn: turned by its rotation about its origin. Its from/to are the box before
    that turn, so for a turned element (a horse's head, tilted 30 degrees) they are not where it is drawn."""
    lo, hi = e["from"], e["to"]
    out = [[x, y, z] for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]
    if "rotation" not in e:
        return out
    o, m = e["rotation"]["origin"], turn(e["rotation"])
    return [[sum(m[i][j] * (p[j] - o[j]) for j in range(3)) + o[i] for i in range(3)] for p in out]


def drawn(e, undo=None):
    """An element's box {lo, hi} as drawn, or in a tilted head's own frame when {@code undo} (the inverse of the head's
    turn, about the pivot at (8, 8, 8)) is given."""
    points = corners(e)
    if undo is not None:
        points = [[sum(undo[i][j] * (p[j] - 8) for j in range(3)) + 8 for i in range(3)] for p in points]
    return [min(p[i] for p in points) for i in range(3)], [max(p[i] for p in points) for i in range(3)]


def blocks(box, scale):
    """Item pixels {lo, hi} (model point (8, 8, 8) at the origin) to blocks at the mob's real size."""
    return [[round((v - 8) / 16 * scale, 4) for v in corner] for corner in box]


def main():
    dump_path = sys.argv[1] if len(sys.argv) > 1 else DUMP
    dump = json.load(open(dump_path, encoding="utf-8"))
    dirs = {}
    # "tail" held the ears-and-tail animals' tails before 7.4.0; they are parts now, so the old folder is cleared
    for kind in ("head", "ears", "part", "tail"):
        dirs[kind] = (os.path.join(PACK, "assets", "morphreborn", "models", kind), os.path.join(PACK, "assets", "morphreborn", "items", kind))
        for d in dirs[kind]:
            if os.path.isdir(d):
                shutil.rmtree(d)
            if kind != "tail":
                os.makedirs(d)
    models_dir, items_dir = dirs["head"]
    textures = set()
    plugin = {}
    missing = []
    for mob in [m for grown in mobs() for m in (grown, grown + "_baby")]:
        head = dump.get(mob)
        if head is None:
            if not mob.endswith("_baby"):
                missing.append(mob)
            continue
        name = mob.split(":", 1)[1]
        if "elements" not in head:
            # parts only (the ender dragon): no head model, and no neck, so it is no body for a borrowed head yet
            if head.get("parts"):
                textures.add(head["texture"])
                plugin[mob] = {"height": head["height"], "scale": head["scale"], "lendsHead": False}
                for key in ("torso", "all"):
                    if key in head:
                        plugin[mob][key] = head[key]
                plugin[mob]["parts"] = write_parts(dirs["part"], name, head)
            else:
                missing.append(mob)
            continue
        raw = [{key: v for key, v in e.items() if key != "ear"} for e in head["elements"]]
        k, elements = fit(raw)
        clamp_uv(elements)
        texture = head["texture"]
        textures.add(texture)
        write_model(models_dir, items_dir, "head", name, texture, elements)
        # where the head meets its neck, in the model's blocks from its pivot: the middle of its bottom, at the back
        # (item space faces -z), or the middle for a whole body; the plugin lines these up instead of the pivots
        whole = bool(head.get("whole", False))
        # a body lent whole is measured by its big blocks, as the client mod does: a ghast's tentacles and a squid's
        # hang below its body, and lined up by their ends the body sat over the head instead of on it
        core = elements
        if not whole:
            # a hat's brim (villagers, witches: a flat sheet wider than the head) is drawn but doesn't decide where the
            # head meets the neck or how big it is; counted in, its overhang pushed a villager's head forward
            def brim(e):
                lo, hi = drawn(e)
                return hi[1] - lo[1] < 1.5 * k and min(hi[0] - lo[0], hi[2] - lo[2]) >= 12 * k
            core = [e for e in elements if not brim(e)] or elements
        if whole:
            volume = lambda e: (e["to"][0] - e["from"][0]) * (e["to"][1] - e["from"][1]) * (e["to"][2] - e["from"][2])
            biggest = max(volume(e) for e in elements)
            core = [e for e in elements if volume(e) >= biggest * 0.25]
        # every box here as drawn (a turned element's from/to are not: a horse's tilted head measured that way sat a block
        # below its neck)
        boxes = [drawn(e) for e in core]
        lo = [min(b[0][i] for b in boxes) for i in range(3)]
        hi = [max(b[1][i] for b in boxes) for i in range(3)]
        # the head's big blocks (a quarter of the biggest's volume or more), as the client mod measures a head
        # (HybridMorph.coreBox): not its ears, horns, snout or beard. Borrowed ears go on top of these, and the head
        # meets its neck at the middle of their bottom, at the back (HybridMorph.neckPoint): measured with everything, a
        # goat's beard put that 4 pixels low, a borrowed head on a goat sank into it, and a goat's head lent sat high
        volume = lambda e: (e["to"][0] - e["from"][0]) * (e["to"][1] - e["from"][1]) * (e["to"][2] - e["from"][2])
        biggest = max(volume(e) for e in core)
        big = [e for e in core if volume(e) >= biggest * 0.25]
        big_boxes = [drawn(e) for e in big]
        blo = [min(b[0][i] for b in big_boxes) for i in range(3)]
        bhi = [max(b[1][i] for b in big_boxes) for i in range(3)]
        neck_px = [(blo[0] + bhi[0]) / 2, blo[1], (blo[2] + bhi[2]) / 2 if whole else bhi[2]]
        anchor = [(v - 8) / 16 for v in neck_px]
        crown = [[round((blo[i] - neck_px[i]) / 16, 4) for i in range(3)], [round((bhi[i] - neck_px[i]) / 16, 4) for i in range(3)]]
        plugin[mob] = {"neck": head["neck"], "height": head["height"], "scale": round(head["scale"] / k, 4),
                       "whole": whole, "anchor": [round(v, 4) for v in anchor],
                       # the head's size (model blocks): a borrowed head smaller than the body's own is drawn big
                       # enough to cover it for players without the mod, whose disguise always draws the body's head
                       "size": [round((hi[i] - lo[i]) / 16, 4) for i in range(3)],
                       # its big blocks' box (model blocks from where it meets its neck; the face towards -z)
                       "crown": crown}
        if "tilt" in head and not whole:
            # a head tilted at rest (a horse's, 30 degrees): its turn (item space) and its big blocks' box in its own frame,
            # model blocks from its pivot; borrowed ears go on top of that box and turn with the head, as with the mod
            undo = [list(r) for r in zip(*quaternion(head["tilt"]))]
            own = [drawn(e, undo) for e in big]
            plugin[mob]["frame"] = {"tilt": head["tilt"],
                                    "crown": [[round((min(b[0][i] for b in own) - 8) / 16, 4) for i in range(3)],
                                              [round((max(b[1][i] for b in own) - 8) / 16, 4) for i in range(3)]]}
        for key in ("torso", "all"):
            # the torso borrowed parts are grafted onto and the whole body's box (item space, blocks from the feet,
            # real size): rods are spread over a body's whole height
            if key in head:
                plugin[mob][key] = head[key]
        plugin[mob]["lendsHead"] = bool(head.get("lendsHead", True))
        if "ears" in head:
            # an animal that lends its ears and tail, not its head: the ears as a model of their own (the tail is one of
            # its parts). Ear and core boxes are blocks from the head's pivot, in the head's own frame (the dump writes
            # the ears there too: a horse's, written as they sit on its tilted head, leaned on the head they went on).
            # "scale" is the display's scale at the mob's real size (the renderer's, over the model's shrink to fit)
            renderer = head["scale"]
            source = head["ears"].get("elements") or [e for e in head["elements"] if e.get("ear")]
            ears = [{key: v for key, v in e.items() if key != "ear"} for e in source]
            ke, ears = fit(ears)
            clamp_uv(ears)
            write_model(*dirs["ears"], "ears", name, texture, ears)
            plugin[mob]["ears"] = {"box": blocks(head["ears"]["box"], renderer), "core": blocks(head["ears"]["core"], renderer),
                                   "scale": round(renderer / ke, 4)}
        if head.get("parts"):
            plugin[mob]["parts"] = write_parts(dirs["part"], name, head)
    textures |= write_skin()
    # every model as boxes, for the morph-skin effects (transformations, absorbing): the plugin's own table
    boxes = {mob: {"height": entry["height"], "boxes": entry["boxes"]} for mob, entry in sorted(dump.items()) if entry.get("boxes")}
    json.dump(boxes, open(PLUGIN_BOXES, "w", encoding="utf-8", newline="\n"), separators=(",", ":"), sort_keys=True)
    atlas_dir = os.path.join(PACK, "assets", "minecraft", "atlases")
    os.makedirs(atlas_dir, exist_ok=True)
    atlas = {"sources": [{"type": "minecraft:single", "resource": t} for t in sorted(textures)]}
    json.dump(atlas, open(os.path.join(atlas_dir, "items.json"), "w", encoding="utf-8", newline="\n"), indent=1)
    json.dump(plugin, open(PLUGIN_DATA, "w", encoding="utf-8", newline="\n"), indent=1, sort_keys=True)
    babies = sorted(m.split(":", 1)[1][:-5] for m in plugin if m.endswith("_baby"))
    lending = sorted(m.split(":", 1)[1] for m in plugin if "ears" in plugin[m])
    parts = sorted(m.split(":", 1)[1] for m in plugin if not plugin[m]["lendsHead"] and "ears" not in plugin[m])
    tails = sorted(m.split(":", 1)[1] for m in plugin if plugin[m]["lendsHead"] and "parts" in plugin[m])
    count = sum(len(plugin[m].get("parts", [])) for m in plugin)
    print(len(plugin), "entries;", len(textures), "textures;", count, "parts;", "ears and tails:", lending, "parts only:", parts,
          "head and parts:", tails, "no data for:", missing, ";", len(boxes), "box lists;", len(babies), "babies:", babies)


def write_skin():
    """The morph skin for players without the mod, as the mod draws its effects: the textures (the mod's own, black and
    red) and item models of a box (four of them, each showing a different patch of the skin, so neighbouring boxes
    don't look alike) and of a tendril segment (from the display's origin to one block forward, after the display's own
    half turn). Returns the textures for the atlas."""
    src = os.path.join(ROOT, "fabric-client", "src", "main", "resources", "assets", "morphreborn", "textures", "skin")
    dst = os.path.join(PACK, "assets", "morphreborn", "textures", "skin")
    os.makedirs(dst, exist_ok=True)
    models_dir = os.path.join(PACK, "assets", "morphreborn", "models", "skin")
    items_dir = os.path.join(PACK, "assets", "morphreborn", "items", "skin")
    for d in (models_dir, items_dir):
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d)
    faces = ("north", "east", "south", "west", "up", "down")
    textures = set()
    for colour, name in (("", "morphskin"), ("red_", "morphskin_red")):
        shutil.copyfile(os.path.join(src, name + ".png"), os.path.join(dst, name + ".png"))
        texture = "morphreborn:skin/" + name
        textures.add(texture)
        for k in range(4):
            box = {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {}}
            for f, face in enumerate(faces):
                u, v = (k * 5 + f * 3) % 13, (k * 7 + f * 5) % 13
                box["faces"][face] = {"uv": [u, v, u + 3, v + 3], "texture": "#t"}
            write_model(models_dir, items_dir, "skin", colour + "box_" + str(k), texture, [box])
        stick = {"from": [0, 0, -8], "to": [16, 16, 8], "faces": {}}
        for f, face in enumerate(faces):
            u = (f * 2) % 14
            stick["faces"][face] = {"uv": [u, 0, u + 2, 16] if face not in ("north", "south") else [u, 0, u + 2, 2], "texture": "#t"}
        write_model(models_dir, items_dir, "skin", colour + "stick", texture, [stick])
    return textures


def write_parts(dirs, name, head):
    """Each part as a model of its own (part/<mob>/<part>), and its numbers for the plugin: where it joins its own body
    (item space, blocks from the feet, real size), its box from there, the display's scale at the mob's real size, and
    any flap."""
    renderer = head["scale"]
    out = []
    for part in head["parts"]:
        k, elements = fit(part["elements"])
        clamp_uv(elements)
        write_model(*dirs, "part", name + "/" + part["name"], head["texture"], elements)
        entry = {"name": part["name"], "kind": part["kind"], "touch": part["touch"], "joint": part["joint"],
                 "box": blocks(part["box"], renderer), "scale": round(renderer / k, 4)}
        if "flap" in part:
            # the part's turn (item space, about where it joins) from the pose it is modelled in to the other end of its
            # creature's flap, and the ticks a stroke takes: the plugin swings it back and forth between them
            entry["flap"] = part["flap"]
            entry["flapTicks"] = part["flapTicks"]
        out.append(entry)
    return out


if __name__ == "__main__":
    main()
