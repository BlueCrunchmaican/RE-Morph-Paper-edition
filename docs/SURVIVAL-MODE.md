# Survival Mode

RE:Morph has two gameplay modes, chosen by the server in `config.yml`:

- **Classic** (default): the original Morph. Kill a creature to unlock it as a morph.
- **Survival**: iChun's planned "Default Mode" for Morph, finished. Morphing is earned through biomass.

Both run on the same plugin and the same morph, ability and disguise systems. Survival only adds rules on top.

## How Survival Mode plays

1. **Nothing at first.** Kills give no biomass and no morphs, and the selector doesn't open.
2. **"A Mutation Complication."** Having **Wither and Regeneration at the same time** unlocks the Biomass ability
   (iChun's rule; Nether fortresses have both: wither skeletons, and the blaze rods for Regeneration potions). A title
   and a chat message announce it.
3. **Absorbing.** From then on every kill absorbs biomass: the creature's mass (iChun's formula: width × width × height ×
   1000 kg) × 0.3 × the player's Biomass Efficiency × the server's gain multiplier (`biomass.gain-multiplier`,
   default 1; `/morph cost gain 2` doubles it). A zombie (702 kg) gives 21 at the start. With the mod a biomass-only
   kill is pulled in by dark red tendrils and a kill that unlocks a new morph by the original black ones (each
   changeable in `/morphclient`); without it, red trails and dark trails. The action bar shows the gain.
   - Too heavy (above the player's Absorbable Mass) or too far away (beyond Biomass Reach): no biomass. The action bar
     says why. Classic Mode has no such limits: a kill counts at any distance.
   - Biomass is stored up to Biomass Capacity.
4. **Upgrades.** Biomass buys upgrades. With the client mod, a click on the round Biomass button in the middle of the
   favourites wheel (or `/morph upgrades`, or the Biomass Menu key once bound) opens the upgrade tree, in the
   advancement screen's style: each upgrade a framed status effect icon, details on hover, click to buy, a red glow on
   what you can afford. It has three sections: **Biomass** (Capacity, with Efficiency, Reach and Absorbable Mass under
   it), the **Morph Ability** on its own as a lump of biomass with tendrils, the **Morph** section it opens (Morph
   Capacity with Variant Capacity under it, Morph Efficiency, Morph Abilities), and the **Abilities** below them.
   Letting go of the favourites key with the mouse in the middle just closes the wheel. Without the mod,
   `/morph upgrades` opens a chest menu with the same sections. The first goal is the **Morph Ability**.
5. **Morphing.** Once bought, the selector opens and kills also unlock morphs, as long as the collection has room
   (Morph Capacity kinds, Variant Capacity looks per kind). Morphs collected before, including a whole Classic
   collection, become usable at that moment. **Each morph costs biomass**, paid when the transformation starts
   (going back to your own body is free): the form's mass × 0.01, rounded up, at least 1. A chicken costs 2, a
   zombie 8, a cow 12, an iron golem 53, a ghast 640. The selector shows each price after the name (red: affordable,
   grey: not); without the mod, `/morph use` names the price when you're short. `survival.yml` `morph-cost` (or
   `/morph cost`) turns it off, changes the rate and minimum, or fixes a price per mob.
6. **More.** Transformations start slow, 6.5 seconds, and Morph Efficiency brings them down to 3.5. Ability
   cooldowns start at ×1.3 their length in `abilities.yml`, and Morph Abilities brings them down to ×0.7. (Classic Mode
   transforms in 5 seconds with cooldowns at ×1, the middle of both.) Morph
   Abilities unlocks the ability key and an enderman's block carrying, and opens the **Abilities** section.
7. **Abilities.** What a morph's body does for it by itself, the good parts, is bought one kind at a time: night
   vision, breathing underwater, fire immunity, flight, climbing and the rest (table below). The drawbacks are always
   on, from the start: undead morphs burn in daylight and are hurt by Healing, blazes and endermen are hurt by water,
   fish suffocate on land, golems sink, giants are slow. Creatures that can't live out of water (fish, squid,
   axolotls, nautiluses) always breathe underwater, or they'd have nowhere to breathe. Flying morphs glide down
   slowly until Morph Flight, and never take fall damage.

Dying costs no biomass. Upgrades are never lost.

## The upgrade tree (default `survival.yml`)

