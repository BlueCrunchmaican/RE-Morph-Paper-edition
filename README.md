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

- **Survival mode** (the default since 7.2.0): iChun's unfinished "Default Mode", completed. Unlock the Biomass
  ability, absorb biomass from kills, and spend it in an upgrade tree on the ability to morph, more storage, reach,
  faster transformations and cooldowns, and each kind of morph trait: night vision, fire immunity, flight, climbing,
  camouflage and more. Each morph costs biomass too, scaled by the creature's size.
  See [docs/SURVIVAL-MODE.md](docs/SURVIVAL-MODE.md).
- **Classic mode:** kill a creature to unlock it. Variants unlock separately: sheep colours, villager jobs, cat
  breeds, babies and so on. Killing a player unlocks their form.
- **Abilities and traits for 89 of the 90 mobs in 26.3:** flight, swimming, fire immunity, creeper explosions
  (charged by lightning, too), enderman teleporting and block carrying, witch potions, bat perching, rabbit hops,
  skeleton and baby zombie jockeys, trading with villager players, spiders climbing cobwebs, and more. Full table: [docs/ABILITIES.md](docs/ABILITIES.md).
- **Multimorph (7.0.0):** be two morphs at once. A main body borrows a second creature's head, or its wings, tail,
  fins or rods, and gets both creatures' traits and abilities: a zombie with bat wings, a cow with a wolf's head, a
  ghast with a creeper's blast. Shift-pick in the selector or wheel (a preview panel shows the result), or
  `/morph combine`. In Survival it's an upgrade. Players without the mod see the borrowed head, ears, tail, wings, fins
  or rods too, flapping (with the resource pack). Babies borrow and lend as babies (7.5.0). 8.0.0 tidies a lot of
  pairs: borrowed heads and parts sit where a real creature would carry them, a shulker keeps what it borrows inside its
  shell, and a camel that borrows a dolphin dashes on land and in water as each should. All 8,190 pairs have been tried,
  and the [multimorph grid](https://bluecrunchmaican.github.io/RE-Morph-Paper-edition/combos/) shows which still need a second look. See
  [docs/GUIDE.md](docs/GUIDE.md).
- **Conversions (7.1.0):** a morph changes the way its creature does in vanilla: a villager killed by a zombie
  becomes a zombie villager (and can be cured), a zombie drowns, a skeleton freezes into a stray, piglins zombify
  outside the Nether, lightning turns a pig into a zombified piglin. Only that body changes; your saved morph doesn't.
- **Devouring Frenzy (7.2.0 as Rage; three levels from 7.3.0):** evens the odds against a crowd. Take hits from
  several attackers in quick succession and tendrils latch onto every creature around you, draining them while they
  live; what dies is absorbed as your kill. Jump while it runs to rise on its tendrils and walk over the ground on them
  (7.4.0). Players are hurt but never killed, bosses drained more slowly, your pets
  and anything saddled or armoured left alone. Hits charge it, and you set it off when you choose: the Devouring
  Frenzy key or `/morph frenzy`. Each level reaches further, hits harder, lasts longer and lifts you higher; in Survival they're bought
  as an upgrade. See [docs/GUIDE.md](docs/GUIDE.md).
- **The arm (8.0.0):** your own arm has a few tricks up its sleeve. Hold `J` with the mod to find out, or try
  `/morph arm` without it. In Survival it runs on your biomass. See [docs/GUIDE.md](docs/GUIDE.md).
- **Biomass passives (8.0.0):** Survival upgrades you switch on and off, such as night eyes, gills, fire shedding,
  antitoxin and a scavenger that fetches nearby items. A passive uses no biomass until it acts. On the chest menu's
  Passives page, the switches in the mod's upgrade tree, or `/morph passive`.
- **Quiet steps (8.0.0):** soft-footed forms such as foxes, rabbits and cats raise no step vibrations, so wardens and
  sculk sensors don't hear them walk by.
- **Client mod:** the selector and favourites wheel, Morph's transformation and absorb animations, a paper doll (a
  live miniature of you and your morph, animations, transformations and vehicle included, with a soft vignette and
  head, pose and auto-hide settings), an advancement-style upgrade
  tree, a biomass bar with the frenzy meter, the room left in your collection, and a settings screen (`/morphclient`).
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
| 6.5.0 to 8.0.0 | 26.3 | 26.3 build 235 | 0.20.1 | 0.165.0+26.3 | 3 |

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
| [Multimorph grid](https://bluecrunchmaican.github.io/RE-Morph-Paper-edition/combos/) | Every main body against every borrowed form, coloured by what I know. Click a square to report a problem |
| [MULTIMORPH-COMBOS.md](docs/MULTIMORPH-COMBOS.md) | The same list written out, for people who can't run scripts, with a CSV and a picture |
| [THIRD-PARTY.md](docs/THIRD-PARTY.md) | Every third-party component and its licence |

## Help test multimorphs

There are 8,190 pairs of a main body and a borrowed form, and I can't check every look and every ability by myself.
[The multimorph grid](https://bluecrunchmaican.github.io/RE-Morph-Paper-edition/combos/) shows each pair coloured by what I know so far. Click a square to report a
problem with that pair: it opens a GitHub issue with the pair already filled in, and a pair that someone has reported
turns pink until I've been through it. The ones marked as needing a look or a retest are the easiest to help with.

## Credits

- **iChun**: [Morph](https://github.com/iChun/Morph) and [iChunUtil](https://github.com/iChun/iChunUtil). RE:Morph
  ports their design and algorithms and includes Morph's textures and sounds as they were, plus a red-tinted copy
  of its morph skin texture. Survival Mode follows his plans for Morph's Default Mode. [NOTICE.md](NOTICE.md) lists exactly what was taken.
- **libraryaddict**: [Lib's Disguises](https://github.com/libraryaddict/LibsDisguises), which shows morphs to players
  without the mod. Not bundled; server admins install it.
- **retrooper and contributors**: [PacketEvents](https://github.com/retrooper/packetevents). Not bundled.
- RE:Morph was built with help from AI tools.

## Licence

LGPL-3.0, the same as the original Morph. See [LICENSE](LICENSE) (which builds on [LICENSE-GPL-3.0](LICENSE-GPL-3.0)),
[NOTICE.md](NOTICE.md) and [docs/THIRD-PARTY.md](docs/THIRD-PARTY.md).

Not affiliated with Mojang or Microsoft. Minecraft is a trademark of Mojang AB.
