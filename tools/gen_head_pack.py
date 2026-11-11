"""Builds the multimorph head models from the client's head dump.

Players without the client mod see a multimorph as the main body (Lib's Disguises) with the borrowed mob's head on
it: an item display showing that head as an item model from RE:Morph's resource pack. This script writes:

- resourcepack/assets/morphreborn/models/head/<mob>.json and items/head/<mob>.json: one head model per mob, made
  from the game's own model geometry and textures (nothing of Mojang's is copied into the pack; the models only
  point at the game's textures);
- resourcepack/assets/minecraft/atlases/items.json: adds those textures to the item atlas, so item models can use them;
- paper-plugin/src/main/resources/morph-heads.json: for the plugin, where each body's neck and passenger seat are,
  and how big each head is drawn.

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


def main():
    dump_path = sys.argv[1] if len(sys.argv) > 1 else DUMP
    dump = json.load(open(dump_path, encoding="utf-8"))
    models_dir = os.path.join(PACK, "assets", "morphreborn", "models", "head")
    items_dir = os.path.join(PACK, "assets", "morphreborn", "items", "head")
    for d in (models_dir, items_dir):
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d)
    textures = set()
    plugin = {}
    missing = []
    for mob in mobs():
        head = dump.get(mob)
        if head is None:
            missing.append(mob)
            continue
        name = mob.split(":", 1)[1]
        k, elements = fit(head["elements"])
        # Mob models may sample past their texture's edge (the game wraps around); item models refuse that.
        for e in elements:
            for face in e["faces"].values():
                face["uv"] = [min(16.0, max(0.0, v)) for v in face["uv"]]
        texture = head["texture"]
        textures.add(texture)
        model = {"textures": {"t": texture, "particle": texture}, "elements": elements}
        json.dump(model, open(os.path.join(models_dir, name + ".json"), "w", encoding="utf-8", newline="\n"), separators=(",", ":"))
        item = {"model": {"type": "minecraft:model", "model": "morphreborn:head/" + name}}
        json.dump(item, open(os.path.join(items_dir, name + ".json"), "w", encoding="utf-8", newline="\n"), indent=1)
        # where the head meets its neck, in the model's blocks from its pivot: the middle of its bottom, at the back
        # (item space faces -z), or the middle for a whole body; the plugin lines these up instead of the pivots
        whole = bool(head.get("whole", False))
        # a body lent whole is measured by its big blocks, as the client mod does: a ghast's tentacles and a squid's
        # hang below its body, and lined up by their ends the body sat over the head instead of on it
        core = elements
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
    atlas_dir = os.path.join(PACK, "assets", "minecraft", "atlases")
    os.makedirs(atlas_dir, exist_ok=True)
    atlas = {"sources": [{"type": "minecraft:single", "resource": t} for t in sorted(textures)]}
    json.dump(atlas, open(os.path.join(atlas_dir, "items.json"), "w", encoding="utf-8", newline="\n"), indent=1)
    json.dump(plugin, open(PLUGIN_DATA, "w", encoding="utf-8", newline="\n"), indent=1, sort_keys=True)
    print(len(plugin), "heads;", len(textures), "textures;", "no head for:", missing)


if __name__ == "__main__":
    main()
