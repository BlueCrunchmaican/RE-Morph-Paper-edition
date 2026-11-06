"""Morph-by-morph ability harness over RCON. Usage: py -3 -I mobtest.py <morph-port root> [mob ...]"""
import json, re, socket, struct, sys, time
from pathlib import Path

ROOT = Path(sys.argv[1])
ONLY = sys.argv[2:]
T = 'MorphTester1'
X, Y, Z = 200.5, -60, 200.5


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
yml = (ROOT / 'paper-plugin/src/main/resources/abilities.yml').read_text(encoding='utf-8')
active_part = yml.split('\nactive:\n')[1].split('\ntargeting:')[0]
ACTIVE, COOLDOWN = {}, {}
for m in re.finditer(r'\n  ([a-z-]+):\n    types: \[([^\]]*)\]\n(?:    cooldown-seconds: ([\d.]+))?', '\n' + active_part):
    COOLDOWN[m.group(1)] = float(m.group(3) or 0)
    for t in m.group(2).split(','):
        ACTIVE[t.strip()] = m.group(1)
MOBS = re.search(r'MOBS = """(.*?)"""', (ROOT / 'tools/gen_abilities_doc.py').read_text(encoding='utf-8'), re.S).group(1).split()
if ONLY:
    MOBS = ONLY

PROJECTILE = {'large-fireball': 'fireball', 'small-fireball': 'small_fireball', 'wither-skull': 'wither_skull',
              'dragon-fireball': 'dragon_fireball', 'snowball': 'snowball', 'llama-spit': 'llama_spit',
              'shulker-bullet': 'shulker_bullet', 'wind-charge': 'wind_charge', 'arrow': 'arrow', 'trident': 'trident',
              'witch-potions': 'splash_potion', 'evoker-fangs': 'evoker_fangs'}
MOVE = {'dash', 'ram', 'swoop', 'roll'}
TARGETED = {'toss', 'scare', 'tongue', 'guardian-beam', 'sonic-boom'}
SELF_EFFECT = {'vanish': 'invisibility', 'roll-up': 'resistance', 'play-dead': 'regeneration'}
AREA_EFFECT = {'ink': 'blindness', 'puff': 'poison'}
last_used = {}

log_path = ROOT / 'test-server/server.log'


def log_size():
    return log_path.stat().st_size


def new_errors(since):
    with open(log_path, 'rb') as f:
        f.seek(since)
        text = f.read().decode('utf-8', 'replace')
    lines = [l for l in text.splitlines() if ('ERROR' in l or 'Exception' in l or 'WARN]: [RE-Morph' in l) and 'oshi' not in l and 'Perflib' not in l]
    return lines[:3]


def pos():
    out = r.cmd(f'data get entity {T} Pos')
    return [float(v) for v in re.findall(r'(-?[\d.]+)d', out)]


def num(cmd):
    out = r.cmd(cmd)
    m = re.search(r'is (-?\d+(?:\.\d+)?)', out)
    return float(m.group(1)) if m else None


def count(selector):
    out = r.cmd(f'execute at {T} if entity {selector}')
    m = re.search(r'Count: (\d+)', out)
    return int(m.group(1)) if m else 0


def dummy(health=1000):
    r.cmd('kill @e[tag=dummy]')
    r.cmd(f'summon minecraft:pig {X} {Y} {Z + 2} {{NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["dummy"],'
          f'attributes:[{{id:"minecraft:max_health",base:{health}}}],Health:{health}f}}')


def dummy_health():
    out = r.cmd('data get entity @e[tag=dummy,limit=1] Health')
    m = re.search(r'(-?[\d.]+)f', out)
    return float(m.group(1)) if m else None


def effects(target):
    out = r.cmd(f'data get entity {target} active_effects')
    return sorted(set(re.findall(r'id: "minecraft:([a-z_]+)"', out)))


def place(yaw, pitch):
    r.cmd(f'tp {T} {X} {Y} {Z} {yaw} {pitch}')
    time.sleep(0.15)


def place_facing_dummy():
    r.cmd(f'tp {T} {X} {Y} {Z} facing entity @e[tag=dummy,limit=1] eyes')
    time.sleep(0.15)


def wait_cooldown(ability):
    ready = last_used.get(ability, 0) + COOLDOWN.get(ability, 0) + 0.2
    if time.time() < ready:
        time.sleep(ready - time.time())


def trigger(ability):
    wait_cooldown(ability)
    r.cmd(f'morph ability {T}')
    last_used[ability] = time.time()


def reset_arena():
    r.cmd(f'fill {X - 8:.0f} {Y} {Z - 10:.0f} {X + 8:.0f} {Y + 8} {Z + 8:.0f} minecraft:air')
    r.cmd(f'fill {X - 8:.0f} {Y - 1} {Z - 10:.0f} {X + 8:.0f} {Y - 1} {Z + 8:.0f} minecraft:grass_block')
    r.cmd(f'kill @e[type=!player,distance=..40,x={X},y={Y},z={Z}]')


