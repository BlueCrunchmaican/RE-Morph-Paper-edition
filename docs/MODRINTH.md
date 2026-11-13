Kill a mob, become that mob. RE:Morph brings iChun's [Morph](https://github.com/iChun/Morph) to Minecraft Java 26.3 as a Paper plugin, with an optional Fabric mod that puts back the original selector and animations.

This is an unofficial fan port. iChun made Morph, but he didn't make this and hasn't endorsed it.

## Do players need the mod?

No. The plugin does the real work (unlocking, saving collections, abilities and disguises), so anyone can join and play. Without the mod you pick morphs with `/morph`, and other players see you as the mob through Lib's Disguises.

With the Fabric mod you get Morph the way it used to look: the `[` `]` selector, the favourites wheel, the transformation and absorb animations, and the upgrade tree on its own screen. Nobody gets kicked for not having it. Bedrock players coming in through Geyser should play like anyone without the mod, but that hasn't been tested yet.

## Features

### Two ways to play

**Survival** is what a new server starts with (since 7.2.0). It's the "Default Mode" iChun planned for Morph and never finished, and it makes you earn morphing with biomass. There's a full walkthrough further down.

**Classic** is the original Morph: kill something and you can turn into it. Variants count on their own, so a red sheep and a blue sheep are two different morphs, and the same goes for villager jobs, cat breeds and babies. Kill a player and you get their skin.

You can switch with `/morph mode` whenever you like. Collections and Survival progress are kept either way.

### Abilities

89 of the 90 mobs in 26.3 do something. Bats fly and hang from ceilings, creepers explode (and get charged by lightning), endermen teleport and carry blocks, witches throw potions, spiders climb walls and cobwebs, and other players can trade with you while you're a villager. The downsides come with them: zombies burn in daylight and endermen get hurt by water. Every mob is in [ABILITIES.md](https://github.com/BlueCrunchmaican/RE-Morph-Paper-edition/blob/main/docs/ABILITIES.md).

### Multimorph

You can be two creatures at once. Your main body borrows a second one's head, or its wings, tail, fins or rods, and you get both creatures' abilities. So you can be a zombie with bat wings, a cow with a wolf's head, or a ghast that blows up like a creeper. Hold Shift when you pick in the selector (a preview shows what you'll get) or use `/morph combine`. In Survival it's one of the upgrades.

### Devouring Frenzy

For when a crowd turns on you. Get hit by several mobs in quick succession (three skeletons juggling you with arrows, say) and a frenzy charges up. When you set it off (the Devouring Frenzy key, or `/morph frenzy` without the mod), tendrils latch onto the creatures around you and drain them while they're still alive, and whatever dies gets absorbed like any other kill, morph included. It hurts other players but never kills them, bosses only lose health slowly, and your pets and anything saddled or armoured are left alone. While it runs you can jump to rise on its tendrils and walk over the ground on them, then crouch to come back down. It has three levels, each reaching further, hitting harder, lasting longer and lifting you higher; in Survival they're an upgrade.

### Conversions

Morphs change the way their mobs do in vanilla. A villager morph killed by a zombie becomes a zombie villager and can be cured, zombies turn into drowned underwater, skeletons freeze into strays, piglins zombify outside the Nether, and lightning turns a pig into a zombified piglin. Your saved morph doesn't change, only the body you're in.

### The client mod

