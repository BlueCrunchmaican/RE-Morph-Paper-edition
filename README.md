# RE:Morph (Paper edition)

Kill a mob, become that mob. RE:Morph brings iChun's [Morph](https://github.com/iChun/Morph) mod to Minecraft Java
26.3 as a Paper plugin, with an optional Fabric client mod that adds the original selector and animations.

> **Unofficial.** RE:Morph is a fan port. It isn't made or endorsed by iChun, who created Morph. It was called
> "Morph Reborn" before version 6.0.0.

## How it works

- **The server does the work.** A Paper plugin handles unlocking, saved collections, abilities and disguises.
- **Players with the client mod** (Java + Fabric) get the original Morph experience: the `[` `]` selector, the
  favourites wheel, and the morph and absorb animations.
- **Everyone else** (plain Java, and Bedrock players through Geyser, which is untested so far) still sees and uses
  morphs. Other players show up as the mob through Lib's Disguises, and morphs are picked with `/morph`.

Nobody is kicked for not having the mod.

## Features

- **Classic mode:** kill a creature to unlock it. Variants unlock separately: sheep colours, villager jobs, cat
  breeds, babies and so on. Killing a player unlocks their form.
- **Survival mode:** iChun's unfinished "Default Mode", completed. Unlock the Biomass ability, absorb biomass from
  kills, and spend it in an upgrade tree on the ability to morph, more storage, reach, faster transformations and
  cooldowns, and each kind of morph trait: night vision, fire immunity, flight, climbing, camouflage and more.
  Each morph costs biomass too, scaled by the creature's size.
  See [docs/SURVIVAL-MODE.md](docs/SURVIVAL-MODE.md).
- **Abilities and traits for 89 of the 90 mobs in 26.3:** flight, swimming, fire immunity, creeper explosions
  (charged by lightning, too), enderman teleporting and block carrying, witch potions, bat perching, rabbit hops,
  skeleton and baby zombie jockeys, trading with villager players, spiders climbing cobwebs, and more. Full table: [docs/ABILITIES.md](docs/ABILITIES.md).
- **Multimorph (7.0.0):** be two morphs at once. A main body borrows a second creature's head, or its wings, tail,
  fins or rods, and gets both creatures' traits and abilities: a zombie with bat wings, a cow with a wolf's head, a
  ghast with a creeper's blast. Shift-pick in the selector or wheel (a preview panel shows the result), or
  `/morph combine`. In Survival it's an upgrade. See [docs/GUIDE.md](docs/GUIDE.md).
- **Conversions (7.1.0):** a morph changes the way its creature does in vanilla: a villager killed by a zombie
  becomes a zombie villager (and can be cured), a zombie drowns, a skeleton freezes into a stray, piglins zombify
  outside the Nether, lightning turns a pig into a zombified piglin. Only that body changes; your saved morph doesn't.
- **Client mod:** the selector and favourites wheel, Morph's transformation and absorb animations, a paper doll (a
  live miniature of you and your morph, animations, transformations and vehicle included, with a soft vignette and
  head, pose and auto-hide settings), an advancement-style upgrade
  tree, a biomass bar, and a settings screen (`/morphclient`).
- **Admin control:** the gameplay mode, every ability, cooldowns and limits are in the plugin's config files. A newer
  release updates the config files by itself and keeps a backup: new settings appear in `config.yml` and `survival.yml`
  with their defaults, and every value you set stays.

## Requirements

| | Needs |
|---|---|
| Server | Paper 26.3 on Java 25 or newer, [Lib's Disguises](https://www.spigotmc.org/resources/libs-disguises-free.81/) 26.10.24 or newer (the Free build works), [PacketEvents](https://modrinth.com/plugin/packetevents) 2.14.0 or newer |
| Client (optional) | [Fabric Loader](https://fabricmc.net/use/installer/) 0.20.1 or newer and [Fabric API](https://modrinth.com/mod/fabric-api) 0.165.0+26.3 for Minecraft 26.3 |
| Optional compatible mod | [First-person Model](https://modrinth.com/mod/first-person-model) by tr7zw (tested with 2.7.3 for 26.3): with it, your morph is your body in first person. Not required, not bundled |

| RE:Morph | Minecraft | Paper API | Fabric Loader | Fabric API | Client protocol |
|---|---|---|---|---|---|
| 6.5.0 | 26.3 | 26.3 build 235 | 0.20.1 | 0.165.0+26.3 | 3 |

The client mod only works with a server running the plugin; it does nothing in singleplayer.

## Install

Download the release, then:

1. **Server:** put `RE-Morph-paper-<version>.jar`, Lib's Disguises and PacketEvents in `plugins/`, then start the
   server.
2. **Players (optional):** put `RE-Morph-fabric-<version>+26.3.jar` and Fabric API in `.minecraft/mods/`. Add
   [First-person Model](https://modrinth.com/mod/first-person-model) too if you want your morph as your body in first person.

The [server and player guide](docs/GUIDE.md) covers updating, settings, commands, permissions and known limitations.

## Documentation

| Document | What's in it |
|---|---|
| [GUIDE.md](docs/GUIDE.md) | Install, updating, playing, commands, permissions, settings |
| [SURVIVAL-MODE.md](docs/SURVIVAL-MODE.md) | Survival Mode: the unlock, biomass, the upgrade tree and its numbers |
| [ABILITIES.md](docs/ABILITIES.md) | Every mob's traits and abilities (generated from `abilities.yml`) |
| [MOB-MASSES.md](docs/MOB-MASSES.md) | Survival Mode: every mob's mass, the upgrade it needs, its biomass and morph cost |
| [BUILDING.md](docs/BUILDING.md) | Building the plugin and the mod from source |
| [VERSIONING.md](docs/VERSIONING.md) | How versions are numbered (Semantic Versioning), and how older releases were renumbered |
| [ROADMAP.md](docs/ROADMAP.md) | What's done, what's open, and why things were decided |
| [TESTING.md](docs/TESTING.md) | What has been tested, and the live-test checklist |
| [THIRD-PARTY.md](docs/THIRD-PARTY.md) | Every third-party component and its licence |

## Credits

- **iChun**: [Morph](https://github.com/iChun/Morph) and [iChunUtil](https://github.com/iChun/iChunUtil). RE:Morph
  ports their design and algorithms and reuses Morph's textures and sounds unmodified. Survival Mode follows his plans
  for Morph's Default Mode. [NOTICE.md](NOTICE.md) lists exactly what was taken.
- **libraryaddict**: [Lib's Disguises](https://github.com/libraryaddict/LibsDisguises), which shows morphs to players
  without the mod. Not bundled; server admins install it.
- **retrooper and contributors**: [PacketEvents](https://github.com/retrooper/packetevents). Not bundled.

## Licence

LGPL-3.0, the same as the original Morph. See [LICENSE](LICENSE) (which builds on [LICENSE-GPL-3.0](LICENSE-GPL-3.0)),
[NOTICE.md](NOTICE.md) and [docs/THIRD-PARTY.md](docs/THIRD-PARTY.md).

Not affiliated with Mojang or Microsoft. Minecraft is a trademark of Mojang AB.