def test_ability(mob, ability):
    if ability in PROJECTILE:
        place(180, -15)
        trigger(ability)
        time.sleep(0.1)
        n = count(f'@e[type=minecraft:{PROJECTILE[ability]},distance=..40]')
        return n > 0, f'{n} {PROJECTILE[ability]} in flight'
    if ability in MOVE:
        place(180, 0)
        start = pos()
        trigger(ability)
        time.sleep(0.7)
        end = pos()
        d = sum((a - b) ** 2 for a, b in zip(start, end)) ** 0.5
        return d > 1.5, f'moved {d:.1f} blocks'
    if ability == 'teleport':
        place(180, 35)
        start = pos()
        trigger(ability)
        time.sleep(0.3)
        end = pos()
        d = sum((a - b) ** 2 for a, b in zip(start, end)) ** 0.5
        return d > 1.0, f'teleported {d:.1f} blocks'
    if ability in TARGETED:
        dummy()
        time.sleep(0.2)
        place_facing_dummy()
        before = dummy_health()
        trigger(ability)
        time.sleep(0.6)
        after = dummy_health()
        ok = before is not None and after is not None and after < before
        return ok, f'target health {before} -> {after}'
    if ability in SELF_EFFECT:
        trigger(ability)
        time.sleep(0.3)
        eff = effects(T)
        return SELF_EFFECT[ability] in eff, f'effects now {eff}'
    if ability in AREA_EFFECT:
        dummy()
        time.sleep(0.2)
        trigger(ability)
        time.sleep(0.3)
        eff = effects('@e[tag=dummy,limit=1]')
        return AREA_EFFECT[ability] in eff, f'target effects {eff}'
    if ability == 'explode':
        dummy()
        time.sleep(0.2)
        before = dummy_health()
        trigger(ability)
        time.sleep(2.2)
        after = dummy_health()
        ok = before is not None and after is not None and after < before
        return ok, f'target health {before} -> {after}'
    if ability == 'web':
        place(180, 50)
        trigger(ability)
        time.sleep(0.3)
        out = r.cmd(f'fill {X - 6:.0f} {Y} {Z - 8:.0f} {X + 6:.0f} {Y + 4} {Z + 2:.0f} minecraft:air replace minecraft:cobweb')
        m = re.search(r'filled (\d+)', out)
        return bool(m and int(m.group(1)) > 0), out.strip()[:60]
    if ability == 'graze':
        trigger(ability)
        time.sleep(0.3)
        out = r.cmd(f'execute if block {X:.0f} {Y - 1} {Z:.0f} minecraft:dirt')
        r.cmd(f'setblock {X:.0f} {Y - 1} {Z:.0f} minecraft:grass_block')
        return 'passed' in out, 'grass under you became dirt' if 'passed' in out else 'grass unchanged'
    if ability == 'sniff':
        trigger(ability)
        time.sleep(2.6)
        n = count('@e[type=item,distance=..4,nbt={Item:{id:"minecraft:torchflower_seeds"}}]') + count(
            '@e[type=item,distance=..4,nbt={Item:{id:"minecraft:pitcher_pod"}}]')
        return n > 0, f'{n} seed items dropped'
    if ability == 'lay-egg':
        trigger(ability)
        time.sleep(0.3)
        n = count('@e[type=item,distance=..3,nbt={Item:{id:"minecraft:egg"}}]')
        return n > 0, f'{n} eggs'
    if ability == 'magnet':
        r.cmd(f'summon item {X} {Y} {Z - 5} {{Item:{{id:"minecraft:stone",count:1}},PickupDelay:32767}}')
        time.sleep(0.3)
        trigger(ability)
        time.sleep(0.3)
        n = count('@e[type=item,distance=..1.5]')
        return n > 0, f'{n} items pulled in'
    if ability == 'mimic':
        trigger(ability)
        return None, 'plays a sound only (not machine-checkable)'
    return None, f'no check written for {ability}'


def run():
    r.cmd(f'gamemode survival {T}')
    r.cmd('time set midnight')
    r.cmd('gamerule spawn_monsters false')
    r.cmd('weather clear')
    results = []
    for mob in MOBS:
        reset_arena()
        r.cmd(f'effect clear {T}')
        r.cmd(f'effect give {T} minecraft:instant_health 1 10 true')
        r.cmd(f'effect give {T} minecraft:saturation 1 10 true')
        place(180, 0)
        since = log_size()
        out = r.cmd(f'morph force {T} {mob}')
        row = {'mob': mob, 'force': out.strip()[:80]}
        for _ in range(40):
            time.sleep(0.25)
            info = r.cmd(f'morph info {T}')
            if f'form: minecraft:{mob}' in info and 'transforming' not in info:
                break
        row['form_ok'] = f'form: minecraft:{mob}' in info and 'transforming' not in info
        time.sleep(0.3)
        row['scale'] = round(num(f'attribute {T} minecraft:scale get') or 0, 3)
        row['max_health'] = num(f'attribute {T} minecraft:max_health get')
        row['speed'] = round(num(f'attribute {T} minecraft:movement_speed get') or 0, 4)
        row['mayfly'] = '1b' in r.cmd(f'data get entity {T} abilities.mayfly')
        row['effects'] = effects(T)
        ability = ACTIVE.get(mob)
        row['ability'] = ability
        if ability:
            ok, detail = test_ability(mob, ability)
            row['ability_ok'] = ok
            row['detail'] = detail
        row['errors'] = new_errors(since)
        results.append(row)
        print(json.dumps(row), flush=True)
    (Path(__file__).parent / 'mobtest_results.json').write_text(json.dumps(results, indent=1))


run()
