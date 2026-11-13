# Testing

## How it was tested

- A local **Paper 26.3 build 235** server with Lib's Disguises 26.10.24 Free and PacketEvents 2.14.0, offline mode,
  flat world.
- Two **Fabric 26.3** clients with the mod (Fabric Loader 0.20.1, Fabric API 0.165.0+26.3), run side by side so
  each could watch the other.
- Admin commands sent from the server console over RCON.
- Compatibility with older servers (7.2.0 on): `py -3 tools/compat_check.py <server jars>` starts the test server with
  each released plugin jar and a fresh data folder, joins with the client mod built from this checkout, drives every
  server packet over RCON and reads both logs. Logs and each command's reply land in `test-server/compat/`. Run it
  against every released version before a release ([PROTOCOL.md](PROTOCOL.md)).
- Single-player: `gradlew runSingleplayer` opens the world "RE-Morph test" in `run-client2/saves` (make it once
  through the menus; a copy of the test server's world doesn't load, since Paper stores it differently).
- Unit tests: `paper-plugin` → `gradlew test`, **31 of 31 pass** (variant filter and which creatures have looks,
  config versions, config.yml upgrades, Survival upgrade curves).

## Checked and working

Tried on the test clients during development, but not re-checked after the latest changes:

- Unlocking by killing, the selector (`[` `]`, Shift, Enter, Esc), favourites, and the morph and unlock animations.
- Morphs are full size (no half-size mobs), show name tags, and wear the player's armour and held items.
- Mobs that wouldn't attack the mob you're morphed as leave you alone.

Checked in the latest round, between the two modded clients and the console:

- **Enderman:** visibly holds its block (seen on screen), and goes back to empty hands after putting it down.
- **Creeper:** swells once, explodes once and dies in the blast (`<player> blew up`). It comes back as a normal,
  unswollen creeper after respawning.
  - With mobGriefing on, the blast left a crater in a test wall.
  - With `explosion-block-damage off`, the same blast left the wall untouched.
- **Frog:** a sneak-jump reached over 5 blocks up (a normal jump about 2.5) and landed on top of a 3-block wall.
- `/morph setting`: lists, changes, rejects bad values, and saves to `config.yml` with its comments intact. This
  includes the new `enderman-pickup` setting.
- `/morph ability <player>` and `/morph carry` from the console.
- **Spider**, with and without the mod: walking into a wall climbs from the ground up (about 2.4 blocks/second with
  the mod, matching a ladder; about 3 without), letting go slides down slowly, sneaking holds still.
- **Skeleton:** holds a bow up in the aiming pose (seen from the front in third person).

## Death and gear (checked 2026-11-02)

- **keepInventory off:** dying as a morph drops everything at the death spot. Tried: the creeper's own blast, drowning,
  a fish on land, sunburn and a plain kill.
- **keepInventory on:** a creeper morph that blows itself up keeps its full netherite armor on, undamaged, plus its
  held sword. Nothing drops, with or without the mod, and the death message is still "blew up".
- **Vanilla behaviour to know:** a second explosion destroys dropped items, netherite included (netherite only
  survives fire and lava).

## Live test (2026-11-03)

Tested on the local test server with a modded client and an unmodded viewer.

**Worked:**
- horse jumps, pounces and leaps;
- the witch's potion choice;
- spiders in cobwebs, rabbits and foxes on powder snow;
- enderman pickup;
- the creaking freezing while watched;
- projectiles against breezes and endermen;
- bee stings;
- the modded onlooker's view of the illusioner.

**Fixed after it,** each checked on the test server from a modded and an unmodded client:

| Problem | Why | Fix | Checked |
|---|---|---|---|
| Ender dragon doesn't flap (no mod) | The hidden template mob that disguises copy had "no AI" set, and a no-AI dragon holds its wings still. | Templates no longer set it. | Wings move from frame to frame |
| Enderman's block missing (no mod) | Lib's Disguises' material shortcut loses modern blocks. | The real block is sent. | Pumpkin in its hands |
| Strider bobs on lava instead of walking on it | In 26.3 a mob stands on lava through its own half-block lava "floor", which a player doesn't have. The server's upward push did the rest. | The mod gives strider morphs that floor and floats them up when sunk, like a strider. Modded players no longer get the push. | Lands at the surface and holds the same height; rises from 1.5 blocks deep |
| Turtle legs flail | The turtle's land walk is made for its slow pace. | On land it walks at 0.6× (a real turtle's pace) and sprinting adds nothing; full speed in water. `land-speed` in `abilities.yml`. | Speed 0.06 on land, 0.1 in water |
| Copper golem doesn't animate sorting | Never sent. Its animations were also frozen by the "no AI" template. | It reaches into the copper chest, then puts the stack down as the other chest opens, for both kinds of player. | Seen with and without the mod |
| Sheared sheep keeps its wool (no mod) | Not sent. | Bald after shearing, wool back after grazing. | Seen, including the eating animation |
| Sulfur cube huge (no mod) | Lib's Disguises picks the cube's size itself. | The variant's own size and age are used. | Matches a real small sulfur cube |
| Bee keeps its stinger (no mod); no stinger after respawning | Not sent; and the morph's look wasn't reset on death. | Stinger loss shown. Dying resets the look: stinger, wool, primed creeper. | Both seen |
| Bee wings still | A bee only flaps in the air or while moving, and other players always look motionless to a client. | The bee's real movement is sent (no mod) or worked out (mod). | Wings flap while walking, on both |
| Illusioner shows overlapping copies and doesn't cast (no mod) | Vanilla draws an invisible illusioner as four mirror images that only spread when it is hurt. | Casting spreads them, as vanilla's mirror spell does; the casting pose shows without the mod. | Copies spread out and the real spot stays empty, on both |
| Panda roll, goat ram and polar bear rearing not seen (no mod) | Ability animations only went to modded players. | Sent to everyone, with the ghast's attack face and the evokers' casting pose too. The goat also lifts its head again afterwards (it stayed lowered on modded clients). | Panda, polar bear, sheep, illusioner, copper golem seen |
| Pillager's arms still while loading; no trident wind-up (no mod) | The mob's combat flags weren't sent. | Sent while the player draws a bow, crossbow or trident. | Both poses seen |
| Not reported | | Iron golems, ravagers, hoglins, zoglins, wardens and creakings play their attack animation on a melee hit, without the mod too. | Not checked yet |

## 5.1.0 fixes and features (2026-11-05)

Checked on the local test server with the modded client (MorphTester1) and the unmodded one (VanillaViewer). Positions
come from the server over RCON; "seen" means a screenshot from that client.

