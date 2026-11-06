"""Checks for the 2026-11-02 fixes. MorphTester1 (modded) = T, VanillaViewer (unmodded) = V."""
import json, math, pathlib, re, socket, struct, time

ROOT = pathlib.Path(__file__).resolve().parents[2]

T, V = 'MorphTester1', 'VanillaViewer'
BX, BY, BZ = 500.5, -60, 500.5


class Rcon:
    def __init__(self):
        self.s = socket.create_connection(('127.0.0.1', 25598), timeout=30)
        self.cmd('morphtest', 3)

    def cmd(self, body, kind=2):
        data = struct.pack('<ii', 7, kind) + body.encode() + b'\0\0'
        self.s.sendall(struct.pack('<i', len(data)) + data)
        n = struct.unpack('<i', self._read(4))[0]
        return re.sub('\u00a7.', '', self._read(n)[8:-2].decode('utf-8', 'replace'))

    def _read(self, n):
        buf = b''
        while len(buf) < n:
            buf += self.s.recv(n - len(buf))
        return buf


r = Rcon()
results = []


def rec(name, ok, detail):
    row = {'test': name, 'ok': ok, 'detail': detail}
    results.append(row)
    print(('OK  ' if ok else ('--  ' if ok is None else 'FAIL')), name, '|', detail, flush=True)


def val(who, path):
    out = r.cmd(f'data get entity {who} {path}')
    m = re.search(r'data: (-?[\d.]+)', out)
    return float(m.group(1)) if m else None


def pos(who):
    return [float(v) for v in re.findall(r'(-?[\d.]+)d', r.cmd(f'data get entity {who} Pos'))]


def attr(who, a):
    m = re.search(r'is (-?[\d.]+)', r.cmd(f'attribute {who} {a} get'))
    return float(m.group(1)) if m else None


def morph(who, mob, snbt=''):
    r.cmd(f'morph force {who} {mob} {snbt}'.strip())
    for _ in range(60):
        time.sleep(0.25)
        info = r.cmd(f'morph info {who}')
        if f'form: minecraft:{mob}' in info and 'transforming' not in info:
            time.sleep(0.3)
            return True
    return False


def arena(x, z, floor='stone'):
    r.cmd(f'fill {x - 5:.0f} {BY} {z - 5:.0f} {x + 5:.0f} {BY + 14} {z + 5:.0f} minecraft:air')
    r.cmd(f'fill {x - 5:.0f} {BY - 1} {z - 5:.0f} {x + 5:.0f} {BY - 1} {z + 5:.0f} minecraft:{floor}')
    r.cmd(f'kill @e[type=!player,x={x},y={BY},z={z},distance=..12]')


def heal(who):
    r.cmd(f'effect clear {who}')
    r.cmd(f'effect give {who} minecraft:instant_health 1 10 true')
    r.cmd(f'effect give {who} minecraft:saturation 1 10 true')


def dummy(x, z, ai=False, health=1000):
    r.cmd('kill @e[tag=dummy]')
    noai = 'NoAI:1b,' if not ai else ''
    r.cmd(f'summon minecraft:pig {x} {BY} {z} {{{noai}Silent:1b,PersistenceRequired:1b,Tags:["dummy"],'
          f'attributes:[{{id:"minecraft:max_health",base:{health}}},{{id:"minecraft:movement_speed",base:0}}],Health:{health}f}}')
    time.sleep(0.3)


def dummy_val(path):
    out = r.cmd(f'data get entity @e[tag=dummy,limit=1] {path}')
    m = re.search(r'data: (-?[\d.]+)', out)
    return float(m.group(1)) if m else None


def aim_at(who, x, y, z, tx, ty, tz, scale):
    eye = y + 1.62 * scale
    dx, dy, dz = tx - x, ty - eye, tz - z
    yaw = math.degrees(math.atan2(-dx, dz))
    pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
    r.cmd(f'tp {who} {x} {y} {z} {yaw:.1f} {pitch:.1f}')
    time.sleep(0.25)


def ability(who):
    r.cmd(f'morph ability {who}')


