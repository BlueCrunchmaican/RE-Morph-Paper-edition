# RE:Morph: technical assessment and architecture

Phase 1 and 2 output. Written 2026-11-01, before implementation started.

## 1. What was supplied

| File | What it is | Role in this project |
|---|---|---|
| `Morph-1.16.5-10.2.1.jar` | iChun's Morph, Forge 1.16.5, LGPL-3.0 | **Primary reference** for behaviour and presentation. The matching source was cloned from `github.com/iChun/Morph` (branch `1.16`) into `reference/src/morph-mod-1.16`. |
| `iChunUtil-1.16.5-10.7.0.jar` | iChun's shared library, LGPL-3.0 | Supplies the model-capture and interpolation helpers the transition uses, plus the KeyBind wrapper, `sineifyProgress` and GUI helpers. Source in `reference/src/ichunutil-1.16`. |
| `iChunUtil-1.21.5-Fabric-1.0.7.jar` | Fabric iChunUtil | Not used. It has no Morph build that pairs with it. |
| `Morph-3.7.0.jar` | BumbleBee's "Morphy" Spigot plugin | Reference for which abilities and admin options exist. **No public source or licence was found**, so none of its code is copied (see §6). |
| `LibsDisguises-26.10.24-Free.jar` | Disguise plugin | Kept as the vanilla-client disguise backend. |
| `ProtocolLib.jar` (5.4.0) | Packet library | **Not needed.** Neither Morphy nor Lib's Disguises 26.x depends on it. Lib's Disguises uses PacketEvents. |

All originals are untouched in `Downloads\New folder (2)`, with a copy in `reference/supplied-jars`.

## 2. How the original Morph mod works

### Unlocking
- `LivingDeathEvent` fires when a player kills a mob → `ClassicMode.handleMurderEvent` → `createVariant(living)`.
  - Shipped default is `CLASSIC` mode. The biomass `DEFAULT` mode is force-disabled in `ConfigServer` (`//TODO remove this to enable default mode`).
- Killing a player who is currently morphed gives you their *current morph*. Killing an unmorphed player gives you that player as a morph.
- **Variant capture** in `MorphHandler.createVariant`:
  1. Strip the mob's equipment and record its "supported attributes" (max health, speed, attack, armour…) into `nbtMorph`.
  2. Write the entity's full NBT.
  3. Apply an **NBT whitelist** per entity class from `mobsupport.zip/nbt/*.json`. Examples: Sheep keeps `Color` and `Sheared`, Cat keeps `CatType` and `CollarColor`, Villager keeps `VillagerData`, LivingEntity keeps `CustomName`, Mob forces `NoAI` and `PersistenceRequired`.
  4. Apply special setters: babies get `Age=-24000`, panda genes are normalised, the wither keeps its invulnerability glow, angerable mobs stay angry.
- `MorphVariant` stores one entry per entity type. Tags shared by every variant are in `nbtCommon`; the tags that differ are in each `Variant.nbtVariant`. Each variant gets a random 20-character identifier and an `isFavourite` flag.
- Two variants are the same morph when their whitelisted tags are equal. That is the whole variant system: **appearance = whitelisted entity data.**
- Collections are saved in world `SavedData`. The player's own form is always entry 0 (`default_player_state`) and can't be deleted.

### Selector GUI (`client/core/HudHandler`, 1,456 lines)
- A HUD overlay, not a `Screen`.
- **Vertical column:** one 50×50 tile per entity type (`gui_unselected.png`), drawn at the left edge, `selectorDistanceFromTop`=30.
- **Horizontal row:** the selected type's variants extend to the right (`gui_unselected_side.png`, capped by `gui_unselected`). `gui_selected.png` marks the cursor.
- **Slide-in:** the selector slides in from the left over 8 ticks (`SHOW_SELECTOR_TIME`).
- **Scrolling:** moving the cursor animates over 4 ticks (`INDEX_TIME`) with `sineifyProgress` easing between `lastIndex` and `index`. Both axes wrap around.
- **Entity previews:**
  - Each tile shows a live entity instance via `InventoryScreen.drawEntityOnScreen`. Scale is `0.5/max(1, max(w,h)/1.95)`, tilted −10° on X, facing yaw 35 / pitch −60.
  - The selected tile's entity scales back up when the cursor lands on it.