| Upgrade | Starts at | Max | Value by level | Cost of each level | Needs |
|---|---|---|---|---|---|
| **Biomass** | | | | | |
| Biomass Capacity | 1 | 10 | 100, 200, 400 … 51,200 biomass | 80, 160, 320 … 20,480 | — |
| Biomass Efficiency | 1 | 10 | 10%, 15% … 55% absorbed | 48, 77 … 2,062 | Capacity |
| Biomass Reach | 1 | 6 | 3, 8, 16, 32, 64, 150 blocks | 60, 150, 350, 800, 1,600 | Capacity |
| Absorbable Mass | 1 | 5 | 1,200 kg, 3,000 kg, 10,000 kg, 160,000 kg, no limit | 150, 500, 1,500, 5,000 | Capacity |
| **Morph Ability** | 0 | 1 | lets you morph | 150 | Capacity level 2 |
| **Morph** | | | | | |
| Morph Capacity | 1 | 30 | 3, 6 … 90 kinds of creature | 58 … 2,879 | Morph Ability |
| Variant Capacity | 1 | 16 | 1 … 16 looks per creature | 50 … 1,137 | Morph Capacity |
| Morph Efficiency | 1 | 6 | 6.5, 5.8, 5, 4.5, 4, 3.5 seconds per transformation | 80, 128, 205, 328, 524 | Morph Ability |
| Morph Abilities | 0 | 6 | unlocks abilities; ability cooldowns ×1.3, ×1.15, ×1, ×0.9, ×0.8, ×0.7 | 100, 170, 289, 491, 835, 1,420 | Morph Ability |

The **Abilities** section: one-time unlocks, each needing Morph Abilities.

| Unlock | Cost | What it turns on |
|---|---|---|
| Night Vision | 100 | night vision (bats, phantoms, cats, foxes, fish, glow squid) |
| Water Breathing | 150 | breathing underwater (guardians, turtles, frogs, the drowned and the other undead, copper golems) |
| Swift Swimming | 150 | Dolphin's Grace in water (dolphins, fish, squid, guardians, axolotls, turtles, polar bears, drowned) |
| Fire Immunity | 400 | no fire or lava damage (blazes, striders, magma cubes, ghasts, wither skeletons, zombified piglins...) |
| Soft Landing | 200 | no fall damage (cats, golems, slimes, goats, frogs...) and the chicken's slow fall |
| Morph Flight | 300 (and Capacity level 3) | flying morphs fly instead of gliding |
| Climbing | 250 | spiders climb walls, walk through cobwebs and climb them like scaffolding |
| Leaping | 150 | Jump Boost (rabbits, slimes, magma cubes, frogs, goats, camels...) and the sneak-jump leaps |
| Mob Strength | 400 | the Speed, Strength, Resistance and Hero of the Village a mob gets (horses, golems, wardens, piglin brutes, villagers...) |
| Sure Footing | 150 | through berry bushes, over powder snow, standing on and floating up through lava, no freezing |
| Defences | 400 | breezes deflect projectiles, endermen teleport away from them, witches resist magic, pufferfish sting back |
| Attack Effects | 300 | hits that poison, wither, starve or slow (cave spiders, wither skeletons, husks, strays...), and auras (elder guardian, warden, dolphin) |
| Camouflage | 500 | mobs treat the morph as what it looks like (monsters leave other monsters alone, predators pick their prey), cats and ocelots scare creepers and phantoms, villagers flee zombies and illagers |

Each unlock lists its traits in `survival.yml` (`traits:`), by their abilities.yml names, so a server can move a trait
to another unlock, split one up, or add its own. A trait no unlock lists is always on.

What a kill is worth, and what it takes (every mob, with its morph cost: [MOB-MASSES.md](MOB-MASSES.md)):

| Creature | Mass | Biomass at 10% | Absorbable from |
|---|---|---|---|
| chicken | 112 kg | 3 | start |
| zombie, skeleton, villager | about 700 kg | 21 | start |
| cow, sheep, enderman | about 1,040–1,130 kg | 31–34 | start |
| spider | 1,764 kg | 53 | Absorbable Mass 2 |
| warden, wither | 2,350–2,840 kg | 70–85 | Absorbable Mass 2 |
| horse, iron golem | 3,120–5,292 kg | 94–159 | Absorbable Mass 3 |
| ravager | 8,366 kg | 251 | Absorbable Mass 3 |
| ghast | 64,000 kg | 1,920 | Absorbable Mass 4 |
| ender dragon | 2,048,000 kg | 61,440 (capped by capacity) | Absorbable Mass 5 |

