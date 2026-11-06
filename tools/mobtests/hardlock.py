"""Automatic-trait checks (sunburn, breathing, water/fire/fall/freeze) on the unmodded tester, over RCON."""
import json, re, socket, struct, sys, time
from pathlib import Path

V = 'VanillaViewer'
X, Y, Z = 300.5, -60, 300.5


class Rcon:
    def __init__(self):
        self.s = socket.create_connection(('127.0.0.1', 25598), timeout=20)
        self.cmd('morphtest', 3)

    def cmd(self, body, kind=2):
        data = struct.pack('<ii', 7, kind) + body.encode() + b'\0\0'
        self.s.sendall(struct.pack('<i', len(data)) + data)
        n = struct.unpack('<i', self._read(4))[0]
        return re.sub('§.', '', self._read(n)[8:-2].decode('utf-8', 'replace'))

    def _read(self, n):
        buf = b''
        while len(buf) < n:
            buf += self.s.recv(n - len(buf))
        return buf


r = Rcon()


def val(path):
    out = r.cmd(f'data get entity {V} {path}')
    m = re.search(r'data: (-?[\d.]+)', out)
    return float(m.group(1)) if m else None


def morph(mob):
    r.cmd(f'morph force {V} {mob}')
    for _ in range(40):
        time.sleep(0.25)
        info = r.cmd(f'morph info {V}')
        if f'form: minecraft:{mob}' in info and 'transforming' not in info:
            return True
    return False


def reset(mob, x=X, y=Y, z=Z):
    r.cmd(f'fill {x - 3:.0f} {y - 1} {z - 3:.0f} {x + 3:.0f} {y + 20} {z + 3:.0f} minecraft:air')
    r.cmd(f'fill {x - 3:.0f} {y - 1} {z - 3:.0f} {x + 3:.0f} {y - 1} {z + 3:.0f} minecraft:stone')
    r.cmd(f'tp {V} {x} {y} {z}')
    r.cmd(f'item replace entity {V} armor.head with minecraft:air')
    r.cmd(f'effect clear {V}')
    r.cmd(f'effect give {V} minecraft:instant_health 1 10 true')
    r.cmd(f'effect give {V} minecraft:saturation 1 10 true')
    time.sleep(0.6)
    ok = morph(mob)
    r.cmd(f'effect give {V} minecraft:instant_health 1 10 true')
    time.sleep(0.6)
    return ok


results = []


def record(name, ok, detail):
    row = {'test': name, 'ok': ok, 'detail': detail}
    results.append(row)
    print(json.dumps(row), flush=True)


def health():
    return val('Health')


r.cmd(f'gamemode survival {V}')
r.cmd('weather clear')

# Sunburn: burner without helmet burns, burner with helmet doesn't, non-burner doesn't.
r.cmd('time set noon')
for mob, helmet, expect in [('zombie', False, True), ('zombie', True, False), ('husk', False, False), ('phantom', False, True),
                            ('zombified_piglin', False, False)]:
    reset(mob)
    if helmet:
        r.cmd(f'item replace entity {V} armor.head with minecraft:leather_helmet')
    time.sleep(2.5)
    fire = val('Fire') or 0
    record(f'sunburn {mob}{" +helmet" if helmet else ""}', (fire > 0) == expect, f'Fire={fire} (expected {"burning" if expect else "not burning"})')
r.cmd('time set midnight')
time.sleep(0.3)

# Fish out of water lose air; land mob keeps it.
for mob, expect_drop in [('cod', True), ('tadpole', True), ('pig', False), ('axolotl', True), ('dolphin', True), ('nautilus', True)]:
    reset(mob)
    a0 = val('Air')
    time.sleep(3)
    a1 = val('Air')
    h = health()
    record(f'on land {mob}', (a1 is not None and a0 is not None and a1 < a0) == expect_drop,
           f'Air {a0} -> {a1} (vanilla {"dries out/suffocates" if expect_drop else "fine"}), health {h}')

# Underwater: water breathers keep air, others lose it.
for mob, expect_keep in [('drowned', True), ('cod', True), ('zombie', True), ('pig', False), ('turtle', True)]:
    reset(mob)
    r.cmd(f'fill {X - 2:.0f} {Y} {Z - 2:.0f} {X + 2:.0f} {Y + 4} {Z + 2:.0f} minecraft:water')
    time.sleep(0.4)
    a0 = val('Air')
    time.sleep(3)
    a1 = val('Air')
    record(f'underwater {mob}', (a1 == 300) == expect_keep, f'Air {a0} -> {a1}')
    r.cmd(f'fill {X - 2:.0f} {Y} {Z - 2:.0f} {X + 2:.0f} {Y + 4} {Z + 2:.0f} minecraft:air')