| Item | Cause found | Change | Checked |
|---|---|---|---|
| Piglins and hoglins jiggle left and right (selector, own morph, other players) | Vanilla's "turning into a zombified piglin" shake: outside the Nether, a piglin, brute or hoglin without NoAI or zombification immunity shakes its body (LivingEntityRenderer.isShaking). The morph copies had neither; 5.0.0 removed NoAI from the server templates, so unmodded players saw it too. | Morph copies are marked immune to zombification, on the client and in the disguise. NoAI stays off (it freezes dragon wings and copper golems). | Seen steady from both clients and in the selector, next to a real overworld piglin that visibly shakes |
| Baby ↔ adult morph pops to the new size at the end | 26.3 renderers keep separate adult and baby models (also cold/warm cows, pigs and chickens, salmon and pufferfish sizes, small sulfur cubes) and only pick one while drawing. The transition read whichever was drawn last, so both ends used the same model. | The transition makes each renderer pick the right model before capturing it. | Seen from a fixed camera: the black body grows step by step from baby to adult height |
| Chicken stops dead when jumping or running off a ledge | The server capped the fall by sending a whole new velocity, and the server never knows a player's sideways speed, so each push zeroed it. | Client mod: vanilla's chicken rule on the player's own game (falling speed ×0.6 per tick). Without the mod: an invisible Slow Falling effect while airborne. | Mod: falls at a steady 2.26 blocks/s (a vanilla chicken's speed) at full walking speed (4.3 blocks/s). No mod: full walking speed; the fall speeds up on long drops (up to 6 blocks/s over 10 blocks) |
| Rabbit's R hop gone | Removed in the 4 Nov mob review (it was a camel dash). | Restored as its own `hop` ability (same push as before) with the rabbit's jump sound and hop animation for everyone. | Hop of about 7 blocks; hop pose seen without the mod |
| Rabbit walking animation | Rabbits only animate when they hop; the original never synced jumps to the morph either. | Left as it is. | — |
| Bat perching (new) | — | Ability key hangs the bat under a full-faced block within 2.5 blocks of its head, upside down for everyone (BatFlags). Lets go on the ability key, a movement key or jump, getting hurt, or the block vanishing, and restores flying or standing. | Standing → hang → standing (W), flying → hang → flying (R twice), hurt and block removal both release; the hanging pose seen from both clients |
| Fish night vision (new) | — | Cod, salmon, tropical fish and pufferfish have night vision while morphed. | Effect present for each, gone after demorphing |
| Absorbing / transforming animation settings (new) | The options existed only in the JSON file, and "off" meant no effect at all. | `/morphclient` settings screen; "Plain" shows what players without the mod see, on this player's screen only. | Screen, saving, Plain transformation and Plain absorbing seen; Morph versions still play after switching back |
| Flight lost after changing game mode while morphed (found while testing the bat) | Minecraft resets flying on every game mode change; the plugin only granted a morph's flight when the form changed. | The flight grant is redone a tick after the game mode changes (creative and spectator get their normal fly speed back). | Bat: survival → creative → survival keeps flight at the bat's speed; morphed in creative then survival can fly; adventure keeps it; a pig never gets it; demorphing removes it |
| Config upgrade 5.0.0 → 5.1.0 | — | abilities.yml now `config-version: 5.1.0` | Test server replaced it and kept `abilities-old-5.0.0.yml`; variants.yml (still 5.0.0) was left alone |

## 5.2.0 Survival Mode (2026-11-05)

Checked on the local test server. The unmodded client (VanillaViewer) was driven from the console; the modded client
(MorphTester1) on screen.

| Check | Result |
|---|---|
| Classic unchanged | A kill still unlocks straight away (pig: 91 → 92 morphs); `/morph info` shows "classic" |
| Fresh Survival player | Biomass locked; a zombie kill gives nothing |
| Wither + Regeneration together | Unlocked ("unlocked the Biomass ability" in the log); title and chat text seen on the modded client |
| Zombie kill | +21 biomass (702 kg × 0.3 × 10%); no morph before the Morph Ability |
| Iron golem kill (5,292 kg) | Nothing: too heavy at Absorbable Mass 1 (1,200 kg) |
| Zombie killed 10 blocks away | Nothing: beyond Reach 1 (4 blocks) |
| Buying | Morph Ability refused without Capacity 2; Capacity 2 (80) then Morph Ability (150) bought, from the console and by clicking the chest menu |
| After the Morph Ability | Cow, pig and sheep unlocked; the chicken gave biomass but no morph (Morph Capacity 3 kinds) |
| Abilities | Rabbit hop did nothing before Morph Abilities, moved the player after buying it |
| Flight | Bat and allay can't fly without Morph Flight and glide down at 2.27 blocks/s; flight after buying it |
| Morph Efficiency 1 | Transformation 100 → 90 ticks |
| Modded client | Selector refuses with a "can't morph yet" message; B opens the menu; after buying, the selector opens with the old collection; the biomass bar shows 41 / 200 after a kill |
| Saving | Survival progress in both player files after shutdown; still there after a Classic session |

## 5.2.0 additions (2026-11-05, second round)

| Check | Result |
|---|---|
| Creeper morph struck by lightning | Look becomes `{powered:1b}`; blue aura seen from the modded and the unmodded client |
| Charged explosion | Same blast on a block of dirt: 52 blocks uncharged, 201 charged; the charge is gone after exploding and respawning |
| Upgrade tree screen (mod) | Opens with B; Morph Ability refused until Capacity 2; clicking Capacity then Morph Ability bought both (250 → 20 biomass), "Bought Morph Ability." shown; dragging reveals the rest of the tree |
| Biomass bar (mod) | Green static fill, swaying black tendrils, readable label and amount; `+21` after a kill |
| abilities.yml 5.1.0 → 5.2.0 | Replaced on startup, old file kept as `abilities-old-5.1.0.yml` |
| Charged-creeper morph | Killing a creeper with `powered:1b` added "Creeper {powered:1b}"; as that morph the blast took 203 blocks (double); the aura shows without the mod |
| Favourites wheel (Survival) | Opens before the Morph Ability with only the centre Biomass button; clicking it, or letting go of the key over it, opens the upgrade tree |
| Red accents and effect icons | Tree: effect icons (Absorption, Saturation, Glowing, Strength, Nausea, Luck, Hero of the Village, Haste, Jump Boost, Levitation), red glow and red amount; bar: red fill, red edge, `+21` in red |

## 6.0.0 (2026-11-05)

| Check | Result |
|---|---|
| Selector keys bound to mouse buttons 4 and 5 | Was: the first press opened the selector, then it never moved (the selector swallowed every mouse button while open). Now: mouse 5 opened it and moved down two rows, mouse 4 moved back up |
| Config upgrade 5.2.0 → 6.0.0 | abilities.yml and survival.yml replaced, old copies kept |

### Rename to RE:Morph (2026-11-05)

Server side only, over RCON; the client's changes are names and text.

| Check | Result |
|---|---|
| Upgrade from a MorphReborn jar | `plugins/MorphReborn` moved to `plugins/RE-Morph` on first start, byte-for-byte identical to a backup; enabled in Classic mode with no warnings |
| The move fails (a file in the old folder held open by another process) | Logged the error, refused to start, created nothing; the old folder untouched |
| Old and new jars both in `plugins/`, in either file order | Paper loads RE-Morph and refuses the old jar (`provides: [MorphReborn]`); no new `MorphReborn` folder |
| `/morph`, `/remorph`, `/morphreborn reload` | All answer; help header and messages say RE:Morph |
| Built jars | Plugin name `RE-Morph`; mod name "RE:Morph (Client)" with the GitHub links; key category and settings title "RE:Morph"; licence and NOTICE inside both |
| Unit tests | 17 of 17 pass |

The saved-data keys (`morphreborn:morph_scale` and the others) were built from the plugin's name; they're now fixed
strings, so they're the same as before the rename.

### config.yml upgrades (6.1.0, 2026-11-05)

On the local test server; the merge rules also have unit tests (`ConfigMergeTest`).

| Check | Result |
|---|---|
| The first release's config.yml with an admin's edits (40-tick transitions, an extra disabled mob, max health without a cap, `debug: true`, a note of their own) | Added `gameplay-mode`, `bee-sting-kills-user`, `min-max-health` and `health-overrides` in their places with their comments, and the console named them. Every edited value kept; the max-health entry wasn't given its default cap back. Backup `config-old-pre-6.1.4.yml` holds the old file and the note. Started with 40-tick transitions |
| The test server's 6.0.0 config.yml, scrambled by earlier `/morph setting` saves | Put back in the release's order with its comments; no new settings, values unchanged |
| Restart with an up-to-date config.yml | Nothing written, no backup |
| `/morph reload` with an old config.yml | Same upgrade as on startup |
| A config.yml with a YAML error | Error logged, file left byte-for-byte as it was, no backup |
| Long messages | Stay on one line (Paper wraps at 80 characters unless told otherwise; `/morph setting` saves fixed the same way) |

### /morph mode (6.1.0, 2026-11-05)

Local test server; MorphTester1 (with the mod) and VanillaViewer (without) online in survival game mode.

