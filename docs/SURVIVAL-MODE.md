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
   1000 kg) × 0.3 × the player's Biomass Efficiency. A zombie (702 kg) gives 21 at the start. The kill shows the
   acquisition animation (tendrils with the mod, dark trails without), and the action bar shows the gain.
   - Too heavy (above the player's Absorbable Mass) or too far away (beyond Biomass Reach): no biomass. The action bar
     says why.
   - Biomass is stored up to Biomass Capacity.
4. **Upgrades.** Biomass buys upgrades. With the client mod, the Biomass button in the middle of the favourites wheel
   (or B, or `/morph upgrades`) opens an upgrade tree laid out like the advancement screen: each upgrade a framed
   status effect icon linked to the upgrade it grows from, details on hover, click to buy, a red glow on what you can
   afford. Without the mod, `/morph upgrades` opens a chest menu. The first goal is the
   **Morph Ability**.
5. **Morphing.** Once bought, the selector opens and kills also unlock morphs, as long as the collection has room
   (Morph Capacity kinds, Variant Capacity looks per kind). Morphs collected before, including a whole Classic
   collection, become usable at that moment.
6. **More.** Morph Abilities unlocks the ability key and an enderman's block carrying, and later levels shorten
   cooldowns. Morph Flight lets flying morphs fly (before that they glide down like a chicken). Morph Efficiency speeds
   up transformations.

Dying costs no biomass. Upgrades are never lost.

## The upgrade tree (default `survival.yml`)

| Upgrade | Starts at | Max | Value by level | Cost of each level | Needs |
|---|---|---|---|---|---|
| Biomass Capacity | 1 | 10 | 100, 200, 400 … 51,200 biomass | 80, 160, 320 … 20,480 | — |
| Biomass Efficiency | 1 | 10 | 10%, 15% … 55% absorbed | 48, 77 … 2,062 | Capacity |
| Biomass Reach | 1 | 6 | 4, 8 … 24 blocks | 72, 130 … 756 | Capacity |
| Absorbable Mass | 1 | 10 | 1,200 kg, 3,000 kg … 4.6 million kg | 120, 240 … 30,720 | Capacity |
| Morph Ability | 0 | 1 | lets you morph | 150 | Capacity level 2 |
| Morph Capacity | 1 | 30 | 3, 6 … 90 kinds of creature | 58 … 2,879 | Morph Ability |
| Variant Capacity | 1 | 16 | 1 … 16 looks per creature | 50 … 1,137 | Morph Capacity |
| Morph Efficiency | 0 | 5 | 10% … 50% faster transformations | 80 … 524 | Morph Ability |
| Morph Abilities | 0 | 6 | unlocks abilities; then 10% … 50% shorter cooldowns | 100 … 1,420 | Morph Ability |
| Morph Flight | 0 | 1 | flying morphs can fly | 300 | Morph Ability, Capacity level 3 |

What a kill is worth, and what it takes:

| Creature | Mass | Biomass at 10% | Absorbable from |
|---|---|---|---|
| chicken | 112 kg | 3 | start |
| zombie, skeleton, villager | about 700 kg | 21 | start |
| cow, sheep, enderman | about 1,040–1,130 kg | 31–34 | start |
| spider | 1,764 kg | 53 | Absorbable Mass 2 |
| warden, wither | 2,350–2,840 kg | 70–85 | Absorbable Mass 2 |
| iron golem | 5,292 kg | 159 | Absorbable Mass 3 |
| ravager | 8,366 kg | 251 | Absorbable Mass 4 |
| ghast | 64,000 kg | 1,920 | Absorbable Mass 6 |
| ender dragon | 2,048,000 kg | 61,440 (capped by capacity) | Absorbable Mass 10 |

Everything above is data in `survival.yml`: the unlock effects, the 0.3 value, per-creature multipliers or fixed
masses, creatures that give nothing, and every upgrade's name, icon, parent, requirements, start level, maximum, and
value and cost curves (iChun's five formulas). The upgrade ids are what the plugin gives meaning to; removing one locks
what it unlocks, or lifts the limit for capacities.

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
| Biomass spending | upgrades; ability costs were a TODO | upgrades only |
| Biomass menu | a client-only GUI, disabled | an advancement-style tree screen with the mod; a server chest menu for everyone else |
| Biomass bar | client HUD, modes off / on change / always | the same modes, bottom left: red under the morph's black static, swaying tendrils; the action bar without the mod |
| Trait tiers (`upgradeFor`, bat flapping → full flight) | data field only | flight only: gliding until Morph Flight |

## Decisions (2026-11-05)

1. Before the Morph Ability, kills give biomass only. Forms aren't saved; hunt again afterwards (iChun's plan).
2. Switching Classic → Survival keeps collections, locked until the Morph Ability.
3. Biomass is spent on upgrades only; abilities don't cost biomass.
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

Places that ask the mode: what a kill gives, whether the player may morph, use abilities or fly, cooldown and
transformation length, and collection capacity. Everything else is shared, so an ability added to abilities.yml works
in both modes.

`SurvivalMode` always exists, so admins can see and edit progress with `/morph survival` while the server is in
Classic. Progress lives in each player's file under `survival`, which Classic never reads or clears.

Client protocol 3: the handshake carries the mode; `S_SURVIVAL` sends biomass, capacity, unlocked and can-morph for
the bar and the selector; `C_OPEN_UPGRADES` asks for the upgrades, answered with `S_UPGRADES` (the tree with each
upgrade's level, values, cost and what it still needs); `C_BUY_UPGRADE` buys one. The client only displays; every
rule and purchase is checked on the server.

## Not done yet

- A real advancement for "A Mutation Complication" (would need a generated datapack). Today it's a title and a message.
- Trait tiers beyond flight (iChun's `upgradeFor`), and ability costs in biomass (left out by decision 3, available
  later as an option).
- Biomass values in the mob reference table.

## Modded and plugin mobs (later)

Biomass comes from the bounding box, which every entity has; abilities and variants are keyed by namespaced type ids,
so a modded mob with its own type fits. Plugin "mobs" (MythicMobs and similar) are vanilla entities underneath and
would need a hook that names what they really are before the variant is captured.
