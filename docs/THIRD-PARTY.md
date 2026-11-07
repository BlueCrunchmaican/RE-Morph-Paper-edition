# Third-party components and licences

Everything RE:Morph uses, ships or links to, and on what terms. **Status** says how sure each row is:

- **Checked:** confirmed from the licence file or the project's own repository.
- **To verify:** not confirmed at the source. Only rows for things we never redistribute are left like this.

Being publicly downloadable is never treated as permission to redistribute.

Last reviewed: 2026-11-05 (RE:Morph 6.0.0, before the first public push).

## Original work this project is derived from

| Component | Author | What we use | Licence | Status | Source | Redistribute in our repo? |
|---|---|---|---|---|---|---|
| Morph (1.16.5 source, 10.2.1) | iChun | Design and algorithms re-implemented: selector HUD, radial favourites, variant model and whitelists, transition timeline and box interpolation, acquisition tendrils, the Survival Mode plan (docs only) | LGPL-3.0 | Checked: `COPYING.LESSER` in the source tree | https://github.com/iChun/Morph | Yes, as a derivative under LGPL-3.0, with the licence text and a notice of changes |
| iChunUtil (1.16) | iChun | `ModelHelper` part interpolation, `RenderHelper.createInterimStackEntry` (re-implemented) | LGPL-3.0 | Checked: `COPYING.LESSER` in the source tree | https://github.com/iChun/iChunUtil | Same as above |
| Morph textures: `gui_selected.png`, `gui_unselected.png`, `gui_unselected_side.png`, `fav.png`, `morphskin.png` | iChun | Copied unmodified into the Fabric mod | LGPL-3.0 (covered by the repository licence) | Checked: the repository has no separate asset licence or credits, so its licence covers them | Morph repository, `assets/morph/textures` | Yes, with attribution (NOTICE.md) |
| Morph sounds: `morph1.ogg` … `morph6.ogg` | iChun's repository | Copied unmodified into the Fabric mod and the optional resource pack | LGPL-3.0 (repository licence) | Checked as far as possible: the repository doesn't say where the sounds came from, and names no other terms | Morph repository, `assets/morph/sounds` | Yes, credited to iChun's repository. Small leftover risk: if anyone shows they're under other terms, replace them |

## Runtime dependencies (players or admins install these; we never bundle them)

| Component | Purpose | Needed at | Author | Licence | Status | Official source | Redistribute? |
|---|---|---|---|---|---|---|---|
| Paper 26.3 | Server software the plugin runs on | Runtime (server) | PaperMC | GPL-3.0 (Bukkit/CraftBukkit heritage); Paper's own patches MIT | To verify | https://papermc.io, https://github.com/PaperMC/Paper | No: admins install it |
| Lib's Disguises 26.10.24 (Free) | Shows morphs to players without the mod | Runtime (server) | libraryaddict | Not stated in a form we could read: the jar has no licence file; the repository's README says nothing about redistribution and forbids bypassing the code that limits paid features | Checked as far as possible | https://github.com/libraryaddict/LibsDisguises, https://www.spigotmc.org/resources/libs-disguises-free.81/ | No: link only. Never commit the jar (`reference/supplied-jars` and `paper-plugin/libs` are git-ignored). We use the Free build's public API and never touch its paid limits |
| PacketEvents 2.14.0 | Packet library Lib's Disguises needs; we send a few animation packets through it | Runtime (server) | retrooper and contributors | GPL-3.0 | Checked: repository README badge and Maven metadata | https://github.com/retrooper/packetevents | No: link only |
| Fabric Loader 0.20.1 | Loads the client mod | Runtime (client) | FabricMC | Apache-2.0 | To verify | https://github.com/FabricMC/fabric-loader | No: players install it |
| Fabric API 0.165.0+26.3 | Events, key mappings, client commands, rendering hooks | Runtime (client) | FabricMC | Apache-2.0 | To verify | https://github.com/FabricMC/fabric | No: players install it |
| Minecraft Java Edition 26.3 | The game | Runtime | Mojang | Minecraft EULA and Usage Guidelines | To verify the current wording | https://www.minecraft.net/eula | Never: no Minecraft code or assets go in the repo |

## Optional compatible mods (players may install these; we never bundle them or use their code)