- **Names:**
  - Shown to the right of the tile. Gold = your current morph, yellow = selected, white = other.
  - A custom-named mob shows its name in italics underneath.
- **Favourites:**
  - A star (`fav.png`) in the tile's corner.
  - A cyan star on the type tile means "this type has a favourite variant".
- **Radial favourites menu** (hold `` ` ``):
  - A translucent black ring with a 0.55 dead zone. Your player form plus every favourite is laid out around it.
  - Selection follows the mouse angle and the entry under the mouse grows. Releasing the key confirms.
- **Keys and mouse:**
  - `[` / `]` open the selector and move up/down; `Shift`+`[` / `]` move left/right. Arrow keys also work.
  - Enter or left click confirms. Esc or right click closes. Middle click or `` ` `` while open toggles a favourite.
  - Delete removes a morph (classic mode only).
  - The mouse wheel scrolls (`Shift` scrolls horizontally).
- The keybinds are `IN_GAME_MODIFIER_SENSITIVE` (iChunUtil), so they don't fire while typing in chat. If a screen opens, the selector closes.

### Morph state and transition (`MorphInfo`, `MorphRenderHandler`)
- **Timing:**
  - State is `prevState` → `nextState`, `morphTime`/`morphingTime`. Default **100 ticks**.
  - Progress splits **12.5% / 75% / 12.5%**:
    1. The old body is covered by the morph skin as `alpha = sine(p/0.125)`.
    2. A box-by-box interpolation runs, eased with sine over the middle 75%.
    3. The skin fades off the new body.
  - The mob's hitbox and eye height are interpolated over the same curve.
- **How the morph animation works:**
  - Both entities are rendered with a mixin that captures every `ModelRenderer.render` call (pose matrix plus part) instead of drawing it.
  - Lists are padded with zero-size parts so both have the same length.
  - Each pair is interpolated: the pose matrix element-wise and the box position, size and inflate linearly.
  - The result is drawn with `textures/skin/morphskin.png` (`entityTranslucent`). This is the "melting" look.
- **Sound:** `morph:morph` (six 3-second variations), played at `(morphingTime-60)/2` ticks so it is centred.
- **Attributes:** the mob's attributes are applied to the player and blended over the transition (`supportedAttributes`). With the default caps, max health is at most 20 and speed at most 0.1.
- **Multiplayer:** the server is authoritative. `PacketMorphInfo` (the full state as NBT) goes to everyone tracking the player and to the player. Each client runs the same tick and renders it locally.

### Acquisition animation (`EntityAcquisition`)
- The server sends `PacketAcquisition(origin, acquired)` to trackers.
- The client:
  1. Removes the dying mob.
  2. Freezes its pose and covers it with morph skin over 10 ticks.
  3. Grows up to 5 morph-skin **tendrils** (chains of boxes, re-aiming each segment by ≤30°) from the player to the mob.
  4. The tendrils retract, spinning, carrying the captured mob model back into the player.

### Other parts
- **Mob traits and abilities:** JSON in `mobsupport/mob`. Examples: hostile mobs ignore you, fire immunity, water breathing, flight (creative-style) and flap-flight, fall negation, climbing, slow fall, step height, sunburn, water sensitivity, sink/float, intimidate, effect resistance, attack effects.
- **First-person hand:** `HandHandler` plus `mobsupport/hand/*.json` render the morph's limb as your hand.

### From iChunUtil
- The `ModelHelper` part capture and interpolation, `createInterimStackEntry`, `sineifyProgress`, the `KeyBind` wrapper with holdable keys and modifier contexts, `MouseHelper.getSelectedIndex` for the radial menu, and `EntityHelper` profile lookups.
- None of it can be reused binary-wise on 26.3. The algorithms are small and get re-implemented in the Fabric mod.

## 3. How the existing Morphy plugin works (from its jar metadata, configs and class list)

- **Dependencies:** hard-depends on **Lib's Disguises**; `pom.xml` also lists PacketEvents and ProtocolLib. Built against Spigot API 1.8.8 through 26.1.2, Java 9 bytecode.
- **Disguises:**
  - It disguises the player as a `MobDisguise` of the type.
  - **Variants are not captured.** One type is one morph. There is no sheep colour, villager profession or baby handling beyond what Lib's Disguises defaults to.
- **Scaling:**
  - `enableScale` sets the player's `GENERIC_SCALE` attribute to the per-mob `scale:` value in `mobConfig.yml`.
  - Lib's Disguises applies the player's scale attribute to the disguise as well. Its `self_disguise.yml` warns that other scale modifiers "may behave unexpectably".
  - **This is the likely cause of the "mobs are half size" bug.** Not yet confirmed by test; it is test case T-SCALE in §8.
- **Selection:** a chest-inventory GUI, `/morph`, or Crouch+F.
- **Progression:** kill N mobs (`requiredKills`) per type, a per-mob morph time limit and cooldown.
- **Abilities:**
  - Creeper explosion, enderman teleport, ghast and blaze fireballs, spider climb plus web, rabbit jump, pufferfish poison, bee sting, llama spit, evoker fangs and vex, warden sonic boom, snow golem snowballs.
  - Night vision, water breathing, fire resistance and speed effects.
  - "Morph power" flight.
- **Data and permissions:**
  - Player data is in flat files.
  - Permissions are per mob: `morph.into.<mob>`, `bypasskill`, `bypassreset`, `bypasstime`. The `bypasskill.*` children are mis-declared as `morph.into.<mob>: false`.

### Why it is not used as the foundation
1. Its whole data model is "one entity type = one morph". Variants and babies, priority 5, would mean replacing that model anyway.
2. It has no client protocol, transition state machine or acquisition events, so none of priorities 1–4 can build on it.
3. Its source is not available and no licence was found, so we couldn't legally redistribute a modified version even if we decompiled it.

**Decision:** clean implementation. The original mod is the behaviour reference and Morphy is a checklist of server features worth keeping.

## 4. Target platform (verified 2026-11-01)

| Component | Version | Source |
|---|---|---|
| Minecraft | 26.3 (unobfuscated, Mojang names) | |
| Paper | 26.3 build 235 (STABLE, 2026-10-31) | fill.papermc.io |
| Paper API | `io.papermc.paper:paper-api:26.3.build.235-stable` | repo.papermc.io |
| Fabric Loader | 0.20.1 | Same toolchain as the working Inventory Hotswap 26.3 port |
| Fabric API | 0.165.0+26.3 | ″ |
| Loom | 1.19-SNAPSHOT, Gradle 9.8.2 | ″ |
| Java | 25 bytecode (built with JDK 26.0.1) | |
| Lib's Disguises | 26.10.24 Free (build 1836) | Supplied. Pulls in PacketEvents. |

## 5. Architecture

```
            ┌────────────────────── Paper 26.3 server ──────────────────────┐
            │ RE-Morph plugin (authoritative)                                │
            │  • Collections + favourites (per-player JSON, atomic writes)   │
            │  • Variant capture: entity snapshot SNBT → whitelist filter    │
            │  • Morph state machine (prev/next, 100-tick transition)        │
            │  • Hitbox/eye: player scale attribute eased prev→next          │
            │  • Abilities, attributes, permissions, admin commands          │
            │  • Disguise backend (vanilla viewers) ── Lib's Disguises ──────┼──► vanilla clients
            │  • Client protocol on plugin channel morphreborn:main ─────────┼──► Fabric clients
            └────────────────────────────────────────────────────────────────┘
```

### Variants
- Stored as **whitelisted SNBT**, exactly like the original:
  - Server: Paper `Entity#createSnapshot().getAsString()` → our SNBT parser → keep only whitelisted keys → canonical string. A variant's identity is `entity type + canonical SNBT`.
  - The whitelist is data in `variants.yml`, per entity type with shared groups (ageable, tameable…). A **generic fallback** keeps keys like `variant`, `Variant`, `Type`, `Color`, `Size`, `IsBaby` and `Age`, so mobs added in future Minecraft versions get variant support without new code.
- **Vanilla viewers:** `EntityFactory.createEntitySnapshot(snbt).createEntity(world)` gives an unspawned entity → `DisguiseAPI.constructDisguise(entity)` copies its real data watcher. Colours, professions, babies and so on come out correctly with no per-mob code.
- **Fabric viewers:** the client builds the same entity from the SNBT with `EntityType.create` plus the 26.3 `ValueInput` loader and renders it. This is the same thing the original did with `ent.read(tags)`.

### Who sees what

| Viewer | Sees |
|---|---|
| Fabric client (mod installed) | Real player entity. The mod renders the morph in its place, with the full original transition, using the server's state packets. Excluded from the Lib's Disguises disguise via `TargetedDisguise` observers, updated live as Fabric players join and leave. |
| Vanilla client | Lib's Disguises mob disguise with explicit watcher scale (avoids the half-size bug), plus the vanilla transition below. |
| Morphing player, Fabric | Renders themselves. Self-disguise off. |
| Morphing player, vanilla | Lib's Disguises self-disguise. |

### Vanilla-compatible transition (no custom rendering possible)
1. **0–12.5%:** morph-skin-coloured dust particles condense around the old body. The hurt-free morph sound plays. The original `morph:morph` sounds are offered as an optional server resource pack, with a vanilla sound as fallback.
2. **12.5–50%:** the old form shrinks. The disguise watcher scale goes 1→0.15, or a self player-disguise for player→mob if Lib's Disguises allows it.
3. **50%:** swap the disguise with a particle burst.
4. **50–87.5%:** the new form grows, watcher scale 0.15→1.
5. **87.5–100%:** the particles dissipate.
- The hitbox and eye height ease exactly as in the original, for everyone.

### Acquisition animation for vanilla viewers
- `TRAIL` particles from the mob to the player along 5 jittered paths, plus a skin-colour burst on the corpse.
- Fabric viewers get the original tendril animation.

### Protocol (`docs/PROTOCOL.md`)
- One custom-payload channel, `morphreborn:main`, length-prefixed binary, opened by a `HELLO` with the protocol version.
- The server ignores clients until `HELLO`. Clients with the wrong version are told to update and are treated as vanilla.
- The server always sends full state after any change, so a client never applies its own predictions.

## 6. Licensing

| Work | Licence | Consequence |
|---|---|---|
| Morph (iChun) code and assets | LGPL-3.0 (`COPYING.LESSER` in repo; assets ship in the same repo with no separate terms) | Algorithms are ported and the textures (`gui_*.png`, `fav.png`, `morphskin.png`) and `morph*.ogg` sounds are reused **under LGPL-3.0 with attribution**. Our derived work is released as LGPL-3.0 with full source. |
| iChunUtil | LGPL-3.0 | Algorithms re-implemented in our code, same terms. |
| Morphy (BumbleBee) | No licence or public source found → all rights reserved | **No code, configs or assets copied.** Ability ideas are re-implemented independently from their observable behaviour and config option names. |
| Lib's Disguises Free | Its own licence | Not bundled. The server admin installs it. We only call its public API. |
| Minecraft / Paper / Fabric | EULA, GPL-3 (Paper), Apache-2 (Fabric API) | Standard plugin/mod linking. No Minecraft assets redistributed. |

## 7. What gets ported, adapted or rebuilt

| Feature | Approach |
|---|---|
| Selector layout, timings, easing, keys, mouse, radial, favourites, names, stars | **Ported 1:1** (algorithm and constants) onto 26.3 `GuiGraphics` / HUD layer / `KeyMapping` |
| Variant whitelist and equality | **Adapted:** SNBT on Paper, `variants.yml` instead of class-based JSON |
| Transition interpolation | **Adapted:** 26.3 renders deferred (`SubmitNodeCollector`), so instead of capturing `ModelRenderer.render` calls the mod walks the posed `ModelPart` tree itself and interpolates the flattened cube lists |
| Acquisition tendrils | **Ported** behaviour onto a client-side effect object (no entity registration needed) |
| Hitbox and eye-height easing | **Adapted** to the player `scale` attribute (Paper can't set arbitrary dimensions) |
| Traits and abilities | **Rebuilt** on the Paper API, configurable, with Morphy's actives included |
| Biomass mode | **Not ported:** disabled in the original's shipped config |
| First-person morph hand | Attempted after the core features. Listed as a limitation if not done |

## 8. Test plan

T1–T15 are the original test list. T-SCALE additionally measures the disguise size against a real mob of the same type, side by side, from a second client.

Two dev clients are available through Loom (`runClient`, `runClient2`) against a local offline-mode Paper server. A client without the companion mod stands in as the vanilla viewer, since the protocol and rendering are identical for an unmodded client.