for who in (T, V):
    r.cmd(f'gamemode survival {who}')
r.cmd('time set midnight')
r.cmd('weather clear')
r.cmd('gamerule spawn_monsters false')
r.cmd('gamerule keep_inventory true')

x, z = BX, BZ
# ---------------------------------------------------------------- happy ghast has no fireball
arena(x, z); r.cmd(f'tp {T} {x} {BY} {z} 180 -10'); heal(T); morph(T, 'happy_ghast')
ability(T); time.sleep(0.2)
n = re.search(r'Count: (\d+)', r.cmd(f'execute at {T} if entity @e[type=fireball,distance=..30]'))
rec('happy ghast shoots nothing', n is None, 'fireballs: ' + (n.group(1) if n else '0'))

# ---------------------------------------------------------------- sulfur cube: plain doesn't explode, explosive does
x += 30; arena(x, z); r.cmd(f'tp {T} {x} {BY} {z} 0 0'); heal(T); morph(T, 'sulfur_cube')
dummy(x, z + 2); h0 = dummy_val('Health'); ability(T); time.sleep(7); h1 = dummy_val('Health')
rec('plain sulfur cube does not explode', h0 == h1, f'dummy {h0} -> {h1}')
x += 30; arena(x, z); r.cmd(f'tp {T} {x} {BY} {z} 0 0'); heal(T)
r.cmd('morph setting creeper-explosion-kills-user false')
ok = morph(T, 'sulfur_cube', '{equipment:{body:{id:"minecraft:tnt",count:1}}}')
dummy(x, z + 2); h0 = dummy_val('Health'); ability(T); time.sleep(7); h1 = dummy_val('Health')
rec('sulfur cube with TNT inside explodes', ok and h1 is not None and h0 is not None and h1 < h0, f'morphed={ok} dummy {h0} -> {h1}')
r.cmd('morph setting creeper-explosion-kills-user true')

# ---------------------------------------------------------------- creeper self-blast keeps armour, says "blew up"
x += 30; arena(x, z); r.cmd(f'tp {V} {x} {BY} {z}'); heal(V)
r.cmd('gamerule keep_inventory false'); morph(V, 'creeper')
r.cmd(f'clear {V}')
for slot, item in [('armor.head', 'netherite_helmet'), ('armor.chest', 'netherite_chestplate'), ('armor.legs', 'netherite_leggings'), ('armor.feet', 'netherite_boots')]:
    r.cmd(f'item replace entity {V} {slot} with minecraft:{item}')
ability(V); time.sleep(2.5)
found = sum(int((re.search(r'Count: (\d+)', r.cmd(f'execute positioned {x} {BY} {z} if entity @e[type=item,distance=..10,nbt={{Item:{{id:"minecraft:{i}"}}}}]')) or [0, 0])[1])
            for i in ['netherite_helmet', 'netherite_chestplate', 'netherite_leggings', 'netherite_boots'])
rec('creeper blast drops all 4 armour pieces', found == 4, f'{found} of 4 netherite pieces on the ground')
r.cmd('gamerule keep_inventory true'); r.cmd(f'kill @e[type=item,x={x},y={BY},z={z},distance=..12]')
time.sleep(1)

# ---------------------------------------------------------------- undead potions, witch magic resistance, health floors
x += 30; arena(x, z); r.cmd(f'tp {V} {x} {BY} {z}'); heal(V); morph(V, 'zombie'); time.sleep(0.5)
r.cmd(f'damage {V} 10 minecraft:generic'); h0 = val(V, 'Health')
r.cmd(f'effect give {V} minecraft:instant_damage 1 0'); time.sleep(0.4); h1 = val(V, 'Health')
rec('Harming heals a zombie', h1 is not None and h0 is not None and h1 > h0, f'health {h0} -> {h1}')
r.cmd(f'effect give {V} minecraft:instant_health 1 0'); time.sleep(0.4); h2 = val(V, 'Health')
rec('Healing hurts a zombie', h2 is not None and h1 is not None and h2 < h1, f'health {h1} -> {h2}')
heal(V); morph(V, 'witch'); time.sleep(0.5); h0 = val(V, 'Health')
r.cmd(f'effect give {V} minecraft:instant_damage 1 0'); time.sleep(0.4); h1 = val(V, 'Health')
rec('witch shrugs off most magic', h0 - h1 < 2, f'Harming took {h0 - h1:.1f} health (a player loses 6)')
for mob, expect in [('slime', 4), ('magma_cube', 4), ('creaking', 20), ('chicken', 4), ('cow', 10)]:
    morph(V, mob); mh = attr(V, 'minecraft:max_health')
    rec(f'{mob} max health', mh == expect, f'{mh} (expected {expect})')

