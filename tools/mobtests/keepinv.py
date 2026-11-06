"""Creeper morph blows itself up with keepInventory on: armour must stay equipped, nothing dropped or lost."""
import pathlib, re, time
# Reuses the RCON helpers and arena setup from the top of fixtests.py.
src = (pathlib.Path(__file__).resolve().parent / 'fixtests.py').read_text(encoding='utf-8')
exec(compile(src.split('x, z = BX, BZ')[0], 'fixtests.py', 'exec'))
ARMOR = [('armor.head', 'netherite_helmet'), ('armor.chest', 'netherite_chestplate'), ('armor.legs', 'netherite_leggings'), ('armor.feet', 'netherite_boots')]
log = pathlib.Path(__file__).resolve().parents[2] / 'test-server' / 'server.log'

for who in (V, T):
    x, z = BX + 1500 + (0 if who == V else 30), BZ
    arena(x, z)
    r.cmd(f'gamemode survival {who}')
    r.cmd('gamerule keep_inventory true')
    r.cmd('morph setting creeper-explosion-kills-user true')
    r.cmd(f'tp {who} {x} {BY} {z}')
    heal(who)
    morph(who, 'creeper')
    r.cmd(f'clear {who}')
    for slot, item in ARMOR:
        r.cmd(f'item replace entity {who} {slot} with minecraft:{item}')
    r.cmd(f'item replace entity {who} weapon.mainhand with minecraft:netherite_sword')
    before = r.cmd(f'data get entity {who} equipment')
    mark = len(open(log, 'rb').read())
    ability(who)
    time.sleep(3)
    tail = re.sub(r'\x1b\[[0-9;]*m', '', open(log, 'rb').read()[mark:].decode('utf-8', 'replace'))
    death = [l.split('System chat: ')[-1] for l in tail.splitlines() if f'{who} blew up' in l]
    after = r.cmd(f'data get entity {who} equipment')
    hand = r.cmd(f'data get entity {who} SelectedItem')
    worn = [item for _, item in ARMOR if item in after]
    dropped = re.search(r'Count: (\d+)', r.cmd(f'execute positioned {x} {BY} {z} if entity @e[type=item,distance=..15]'))
    durability = re.findall(r'damage": (\d+)|damage: (\d+)', after)
    client = 'with the mod' if who == T else 'without the mod'
    print(f'{client}: death message {death or "none"}; still wearing {len(worn)}/4 ({", ".join(worn)}); '
          f'sword {"still held" if "netherite_sword" in hand else "GONE"}; items on the ground: {dropped.group(1) if dropped else 0}; '
          f'armor damage values: {[a or b for a, b in durability] or "none (undamaged)"}', flush=True)
    r.cmd(f'gamemode creative {who}')
    r.cmd(f'morph force {who} off')
