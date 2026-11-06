# Mob review, 2026-11-02

> **Second review, 2026-11-06:** see the end of this file.

> **Status:** everything below was fixed on 2026-11-02 except the water/rain damage note (bug 8), which was left as it is on
> purpose. See "Fixes" at the end for what changed and how each fix was checked.

Every one of the 90 mobs was morphed into on the test server and checked. Abilities were checked by firing them and
looking at what happened in the world. Automatic traits were checked by putting the tester in the situation (sun,
water, lava, falls, powder snow, poison), and deaths by dying with full netherite on.

The scripts are in `tools/mobtests/` and can be re-run after fixes.

## What works

- **All 90 morphs** load, show and run with no errors on the server, on modded clients or on unmodded clients.
- **All 68 mobs with an ability** do what their ability says when the key is pressed:
  - **Projectiles:** fireballs, small fireballs, wither skulls, dragon fireballs, snowballs, spit, shulker bullets,
    wind charges, arrows (tipped for stray, bogged, parched), tridents, splash potions.
  - **Ground and movement:** evoker fangs, dashes, rams, swoops, teleport.
  - **Hitting a target:** guardian beam, sonic boom, toss, tongue, shove.
  - **Area and self effects:** ink, puff, invisibility, roll-up, play dead.
  - **World:** web, graze, sniff, egg, item magnet.
  - **Explosions:** creeper, sulfur cube.
  - **Not checked:** the parrot's mimic only plays a sound, so it couldn't be checked automatically.
- **Automatic traits that behave like vanilla:**
  - **Sunburn:** undead burn in daylight, a helmet protects, and husks and zombified piglins don't burn.
  - **Breathing:** fish and tadpoles suffocate on land; water breathers keep their air underwater; land mobs drown.
  - **Water damage:** water hurts blazes, snow golems, striders and endermen (see the note below).
  - **Fire:** fire-immune mobs ignore lava, and striders float up through it.
  - **Falling:** cats, chickens, slimes and frogs take no fall damage; pigs and wolves do.
  - **Water movement:** iron golems sink; pigs float.
  - **Poison:** undead and spiders ignore it.
  - **Powder snow:** strays don't freeze; others do.
  - **Flight:** bat, bee, ghast, happy ghast, phantom, allay, parrot, vex and blaze can fly; the breeze correctly
    can't.
- **Deaths while morphed drop everything:** /kill, drowning, a fish on land and sunburn all dropped the full set at the
  death spot. The one exception is the first bug below.

## Bugs

1. **The creeper's (and sulfur cube's) own explosion destroys your armor.** To make sure the creeper dies, the plugin
   hits it with 1,000,000 damage. Damage also wears down armor, so every armor piece breaks before the death drop.
   - **Tested:** full netherite armor was gone; only the sword and the dirt dropped.
   - **Fix:** kill the creeper without the damage reaching your armor, and keep the "blew up" message and the totem
     save.
2. **The happy ghast shoots ghast fireballs.** In vanilla, happy ghasts are peaceful.
3. **Every sulfur cube explodes.** In vanilla only a sulfur cube that has swallowed an explosive block explodes; the
   others bounce, stick or burn on contact, depending on the block inside.
4. **Spiders get stuck in cobwebs, including their own web shots.** Real spiders walk through webs at full speed.
   - **Not tested:** this comes from reading the code.
5. **Undead morphs are hurt by potions of Harming.** Vanilla is the other way round: Harming heals undead mobs and
   Healing hurts them.
   - **Tested:** a zombie morph lost 6 health to a Harming potion.
6. **The creaking, small slime and small magma cube morphs have half a heart.** These are their real vanilla health
   values, but a real creaking can't be hurt while its heart block exists, so as a player it dies to anything. Slimes
   unlocked from bigger kills have more health.
7. **The elder guardian's aura gives Mining Fatigue III to every other player within 50 blocks, every minute,
   anywhere.** In vanilla it only happens around ocean monuments.
   - **Warden:** its Darkness on everyone within 20 blocks every 10 seconds is vanilla-like, but it hits friends too.
8. **Water and rain barely hurt endermen, blazes, snow golems and striders.** The damage is there, but with a full
   food bar your natural healing nearly cancels it (about 1 health lost in 3 seconds standing in water). Real mobs
   don't heal like that. Vanilla endermen also teleport away from water.
9. **Minor:** a cod lost a little air underwater once (300 → 286 in 3 s). It isn't dangerous, but it shouldn't
   happen.

## Abilities that aren't what the mob does in vanilla

Each of these is triggered by the ability key, but the move isn't that mob's own.

| Mob | Now | What the real mob does |
|---|---|---|
| horse, donkey, mule, skeleton horse, zombie horse | dash | a charged high jump (hold jump) |
| wolf, cat, ocelot | dash | pounce: a short leap at a target, like the fox's |
| panda | dash | roll forward; babies sneeze |
| rabbit | dash | nothing extra (it already has jump boost) |
| dolphin | dash, on land too | swim fast; it gives Dolphin's Grace to nearby swimmers |
| cow, mooshroom, pig | graze | only sheep eat grass (and that regrows their wool) |
| creaking | shove | freezes while looked at; no shove |
| illusioner | invisibility only | shoots a bow, casts blindness and mirror images |
| witch | a random potion each throw | picks a potion to suit the target, and drinks healing, fire resistance, water breathing or speed when it needs them |
| copper golem | pulls items to you | sorts items between copper chests |
| guardian, elder guardian | instant beam | the beam charges for about 2 seconds and needs line of sight |

These 22 mobs have no ability. Ideas:

- **bee:** sting, which kills the bee as in vanilla.
- **turtle:** lay eggs on sand.
- **polar bear:** rear up and roar.
- **silverfish:** hide in stone.
- **slime, magma cube:** a big bounce.
- **strider:** walk on top of lava.
- **No real vanilla move to copy:** bat, cod, salmon, tropical fish, tadpole, endermite, giant, husk, zombie,
  zombie villager, zombified piglin, piglin brute, vindicator, villager, wither skeleton.

## Missing automatic traits

- **Axolotls, dolphins and nautiluses never dry out on land.** In vanilla they take damage after a few minutes out of
  water.
  - **Tested:** air stayed at 300 on land.
- **Dolphins drown like a player (15 seconds).** A real dolphin holds its breath for about 4 minutes.
- **Spiders are slowed by cobwebs** (bug 4).
- **Healing and Harming potions aren't swapped for undead** (bug 5).
- **Rabbits, foxes, endermites and silverfish don't walk on powder snow.**
- **Endermen don't teleport out of water.**
- **Snowballs don't hurt blazes.** In vanilla they do 3 damage.
- **Striders float up through lava** instead of standing on it.
- **Size cap:** the ender dragon and the giant stop at 2.5× player size. The real ones are much bigger.

## Planned feature

**Witch potion indicator:** show the next potion in the action bar, with a way to choose it (for example sneak + ability
key to cycle).

## Not checked here

- **Snow golems melting in hot biomes:** the test world has no hot biome.
- **Mobs ignoring or hunting morphed players:** checked earlier in the project, not re-checked.
- **Milking:** needs a second player doing it.
- **How each mob looks:** checked by eye earlier for many mobs. This run only checked that nothing errors.

## Fixes (2026-11-02)

| Finding | Fix | Checked |
|---|---|---|
| Creeper blast destroys armor | the creeper is killed outright, not with a huge hit; "blew up" message and totem save kept | full netherite drops, 4 of 4 |
| Happy ghast fireballs | removed | no fireball |
| Every sulfur cube explodes | sulfur cube variants now remember the swallowed block; only an explosive one explodes, after a TNT-length fuse | plain: nothing; TNT inside: explodes |
| Spiders stuck in webs | walk through cobwebs (client mod), no server slowdown | falls through a 10-block web column in under 2 s (a player: still stuck) |
| Undead and Healing/Harming | swapped, like vanilla | Healing took 5 health from a zombie; Harming healed one |
| Half-heart morphs | minimum 2 hearts; the creaking gets 10 | slime and magma cube 4 health, creaking 20 |
| Elder guardian aura anywhere | only while the elder guardian is in water, never on creative or spectator players | none on land |
| Cod losing air at the surface | half a second of grace before suffocating starts | — |
| Dash on horses and others | horses: charged jump (hold sneak, then jump); wolves, cats, ocelots: pounce; panda: roll; rabbit: none; dolphins and nautiluses dash only in water | panda rolls 4.8 blocks; dolphin can't dash on land |
| Graze on cows, pigs, mooshrooms | sheep only; it regrows a sheared sheep's wool, and other players can shear a sheep morph | cow no longer grazes |
| Creaking shove | removed; the creaking can't move while a player looks at it | speed 0 while watched, normal otherwise |
| Illusioner only invisible | blindness on what you look at, or vanish when looking at nothing | both checked |
| Random witch potion | choose it: shown in the action bar, sneak + ability key (or F without the mod) to change; throws harming, poison, slowness, weakness; drinks healing, fire resistance, water breathing, swiftness. Witches also resist 85% of magic | throws the selected potion; Harming did 0.9 instead of 6 |
| Copper golem magnet | sorts: carries a stack from a copper chest to a nearby chest | 5 diamonds moved |
| Instant guardian beam | charges for 2 s and needs line of sight; elder guardian hits for 8 | hit after charging |
| No abilities | bee sting (dies a minute later, setting `bee-sting-kills-user`), turtle eggs on sand, polar bear rears up, silverfish infests stone, slimes and magma cubes leap | sting poisons; eggs; infested stone |
| Drying out | axolotl 5 min, dolphin 2 min, nautilus 15 s out of water; rain counts for axolotls and dolphins | nautilus took damage after 15 s |
| Dolphin breath | 4 minutes underwater | air barely moved in 5 s |
| Powder snow | rabbits, foxes, endermites, silverfish walk on it | rabbit stood on top |
| Enderman and water | teleports away | moved 16 blocks |
| Snowballs and blazes | 3 damage | health dropped |
| Strider and lava | stands on top (client mod) | stood at the lava surface |
| Size cap 2.5× | 7×: giant and ender dragon at full size | — |

## Second review (2026-11-06)

Every mob was reviewed again and marked fine or to change. Done in 6.1.6:

- **Dolphin:** dries out after 4 minutes on land (was 2).
- **Phantom:** sees in the dark (night vision), like the other night creatures.
- **Spider and cave spider:** climb cobwebs like scaffolding: jump to go up, stand on top, sneak to go down (with the
  client mod).
- **Skeletons and baby zombies:** ride spiders and chickens as jockeys (also stray, wither skeleton, bogged, parched;
  baby husk, drowned, zombie villager, zombified piglin, piglin; a baby piglin rides a hoglin).
- **Villager:** other players can trade with a villager morph.
- **Axolotl:** keeps vanilla drying out (300 seconds); no change.

Not reviewed this time, left as they are: strider, endermite, shulker, wither, warden, ravager, elder guardian,
guardian, sniffer, armadillo.