Everything above is data in `survival.yml`: the unlock effects, the 0.3 value, per-creature multipliers or fixed
masses, creatures that give nothing, and every upgrade's name, icon, parent, requirements, start level, maximum, and
value and cost by level (a list with one number per level, or iChun's five formulas). The upgrade ids are what the
plugin gives meaning to; removing one locks what it unlocks, or lifts the limit for capacities. Ability unlocks are
plain upgrades with a `traits:` list, so they can be renamed, added or removed freely.

**Updating from before 6.6.0:** the `upgrades` section of an older `survival.yml` is replaced with the new one (the
levels mean something else now; the old file is kept as `survival-old-<version>.yml`), and the console says so. Levels
players bought move to the first new level that is at least as good: 24 blocks of reach becomes 32, Morph Efficiency
level 2 (4 seconds) becomes level 5 (4 seconds), and Morph Abilities level 1 (cooldowns ×1) becomes level 3 (×1). The ability unlocks are new
and start unbought, so morphs lose their good traits until those are bought.

## What came from iChun, and what is ours

The 1.16.5 source (`reference/src/morph-mod-1.16`, LGPL-3.0) had Default Mode half-built. His official page says
development wrapped up and "Classic" was released instead ([CurseForge](https://www.curseforge.com/minecraft/mc-mods/morph),
[ModPackIndex](https://www.modpackindex.com/mod/7033/morph)).

| | iChun's code | RE:Morph |
|---|---|---|
| Mode switch | `morphMode: DEFAULT` silently turned back into CLASSIC ("TODO remove this to enable default mode") | `gameplay-mode: survival` (`default` also accepted), or `/morph mode survival` in game |
| Unlock | advancement "A Mutation Complication": Wither + Regeneration together | the same rule, checked when a player's effects change; the same title and text |
| Biomass formula | width² × height × 1000 × 0.3 × efficiency, per-mob multiplier and override | the same, with efficiency as a percentage |
| Upgrade data | JSON tree: id, parent, max level, value and cost curves (5 equations), requirements | the same shape in YAML, same equations |
| Upgrades | capacity, efficiency, reach shipped; morph ability, morph capacity, variant capacity, morph efficiency, morph abilities, max absorbable mass named but TODO | all of those working, plus Morph Flight (ours) |
| Numbers | placeholders (a zombie overflowed the starting capacity 40×; Capacity 2 cost more than Capacity 1 held) | rebalanced (table above) |
| Early kills | biomass only until the morph ability (his intended code) | the same |
| Biomass spending | upgrades; ability costs were a TODO | upgrades, and morphing itself (6.3.0); abilities stay free |
| Biomass menu | a client-only GUI, disabled | an advancement-style tree screen with the mod; a server chest menu for everyone else |
| Biomass bar | client HUD, modes off / on change / always | the same modes, bottom left: red under the morph's black static, swaying tendrils; the action bar without the mod |
| Trait tiers (`upgradeFor`, bat flapping → full flight) | data field only | every kind of good trait is its own unlock (6.6.0); flying morphs glide until Morph Flight |

## Decisions (2026-11-05)

1. Before the Morph Ability, kills give biomass only. Forms aren't saved; hunt again afterwards (iChun's plan).
2. Switching Classic → Survival keeps collections, locked until the Morph Ability.
3. Biomass is spent on upgrades, and since 6.3.0 on morphing (scaled by the form's size, adjustable with
   `/morph cost`). Abilities don't cost biomass.
4. Death costs no biomass.
5. The upgrade tree starts from iChun's names.

## Architecture

```
config.yml gameplay-mode ──► GameplayMode (at startup; /morph mode and /morph reload switch it live)
                                 ├─ ClassicMode   kill → unlock; everything allowed
                                 └─ SurvivalMode  kill → biomass (+ unlock if allowed); asks upgrades
                                        └─ survival.yml (SurvivalConfig), progress in the player file (SurvivalData)
MorphManager, AbilityManager, ClientChannel, DisguiseBridge: shared; they ask the mode
```

Places that ask the mode: what a kill gives, whether the player may morph, use abilities or fly, which good traits
the form may use (`hasTrait`), cooldown and transformation length, and collection capacity. Everything else is shared, so an ability added to abilities.yml works
in both modes.

`SurvivalMode` always exists, so admins can see and edit progress with `/morph survival` while the server is in
Classic. Progress lives in each player's file under `survival`, which Classic never reads or clears.

Client protocol 3: the handshake carries the mode; `S_SURVIVAL` sends biomass, capacity, unlocked and can-morph for
the bar and the selector; `C_OPEN_UPGRADES` asks for the upgrades, answered with `S_UPGRADES` (the tree with each
upgrade's level, values, cost and what it still needs); `C_BUY_UPGRADE` buys one. The client only displays; every
rule and purchase is checked on the server.

## Not done yet

- A real advancement for "A Mutation Complication" (would need a generated datapack). Today it's a title and a message.
- Ability costs in biomass (left out by decision 3, available later as an option).

## Modded and plugin mobs (later)

Biomass comes from the bounding box, which every entity has; abilities and variants are keyed by namespaced type ids,
so a modded mob with its own type fits. Plugin "mobs" (MythicMobs and similar) are vanilla entities underneath and
would need a hook that names what they really are before the variant is captured.