# Water hurts enderman, blaze, snow golem, strider.
for mob, expect_hurt in [('enderman', True), ('blaze', True), ('snow_golem', True), ('strider', True), ('pig', False)]:
    reset(mob)
    h0 = health()
    r.cmd(f'setblock {X:.0f} {Y} {Z:.0f} minecraft:water')
    time.sleep(2.2)
    h1 = health()
    r.cmd(f'setblock {X:.0f} {Y} {Z:.0f} minecraft:air')
    record(f'water hurts {mob}', (h1 is not None and h0 is not None and h1 < h0) == expect_hurt, f'health {h0} -> {h1}')

# Lava: fire immune mobs take nothing.
for mob, expect_safe in [('blaze', True), ('strider', True), ('magma_cube', True), ('pig', False)]:
    reset(mob)
    h0 = health()
    r.cmd(f'setblock {X:.0f} {Y} {Z:.0f} minecraft:lava')
    time.sleep(2)
    h1 = health()
    y1 = val('Pos[1]')
    r.cmd(f'fill {X - 3:.0f} {Y} {Z - 3:.0f} {X + 3:.0f} {Y + 2} {Z + 3:.0f} minecraft:air')
    record(f'lava {mob}', (h1 == h0) == expect_safe, f'health {h0} -> {h1}, y {y1}')

# Falls from 12 blocks.
for mob, expect_safe in [('cat', True), ('chicken', True), ('slime', True), ('frog', True), ('pig', False), ('wolf', False)]:
    reset(mob)
    h0 = health()
    r.cmd(f'tp {V} {X} {Y + 12} {Z}')
    time.sleep(2.5)
    h1 = health()
    record(f'12-block fall {mob}', (h1 == h0) == expect_safe, f'health {h0} -> {h1}')

# Sinking heavy mobs, floating striders in lava.
for mob in ['iron_golem', 'pig']:
    reset(mob)
    r.cmd(f'fill {X - 2:.0f} {Y} {Z - 2:.0f} {X + 2:.0f} {Y + 8} {Z + 2:.0f} minecraft:water')
    r.cmd(f'tp {V} {X} {Y + 6} {Z}')
    time.sleep(3)
    y1 = val('Pos[1]')
    record(f'in deep water {mob}', True, f'y after 3s: {y1} (bottom is {Y})')
    r.cmd(f'fill {X - 2:.0f} {Y} {Z - 2:.0f} {X + 2:.0f} {Y + 8} {Z + 2:.0f} minecraft:air')

# Effect immunity: undead ignore poison; spiders ignore poison; pig doesn't.
for mob, expect_block in [('zombie', True), ('spider', True), ('pig', False)]:
    reset(mob)
    r.cmd(f'effect give {V} minecraft:poison 5 0')
    time.sleep(0.3)
    eff = r.cmd(f'data get entity {V} active_effects')
    has = 'poison' in eff
    record(f'poison on {mob}', (not has) == expect_block, f'poison applied: {has}')

# Undead and instant health/harm (vanilla undead are hurt by healing and healed by harming).
reset('zombie')
r.cmd(f'effect give {V} minecraft:instant_damage 1 0')
time.sleep(0.3)
h = health()
r.cmd(f'effect give {V} minecraft:instant_health 1 10 true')
record('zombie + instant damage', h is not None and h >= 20, f'health after harming potion: {h} (vanilla zombie would heal)')

# Powder snow freezing: stray immune, pig not (needs ~7s in snow before damage).
for mob, expect_safe in [('stray', True), ('pig', False)]:
    reset(mob)
    h0 = health()
    r.cmd(f'fill {X:.0f} {Y} {Z:.0f} {X:.0f} {Y + 2} {Z:.0f} minecraft:powder_snow')
    time.sleep(9.5)
    h1 = health()
    r.cmd(f'fill {X:.0f} {Y} {Z:.0f} {X:.0f} {Y + 2} {Z:.0f} minecraft:air')
    record(f'powder snow {mob}', (h1 == h0) == expect_safe, f'health {h0} -> {h1}')

# Flight is granted in survival for flyers.
for mob in ['bat', 'bee', 'ghast', 'happy_ghast', 'phantom', 'allay', 'parrot', 'vex', 'blaze', 'breeze']:
    reset(mob)
    fly = r.cmd(f'data get entity {V} abilities.mayfly')
    record(f'can fly {mob}', None, fly.split('data:')[-1].strip())

r.cmd(f'morph force {V} off')
(Path(__file__).parent / 'hardlock_results.json').write_text(json.dumps(results, indent=1))
