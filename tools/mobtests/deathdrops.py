"""Does dying while morphed drop everything? Full netherite + sword + dirt on the unmodded tester, keepInventory off."""
import json, pathlib, re, socket, struct, time

LOG = pathlib.Path(__file__).resolve().parents[2] / 'test-server' / 'server.log'

V = 'VanillaViewer'
X, Y, Z = 400.5, -60, 400.5
GEAR = ['netherite_helmet', 'netherite_chestplate', 'netherite_leggings', 'netherite_boots', 'netherite_sword', 'dirt']


class Rcon:
    def __init__(self):
        self.s = socket.create_connection(('127.0.0.1', 25598), timeout=20)
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


def morph(mob):
    if mob is None:
        r.cmd(f'morph force {V} off')
        time.sleep(1.8)
        return
    r.cmd(f'morph force {V} {mob}')
    for _ in range(40):
        time.sleep(0.25)
        info = r.cmd(f'morph info {V}')
        if f'form: minecraft:{mob}' in info and 'transforming' not in info:
            return


def equip(helmet=True):
    r.cmd(f'clear {V}')
    if helmet:
        r.cmd(f'item replace entity {V} armor.head with minecraft:netherite_helmet')
    r.cmd(f'item replace entity {V} armor.chest with minecraft:netherite_chestplate')
    r.cmd(f'item replace entity {V} armor.legs with minecraft:netherite_leggings')
    r.cmd(f'item replace entity {V} armor.feet with minecraft:netherite_boots')
    r.cmd(f'item replace entity {V} weapon.mainhand with minecraft:netherite_sword')
    r.cmd(f'give {V} minecraft:dirt 5')


def arena(x, z):
    r.cmd(f'fill {x - 6:.0f} {Y - 1} {z - 6:.0f} {x + 6:.0f} {Y + 10} {z + 6:.0f} minecraft:air')
    r.cmd(f'fill {x - 6:.0f} {Y - 1} {z - 6:.0f} {x + 6:.0f} {Y - 1} {z + 6:.0f} minecraft:stone')
    r.cmd(f'kill @e[type=!player,x={x},y={Y},z={z},distance=..20]')


def dropped(x, z):
    found = {}
    for g in GEAR:
        out = r.cmd(f'execute positioned {x} {Y} {z} if entity @e[type=item,distance=..12,nbt={{Item:{{id:"minecraft:{g}"}}}}]')
        m = re.search(r'Count: (\d+)', out)
        found[g] = int(m.group(1)) if m else 0
    return found


def died_since(mark):
    with open(LOG, 'rb') as f:
        f.seek(mark)
        text = f.read().decode('utf-8', 'replace')
    return [re.sub(r'\x1b\[[0-9;]*m', '', l).split('System chat: ')[-1] for l in text.splitlines() if V in l and 'System chat' in l
            and any(w in l for w in ('blew up', 'drowned', 'burned', 'died', 'killed', 'fell', 'suffocated', 'was '))]


def logsize():
    import os
    return os.path.getsize(LOG)


results = []
r.cmd('gamerule keep_inventory false')
r.cmd('gamerule immediate_respawn true')
r.cmd(f'gamemode survival {V}')
r.cmd('morph setting creeper-explosion-kills-user true')
r.cmd('morph setting explosion-block-damage off')

cases = [
    ('no morph, /kill', None, 'kill'),
    ('pig morph, /kill', 'pig', 'kill'),
    ('creeper morph, its own explosion', 'creeper', 'explode'),
    ('pig morph, drowning', 'pig', 'drown'),
    ('cod morph, out of water', 'cod', 'fish'),
    ('zombie morph, sunburn (no helmet)', 'zombie', 'sun'),
    ('pig morph, killed by a vanilla creeper', 'pig', 'creeper'),
    ('pig morph, vanilla creeper then a second blast on the drops', 'pig', 'two_creepers'),
]
for i, (name, mob, how) in enumerate(cases):
    x, z = X + i * 30, Z
    arena(x, z)
    r.cmd(f'time set midnight')
    r.cmd(f'tp {V} {x} {Y} {z}')
    r.cmd(f'effect clear {V}')
    r.cmd(f'effect give {V} minecraft:instant_health 1 10 true')
    time.sleep(0.5)
    morph(mob)
    equip(helmet=how != 'sun')
    r.cmd(f'effect give {V} minecraft:instant_health 1 10 true')
    time.sleep(0.5)
    mark = logsize()
    if how == 'kill':
        r.cmd(f'kill {V}')
        time.sleep(1)
    elif how == 'explode':
        r.cmd(f'morph ability {V}')
        time.sleep(2.5)
    elif how == 'drown':
        r.cmd(f'fill {x - 2:.0f} {Y} {z - 2:.0f} {x + 2:.0f} {Y + 5} {z + 2:.0f} minecraft:water')
        r.cmd(f'tp {V} {x} {Y} {z}')
        for _ in range(60):
            time.sleep(1)
            if died_since(mark):
                break
        r.cmd(f'fill {x - 2:.0f} {Y} {z - 2:.0f} {x + 2:.0f} {Y + 5} {z + 2:.0f} minecraft:air')
        time.sleep(1.5)
    elif how == 'fish':
        for _ in range(60):
            time.sleep(1)
            if died_since(mark):
                break
    elif how == 'sun':
        r.cmd('time set noon')
        for _ in range(60):
            time.sleep(1)
            if died_since(mark):
                break
        r.cmd('time set midnight')
    elif how in ('creeper', 'two_creepers'):
        r.cmd(f'summon minecraft:creeper {x} {Y} {z + 1} {{Fuse:0s,ignited:1b,ExplosionRadius:6b}}')
        time.sleep(1.5)
        if how == 'two_creepers':
            r.cmd(f'summon minecraft:creeper {x} {Y} {z} {{Fuse:0s,ignited:1b}}')
            time.sleep(1.5)
    deaths = died_since(mark)
    drops = dropped(x, z)
    row = {'case': name, 'death': deaths[:1], 'drops': drops, 'netherite_dropped': sum(v for k, v in drops.items() if 'netherite' in k)}
    results.append(row)
    print(json.dumps(row), flush=True)

r.cmd('gamerule keep_inventory true')
r.cmd('morph setting creeper-explosion-kills-user false')
r.cmd(f'morph force {V} off')
r.cmd(f'clear {V}')