| Component | Purpose | Author | Licence | How RE:Morph uses it | Official source | Redistribute? |
|---|---|---|---|---|---|---|
| First-person Model 2.7.3 for 26.3 | Draws the player's own body in first person; with it installed, a morph is that body | tr7zw | tr7zw Protective License | Its public API (`FirstPersonAPI.registerPlayerHandler` with a `PlayerOffsetHandler` and an `ActivationHandler`), reached by reflection at runtime. No compile dependency; none of its code or assets in our repo or jars | https://modrinth.com/mod/first-person-model | No: players install it themselves |

## Build-time only (not in any jar we ship)

| Component | Purpose | Licence | Status | Source | Notes |
|---|---|---|---|---|---|
| Paper API 26.3.build.235 | Compile against the server API | GPL-3.0 / MIT (as Paper) | To verify | https://repo.papermc.io | `compileOnly`; downloaded by Gradle |
| Lib's Disguises jar | Compile against its API | As above | To verify | as above | Read from `paper-plugin/libs/`, which is git-ignored, so a fresh clone needs it downloaded separately (document in the build guide) |
| PacketEvents (spigot) 2.14.0 | Compile against its API | GPL-3.0 | Checked | https://repo.codemc.io | `compileOnly` |
| JetBrains annotations 26.0.2 | `@Nullable` and friends | Apache-2.0 | To verify | https://github.com/JetBrains/java-annotations | `compileOnly` |
| JSpecify annotations | `@Nullable` (comes with Minecraft's libraries) | Apache-2.0 | To verify | https://github.com/jspecify/jspecify | compile-time only |
| Fabric Loom 1.19 | Gradle plugin that builds the mod | MIT | To verify | https://github.com/FabricMC/fabric-loom | build tool |
| Gradle wrapper (`gradle-wrapper.jar`, committed twice) | Runs the builds | Apache-2.0 | To verify | https://gradle.org | Redistribution allowed with its licence; normal practice is to commit it |
| JUnit 5.11.4 | Unit tests | EPL-2.0 | To verify | https://junit.org | test only |

## Made for this project

| Item | Notes |
|---|---|
| All Java code under `paper-plugin/src` and `fabric-client/src` | Written for RE:Morph; derivative of Morph/iChunUtil where NOTICE.md says so, so the whole project stays LGPL-3.0 |
| `icon.png`, `resourcepack/pack.png` | Not ours: both are byte-for-byte copies of iChun's `gui_selected.png` (LGPL-3.0), credited in NOTICE.md |
| `abilities.yml`, `variants.yml`, `config.yml`, docs, tools | Written for this project. The variant whitelists are ported from the original's defaults (LGPL-3.0) |

## Never published

- `reference/mc-26.3-src/`: Mojang's decompiled code, for reading only.
- `reference/supplied-jars/`: third-party jars, including the unrelated "Morph" Spigot plugin by BumbleBee
  (`Morph-3.7.0.jar`, no licence published). Nothing of it is used; NOTICE.md says so.
- `reference/src/`: copies of iChun's sources (public, but no need to duplicate them; link instead).
- `test-server/`, `release/`, build output.

All of these are in `.gitignore` already. Re-check with `git ls-files` before the first public push.

## Licence decisions (2026-11-05, before the first public push)

1. **Our licence.** LGPL-3.0, inherited from Morph/iChunUtil (`LICENSE`, which builds on `LICENSE-GPL-3.0`).
   NOTICE.md lists what was ported and which assets are iChun's.
2. **GPL-3.0 PacketEvents.** The plugin only calls PacketEvents' API at runtime, and the server admin installs it.
   We don't bundle or redistribute it, and LGPL-3.0 code may be combined with GPL-3.0 code. Nothing to change.
3. **Lib's Disguises.** Named as a required dependency and linked, never mirrored or bundled. We use the Free build's
   public API and never modify or get around the code that limits paid features, as its README asks.
4. **The original sounds.** Their origin isn't documented anywhere in iChun's repository. They ship under the
   repository's licence with credit; if anyone shows other terms, they get replaced.
5. **The name.** "Morph" is iChun's project name, so this project is called **RE:Morph** (formerly "Morph Reborn") and
   says plainly that it's unofficial and not made or endorsed by iChun. Another unofficial revival for 26.2/26.3,
   "Morph Revived", exists on CurseForge (https://www.curseforge.com/minecraft/mc-mods/morph-mod-revived); RE:Morph is a
   separate project. Asking iChun is optional.
6. **Mojang.** No Minecraft code or assets in the repository; the decompiled reference stays local.