# ---------------------------------------------------------------- elder guardian aura only in water
x += 30; arena(x, z); r.cmd(f'tp {V} {x} {BY} {z}'); r.cmd(f'tp {T} {x + 3} {BY} {z}'); heal(V); heal(T)
r.cmd(f'morph force {T} off'); morph(V, 'elder_guardian'); time.sleep(3.5)
eff = r.cmd(f'data get entity {T} active_effects')
rec('elder guardian on land gives no mining fatigue', 'mining_fatigue' not in eff, 'mining fatigue on the other player: ' + str('mining_fatigue' in eff))

# ---------------------------------------------------------------- dry out (nautilus 15 s), dolphin breath, fish surface grace
x += 30; arena(x, z); r.cmd(f'tp {V} {x} {BY} {z}'); heal(V); morph(V, 'nautilus')
a0 = val(V, 'Air'); time.sleep(4); a1 = val(V, 'Air'); h0 = val(V, 'Health'); time.sleep(13); h1 = val(V, 'Health')
rec('nautilus dries out on land', a1 is not None and a1 < a0 and h1 is not None and h0 is not None and h1 < h0, f'air {a0} -> {a1}, health {h0} -> {h1} after 17 s')
heal(V); morph(V, 'axolotl'); a0 = val(V, 'Air'); time.sleep(4); a1 = val(V, 'Air')
rec('axolotl air drains slowly on land', a1 is not None and a0 is not None and 0 < a0 - a1 < 20, f'air {a0} -> {a1} in 4 s (5 minutes to dry out)')
heal(V); morph(V, 'dolphin')
r.cmd(f'fill {x - 2:.0f} {BY} {z - 2:.0f} {x + 2:.0f} {BY + 5} {z + 2:.0f} minecraft:water'); r.cmd(f'tp {V} {x} {BY} {z}')
time.sleep(0.5); a0 = val(V, 'Air'); time.sleep(5); a1 = val(V, 'Air')
rec('dolphin holds its breath', a1 is not None and a0 is not None and a1 > 250, f'air {a0} -> {a1} after 5 s underwater (a player would be near 200)')
r.cmd(f'fill {x - 2:.0f} {BY} {z - 2:.0f} {x + 2:.0f} {BY + 5} {z + 2:.0f} minecraft:air')

# ---------------------------------------------------------------- enderman teleports out of water, snowballs hurt blazes
x += 30; arena(x, z); r.cmd(f'tp {V} {x} {BY} {z}'); heal(V); morph(V, 'enderman')
p0 = pos(V); r.cmd(f'setblock {x:.0f} {BY} {z:.0f} minecraft:water'); time.sleep(1.5); p1 = pos(V)
d = math.dist(p0, p1) if p0 and p1 else 0
rec('enderman teleports out of water', d > 2, f'moved {d:.1f} blocks')
r.cmd(f'fill {x - 5:.0f} {BY} {z - 5:.0f} {x + 5:.0f} {BY} {z + 5:.0f} minecraft:air replace minecraft:water')
arena(x, z); r.cmd(f'tp {V} {x} {BY} {z}'); heal(V); morph(V, 'blaze'); time.sleep(0.5); h0 = val(V, 'Health')
r.cmd(f'summon minecraft:snowball {x} {BY + 1.2} {z - 1.5} {{Motion:[0.0,0.0,1.5]}}'); time.sleep(0.8); h1 = val(V, 'Health')
rec('snowball hurts a blaze', h1 is not None and h0 is not None and h0 - h1 >= 2.5, f'health {h0} -> {h1}')

