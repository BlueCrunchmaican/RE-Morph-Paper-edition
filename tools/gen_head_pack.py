"""Builds the multimorph head models from the client's head dump.

Players without the client mod see a multimorph as the main body (Lib's Disguises) with the borrowed mob's head on
it: an item display showing that head as an item model from RE:Morph's resource pack. This script writes:

- resourcepack/assets/morphreborn/models/head/<mob>.json and items/head/<mob>.json: one head model per mob, made
  from the game's own model geometry and textures (nothing of Mojang's is copied into the pack; the models only
  point at the game's textures);
- models/ears/<mob>.json and models/tail/<mob>.json (and their items): for the animals that lend their ears and tail
  instead of their head (wolf, fox, cat...), the ears alone and the tail alone;
- resourcepack/assets/minecraft/atlases/items.json: adds those textures to the item atlas, so item models can use them;
- paper-plugin/src/main/resources/morph-heads.json: for the plugin, where each body's neck and passenger seat are,
  and how big each head is drawn, each body's torso (where a borrowed tail goes), and where ears and tails sit.

The input is the client's dump: run the dev client with MORPHREBORN_DUMPHEADS=1 (or /morphclient dumpheads in a dev
client), which writes fabric-client/run/morph-heads.json.

Usage: py -3 -I tools/gen_head_pack.py [morph-heads.json]
"""
import json
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DUMP = os.path.join(ROOT, "fabric-client", "run", "morph-heads.json")
PACK = os.path.join(ROOT, "resourcepack")
PLUGIN_DATA = os.path.join(ROOT, "paper-plugin", "src", "main", "resources", "morph-heads.json")
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


def write_model(models_dir, items_dir, kind, name, texture, elements):
    model = {"textures": {"t": texture, "particle": texture}, "elements": elements}
    json.dump(model, open(os.path.join(models_dir, name + ".json"), "w", encoding="utf-8", newline="\n"), separators=(",", ":"))
    item = {"model": {"type": "minecraft:model", "model": "morphreborn:" + kind + "/" + name}}
    json.dump(item, open(os.path.join(items_dir, name + ".json"), "w", encoding="utf-8", newline="\n"), indent=1)


def blocks(box, scale):
    """Item pixels {lo, hi} (model point (8, 8, 8) at the origin) to blocks at the mob's real size."""
    return [[round((v - 8) / 16 * scale, 4) for v in corner] for corner in box]


def main():
    dump_path = sys.argv[1] if len(sys.argv) > 1 else DUMP
    dump = json.load(open(dump_path, encoding="utf-8"))
    dirs = {}
    for kind in ("head", "ears", "tail"):
        dirs[kind] = (os.path.join(PACK, "assets", "morphreborn", "models", kind), os.path.join(PACK, "assets", "morphreborn", "items", kind))
        for d in dirs[kind]:
            if os.path.isdir(d):
                shutil.rmtree(d)
            os.makedirs(d)
    models_dir, items_dir = dirs["head"]
    textures = set()
    plugin = {}
    missing = []
    for mob in mobs():
        head = dump.get(mob)
        if head is None:
            missing.append(mob)
            continue
        name = mob.split(":", 1)[1]
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
            brim = lambda e: e["to"][1] - e["from"][1] < 1.5 * k and min(e["to"][0] - e["from"][0], e["to"][2] - e["from"][2]) >= 12 * k
            core = [e for e in elements if not brim(e)] or elements
        if whole:
            volume = lambda e: (e["to"][0] - e["from"][0]) * (e["to"][1] - e["from"][1]) * (e["to"][2] - e["from"][2])
            biggest = max(volume(e) for e in elements)
            core = [e for e in elements if volume(e) >= biggest * 0.25]
        lo = [min(e["from"][i] for e in core) for i in range(3)]
        hi = [max(e["to"][i] for e in core) for i in range(3)]
        anchor = [((lo[0] + hi[0]) / 2 - 8) / 16, (lo[1] - 8) / 16, (((lo[2] + hi[2]) / 2 if whole else hi[2]) - 8) / 16]
        plugin[mob] = {"neck": head["neck"], "height": head["height"], "scale": round(head["scale"] / k, 4),
                       "whole": whole, "anchor": [round(v, 4) for v in anchor],
                       # the head's size (model blocks): a borrowed head smaller than the body's own is drawn big
                       # enough to cover it for players without the mod, whose disguise always draws the body's head
                       "size": [round((hi[i] - lo[i]) / 16, 4) for i in range(3)]}
        if "torso" in head:
            # the torso a borrowed tail is grafted onto (item space, blocks from the feet, real size)
            plugin[mob]["torso"] = head["torso"]
        if "ears" in head and "tail" in head:
            # an animal that lends its ears and tail, not its head: both as models of their own. Ear and core boxes are
            # blocks from the head's pivot; the tail's from where it joins the body. "scale" is the display's scale at the
            # mob's real size (the renderer's, over the model's shrink to fit)
            renderer = head["scale"]
            ears = [{key: v for key, v in e.items() if key != "ear"} for e in head["elements"] if e.get("ear")]
            ke, ears = fit(ears)
            clamp_uv(ears)
            write_model(*dirs["ears"], "ears", name, texture, ears)
            tk, tail = fit(head["tail"]["elements"])
            clamp_uv(tail)
            write_model(*dirs["tail"], "tail", name, texture, tail)
            plugin[mob]["ears"] = {"box": blocks(head["ears"]["box"], renderer), "core": blocks(head["ears"]["core"], renderer),
                                   "scale": round(renderer / ke, 4)}
            plugin[mob]["tail"] = {"joint": head["tail"]["joint"], "box": blocks(head["tail"]["box"], renderer), "scale": round(renderer / tk, 4)}
    atlas_dir = os.path.join(PACK, "assets", "minecraft", "atlases")
    os.makedirs(atlas_dir, exist_ok=True)
    atlas = {"sources": [{"type": "minecraft:single", "resource": t} for t in sorted(textures)]}
    json.dump(atlas, open(os.path.join(atlas_dir, "items.json"), "w", encoding="utf-8", newline="\n"), indent=1)
    json.dump(plugin, open(PLUGIN_DATA, "w", encoding="utf-8", newline="\n"), indent=1, sort_keys=True)
    lending = sorted(m.split(":", 1)[1] for m in plugin if "ears" in plugin[m])
    print(len(plugin), "heads;", len(textures), "textures;", "ears and tails:", lending, "no head for:", missing)


if __name__ == "__main__":
    main()