| Check | Result |
|---|---|
| `/morph mode`, `/morph mode survival` twice, `/morph mode bogus` | Shows the mode; switches and saves `gameplay-mode: survival`; "Already in survival mode."; "The mode can be classic or survival." |
| Hand-edit `gameplay-mode` and `/morph reload` | Switched without a restart |
| Classic → Survival, MorphTester1 an allay with no Survival progress | Turned back to their own body, flight off; their client got the new mode (a second hello in its log) |
| Classic → Survival, VanillaViewer an allay with the Morph Ability but not Morph Flight | Stayed an allay, flight off (gliding) |
| Survival → Classic | VanillaViewer's flight back; MorphTester1 could morph again |
| Collections and progress across both switches | Unchanged (103 and 7 variants) |
| 6.1.3-numbered config.yml (an unreleased build) | Updated to 6.1.4 on startup, no new settings |

## 6.2.0 (2026-11-06)

### Movement while transforming

Local test server, MorphTester1 with the mod, survival game mode, sliding along a tunnel wall (slab bumps, a low
ceiling) for 9 seconds. Distance covered:

| Run | 6.1.0 | 6.2.0 |
|---|---|---|
| Plain player, no morph (baseline) | 27.4 blocks | |
| Already an enderman, starting clear of the wall | 27.2 | |
| Turning into an enderman while walking | 15.0 | 27.4 |
| Switching enderman / player / enderman while walking | 12.2 | 26.8 |

| Check | Result |
|---|---|
| Growing into a ghast under a 3-block ceiling | Kept a player-sized body (scale 1.0); grew to 2.22 the moment it was in the open |
| Ghast to rabbit, rabbit to baby zombie, baby zombie to zombie | Sizes 0.33, 0.54, 1.08, each applied once at the half-way point |
| Zombie to iron golem against a wall | Nudged 0.11 blocks off the wall before growing |
| A 6.1.0 enderman teleported overlapping a wall | Couldn't move at all (0.02 blocks in 9 seconds): the stuck state the fix prevents |
| Camera during player to ghast and ghast to rabbit (297 ticks logged) | Largest change between ticks 0.064 blocks; the one-tick drop at the swap (the client's stored eye height lags the new scale by a tick) is gone |

### Paper doll

| Check | Result |
|---|---|
| Player, rabbit, baby zombie, zombie, snowy librarian villager, ghast | Each shown with its own look; the rabbit small and whole, the baby half the zombie's size, the ghast shrunk to fit with its tentacles |
| Mid-transformation (ghast to rabbit) | The black melting boxes, then the rabbit |
| Boat with the player and a pig | Boat, both passengers; a zombie or husk morph sits (riding pose now copied to humanoid morphs, in the world too) |
| Reconnecting while morphed, also while in a boat | Doll shows the morph |
| A zombie morph in daylight | Doll shows it burning, as the world does (the sunburn trait sets the player on fire); in a small screenshot the flames first looked like a player in an orange shirt |
| Opacity 60% | Doll drawn see-through |

Not tested in game: walking/sneaking/swimming/sleeping poses on the doll (they come from the same render state as the
world, so they match it), the settings page itself and the toggle key.

The Biomass Menu key is unbound by default since 6.2.0 (it was B), like the Settings and Paper Doll keys; built, not
checked in game, and neither is the new "Can't morph yet" hint for when it has no key.

## 6.3.0 (2026-11-06)

Local test server (Paper 26.3 build 235), MorphTester1 with the 6.3.0 mod, VanillaViewer without it.

| Check | Result |
|---|---|
| Updating a 6.1.0 config.yml and a 6.0.0 survival.yml | Both merged: `trading` and `biomass.gain-multiplier` / `morph-cost` added, values kept, backups written; abilities.yml replaced with a backup |
| `/morph cost` on, off, rate, minimum, price, formula, gain | Each saved to survival.yml and resent to clients |
| Selector prices in Survival | Shown after each name ("Allay 1 biomass", "Armadillo 4 biomass"), red when affordable |
| Pig priced 50 with 31 biomass | Refused: "Not enough biomass: that morph costs 50." |
| Pig priced 5 | Charged: "-5 biomass (26.9 / 100)", then the pig form |
| Classic kill of a new mob | Black tendrils and the "New morph acquired" toast |
| Survival kill of a mob already owned | +21.9 biomass with the dark red tendrils (Red Wisps checked too) |
| Paper doll settings page | All sections render; Soft vignette on a sheep morph reads as a soft shadow, not an outline |
| Skeleton morph right-clicks a spider | Mounted ("Press Left Shift to dismount"); W moved it 14 blocks in 3 s the way the rider faced; sneak got off |
| Baby zombie morph right-clicks a chicken | Mounted, steered about 12 blocks, sneak got off |
| Right-click a librarian (level 2) villager player | Trade window "Librarian" with 4 trades; 48 paper bought 2 emeralds |
| Same, with `freeze-trading true` | The villager player's speed and jump were 0 while the window was open, back to normal after |
| Spider morph in a 3-high cobweb column | Jump climbed to the top (y -57) and stood there; sneak went back down |
| Phantom morph | Night vision, infinite, no particles |

Found and fixed during the run: every villager trade showed as sold out (Paper's MerchantRecipe copy constructor reads
fields that the server's recipes leave at 0; trades are now copied through the getters).

Not tested in game: the paper doll's head modes, tilt, Fill the Box, the level-head locks, the music-toast hide and
each "show when" trigger one by one (only the settings page and the vignette were looked at); jockeys for the other
riders and the hoglin; the cave spider on webs (same code and list as the spider); the dolphin's 4-minute dry-out
(only the number changed); the daily restock of trades.

## 7.4.0 release (2026-11-12)

- [x] The 7.4.0 jars are the tested 7.4.0-beta.1 code with only the version changed.
- [x] `tools/compat_check.py` with the 7.4.0 client mod against all 15 released servers, 6.0.0 to 7.3.0: every one OK.
- [x] Unit tests 37/37.

## 7.4.0-beta.1, the skin for players without the mod, and the frenzy's hover (2026-11-12)

The same rig as below (VanillaViewer without the mod but with the pack, MorphTester1 with the mod and First-person
Model, MorphTester2 as the subject), the three windows tiled so they can be watched; frames captured in a row from both
viewers. Movement keys can't be posted to the test clients (the game reads the keyboard's state), so Space and crouch
were real key presses with the window active for a moment.

- [x] Transformations: husk to cow, husk to iron golem, husk to ravager, your own body to a cow and back: the boxes melt
      as the mod's do, frame for frame; the disguise is invisible meanwhile (a 0.0625 one showed at the feet), and the
      new body shows at the end. Found and fixed: the skin stayed on to the end; now off at 94%.
- [x] Absorbing a pig, a goat, sheep: tendrils reach out, the creature is covered in the skin (red for biomass), and
      is pulled into the chest with the tendrils still on it, as the mod's. Found and fixed: twice as fast as the mod's;
      the tendrils let go of the body (the far halves jumped to the chest); a frame where they vanished.
- [x] Devouring Frenzy's tendrils to three husks, the player facing south and turned: wavy red skin to each, as the
      mod's. Found and fixed: tendrils took the player's facing (their frame is the world's).
- [x] First person: with the mod, with and without First-person Model, your own frenzy tendrils, absorbing and
      transformation show (with First-person Model the transformation plays on your body). Without the mod: found and
      fixed the transformation's boxes around the camera (now not drawn for you), frenzy tendrils from under the camera
      (now from in front, thinner) and the absorb's near halves (now not drawn for you).
- [x] Players without the pack (`vanilla-heads: auto`, not loaded) keep the particles; players with the mod see only
      the mod's drawing.
- [x] Frenzy Meter: the new row in `/morphclient` (started at Always from the biomass bar), switched to When It Changes:
      shown during the frenzy and just after, gone 8 seconds later while the biomass bar (Always) stays.
- [x] The hover, at levels 1 and 3: jumping lifts the player (about 2 blocks in 1.5 seconds), Space held reaches the cap
      (8 blocks at level 3) and stays there; the frenzy ending in the air: slow falling down, health unchanged. Legs to the
      ground from both viewers, the mod's and the skin, on a husk, a chicken, a cow and a ravager (feet spread with the
      body's width since a ravager's stood inside it); frenzy tendrils from each body's chest.
- [x] Six legs: from the side the two viewers match frame for frame, each leg writhing and the feet stepping as it walks
      forward; from above, an even star about 4.5 blocks across at 8 blocks up. Found and fixed: at 8 blocks they
      bunched under the player (feet planted near the ground never stepped out).
- [x] The Fabric message: VanillaViewer (Fabric, mod switched off) got it 5 seconds after joining; MorphTester1 and
      MorphTester2 (the mod) didn't.
- [x] `config.yml` 7.2.0 gained `appearance.vanilla-skin-effects`; `survival.yml` 7.3.0 had `upgrades.rage` replaced;
      `abilities.yml` replaced (its levels show "hover 4 blocks up" and so on).
- [x] Plugin unit tests 37 of 37.

## 7.4.0-beta.1, wings, fins and rods for players without the mod (2026-11-12)

The local test server with the 7.4.0-beta.1 plugin, MorphTester2 as the subject (`/morph force`, `/morph borrow` over
RCON), and VanillaViewer (no mod, the new pack installed locally, `vanilla-heads: pack`) and MorphTester1 (the mod)
standing at the same spot, captured together from behind, the side and three-quarters behind, so every vanilla shot
has the mod's beside it. Test clients windowed and captured without focus.

- [x] The dump: 92 entries as before plus the ender dragon; no head model or head numbers changed; 65 parts; the same
      15 ears-and-tail animals; parts only for allay, bat, bee, blaze, breeze, chicken, cod, dolphin, ender dragon,
      phantom, salmon, tropical fish, vex; head and tail for the wither.
- [x] Husk (a zombie burns in daylight and hid everything) with each of the 15 creatures: wings at the shoulder blades,
      tails and stingers low on the back, fins, rods around the body, as the mod puts them. The bee's, allay's, vex's,
      dolphin's, parrot's and chicken's matched the mod's shots closely; the bat's and phantom's differed only by the
      moment of their flap.
- [x] Found and fixed: a vex's and an allay's wings vanished from behind (one face painted, items culled): backed flat
      elements; the vex's then matched the mod from every side. At rest a vex's and an allay's wings hung edge-on: parts
      are now posed by the creature's animation.
- [x] Other bodies: cow (bat, phantom, ender dragon, blaze, cod, bee: wings on the flanks, high up), wolf (phantom,
      parrot), horse (ender dragon, vex), your own body (bat, allay, ender dragon), iron golem (phantom, allay), spider
      (bee, bat), slime (bat, dolphin), chicken (ender dragon, bat): each where the mod puts it.
- [x] Unchanged: husk with a cat (now both tail segments, as the mod shows), wolf, horse (ears and tails), cow (head);
      creeper (skull worn), slime (whole), cow and pig heads.
- [x] Flapping, frame by frame from the vanilla client: the dragon's wings beat from raised to below level, the bat's
      fold and spread, the phantom's and allay's beat, the bee's buzz, the cod's tail fin and the wither's tail sway.
- [x] Found and fixed: turned without walking (yaw 90), a creature disguise is drawn facing the look, while the parts
      followed the server's body yaw (40): wings off the back. After the fix the husk's and cow's wings sit square on
      the back from behind the look; a cow's borrowed pig head stays on its neck; your own body (drawn as a player, at
      the body yaw) keeps its wings square from behind the body yaw.