Besides the selector, the wheel and the animations, the mod adds a small paper doll of your morph on screen, a biomass bar for Survival, and a settings screen (`/morphclient`). It also works with [First-person Model](https://modrinth.com/mod/first-person-model) if you want to see your morph's body in first person.

### For server admins

Abilities, cooldowns, limits, morph prices and the whole upgrade tree are in the config files, and a lot of it can be changed in game. When you update, new settings get added to your configs and everything you changed stays the way you set it.

## How Survival works

You start with nothing. Kills don't give you anything and the selector won't open.

1. Get Wither and Regeneration on you at the same time. A Nether fortress has everything you need: wither skeletons for the Wither, and blazes for the rods that go into Regeneration potions. You'll get the advancement "A Mutation Complication", and from then on you can absorb what you kill.
2. Every kill now gives biomass, based on how big the creature is. A chicken is worth about 3, a zombie 21, a cow 34. At first you can only hold 100, you only absorb kills within 3 blocks of you, and anything as heavy as a spider or bigger is too much for you. The upgrades raise all of that.
3. Open the upgrade tree. With the mod it's the round Biomass button in the middle of the favourites wheel; without it, `/morph upgrades` opens the same tree as a chest menu. Your first goal is the Morph Ability. It needs Biomass Capacity at level 2, so it's 230 biomass in total, or about a dozen zombies.
4. Once you have it, kills unlock morphs as well, as long as your collection has room (the selector shows how much is left). Turning into something costs biomass based on its size: 2 for a chicken, 8 for a zombie, 12 for a cow, 640 for a ghast. Going back to your own body is free.
5. After that, spend it however you want. There's more storage, a longer reach, heavier creatures, more morph slots, faster transformations, and Morph Abilities, which turns on the ability key. That opens the last section, where you buy what each mob's body does for it, one kind at a time: night vision, breathing underwater, fire immunity, flight, climbing, camouflage and more. The downsides are there from the start, so a zombie morph will burn in the sun long before it can see in the dark.

Dying doesn't cost you biomass or upgrades. If you want a faster or slower pace, every cost and formula is in `survival.yml`, and [SURVIVAL-MODE.md](https://github.com/BlueCrunchmaican/RE-Morph-Paper-edition/blob/main/docs/SURVIVAL-MODE.md) has the whole tree with its numbers.

## Installing

| File | Where it goes | What it needs |
|---|---|---|
| `RE-Morph-paper-<version>.jar` | The server's `plugins` folder | Paper 26.3 on Java 25 or newer, [Lib's Disguises](https://www.spigotmc.org/resources/libs-disguises-free.81/) 26.10.24 or newer (the Free build is fine) and [PacketEvents](https://modrinth.com/plugin/packetevents) 2.14.0 or newer |
| `RE-Morph-fabric-<version>+26.3.jar` | Each player's `mods` folder, if they want it | [Fabric Loader](https://fabricmc.net/use/installer/) 0.20.1 or newer and [Fabric API](https://modrinth.com/mod/fabric-api) 0.165.0+26.3 |
| `RE-Morph-pack.zip` ([on GitHub](https://github.com/BlueCrunchmaican/RE-Morph-Paper-edition/releases/latest)) | Your server resource pack, if you want it | Nothing. It gives players without the mod the original morph sound, the heads, ears, tails, wings, fins and rods that multimorphs borrow, and the morph skin, so transformations, absorbing and the frenzy look the way the mod draws them |

The mod only does something on a server running the plugin, so it won't change singleplayer.

Players and admins don't have to update at the same time. A newer client mod works on servers running any older version back to 6.0.0, and an older client mod still works on a newer server (it'll tell you there's an update).

The [guide](https://github.com/BlueCrunchmaican/RE-Morph-Paper-edition/blob/main/docs/GUIDE.md) covers commands, permissions, settings and updating.

## Credits

iChun made [Morph](https://github.com/iChun/Morph) and [iChunUtil](https://github.com/iChun/iChunUtil), and RE:Morph is built on his work. The selector, the favourites wheel, the transformation and absorb animations and the way variants work are ported from his code, and Survival follows his plans for Morph's Default Mode. His GUI textures and morph sounds are included as they were. The red tendrils use a red-tinted copy of his morph texture.

[Lib's Disguises](https://github.com/libraryaddict/LibsDisguises) by libraryaddict shows morphs to players without the mod, and [PacketEvents](https://github.com/retrooper/packetevents) by retrooper and contributors handles the packets. Neither one is bundled.

RE:Morph is licensed under LGPL-3.0, the same as the original Morph. It isn't affiliated with Mojang or Microsoft.
