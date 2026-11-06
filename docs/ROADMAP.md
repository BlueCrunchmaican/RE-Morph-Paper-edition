# RE:Morph roadmap

The working plan: what's done, what's open, what's planned, and why things were decided. Update it
whenever one of those changes.

Current version: **6.1.5** (2026-11-06). Two gameplay modes: Classic (default) and Survival. (5.1.0 and 5.2.0 were
built and tested but never released; their changes ship in 6.1.2.)

## Priority 1: existing functionality

Done in 5.1.0 (details and test results in TESTING.md):

- [x] Piglin and hoglin jiggle: vanilla's zombification shake; morphs are now immune to zombification.
- [x] Same-mob size transitions (baby ↔ adult and other multi-model mobs) animate the whole way.
- [x] Chicken fall keeps horizontal movement (client physics with the mod, Slow Falling without).
- [x] Rabbit R hop restored as its own `hop` ability with the rabbit's animation. Walking animation checked: not in
      the original, left alone.
- [x] Bat perching on the ability key, restoring flying or standing on release.
- [x] Fish night vision (cod, salmon, tropical fish, pufferfish).
- [x] Creepers charged by lightning: blue aura for everyone, next explosion twice as big (`charged-multiplier`).
- [x] Charged-creeper morphs, from killing a charged creeper: always charged.

Open, carried over or found while working:

- [x] Changing game mode while morphed no longer drops a flying morph's flight; morphing in creative and then
      switching to survival grants it too. Only flight is redone, so timers like a bee's last minute keep running.
- [ ] **Unmodded chicken fall speeds up on long drops** (Slow Falling). The exact alternative is a relative-velocity
      teleport packet that sets only the vertical speed; it needs testing for rubber-banding before it can replace this.
- [ ] Bat perch accepts any full-faced underside (glass, top slabs). Vanilla bats only rest under solid conductive
      blocks. Tighten it if that matters.
- [ ] Tadpole: in vanilla code a fish, in game a baby frog. Not given night vision; add it if wanted.
- [ ] Rabbit morphs don't hop their legs while walking. The original didn't either; optional: play the hop animation
      on every jump.
- [ ] Still to check live: everything under "Still to check live" in TESTING.md.

## Priority 2: client experience

- [x] Separate Absorbing and Transforming settings (Morph / Plain), local to the player, in a `/morphclient` screen.
- [x] ~~On-screen figure (paper doll)~~: built in 5.2.0, then removed.
- [x] Paper doll (6.1.5): built on the same render path as the world, so it shows the morph, its
      animations, the transformation, poses and vehicles; its own settings page and toggle key.