- [x] Found and fixed: on a baby body, phantom and allay wings stay full size and a wolf's tail and a chicken's wings
      are half size, as with the mod.
- [x] `vanilla-heads: auto` with no server pack loaded: the dragon still shows its vanilla skull, a bat nothing; no
      missing-model cubes.
- [x] Displays: 4 for a borrowed dragon (3 parts and the skull for players without the pack), 12 for a blaze, none
      after `/morph borrow off` or during a transformation.
- [x] Plugin unit tests 37 of 37 (new: placement rules, dragon scaling, every part in the table has a model in the pack).

## 7.3.0 release (2026-11-12)

- [x] The 7.3.0 jars are the tested 7.3.0-beta.1 code with only the version changed (the user kept the beta's numbers).
- [x] `tools/compat_check.py` with the 7.3.0 client mod against all 14 released servers, 6.0.0 to 7.2.0: every one OK.
- [x] Unit tests 31/31.

## 7.3.0-beta.1, Devouring Frenzy levels (2026-11-11)

The test server with the 7.3.0-beta.1 plugin and MorphTester2 (the 7.3.0-beta.1 mod), a cleared flat arena at night,
creatures summoned without AI at set distances, each frenzy set off with `/morph frenzy MorphTester2 go <level>` and
timed by `report: true` (scratchpad script `frenzytest.py`):

- [x] Update from 7.2.0: a 7.2.0 `survival.yml` got the three-level `upgrades.rage` ("the 'upgrades.rage' section
      changed its layout in 7.3.0, so it was replaced"), every other value kept; `abilities.yml` replaced with a backup.
- [x] Kill speed, 8 creatures 4 to 5.5 blocks out (zombie, skeleton, creeper, witch, vindicator, pillager, piglin
      brute, ravager):

      | Level | Zombie, skeleton, creeper (20) | Vindicator, pillager (24) | Witch (26) | Piglin brute (50) | Ravager (100) | Absorbed | Drained |
      |---|---|---|---|---|---|---|---|
      | 1 | 5 s | left at 4 | left at 14 | left at 30 | left at 80 | 3 of 8 | 160 |
      | 2 | 4 s | 5 s | 8 s | left at 10 | left at 60 | 6 of 8 | 226 |
      | 3 | 3 s | 3.5 s | 4.5 s | 7.5 s | left at 30 | 7 of 8 | 258 |

      The witch heals itself with potions, so it outlasts its health (vanilla witch behaviour).
- [x] Reach, zombies in a line at 8, 11, 14, 17, 19, 21 and 23 blocks: level 1 took the one at 8; level 2 those up to
      14; level 3 those up to 19. Nothing past the reach was touched.
- [x] A crowd of 20 zombies in a ring 5 blocks out (8 held at once, the next caught as one dies): level 1 absorbed 8,
      level 2 16, level 3 all 20 (it had time for 24). At 10% Biomass Efficiency that was +168.5, +337 and +421.2
      biomass.
- [x] With `targets: all` at level 2: a plain pig, a cow and a wild horse were drained (pig and cow absorbed in 2 s;
      the 53-health horse left at 13); another player's wolf at a quarter of the rate (absorbed after 6.5 s). Untouched:
      a saddled pig, a tamed horse in iron armour, another player's wolf in wolf armour, the player's own wolf and a
      happy ghast in a harness.
- [x] The client mod's meter reads FRENZY with the bar and seconds left; the tendrils as before. `/morph survival
      info` shows "Devouring Frenzy: 3/3 (20 blocks, 3.5 damage a hit, 10 seconds)".
- [x] Unit tests 31/31.

## 7.2.0 release (2026-11-11)

- [x] The 7.2.0 client jar is the 7.2.0-beta.5 one but for its version in `fabric.mod.json` (every other entry compared
      byte for byte), so beta.5's compat_check run (13 of 13 released servers OK, below) stands for it.
- [x] Plugin: one change after beta.5, the "Your rage is charged" chat line also for players whose client mod is older
      than 7.2.0 (they have no meter). Checked with `SemVer.compare` against 7.2.0: 6.6.0, 7.1.3, 7.2.0-beta.5 and a
      client that sent no version get the line; 7.2.0, 7.2.1 and 7.10.0 don't. Not tried in game with an old client.
- [x] Unit tests 31/31.

## 7.2.0-beta.1, version notices (2026-11-08)

On the test server with MorphTester2 (an admin), builds numbered for each case (same code), GitHub's latest being 7.1.2:

- [x] Plugin and mod both 7.1.1: the console warned that 7.1.2 is out; on joining, the admin line and then the mod's
      own line. A rejoin (`/transfer` back to the same server) showed both again.
- [x] Mod 7.1.1 on a 7.2.0-beta.1 plugin: only the plugin's old-client message, not the mod's line as well. Switching
      the gameplay mode (a repeated hello) repeated nothing.
- [x] Mod 7.3.0 on a 7.2.0-beta.1 plugin: the mod's "server runs 7.2.0-beta.1" line on each join; the console logged
      the newer client once, not again on the rejoin.
- [x] Mod 7.1.1 on a server without RE:Morph: the "7.1.2 is out" line 10 seconds after joining (when the mod gives up
      waiting for RE:Morph).
- [x] Mod 7.2.0-beta.1 on the original 6.1.8 jar (6.5.0): the "from before 6.6.0" line; it connected, synced its
      collection and showed forced morphs (pig, sheep, off) with no errors on either side.
- [x] A single-player world (new, made through the menus; then `runSingleplayer`'s quick play into it): the mod gave
      up on RE:Morph after 10 seconds and showed "RE:Morph 7.1.2 is out; your client mod is 7.1.1" with both links.
- [x] The re-check, with `-Dmorphreborn.updateCheck.refreshSeconds=60`: a rejoin 55 s after GitHub's answer didn't ask
      again; one at 65 s did. A failed check (an unreachable proxy, `retrySeconds=20`) logged the failure, showed no
      release line, and each later rejoin asked again.
- [x] The links, clicked in chat: [Modrinth] and [GitHub] in the mod's line, the admin line and the old-client message
      each opened Minecraft's "open this website?" screen with `https://modrinth.com/plugin/re-morph` or
      `https://github.com/BlueCrunchmaican/RE-Morph-Paper-edition/releases/latest` (which redirects to the newest
      release). Not opened in a browser, which would have taken over the shared screen's browser; the Modrinth page
      stays private until the project is approved.
- [x] By hand on the 6.5.0 server (fresh data) with the 7.2.0-beta.1 mod: the selector (`]`, Enter) morphed into a snow
      golem; the ability key threw snowballs (the server saw one per press); the favourite key starred it and the
      server saved it; Player in the selector turned back. Enter is read once per tick, so a synthetic key press
      shorter than a tick is missed; hold it a quarter second when testing this way.
- [x] Survival as the default: a fresh install of the 7.2.0-beta.1 plugin started in Survival and wrote
      `gameplay-mode: survival`; a server set to Classic with a 7.1.0 config updated it to 7.2.0 and stayed Classic.
      Plugin unit tests: 29 of 29 pass.
- [x] `tools/compat_check.py` with the 7.2.0-beta.1 mod against all 12 released servers (6.0.0, 6.1.0, 6.2.0, 6.3.0,
      6.4.0, 6.5.0, 6.6.0, 6.6.1, 7.0.0, 7.1.0, 7.1.1, 7.1.2), twice: every one OK. On each, the handshake went through,
      the server saw the mod's version, the mod said the server was older, 4 morphs were granted, 2 forced (spider,
      chicken: states, transitions, traits), a cow kill unlocked a fifth (the acquisition), Survival biomass was given
      and unlocked, and the mode switched both ways (a repeated hello; 6.0.0 has no `/morph mode`). No warnings or
      errors on either side. Not driven: the upgrade tree packet, which a server only sends when a player opens the
      screen (its format hasn't changed since 6.0.0).

## 7.2.0-beta.3, room in the collection (2026-11-11)

On the test server in Survival with MorphTester2 (Client 2, GUI scale 2), capacities set with
`/morph survival MorphTester2 level morph_capacity|morph_variant_capacity <n>` and kills made over RCON with
`execute at MorphTester2 run damage @e[type=<mob>,sort=nearest,limit=1] 500 minecraft:player_attack by MorphTester2`:

- [x] Morph Capacity 3 kinds, Variant Capacity 2: white and red sheep, an iron golem. The selector's own-body row read
      "Morphs 2/3"; the iron golem's (one look) only "Morphs 2/3", no look count; the sheep's "Looks 2/2 full" in gold,
      then "Morphs 2/3" in grey.
- [x] Variant Capacity raised to 3 with the selector open: the sheep row changed to "Looks 2/3" at once.
- [x] A blue sheep: unlocked, action bar "+31.6 biomass (2,253.5 / 51,200) · Sheep looks 3/3 full", the row
      "Looks 3/3 full". A green sheep then: "· No room for this Sheep look (3/3)", nothing added.
- [x] A blaze: "· Morphs 3/3 full" on the action bar and the selector. A cow then: "· No room for Cow: morphs full
      (3/3)", nothing added. `/morph list` began with "Morphs 2/3, up to 2 looks of each creature".
- [x] Classic (`/morph mode classic`) with the default `max-variants-per-type: 64`: no second line on any row, no
      prices. With the cap at 3: the sheep row read "Looks 3/3 full", and a fourth sheep's action bar "No room for this
      Sheep look (3/3). Delete one to make room."
- [x] Which creatures have looks, checked against real save data (every mob in [MOB-MASSES.md](MOB-MASSES.md) summoned
      and its look keys read over RCON): of 87, 53 have looks (sheep, villagers, cats, creepers, tropical fish, and
      mobs whose only other look is a baby, such as bees, zombies and polar bears) and 34 have one (blaze, enderman,
      skeletons, iron golem, piglin brute, wandering trader, camel husk, the dragon...); slimes and magma cubes, checked
      on their own, save Size.
      Unit tests cover the same rule, `single-variant-types` and babies switched off.
- [x] `tools/compat_check.py` with the 7.2.0-beta.3 mod against all 13 released servers (6.0.0 to 7.1.3): every one
      OK; older servers send no limits, so the selector shows no room line.
- [x] The released 7.1.3 mod on the 7.2.0-beta.3 server: it connected, got the old-client message, and read the
      collection after a capacity change and a new morph (both send the new fields) with no warnings; its parser stops
      after the rows. Its selector wasn't opened on screen: the window never had focus, and taking it would have
      interrupted someone using the PC.
- Test method: the client window was captured with `PrintWindow` and keys were posted to its window, so no other
  window lost focus or was minimised. This only works on a client window that has had focus since it started.

### Rage set off by choice (7.2.0-beta.5, 2026-11-11)

Same setup as below, on the beta.5 build:

- [x] Before it was charged, a key press (`/morph rage MorphTester2`, which runs what the key sends) said "Rage isn't
      charged: 0/6 hits. It takes 6 within 6s from at least 2 attackers." and nothing happened.
- [x] Four zombies around the player: the meter filled and read READY, and nothing was drained; the zombies kept
      their health. A leader zombie (96 health) then killed the player; after respawning the meter still read READY.
- [x] Set off next to two NoAI zombies and a skeleton: "Rage!", the tendrils and the meter's countdown, all three
      drained to death. Pressed again: "Your rage is spent; hits count toward it again in 13s."
- [x] The one-time hint, first charged with the Rage key unbound: "Your rage is charged. Bind the Rage key in
      Controls (under RE:Morph) to set it off, or type /morph rage." It was on the action bar at first, where the
      server's lines replaced it; it is now a chat line (seen in the client's log).
- [x] A player without the mod (VanillaViewer, Rage upgrade given, charged with `fill`): "Your rage is charged.
      /morph rage sets it off." in chat; set off, a zombie beside it was drained to death in about 4 seconds.
- [ ] Not tried: the Rage key itself, as before (posted keys only reach a test window that has had focus). It sends
      `C_RAGE`, which runs the same code as `/morph rage`.

### Rage, the user's design (7.2.0-beta.4, 2026-11-11)

MorphTester2 (Client 2, the mod) in Survival with the Rage upgrade (`/morph survival MorphTester2 level rage 1`),
Resistance III and Regeneration II so it lived through the test; skeletons and zombies summoned around it at night;
watched by VanillaViewer (no mod) and MorphTester1 (the mod), all three captured without focus:

- [x] Set off by hits: three skeletons 7 to 12 blocks out and two zombies close by. After about 5 seconds of hits, rage
      started by itself ("Rage!" on the action bar, the ravager roar). With the mod, in first person: red tendrils
      from in front of and below the view to each creature, wavering, and the meter (RAGE, a bar running down, "6s").
      Without the mod: wavering red dust lines from the player to each creature. Every creature lost 2.5 health each
      half second and died; the action bar then read "Rage: absorbed 3 creatures" (the test player's biomass was
      already full), and the meter showed the wait ("0:20").
- [x] The first run's reach of 10 blocks missed a skeleton shooting from about 12, and the player died; at 16 every
      skeleton was caught. A first run had no hits counted at all: the test player had Resistance V, which takes all
      the damage, and a hit that does nothing doesn't count.
- [x] One attacker alone (a zombie hitting for 15 seconds): the meter stopped at five of six segments and nothing was
      drained.
- [x] With `targets: all` and a 20-second rage (test settings, put back after): a zombie lost 5 health a second, a
      villager 1.25 (a quarter: the lesser rate), and a player without the mod 1.25 once its food healing ran out.
      Started again at 6.3 health, the player went down to 2.0 (one heart) and stayed there: not killed.
- [x] Survival reach: with biomass emptied, a NoAI zombie 10 blocks away (reach 3) was drained and gave its 21 biomass;
      a chicken beside it, not an enemy, was left alone.
- [x] Seen by another player with the mod (MorphTester1): tendrils from the raging player to each creature, then, as
      each died, the usual absorb animation pulling it in.
- [x] Updating a server: `abilities.yml` 6.6.0 was replaced (backup kept) and has the new `rage` section; `survival.yml`
      7.0.0 gained `upgrades.rage` with every value kept.
- [x] Unit tests 31/31.
- [x] `tools/compat_check.py` with the 7.2.0-beta.4 mod, and again with beta.5, against all 13 released servers, 6.0.0 to
      7.1.3: every one OK both times.

### Ears and tails for players without the mod (7.2.0-beta.4, 2026-11-11)

A fresh head dump (`MORPHREBORN_DUMPHEADS=1 gradlew runClient`) and `tools/gen_head_pack.py`, then MorphTester2 with
borrowed forms over RCON (`/morph borrow`), watched by VanillaViewer with the pack installed locally:

- [x] The dump: 91 heads as before, every head's elements unchanged (ears only marked), every mob with a torso box, and
      ears and a tail for armadillo, axolotl, camel, camel husk, cat, donkey, fox, horse, mule, ocelot, parrot, rabbit,
      skeleton horse, wolf and zombie horse (the mod's own test picks the same). The generator changed no head model
      and no head numbers; it added `models/ears`, `models/tail` and their numbers.
- [x] Own body + wolf: grey wolf ears on the player's head, turning with it, and the tail hanging from the small of the
      back. Zombie + fox: fox ears and the orange, white-tipped tail at the back. Cow + cat: small ears on the cow's
      head, the tail at its rear. Zombie + horse: the horse's two ears on top, tilted back as on a horse, and its long
      tail down the back. Own body + camel: the camel's ears standing out of the sides of the head.
- [x] Only the ears and tail displays are there (no head display) for these animals.
- [ ] A grey column above the player in the viewer's screenshots is an invisible entity of Lib's Disguises that only
      spectators see (the viewer was a spectator); nothing of RE:Morph's.

### Heads for players without the mod (7.2.0-beta.3, 2026-11-11)

MorphTester2 (Client 2) as the subject, `/morph force` and `/morph borrow` over RCON, watched by VanillaViewer
(`runVanilla`, the mod switched off, RE:Morph's pack installed locally):

- [x] Before: a cow borrowing its own head (`/morph borrow MorphTester2 self`) looked like a plain cow, though the
      `player_head` display was there (scale 0.616). Scaled to 3 over RCON it appeared, hanging below its anchor. A
      block ruler beside a test display showed a skull item at scale 1 is half a block (real size), its top at the
      display's origin, its face to the back. The viewer's log also had "Failed to verify signature on property ...
      textures" from the offline account's empty skin; the game still draws a default skin for it, so that wasn't it.
- [x] After: the player head sits on the cow's neck facing forward and covers the cow's head (front, side and back
      views); with `vanilla-heads: auto` and no pack loaded, the player head still shows and a lent zombie's skull sits
      the same way.
- [x] Ghast on a cow: the body on the cow's head with the tentacles hanging under it (before: spread in a wide ring
      on the ground). Villager on a cow: upright, its back on the cow's neck. Giant on a cow: a big zombie head, 1.5
      times the cow's (as designed). A giant wearing a cow's head: on the giant's neck (before: floating above it).
- [x] Fresh head dump (`MORPHREBORN_DUMPHEADS=1 gradlew runClient`) against the 2026-11-07 one: 91 heads both times,
      19 changed, each only by its root scale (same parts, sizes times 4.5 for the ghast, 6 for the giant, 0.9375 for
      illagers and villagers...). The generator then changed those 19 models and plugin entries, and the brim rule
      four entries (villager, wandering trader, witch, zombie villager); nothing else.

## 7.1.3, your own body in multimorphs (2026-11-11)

MorphTester2 (7.1.3 mod) wearing each combination, watched by MorphTester1 (7.1.3 mod and First-person Model) and
VanillaViewer (no mod), all three facing it from the front or from behind; driven over RCON (`/morph borrow`).

- [x] Before: "Your own body can't be combined." Now own body + bat: bat wings on the shoulder blades, from both
      modded clients; the server reports "own body, combined".
- [x] Own body + parrot, phantom, bee, allay, ender dragon, blaze, cod, axolotl: wings, rods, fins and tails where
      they go on a standing body (the dragon's scaled to it).
- [x] Own body + cow, creeper, spider, slime: the borrowed head in place of yours, turning with it.
- [x] Own body + fox, wolf, horse, cat, rabbit, camel, donkey, armadillo (after the ears-and-tail change): your head
      with their ears on top and their tail at the small of your back, checked side on and from behind at close range
      once the user saw them floating: each ear reaches into the head, each tail starts at the back (the camel's flat
      tail just shows behind it). Name tags hidden (a team and `appearance.name-tags: hidden`) so nothing covered them.
- [x] Own body + ghast: a ghast head the size of yours, tentacles over the chest (it was a full-size ghast). Own body +
      slime: the cube in place of your head; for players without the mod it now covers the head instead of sitting
      behind it.
- [x] Own body + giant: the giant's head 1.5 times yours. Giant + your own head: your head at the giant's head size.
- [x] Horse and cow + your own head: the creature with your head where its neck was. The combination came back after a
      relog and after a server restart.
- [ ] Players without the mod, still wrong (left for 7.2.0, see the roadmap): your head on a creature isn't shown, a
      borrowed ghast's tentacles sit by the feet, a giant's head floats above the head.
- [x] Plugin unit tests: 29 of 29.

## 7.1.2, sulfur cubes (2026-11-07)

A sulfur cube morph holding TNT (a player without the mod), and a player with the mod hitting and lighting it:

- [x] Before the fix: a player hit took it from 4 health to 2, and nothing could light it.
- [x] A 6-damage player hit: no damage (the server reports it immune), and it bounced about 1.6 blocks away.
- [x] Flint and steel on it: lit directly (one use of the flint, not a single fire block placed around it), blew up
      6 s later. (Tested from a client with "use" on a key: synthetic mouse clicks had left the client's use button
      held, which lit fire next to the cube instead and muddied the first tries.)
- [x] Fire damage: lit, blew up 6 s later; fire itself still hurts it, as in vanilla.
- [x] Primed TNT going off 3 blocks away: no damage to the cube, and it blew up about 2 s later (the short fuse).
- [x] Later, on the released 7.1.2 jar: a real arrow did no damage and pushed it half a block; an 8-block fall did no
      damage (a cow morph lost 5 health from the same fall); a fire charge lit it (one charge used, blew up 5 s later);
      a burning arrow did no damage and lit it (blew up 6 s later).

**More, the same day (7.1.2 release jar):**

- [x] Dying as a morph: blown up as the TNT sulfur cube, the player came back as the same cube
      (`keep-morph-on-death: true`, the default).
- [x] Milking a goat morph (bucket to milk) and stew from a mooshroom morph (bowl to mushroom stew), another player
      with the mod using them.
- [x] A zombie villager morph with Weakness eating a golden apple itself: one apple used, the cure started (shaking,
      the 3-minute timer), Weakness became Strength.
- [x] A piglin morph in the Nether: no shaking, still a piglin after 20 s. Taken to the Overworld it shook; brought
      back to the Nether after 9 s it stopped and stayed a piglin (the countdown starts over, as vanilla's).

## 7.1.0, vanilla conversions (2026-11-07)

Tried on the test server with two modded clients and a vanilla one; each conversion watched from all three.

- [x] Lightning on a pig morph (a survival player): zombified piglin for everyone; the selector still shows Pig;
      `/morph info` shows the form and "turned into (not saved)".
- [x] Piglin in the Overworld: shakes (the user watched it: the vanilla shake), zombifies 15 s later.
- [x] Villager (librarian) killed by a zombie on Hard: zombie villager, librarian kept, full health; it burns in
      daylight like one.
- [x] Cure: Weakness, then a golden apple fed by another player: shakes, villager 3 minutes later.
- [x] Zombie under water: shaking after 30 s, drowned at 45 s.
- [x] Skeleton in powder snow, at night: shaking after 7 s, stray at 22 s. (At noon the burning skeleton melted the
      snow, as in vanilla.)
- [x] Sulfur cube given TNT by another player: holds it; seen inside by others.
- [x] First-person Model with multimorphs (the user asked for odd ones): the borrowed head never shows (a
      transformation into zombie + cow, watched frame by frame); borrowed parts show on the body (blaze rods around
      the legs; a zombie with the dragon's wings sees them out to the sides looking down; phantom wings stay behind
      the camera); the dragon body with a cow's head keeps the camera at the dragon's head with a clear view; looking
      ahead stays clear in all of them.
- [x] The dragon's tail (the user asked if its tip was missing): the 26.3 model's tail is 12 equal segments ending
      square, with no tip piece; the morph, the preview and a real dragon side by side end the same way.
- [x] Multimorph parts morph with the transformation (asked by the user): zombie + bat into cow + phantom, frame by
      frame: the bat wings go with the zombie's silhouette, the phantom wings grow in with the cow's.
- [x] Milking a cow morph (another player with a bucket): milk. (Was on the untried list.)
- [x] Villager (desert farmer) struck by lightning: witch for everyone, with the witch's potion ability; the selector
      still shows Villager.
- [x] Mooshroom struck by lightning: brown, and red again with a second bolt (once per bolt, though a bolt hurts for
      several ticks). Flipping back still listed a red-mooshroom conversion; fixed for 7.1.1, it now ends the
      conversion.
- [x] Husk under water: zombie at 45 s; still under water, that zombie shook and became a drowned 45 s later (as in
      vanilla).
- [x] Sulfur cube fed TNT by another player (a real right click; one TNT used), set off with sneak + F by the player
      without the mod (Classic mode: in Survival its abilities were locked): it blew up after its 6 s fuse and the
      body went back to an empty cube.

## 7.0.0, Multimorph (2026-11-07)

Tried with two modded test clients and a vanilla one on the test server; combinations mostly through
`/morphclient preview` (built for this), the rest in the world.

**Combinations (client mod).** A zombie body against every borrowed form (about 90), then other bodies across the size
groups: cow, pig, chicken, horse, camel, camel husk, llama, ravager, sniffer, spider, slime, bat, cod, ghast, iron
golem, phantom and the ender dragon, and a baby cow in the world. Found and fixed:

| Seen | Fix |
|---|---|
| Blaze rods reached past the feet into the ground | Rods spread over the body's whole height |
| Chicken wings at a zombie's hips | Wings on a standing body join at the shoulder blades (92% up the torso) |
| Guardian, elder guardian, goat, ghast, happy ghast and squid heads floated above the neck (spikes, beard, tentacle tips counted as the bottom) | The neck point comes from the head's big blocks only |
| Pufferfish lent fins too small to see | It lends its whole body |
| Chicken and bat wings vanished into a cow's or pig's back | Wings on a lying body go on its flanks, high up |
| Camel or llama + panda: the head sank into the chest (the user saw it; their head parts hold the long neck) | A neck point inside the torso moves out to its top or front |
| A dragon body was a speck in the preview and couldn't be turned | Framed by its model; a GUI dragon faces like any body (selector tiles too) |
| A borrowed head on a dragon was a speck on the end of the neck | Scaled up to the dragon's head |
| A borrowed head turned and nodded twice as far as the body's (in the world too) | Posed at rest, turned once by the body's look |
| A baby body borrowed an adult's head | It borrows the creature's baby |

Looked right without changes: horse + cow / phantom, spider + phantom / wolf, slime + cow / phantom, ghast + wolf,
ravager + phantom / fox, sniffer + bat / polar bear, camel + chicken, iron golem + wolf, cow + phantom (wings at the
withers), the dragon with a cow's or a trader's head. Players without the mod: camel + panda (the head covers the
camel's), baby cow + wolf, iron golem + wolf, zombie + creeper skull.

**Fullscreen (the most likely way it's played).** The Shift-pick in the selector: red tile with tendrils, the panel on
the right, Creeper + Iron Golem at 75 biomass ((7 + 53) + 25%). The favourites wheel: the panel covered the right of the
wheel (the user's concern), so while a pair is being picked the wheel now slides left to clear it, and picking follows
it. The preview screen.

**Paper doll head modes** (the user: not solid). Follows You, Turns Only, Looks Up/Down Only and Straight checked with
the head turned 40 degrees and looking up, with a plain and a borrowed head (the borrowed one kept looking up in the
level modes until the double-turn fix above). Looks at You turned the head the wrong way (at a 90-degree turn the doll
showed the back of its head): fixed; its up/down was right.

**Selector order** (the user): variants were in the order they were collected (slimes tiny, big, medium). Now adults
come before babies and bigger before smaller (slimes, magma cubes, sulfur cubes), then the order collected.

**Other parts not tried before:** a goat's head comes back up after a ram (half a second); a creeper morph struck by
lightning (a summoned bolt) is charged and both kinds of player see the aura, but only out of creative (a creative
player isn't struck, as in vanilla); a rabbit's hop happened but the legs weren't caught on screen.

## 6.6.1 (2026-11-07)

- [x] A Survival player with Biomass Capacity 100 (a 100-pixel bar): 6.6.0 crashed the moment the bar showed
      (`ArrayIndexOutOfBoundsException` in `BiomassHud.render`, index 101). With the fix the client ran on with the bar
      showing (tried with the same fix in a test build); every bar width from 100 to 399 stays inside the bar.

## 6.6.0 (2026-11-07)

Built with 29 plugin tests passing (SemVer, curves, the upgrade tree, the level conversion, the speed wording), then
tried in game in a test client and on the test server; what was tried is ticked below.

- [x] Vignette Size: a wide haze (32 screen pixels) stays smooth. Old settings convert to screen pixels once.
- [x] Show in Menus on: the doll on top of the inventory.
- [ ] Show in Menus on a chest, chat, the pause menu and another mod's settings screen; off: hidden in all of them.
- [x] The doll keeps its size at GUI scales 2 and 3.
- [ ] Update notices: a client older than the server gets the old-client message once per join; an admin gets the
      "newer release" line when GitHub has one; the client's own notice doesn't repeat what the server said; the
      [Modrinth] and [GitHub] links open the right pages.
- [ ] A 6.5.0 client on a 6.6.0 server, and a 6.6.0 client on a 6.5.0 server (the extra handshake field).
- [ ] Survival tree screen: three sections, the Morph Ability orb's tendrils move, links light up as things are bought,
      the Abilities title says to buy Morph Abilities first, hover and click work on the orb.
- [ ] Chest menu (no mod): the same sections in a double chest; every unlock buys.
- [ ] Each ability unlock turns its traits on for the current morph straight away (night vision appears, a spider
      starts climbing, fire stops hurting a blaze); drawbacks (sunburn, water damage) work before any unlock.
- [ ] A cod morph without Water Breathing still breathes underwater; a turtle doesn't until it's bought.
- [ ] Camouflage: before it, a zombie morph is attacked by zombies; after it, left alone.
- [ ] Speeds: transformations and cooldowns are slower than Classic at the start, faster at the top level.
- [ ] Reach 3 blocks at the start (a bow kill at 10 blocks gives nothing), Absorbable Mass level 5 absorbs the dragon.
- [ ] A 6.5.0 survival.yml and player file: the upgrades section is replaced with a console warning and a backup, and
      bought levels convert (reach 6 -> 4, efficiency 2 -> 5).
- [ ] Favourites wheel: letting go in the middle only closes; clicking the orb opens the tree; the orb's tendrils move.
- [ ] A spider's web stays.
- [x] Tried in game (beta.2, test client fullscreen at 1440p, automatic GUI scale, 2026-11-07): letting go in the middle closes the wheel; the
      orb and its tendrils; the tree's sections, with Abilities beside Morph on a short window; a click that opens the
      tree buys nothing; one click buys one level; Night Vision on a cat and Fire Immunity on a blaze switch on when
      bought (fire damage 19 -> 17 before, "invulnerable" after); the speed wording ("6.5 seconds per
      transformation", "ability cooldowns ×1.3"); the old survival.yml migration with its warning and backup.
- [x] Blaze in first person: no smoke in view looking ahead, up or down; the rods still float in view; third person
      still shows the full smoke.
- [x] The goo biomass bar at 1440p, automatic GUI scale: rippling edges, drops, black and red tendrils, the static red
      fill (liked in test).
- [ ] The biomass bar at GUI scale 2 and 3 (unchanged size); a capacity of 51,200; the bar while full (FULL label).

## 6.5.0 (2026-11-06)

Local test server with the 6.5.0 plugin, MorphTester1 with the mod, in Creative, seen in third person.

| Check | Result |
|---|---|
| Server start over a 6.3.0 `abilities.yml` | Replaced with the 6.5.0 file, old one kept as `abilities-old-6.1.6.yml` |
| Creeper, sneak + ability key | Swells and flashes white for the fuse, then back to its normal look with a puff of smoke; no blast, no crater |
| Creeper, ability key | Swells, then explodes as before (crater) |
| Enderman looking nearly straight down, after a step (First-person Model on) | Its shoulders at the bottom edge, the ground ahead clear (before the fix its body sat in front of the camera) |
| Sulfur cube with TNT, sneak + ability key | Full 6-second fuse, then no explosion; the cube looks the same throughout (only the hiss, as for its real explosion) |

Not tested in game: the fakeout from a player without the mod (F without sneaking), and the fizzle sound (test
clients run with the sound almost off).

## 6.4.0 (2026-11-06)

Local test server, MorphTester1 with the 6.4.0 mod and First-person Model 2.7.3 installed.

| Check | Result |
|---|---|
| Client start with First-person Model | Its API found and both handlers registered (log line), no mixin errors |
| Pig, horse, chicken, spider looking ahead and 40-60 degrees down | View ahead clear; no head or face in view |
| Same, looking straight down | Pig and chicken: front of the body below; horse: its back; spider: its legs on both sides and the body below |
| Zombie looking down | Its outstretched arms on both sides, as the mod draws a player's body |
| Husk looking 45 degrees down, after a step | Arms out on both sides, middle of the view clear (before the fix the body sat in front of the camera) |
| Ender dragon, F5 from above | Neck and head point the way the player faces, tail behind (before the fix: tail first) |
| Ender dragon, first person ahead, down and up | Camera at its head: no dragon in view; it turns with the player, so it stays behind |
| Husk to pig with the body view on | The animation plays on the body: morph-skin boxes melt into the pig body |
| Slime | Plain first person: no inside of the cube in view |
| The mod's toggle key, off and on as a pig | Off: the body goes and the plain hand view returns; on: the pig body is back |
| F5 back and front views as a pig | Whole pig with its head, in its usual place |
| Favourites wheel in Survival with four favourites | Biomass button sized to its text ("Biomass 26 / 100"), still opens the upgrade tree; prices under the names (spider 18 in red, iron golem 53, horse 32, ender dragon 20,480 in grey); none under the player's own form |

Tried and dropped during the run: the camera at the head's centre (body out of sight unless looking straight down)
and at the neck (a pig's back filled the view and covered the crosshair).

Not tested in game: held items as a non-humanoid morph with the body view on; swimming, flying, riding and baby forms
in the body view; a transformation between two headless forms (the body view pauses, seen only in the code path).

A body that seems turned away after a teleport or a quick turn is the body lagging behind the head, as vanilla bodies
do when standing still; it lines up after a step.

## Still to check live

**New in 6.5.0**
- [ ] The fakeout from a player without the mod, and how it looks and sounds to onlookers with and without the mod.

**New in 6.4.0**
- [ ] First-person Model's body view across more mobs, and the wheel's prices at a normal window size.

**New in 6.3.0**
- [x] The paper doll's head modes and tilt (7.0.0, above). Still to try: the vignette at different screen sizes.
- [ ] Morph prices over a real Survival run: are 0.01 per kg and the minimum of 1 fair?
- [ ] Trading with a villager morph from a Bedrock player, and the restock the next day.

**New in 5.2.0**
- [ ] A Survival run from the start: getting Wither + Regeneration in a real fortress, the first upgrades.
- [ ] The upgrade menu from Bedrock (Geyser) and without the mod; the upgrade tree screen at different screen sizes.
- [ ] A creeper morph caught in a real thunderstorm (a summoned bolt charges it, 7.0.0).

**New in 5.1.0**
- [ ] Jump forward repeatedly as a chicken, with and without the mod: it should feel like a normal jump.
- [ ] Rabbit hop (R) seen from a modded onlooker: the legs should kick.
- [ ] Bat perching without the mod (sneak + F) and as a Bedrock player.
- [ ] `/morphclient` on a normal client; both Plain settings.

**Re-check on a normal client**
- [ ] The fixes above, especially the strider (now a solid lava surface) and the turtle's land pace.
- [ ] A melee hit as an iron golem, ravager, hoglin, zoglin, warden or creaking plays its attack (both kinds of player).
- [x] A goat's head comes back up after a ram (7.0.0).

**Needs two players**
- [x] **Milking:** a cow morph (7.1.0); a goat, and stew from a mooshroom (7.1.2).
- [ ] **Several players at once:** morphing together, and joining while others are already morphed.

**What other players see**
- [ ] **Players without the mod:** armor shows; the transition plays; a skeleton raises its bow.
- [ ] **With the mod, as an onlooker:** frog mid-jump pose; piglin loading a crossbow.
- [ ] **Bedrock players (Geyser/Floodgate):** they can join, see morphs, use `/morph`, and use abilities with
      sneak + swap hands.

**Server settings and setup**
- [ ] **Ghast and blaze fireballs** follow the `fire` and `explosion-block-damage` settings.
- [ ] **Persistence:** log out and back in as a morph (the player should come back morphed); restart the server (collections
      and favourites kept); die as a morph (the morph is kept, per the `death` settings). The first two were done
      many times during 7.0.0 testing, with a multimorph too; dying as a morph (the morph is kept, by default) was tried on 7.1.2.
- [ ] **Snow golem** melts in a hot biome (the test yard has a desert patch).
- [ ] **Mobs ignoring or hunting morphed players,** by morph. Checked earlier in the project, not re-checked since.
- [ ] **The optional sound resource pack,** for players without the mod.
- [ ] **`per-type-permissions: true`** with a permissions plugin such as LuckPerms.
- [ ] **A mismatched client mod version** is told to update and still works as a player without the mod.
