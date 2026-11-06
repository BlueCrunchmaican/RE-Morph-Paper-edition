"""Generates docs/ABILITIES.md from paper-plugin/src/main/resources/abilities.yml.

Usage: py -3 -I tools/gen_abilities_doc.py
Kept dependency-free (no PyYAML): it only understands the shapes abilities.yml uses.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
YML = (ROOT / 'paper-plugin/src/main/resources/abilities.yml').read_text(encoding='utf-8')

# Every living mob in Minecraft 26.3 (EntityTypes, non-MISC categories plus the golems and villager).
MOBS = """allay armadillo axolotl bat bee blaze bogged breeze camel camel_husk cat cave_spider chicken cod copper_golem cow creaking
creeper dolphin donkey drowned elder_guardian ender_dragon enderman endermite evoker fox frog ghast giant glow_squid goat guardian
happy_ghast hoglin horse husk illusioner iron_golem llama magma_cube mooshroom mule nautilus ocelot panda parched parrot phantom pig
piglin piglin_brute pillager polar_bear pufferfish rabbit ravager salmon sheep shulker silverfish skeleton skeleton_horse slime sniffer
snow_golem spider squid stray strider sulfur_cube tadpole trader_llama tropical_fish turtle vex villager vindicator wandering_trader
warden witch wither wither_skeleton wolf zoglin zombie zombie_horse zombie_nautilus zombie_villager zombified_piglin""".split()

PASSIVE_NAMES = {
    'flight': 'Flight', 'slow-fall': 'Slow fall', 'fall-immunity': 'No fall damage', 'fire-immunity': 'Fire immune',
    'water-breathing': 'Water breathing', 'water-only-breathing': 'Suffocates on land', 'swim-boost': 'Fast swimmer', 'sink': 'Sinks',
    'climb': 'Climbs walls', 'sunburn': 'Burns in daylight', 'water-damage': 'Hurt by water', 'milkable': 'Can be milked',
    'snow-trail': 'Snow trail', 'heat-sensitive': 'Melts in heat', 'lava-walk': 'Floats on lava', 'berry-bush-immunity': 'Berry-bush proof',
    'freeze-immunity': 'Powder-snow proof', 'deflect-projectiles': 'Deflects projectiles',
    'teleport-from-projectiles': 'Teleports away from projectiles', 'undead': 'Undead (Healing hurts, Harming heals)',
    'web-walk': 'Walks through cobwebs', 'berry-walk': 'Walks through berry bushes', 'powder-snow-walk': 'Walks on powder snow',
    'lava-stand': 'Stands on lava (client mod)', 'freeze-when-watched': "Can't move while watched",
    'teleport-out-of-water': 'Teleports out of water', 'hurt-by-snowballs': 'Hurt by snowballs',
}
ACTIVE_NAMES = {
    'explode': 'Explode (with fuse)', 'teleport': 'Teleport', 'large-fireball': 'Fireball', 'small-fireball': 'Small fireball',
    'wither-skull': 'Wither skull', 'dragon-fireball': 'Dragon fireball', 'snowball': 'Snowball', 'llama-spit': 'Spit',
    'evoker-fangs': 'Evoker fangs', 'sonic-boom': 'Sonic boom', 'shulker-bullet': 'Shulker bullet', 'wind-charge': 'Wind charge',
    'arrow': 'Shoot arrow', 'trident': 'Throw trident', 'ram': 'Ram', 'splash-potion': 'Throw potion', 'toss': 'Toss',
    'guardian-beam': 'Guardian beam', 'lay-egg': 'Lay egg', 'graze': 'Graze', 'web': 'Shoot web', 'dash': 'Dash', 'ink': 'Ink cloud',
    'vanish': 'Turn invisible', 'tongue': 'Tongue (eat slimes)', 'sniff': 'Sniff seeds', 'roll-up': 'Roll up', 'puff': 'Puff up',
    'mimic': 'Mimic sounds', 'swoop': 'Swoop', 'magnet': 'Collect items', 'play-dead': 'Play dead', 'scare': 'Shove',
    'witch-potions': 'Throw or drink the chosen potion', 'illusion': 'Blindness spell or vanish', 'sting': 'Sting (dies afterwards)',
    'turtle-eggs': 'Lay eggs on sand', 'rear-up': 'Rear up and roar', 'infest': 'Infest stone', 'roll': 'Roll',
    'hop': 'Hop forward', 'perch': 'Hang from ceilings',
    'sort': 'Sort a copper chest',
}


def norm(t):
    return t.strip().replace('minecraft:', '')


def section(text, name):
    m = re.search(r'\n' + name + r':\n(.*?)(?=\n[a-z][a-z-]*:|\Z)', text, re.S)
    return m.group(1) if m else ''


passive = section(YML, 'passive')
active = section(YML, 'active')
traits = {m: [] for m in MOBS}
actives = {m: [] for m in MOBS}

for key, label in PASSIVE_NAMES.items():
    m = re.search(r'\n  ' + key + r':\s*\[([^\]]*)\]', '\n' + passive) or re.search(r'\n  ' + key + r':\n    types: \[([^\]]*)\]', '\n' + passive)
    if m:
        for t in m.group(1).split(','):
            if norm(t) in traits:
                traits[norm(t)].append(label)

for block, label in (('effects', None), ('attack-effects', 'hit: '), ('retaliate-effects', 'when hit: '), ('effect-immunity', 'immune: ')):
    b = re.search(r'\n  ' + block + r':\n((?:    .*\n)+)', '\n' + passive + '\n')
    if not b:
        continue
    for line in b.group(1).splitlines():
        mm = re.match(r'\s+([a-z_]+):\s*\[([^\]]*)\]', line)
        if mm and mm.group(1) in traits:
            effects = ', '.join(e.split(':')[0].strip().replace('_', ' ') for e in mm.group(2).split(','))
            traits[mm.group(1)].append((label or '') + effects)

for block, label in (('intimidate', 'Scares off '), ('fear', 'Makes flee: ')):
    b = re.search(r'\n  ' + block + r':\n((?:    .*\n)+)', '\n' + passive + '\n')
    if b:
        for line in b.group(1).splitlines():
            mm = re.match(r'\s+([a-z_]+):\s*\[([^\]]*)\]', line)
            if mm and mm.group(1) in traits:
                traits[mm.group(1)].append(label + ', '.join(x.strip().replace('_', ' ') for x in mm.group(2).split(',')))

leaps = re.search(r'\n  leap:\n((?:    .*\n)+)', '\n' + passive + '\n')
if leaps:
    for line in leaps.group(1).splitlines():
        mm = re.match(r'\s+([a-z_]+):\s*\{', line)
        if mm:
            traits[mm.group(1)].append('Charged jump (hold sneak, then jump)' if 'charged: true' in line else 'Leap (sneak + jump)')

for block, label in (('dry-out', 'Dries out after {}s out of water'), ('breath-hold', 'Holds breath {}s'), ('magic-resistance', 'Resists magic ({})'),
                     ('land-speed', 'Slow on land ({}x walking speed)')):
    b = re.search(r'\n  ' + block + r':\n((?:    .*\n)+)', '\n' + passive + '\n')
    if b:
        for line in b.group(1).splitlines():
            mm = re.match(r'\s+([a-z_]+):\s*([\d.]+)', line)
            if mm and mm.group(1) in traits:
                traits[mm.group(1)].append(label.format(mm.group(2)))

auras = re.search(r'\n  auras:\n((?:    .*\n)+)', '\n' + passive + '\n')
if auras:
    for line in auras.group(1).splitlines():
        mm = re.match(r'\s+([a-z_]+):\s*\{effect:\s*([a-z_]+)', line)
        if mm and mm.group(1) in traits:
            traits[mm.group(1)].append('Aura: ' + mm.group(2).replace('_', ' '))

for m in re.finditer(r'\n  ([a-z-]+):\n    types: \[([^\]]*)\]', '\n' + active):
    name = m.group(1)
    label = 'Carry blocks (right-click)' if name == 'enderman-carry' else ACTIVE_NAMES.get(name, name)
    for t in m.group(2).split(','):
        if norm(t) in actives:
            actives[norm(t)].append(label)

if 'enderman' in traits:
    traits['enderman'].append('Holds its carried block')
traits['creeper'].append('Swells before exploding')
traits['sulfur_cube'].append('Only explodes with an explosive block inside')

rows = []
for mob in MOBS:
    t = '; '.join(traits[mob]) or '-'
    a = '; '.join(actives[mob]) or '-'
    rows.append(f'| {mob.replace("_", " ")} | {t} | {a} |')

covered = sum(1 for m in MOBS if traits[m] or actives[m])
out = f"""# Morph abilities by mob

Generated from `paper-plugin/src/main/resources/abilities.yml` by `tools/gen_abilities_doc.py`. Edit the YAML, not this file.

**Traits** are always on while you are fully morphed. **Abilities** use the *Morph ability* key (Fabric mod, default **R**)
or **Sneak + F** for players without the mod. Every ability has a configurable cooldown and needs `morphreborn.ability`.
Morphs also copy the mob's attributes (max health capped at 20, speed capped at the player's, armour, attack damage,
step height), see `attributes` in `config.yml`.

{covered} of {len(MOBS)} mobs in Minecraft 26.3 have at least one trait or ability.

| Mob | Traits | Ability |
|---|---|---|
""" + '\n'.join(rows) + '\n'
(ROOT / 'docs/ABILITIES.md').write_text(out, encoding='utf-8')
print('wrote', covered, '/', len(MOBS))