- [ ] Humanoid morphs don't take the player's swimming or elytra pose (the player renderer turns the body for those;
      mob renderers don't). Sitting in vehicles is copied since 6.1.5.
- [ ] Optional Mod Menu entry for the settings screen (needs Mod Menu as an optional dependency).
- [ ] The Plain transformation always uses the shrink/grow version. If an admin turns `vanilla-transition` off,
      unmodded players see an instant swap instead. The server could tell clients that setting in the handshake.

## Priority 3: Survival Mode

Design: [SURVIVAL-MODE.md](SURVIVAL-MODE.md), from iChun's unfinished 1.16 code and his own description.

Built for 6.1.2 (2026-11-05). How it works, the tree and its numbers: [SURVIVAL-MODE.md](SURVIVAL-MODE.md).

- [x] `gameplay-mode: classic | survival`, `GameplayMode` with Classic and Survival, progress saved in both modes.
- [x] Biomass unlock (Wither + Regeneration together), biomass per kill (iChun's formula), reach and mass limits.
- [x] Upgrade tree in `survival.yml`, chest menu for everyone, `/morph upgrades`, `/morph upgrade`, `/morph biomass`,
      admin `/morph survival`, a Biomass Menu key (B until 6.1.5, unbound since) and biomass bar with the mod.
- [x] Gating: morphing, collection capacity, abilities (and cooldowns), flight (gliding until Morph Flight),
      transformation speed.
- [x] Decisions made: biomass only before the Morph Ability; Classic collections kept but locked; biomass buys
      upgrades only; death costs nothing; iChun's upgrade names.
- [ ] A real "A Mutation Complication" advancement (generated datapack).
- [x] A client-side upgrade screen: an advancement-style tree for players with the mod (chest menu for everyone else).
- [x] The modded biomass bar drawn in the morph's static-and-tendrils style, in red.
- [x] A Biomass button in the middle of the favourites wheel; status effect icons in the upgrade tree.
- [ ] More trait tiers (iChun's `upgradeFor`); optional biomass costs for abilities.
- [ ] Balance from real play.
- [ ] Biomass values in the mob reference table.

- [ ] Singleplayer: everything runs in the Paper plugin, so the client mod does nothing without a server running it.
      Supporting singleplayer would mean running the server side inside the mod's integrated server.

## Priority 4: long-term compatibility

- [ ] Modded and plugin mobs. Keep variant identity behind one method (a future `MorphIdentity` hook). Abilities
      already key on namespaced type ids and biomass on the bounding box. See SURVIVAL-MODE.md.

## Priority 5: public release

Repository: https://github.com/BlueCrunchmaican/RE-Morph-Paper-edition

- [x] Licence review: [THIRD-PARTY.md](THIRD-PARTY.md), decisions at the end of it.
- [x] Renamed to **RE:Morph** (formerly Morph Reborn); internal ids kept, see the decisions log and
      [BUILDING.md](BUILDING.md).
- [x] Root `README.md`: what it is, iChun as the original creator, unofficial status, requirements, install,
      versions, features, docs, credits, licence.
- [x] Build guide: [BUILDING.md](BUILDING.md), including the Lib's Disguises jar a fresh clone needs.
- [x] `git ls-files` review: nothing from `reference/`, `test-server/` or `release/`; no personal paths.
- [ ] Ask iChun? Optional, but the project uses his name and design.
- [x] GitHub release for 6.1.2 with the jars attached (the repository only holds source).
- [x] GitHub release for 6.1.4.
- [ ] GitHub release for 6.1.5.
- [ ] Split GUIDE.md into a server guide and a client guide.
- [ ] Mob reference: add columns for "needs the client mod" and special interactions to ABILITIES.md.
- [ ] Mob spreadsheet: generate `mobs.csv` (and an .xlsx) from abilities.yml with `tools/gen_abilities_doc.py`, so
      it can't drift from the real config.
- [ ] `docs/PROTOCOL.md`: both `Wire.java` files say it's the shared spec, but it doesn't exist.

## Decisions log

| Date | Decision | Why |
|---|---|---|
| 2026-11-03 | Server templates don't set NoAI | Unmodded clients froze dragon wings and copper golems |
| 2026-11-03 | abilities.yml / variants.yml carry `config-version` and are replaced (with a backup) when older | Old files silently broke renamed abilities (the witch) |
| 2026-11-05 | Piglin/hoglin shake fixed with zombification immunity, not NoAI | NoAI breaks other mobs' animations |
| 2026-11-05 | Transition captures make the renderer choose its model through a throwaway submit, not per-renderer code | Covers every multi-model renderer, including future ones |
| 2026-11-05 | Movement physics that need the player's own speed run on the client when it has the mod (chicken joins climb, webs, powder snow, lava) | The server can only replace a player's whole velocity |
| 2026-11-05 | Client protocol 2 | The server now relies on the client for the chicken fall and the bat's pose; older clients are treated as unmodded rather than half-working |
| 2026-11-05 | "Plain" animation settings draw the unmodded presentation locally | Keeps it client-only, no protocol change, no effect on others |
| 2026-11-05 | Bat perch held by flying at zero fly speed | Nothing fights gravity, and the earlier flight state restores exactly |
| 2026-11-05 | Survival Mode (renamed from Default Mode) is a `GameplayMode` on top of the shared systems, with progress saved in both modes | One code base; switching modes never loses data |
| 2026-11-05 | The upgrade menu is a server chest menu, not a client screen | Works for players without the mod and Bedrock players too |
| 2026-11-05 | Survival numbers rebalanced | iChun's were placeholders (one zombie overflowed the starting capacity 40 times) |
| 2026-11-05 | Client protocol 3 | The mode, biomass and can-morph status reach the client for the selector and the bar |
| 2026-11-05 | Renamed to RE:Morph; plugin `RE-Morph`, jars `RE-Morph-paper` / `RE-Morph-fabric` | The name for the public release. The plugin moves `plugins/MorphReborn` to `plugins/RE-Morph` on first start |
| 2026-11-05 | Kept the internal ids (`dev.morphreborn`, mod id, channel, permissions, saved-data keys) | Changing them would break live servers' permissions, saved attribute modifiers and older clients |
| 2026-11-05 | config.yml is updated on startup when its `config-version` is older: new settings merged in, every value kept, old file backed up (6.1.4) | Settings added after a server's first install (gameplay-mode among them) never reached its config.yml; config files should follow new releases |
| 2026-11-05 | `/morph mode classic\|survival` switches live, saves to config.yml; /morph reload applies a hand-edited mode (6.1.4) | Admins needed an in-game command; every rule already asks `plugin.mode()`, so only online players need bringing in line |
| 2026-11-06 | The hitbox and copied attributes change in one step at the transformation's half-way point, growing only into free space | Easing them every tick desynced client and server movement (corrections, jitter); vanilla never moves a growing player out of blocks |
| 2026-11-06 | The modded client eases the camera height over the transformation itself | The one-step hitbox change made the camera jump; it should follow the animation |
| 2026-11-06 | Paper doll drawn from `EntityRenderDispatcher.extractEntity`, the world's own path, into its own off-screen texture | The doll shows exactly what the world shows, with no animation code of its own and no effect on the real entities |