# ---------------------------------------------------------------- creaking freezes while watched
x += 30; arena(x, z); heal(T); heal(V); morph(T, 'creaking')
r.cmd(f'tp {T} {x} {BY} {z} 0 0'); r.cmd(f'tp {V} {x} {BY} {z + 6} 180 5')
r.cmd(f'morph force {V} off'); time.sleep(2.2)
s_watched = attr(T, 'minecraft:movement_speed')
r.cmd(f'tp {V} {x} {BY} {z + 6} 0 0'); time.sleep(0.6)
s_free = attr(T, 'minecraft:movement_speed')
rec('creaking frozen while looked at', s_watched == 0 and s_free and s_free > 0, f'speed watched {s_watched}, not watched {s_free}')

# ---------------------------------------------------------------- client traits: spider through webs, rabbit on powder snow, strider on lava
x += 30; arena(x, z); heal(T); morph(T, 'spider')
r.cmd(f'fill {x:.0f} {BY} {z:.0f} {x:.0f} {BY + 9} {z:.0f} minecraft:cobweb'); r.cmd(f'tp {T} {x} {BY + 10} {z}')
time.sleep(2); y_spider = pos(T)[1]
r.cmd(f'morph force {T} off'); time.sleep(6.5); r.cmd(f'tp {T} {x} {BY + 10} {z}'); time.sleep(2); y_player = pos(T)[1]
r.cmd(f'fill {x:.0f} {BY} {z:.0f} {x:.0f} {BY + 10} {z:.0f} minecraft:air')
rec('spider falls through cobwebs freely', y_spider < BY + 1 and y_player > BY + 3, f'after 2 s in a web column: spider y {y_spider:.1f}, player y {y_player:.1f}')
x += 30; arena(x, z); heal(T); morph(T, 'rabbit')
r.cmd(f'fill {x - 1:.0f} {BY} {z - 1:.0f} {x + 1:.0f} {BY + 1} {z + 1:.0f} minecraft:powder_snow'); r.cmd(f'tp {T} {x} {BY + 3} {z}')
time.sleep(2.5); y = pos(T)[1]
rec('rabbit stands on powder snow', y >= BY + 1.9, f'y {y:.2f} (snow top at {BY + 2})')
x += 30; arena(x, z); heal(T); morph(T, 'strider')
r.cmd(f'fill {x - 2:.0f} {BY - 3} {z - 2:.0f} {x + 2:.0f} {BY - 1} {z + 2:.0f} minecraft:lava'); r.cmd(f'tp {T} {x} {BY + 1} {z}')
time.sleep(2.5); y = pos(T)[1]
rec('strider stands on lava', y >= BY - 0.2, f'y {y:.2f} (lava top at {BY})')
r.cmd(f'fill {x - 2:.0f} {BY - 3} {z - 2:.0f} {x + 2:.0f} {BY - 1} {z + 2:.0f} minecraft:stone')

# ---------------------------------------------------------------- new and changed abilities
def ability_test(name, mob, setup, check, wait=0.6, snbt=''):
    global x
    x += 30
    arena(x, z)
    heal(T)
    morph(T, mob, snbt)
    setup()
    ability(T)
    time.sleep(wait)
    ok, detail = check()
    rec(name, ok, detail)


def target_ahead(scale):
    dummy(x, z + 2.5)
    aim_at(T, x, BY, z, x, BY + 0.45, z + 2.5, scale)


def dmg_check():
    h = dummy_val('Health')
    return h is not None and h < 1000, f'target health 1000 -> {h}'


ability_test('bee sting poisons', 'bee', lambda: target_ahead(0.28),
             lambda: ('poison' in r.cmd('data get entity @e[tag=dummy,limit=1] active_effects'), r.cmd('data get entity @e[tag=dummy,limit=1] active_effects')[-60:]))
r.cmd('morph setting bee-sting-kills-user false')
ability_test('illusioner blinds its target', 'illusioner', lambda: target_ahead(1.08),
             lambda: ('blindness' in r.cmd('data get entity @e[tag=dummy,limit=1] active_effects'), 'blindness on target'))
ability_test('illusioner vanishes when looking at nothing', 'illusioner', lambda: r.cmd(f'tp {T} {x} {BY} {z} 180 -60'),
             lambda: ('invisibility' in r.cmd(f'data get entity {T} active_effects'), 'invisible'))
ability_test('guardian beam charges then hits', 'guardian', lambda: target_ahead(0.47), dmg_check, wait=2.8)
ability_test('turtle lays eggs on sand', 'turtle', lambda: (r.cmd(f'setblock {x:.0f} {BY - 1} {z:.0f} minecraft:sand'), r.cmd(f'tp {T} {x} {BY} {z}')),
             lambda: ('passed' in r.cmd(f'execute if block {x:.0f} {BY} {z:.0f} minecraft:turtle_egg'), 'turtle egg at feet'))
ability_test('silverfish infests stone', 'silverfish', lambda: (r.cmd(f'setblock {x:.0f} {BY} {z + 2:.0f} minecraft:stone'),
                                                               aim_at(T, x, BY, z, x, BY + 0.5, z + 1.6, 0.2)),
             lambda: ('passed' in r.cmd(f'execute if block {x:.0f} {BY} {z + 2:.0f} minecraft:infested_stone'), 'stone became infested stone'))


def panda_setup():
    r.cmd(f'tp {T} {x} {BY} {z} 180 0')


p_before = None
ability_test('panda rolls forward', 'panda', panda_setup,
             lambda: (math.dist(pos(T), [x, BY, z]) > 1.0, f'moved {math.dist(pos(T), [x, BY, z]):.1f} blocks'), wait=0.8)


def sort_setup():
    r.cmd(f'setblock {x:.0f} {BY} {z + 2:.0f} minecraft:copper_chest')
    r.cmd(f'setblock {x + 3:.0f} {BY} {z + 2:.0f} minecraft:chest')
    r.cmd(f'item replace block {x:.0f} {BY} {z + 2:.0f} container.0 with minecraft:diamond 5')
    aim_at(T, x, BY, z, x, BY + 0.5, z + 1.6, 0.54)


ability_test('copper golem sorts a stack', 'copper_golem', sort_setup,
             lambda: ('diamond' in r.cmd(f'data get block {x + 3:.0f} {BY} {z + 2:.0f} Items'), r.cmd(f'data get block {x + 3:.0f} {BY} {z + 2:.0f} Items')[-60:]))
ability_test('dolphin does not dash on land', 'dolphin', lambda: r.cmd(f'tp {T} {x} {BY} {z} 180 0'),
             lambda: (math.dist(pos(T), [x, BY, z]) < 0.5, f'moved {math.dist(pos(T), [x, BY, z]):.1f} blocks'))
ability_test('cow no longer grazes', 'cow', lambda: r.cmd(f'tp {T} {x} {BY} {z}'),
             lambda: ('passed' not in r.cmd(f'execute if block {x:.0f} {BY - 1} {z:.0f} minecraft:dirt'), 'ground unchanged'))
ability_test('polar bear rears up (no error)', 'polar_bear', lambda: r.cmd(f'tp {T} {x} {BY} {z} 0 0'), lambda: (None, 'sound and shove only'))


def witch_cycle():
    r.cmd(f'tp {T} {x} {BY} {z} 180 -20')


ability_test('witch throws the selected potion', 'witch', witch_cycle,
             lambda: (bool(re.search(r'Count: [1-9]', r.cmd(f'execute at {T} if entity @e[type=splash_potion,distance=..30]'))), 'splash potion in flight'), wait=0.15)

r.cmd('morph setting bee-sting-kills-user true')
r.cmd(f'morph force {T} off'); r.cmd(f'morph force {V} off')
(ROOT / 'test-server' / 'fixtests.json').write_text(json.dumps(results, indent=1), encoding='utf-8')
