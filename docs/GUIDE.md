# RE:Morph 6.5.0

Kill a mob, become that mob. An unofficial port of iChun's Morph mod to Minecraft Java 26.3.

- **Server:** a Paper plugin does all the work: unlocking, saving collections, abilities, disguises.
- **Players with the client mod (Java + Fabric):** get the original Morph experience: the `[` `]` selector, the
  favourites wheel, and the smooth morph and unlock animations.
- **Everyone else (plain Java, and Bedrock players through Geyser):** sees and uses morphs too. Other players are
  shown as the mob through Lib's Disguises, and morphs are picked with `/morph` commands.

The client mod is optional. Nobody is ever kicked for not having it.

## What's in this folder

| Path | What it is |
|---|---|
| `server/RE-Morph-paper-6.5.0.jar` | The Paper plugin |
| `client/RE-Morph-fabric-6.5.0+26.3.jar` | The Fabric client mod (optional, Java players only) |
| `resourcepack/RE-Morph-pack.zip` | Optional server resource pack for players without the mod: the original morph sound, and the heads multimorphs borrow (until 6.6.1 called `RE-Morph-sounds.zip`) |
| `default-configs/` | The config files the plugin writes on first start, for reference |
| `ABILITIES.md` | Every mob's traits and abilities |
| `MOB-MASSES.md` | Survival Mode: every mob's mass, biomass and morph cost |
| `SURVIVAL-MODE.md` | How Survival Mode works: the unlock, biomass, the upgrade tree and its numbers |
| `TESTING.md` | What has been tested, and a checklist for live testing |
| `source/` | Full source code (required by the LGPL licence) |
| `LICENSE`, `LICENSE-GPL-3.0`, `NOTICE.md` | Licence and credits |

## Server install

Needs a **Paper 26.3** server (built against build 235) running Java 25 or newer.

1. Put these three jars in the server's `plugins` folder:
   - `RE-Morph-paper-6.5.0.jar` (this release)
   - **Lib's Disguises** 26.10.24 or newer: https://www.spigotmc.org/resources/libs-disguises-free.81/
   - **PacketEvents** 2.14.0 or newer (Lib's Disguises needs it): https://modrinth.com/plugin/packetevents
2. Start the server once. The plugin creates `plugins/RE-Morph/` with `config.yml`, `abilities.yml` and
   `variants.yml`.
3. Change settings if you want (see below), then run `/morph reload` or restart.

Geyser and Floodgate need nothing extra. Bedrock players count as players without the mod.

### Updating

Replace the old `RE-Morph-paper-*.jar` with the new one (don't keep both) and restart the server.

- **`config.yml` and `survival.yml`** keep every value you set. When a release adds settings, the plugin updates the
  file on startup: the new settings appear in their place with their defaults and explanations, and the console lists
  them. Your old file is kept next to it (for example `config-old-6.1.4.yml`), along with any notes you wrote in it.
  (`survival.yml` does this since 6.3.0; before that it was replaced like the two below.)
- **`abilities.yml` and `variants.yml`** are replaced automatically when they come from an older release. Each one
  says which release it comes from in its `config-version` line. The console names the backup it keeps, for example
  `abilities-old-pre-5.0.0.yml`; copy back any changes you had made.
- **Players' collections** in `players/` stay as they are.

Players with the client mod should swap their old `RE-Morph-fabric-*.jar` for the new one; two copies in the
`mods` folder stop the game from starting.

**Coming from Morph Reborn** (the project's name until 6.0.0): remove `MorphReborn-paper-*.jar` and put
`RE-Morph-paper-6.5.0.jar` in its place. On first start the plugin moves `plugins/MorphReborn/` to `plugins/RE-Morph/`,
so settings and collections carry over. Permissions (`morphreborn.*`), `/morph` and its old `/morphreborn` alias,
and the client mod's settings file keep their names. Players swap `morphreborn-fabric-*.jar` for
`RE-Morph-fabric-6.5.0+26.3.jar`.

**6.0.0 changes the client protocol.** On a 6.0.0 server, an older client mod (5.x) is told to update and is treated
as a player without the mod until it does: they still see and use morphs, just without the selector and animations.

6.0.0 also adds `survival.yml` and the `gameplay-mode` setting (see Survival Mode).

**6.1.0 updates `config.yml` for you.** Before 6.1.0 the plugin never touched an existing `config.yml`, so settings
added since your first install (such as `gameplay-mode`) were missing from it and silently used their defaults. On
the first start with 6.1.0 they are added, at their defaults, and your values stay.

**6.1.0 also adds `/morph mode`**, so admins can switch between Classic and Survival in game, at once, without a
restart (see Admin commands).

**6.2.0** fixes players getting stuck or jittering against walls and rough ground while transforming, and adds the
paper doll to the client mod. The Biomass Menu key no longer takes B by default (bind it in Controls if you want it;
its Reset button there unbinds it on clients that still have B). Nothing to change in your config files. Players should update the client mod to get the
doll and the smooth camera; 6.1.0 clients still work.

**6.3.0** adds, in Survival, a biomass price for morphing (`/morph cost`, see Survival Mode); jockeys; trading with
villager morphs; climbing cobwebs as a spider; and many more paper doll settings, including a soft vignette around the
doll. A kill that only gives biomass now looks different from unlocking a new morph (dark red tendrils instead of
black ones). The phantom morph sees in the dark, and the dolphin dries out after 4 minutes instead of 2. On first start
`config.yml` and `survival.yml` get the new settings and keep your values; `abilities.yml` is replaced (copy back any
changes from the backup). 6.2.0 clients still work, but only 6.3.0 shows morph prices in the selector, the new absorb
looks and the web climbing.

**6.4.0** is a client update. It adds optional support for First-person Model (your morph is your body in its
first-person view, see Playing), shows morph prices on the favourites wheel with a smaller Biomass button in its
middle, and the ender dragon morph no longer faces backwards. The plugin only changed its version number, so a 6.3.0
server and 6.3.0 clients keep working with it.

**6.5.0** adds a fakeout explosion for creepers and sulfur cubes with TNT inside (sneak + ability key, see Abilities)
and fixes endermen in First-person Model's view. On first start `abilities.yml` is replaced with the new version (your
old one is kept as `abilities-old-6.1.6.yml`; copy back any changes); `config.yml` and `survival.yml` don't change.
Older clients still work, fakeout included; update the client mod for the enderman fix.

### Optional: the resource pack for players without the mod

The original Morph sound is built into the client mod. Players without the mod hear a vanilla stand-in instead, and see
a multimorph's borrowed head only where the game has a skull for it. `resourcepack/RE-Morph-pack.zip` gives them the real
sound, every borrowed head, and the ears and tails that animals like wolves, foxes, cats and horses lend: add it to your server resource pack (or use it as the server resource pack), then set
`appearance.vanilla-clients-have-sound-pack: true` in `config.yml`. `multimorph.vanilla-heads` decides who is sent the
pack's heads (`auto`: players whose game loaded the server's pack; `pack`: everyone, when the pack is required). A
player's head lent to a creature is a vanilla skull either way, so every player without the mod sees it.

## Client install (Java players, optional)

1. Install **Fabric Loader** 0.20.1 or newer for Minecraft 26.3: https://fabricmc.net/use/installer/
2. Put these in the `.minecraft/mods` folder:
   - `RE-Morph-fabric-6.5.0+26.3.jar` (this release)
   - **Fabric API** 0.165.0+26.3 or newer: https://modrinth.com/mod/fabric-api
   - Optional: **First-person Model** by tr7zw (tested with 2.7.3), for your morph as your body in first person:
     https://modrinth.com/mod/first-person-model
3. Start Minecraft with the Fabric profile and join the server.

The mod only does something on a server running the RE:Morph plugin. If the mod and the plugin don't speak the
same protocol version, the player is told to update and is treated as a player without the mod until they do.

## Playing

**Unlocking:** kill a mob to unlock it as a morph. Different looks (sheep colours, villager jobs, babies, cat breeds and
so on) unlock separately. Killing a player unlocks their form; killing a morphed player unlocks the morph they're using.
A server keeps at most `max-variants-per-type` looks of one creature (64 by default); a kill that can't add one says
so on the action bar.

### With the client mod

| Key | Does |
|---|---|
| `[` / `]` | Open the selector, move up and down through mob types |
| `Shift` + `[` / `]`, or arrow keys | Move left and right through that mob's variants |
| `Enter` or left click | Morph into the selected form |
| `Esc` or right click | Close the selector |
| Middle click, or `` ` `` while the selector is open | Favourite / unfavourite |
| `Delete` | Remove a morph from your collection |
| Hold `` ` `` | Favourites wheel: point at a favourite and let go |
| `R` | Use the morph's ability |
| Biomass Menu (unbound at first) | Survival Mode: the upgrade tree. Also a click on the Biomass button in the middle of the favourites wheel, or `/morph upgrades` |
| Rage (unbound at first) | Set off your rage once it is charged (see Rage below). Also `/morph rage` |

Keys can be changed in **Options → Controls → Key Binds**, under RE:Morph (mouse side buttons work too). "Return to player form" has no key by
default. You can also pick your own player form in the selector.

**Room in your collection:** where a limit applies, the selected row in the selector has a second line under its name:
*Looks 2/3* for a creature that comes in more than one look, and in Survival *Morphs 4/6* for kinds of creature. Each
turns gold with "full" once a kill can't add another. Creatures with only one look (an iron golem, a blaze) never get
a look count. In Classic the line only appears when a creature has reached the server's look limit.

**Client settings:** type `/morphclient` (or bind the "RE:Morph Settings" key). Only your own screen changes:

| Setting | Choices |
|---|---|
| New Morph | What unlocking a new morph looks like. **Black Tendrils** (default): the original, black tendrils pull the defeated mob in. **Red Tendrils**: the same, tinted dark red. **Red Wisps**: short red streams from the body into you; the mob isn't taken. **Plain**: dark particle trails, as players without the mod see it. **Off**. |
| Biomass Gain | Survival Mode: what a kill that only gives biomass looks like. Same choices; **Red Tendrils** by default, so it can't be mistaken for a new morph. |
| Transforming | **Morph**: the body turns black and melts into the new shape. **Plain**: the old body shrinks into dark smoke and the new one grows back, as players without the mod see it. |
| Unlock toasts, Name tags, Mouse in selector, Invert scroll | On / off |
| Update Check | On (default): each time you join a server or a world, the mod says in chat if a newer RE:Morph release is out, and if the server runs an older RE:Morph than the mod (7.2.0). It asks GitHub at game start and every 6 hours |
| Biomass Bar | Survival Mode: **When It Changes** (default), **Always** or **Off** |
| Paper Doll... | Opens the paper doll's own settings (below) |

Unlocking and transforming work the same either way, and other players see their own setting, not yours. The
settings are saved in `config/morphreborn-client.json`.

**Paper doll:** a live miniature of yourself in a corner of the screen. It shows exactly what the world shows: your
morph with its variant and baby size, its own animations (a rabbit's hop, a chicken's flapping, a fish flopping), your
pose (walking, sneaking, swimming, sleeping, sitting in a vehicle), the transformation as it happens, and, while you
ride, the boat, minecart or mount with everyone on it. A soft dark vignette around it keeps it readable on any
background. Every morph follows the same settings as your own body. Settings (RE:Morph Settings → Paper Doll...):

| Section | Setting | Choices |
|---|---|---|
| Doll | Paper Doll | On (default) / off. The **Show/Hide Paper Doll** key (Controls, unbound at first) flips it. |
| | Show | **Always** (default), **While Morphed**, or **While Active** (only while one of the "Show When" things below is happening) |
| | Stays After | How long a While Active doll lingers, 0–5 s (default 1 s) |
| Placement | Corner, Size | Top left (default) or any corner; 40–800 screen pixels (default 100). The doll keeps its size whatever the GUI scale (6.6.0; older settings are converted once at the GUI scale in use) |
| | Side Distance, Edge Distance | How far in from the corner (default 8 pixels) |
| Look | Turn, Tilt | Which way it faces, -180° to 180° (default 25°); how far you look down on it, -60° to 60° (default 10°) |
| | Sizing | **True Size** (default): a player fills the box, smaller morphs are drawn smaller, only bigger ones are shrunk. **Fill the Box**: every morph is scaled to fill it |
| | Lighting, Opacity | **Bright** (default) or **Like the World**; 10–100% |
| | Vignette Size, Vignette Darkness | How far the haze reaches out, 0–32 screen pixels (0 = off, default 6); how dark it is next to the doll, 10–100% (default 70%) |
| Pose | Head | **Follows You** (default; turns and looks as you do), **Turns Only**, **Looks Up/Down Only**, **Straight**, or **Looks at You** (faces out of the screen) |
| | Level Head: Elytra, Level Head: Riptide | Keep the head level while gliding or spinning with a trident (both on) |
| | Vehicles | Show what you ride, with its passengers (default on) |
| Hide | Show in Menus | Off (default). On: the doll stays while a menu is open, drawn on top of it: your inventory, chests, chat, the pause menu, other mods' screens |
| | Hide in Third Person, Hide with F3 | When it steps aside (both on by default) |
| | Hide for Music Toast | Steps aside while the "now playing" music toast is up (default on) |
| While Active, Show When... | Walking, Crouching, Sprinting, Jumping and Falling, Elytra Gliding, Swimming, Flying, Climbing, Riding, Blocking, Using an Item, Swinging, Taking Damage, On Fire, In Powder Snow, Sleeping, Transforming, Using an Ability | Each on or off (all on except Walking) |

It also steps aside while the selector or the favourites wheel is open.

**7.0.0** adds multimorphs (two morphs at once, see Playing) and renames the resource pack to `RE-Morph-pack.zip`
(it carries the heads multimorphs borrow; swap it in if you serve the pack). `config.yml` and `survival.yml` gain their
multimorph settings and keep your values; `abilities.yml` is left alone. 6.6 clients keep working, without
multimorphs.

**7.1.0** adds vanilla conversions for morphs (see Conversions below): `config.yml` gains the `conversions` section,
everything on, and keeps your values. In First-person Model's view a multimorph's borrowed parts now show on your
body (never its borrowed head). Older clients still work and see conversions too.

**7.2.0** adds rage (see Rage below), the room left in your collection (see Room in your collection), version notices
on every join, and makes Survival the gameplay mode of new servers (an updated server keeps the mode it has). On first
start `abilities.yml` is replaced (it gains the `rage` section; copy back any changes from the backup), `survival.yml`
gains the Rage upgrade and keeps your values, and `config.yml` keeps your values. Players without the mod see borrowed
skulls and player heads properly, better heads for some creatures, and the ears and tail an animal like a wolf or a fox
lends (they used to see its whole head), so swap in the 7.2.0 `RE-Morph-pack.zip` if you serve it. Older clients keep
working, without the room counts, the Rage key, the rage meter and its tendrils (they rage with `/morph rage`, and see
the particles players without the mod see).

**First-person Model (optional compatible mod):** with [First-person Model](https://modrinth.com/mod/first-person-model)
installed, your body in its first-person view is your morph. Its head is left out and the camera sits just in front of its body, so the view ahead stays clear
and looking down shows its front legs and back (a spider sees its legs on both sides). Humanoid morphs such as zombies
look like a player's body does. First-person Model's own toggle stays in charge: switch its view off and first person
is plain again. Transformations play on your body. As a morph with no head to look out of (slimes, magma cubes,
ghasts, squid), first person is plain. As the ender dragon the camera is at its head, so the rest of it stays behind
you. A multimorph's borrowed parts are part of your body there (blaze rods around your legs, wings behind your
shoulders); its borrowed head is left out like your own. RE:Morph doesn't need First-person Model: without it, first
person is unchanged.

### Multimorph: two morphs at once

A multimorph is a main body with a second form it borrows from. In Survival it needs the **Multimorph** upgrade first.

- **Picking (client mod):** hold `Shift` when you pick a creature in the selector (Enter or click) or the favourites
  wheel. Its tile turns red with tendrils (a red ring in the wheel), and a panel shows the body with the morph under
  your cursor on it, and the price. Pick a second creature to combine them. Picking the first again makes you just
  that creature; right click, `Esc` or `Backspace` closes without changing anything. In the wheel you can also let go
  of its key with `Shift` held and click the second favourite.
- **Your own body** (7.1.3) takes part like any other form: shift-pick **Player** and then a creature for your body
  with that creature's wings, ears or head, or a creature and then **Player** for that creature with your head. It
  costs nothing itself (the creature still costs its price in Survival); picking Player again turns you back.
- **Without the mod:** `/morph combine <main> <borrowed>` (a new pair; `self` or your name is your own body),
  `/morph combine <borrowed>` (onto the body you're in, your own included), `/morph combine off`.
- **What the borrowed form lends:** a creature known by its wings, rods or fins (bat, phantom, parrot, bee, chicken,
  allay, vex, blaze, breeze, fish, dolphin, the ender dragon) lends those, with its tail and stinger; the body keeps
  its own head. An animal with a tail and ears that stand out from its head (wolf, fox, cat, ocelot, rabbit,
  armadillo, camel, the horse family) lends its ears, on top of the body's own head, and its tail (7.1.3). Anything
  else lends its head (at its real size) and any tail; a creature with no separate head (slime, ghast, squid) is lent
  whole, no bigger than the head it replaces. The ender dragon's wings and tail are scaled to the body. Parts morph
  in with the transformation.
- **Traits and abilities:** the combination has every trait either form has (good ones still need their Survival
  unlocks), and every drawback. With two different abilities, the second input (sneak + ability key with the mod,
  `F` without) steps through both; the action bar shows which one the ability key uses. A borrowed creeper's blast
  grows with the body: a ghast's is huge, a chicken's a pop (`multimorph-max-power` under `explode` in
  `abilities.yml` caps it).
- **Price (Survival):** each form that is new to your body costs its price, plus a quarter
  (`morph-cost.multimorph-extra` in `survival.yml`).
- Picking your body again (normally) drops what it borrows. The combination is saved with you and comes back when you
  log in.
- `/morphclient preview [main] [borrowed]` shows any two creatures combined, without owning or becoming them.
- Players without the mod see the body with the borrowed head on it, or an animal's ears on its head and its tail at
  its back, as the mod draws them (see the resource pack above). Wings, fins and rods are shown only with the mod;
  without it a creature known by them lends its head.

### Conversions

A morph changes the way its creature does in vanilla, for as long as you keep that body. The selector, your
collection and your saved morph keep the form you picked; picking a form, respawning or logging out ends it.
Multimorphs don't convert. Your action bar says when something is under way (vanilla shows it only as shaking).

| Morph | What does it | Becomes |
|---|---|---|
| Villager | Killed by a zombie (never on Easy, half the time on Normal, always on Hard): you live on, at full health | Zombie villager, same job |
| Zombie villager | Weakness, then a golden apple (fed by another player, or eaten): shakes for 3 minutes | Villager |
| Zombie, zombie villager | 30 seconds under water, then 15 seconds shaking | Drowned |
| Husk | The same | Zombie |
| Skeleton | 7 seconds in powder snow, then 15 seconds shaking (in the sun a skeleton burns, and fire melts the snow) | Stray |
| Piglin, piglin brute, hoglin | 15 seconds outside the Nether, shaking | Zombified piglin, zoglin |
| Pig, villager | Lightning (not on Peaceful) | Zombified piglin, witch |
| Mooshroom | Lightning | The other colour |
| Creeper | Lightning | Charged (its next blast is twice as big; see Abilities) |
| Sulfur cube | Another player right-clicks it with a block it can take | Holding the block; TNT makes it explosive, and the blast spends it |

A sulfur cube holding a block (fed, or picked that way) is vanilla's too: hits, arrows, falls and blasts do no harm
and bounce it away instead. Holding TNT, it can be lit like TNT: flint and steel or a fire charge (another player's
right click), fire, a burning arrow, or a blast nearby (a short fuse). Lit, it blows up after its fuse.

The new creature's traits and abilities apply (a zombie villager burns in daylight, a drowned breathes under water).
A creative player isn't struck by lightning. Each kind can be switched off under `conversions` in `config.yml`, along
with the cure's length.

### Without the client mod (plain Java and Bedrock)

| Command | Does |
|---|---|
| `/morph list [player]` | Your morphs (click one to morph), or someone else's |
| `/morph use <id or mob>` | Morph, e.g. `/morph use pig` |
| `/morph off` | Back to your own body |
| `/morph fav <id>` | Favourite / unfavourite |
| `/morph combine <main> <borrowed>`, `/morph combine <borrowed>`, `/morph combine off` | Multimorph (see above) |
| `/morph rage` | Set off your rage once it is charged (see Rage below); otherwise it says how far along it is |
| Sneak + swap hands (`F`) | Use the morph's ability (`abilities.yml` → `activation.vanilla-trigger`) |

`/morph ability` also works for everyone.

### Rage

Rage evens the odds when a crowd turns on you. Take 6 hits within 6 seconds from at least two attackers (three
skeletons juggling you with arrows, a ring of zombies) and your rage is charged. It doesn't go off by itself: you choose
when, with the Rage key (unbound at first, set it in Controls) or `/morph rage`, and it stays charged until you do,
even through death. You're told when it is charged: READY on the meter with the mod, a chat line without it (or with
a mod older than 7.2.0).

Set off, for 8 seconds, tendrils latch onto the creatures within 16 blocks that you can see (up to 8 at once, whoever
hit you first) and drain their health while they live: 5 health a second each, so a zombie lasts about 4 seconds. Each one that dies counts as
your kill and is absorbed with the usual animation, giving biomass and a morph under the usual rules (room in your
collection, Absorbable Mass in Survival; reach doesn't apply, the tendrils reach them). When it ends, the action bar
sums it up ("Rage: absorbed 4 creatures, +86.2 biomass, 1 new morph"), and hits don't count toward the next one for
20 seconds.

- **What it drains:** hostile creatures, anything that is after you, and whoever hit you (players too). Endermen,
  zombified piglins, piglins and spiders only when they're after you. Your own pets never.
- **Less:** the ender dragon, the wither, the warden, the elder guardian, villagers, wandering traders, name-tagged
  creatures, other players' pets and players are drained at a quarter of the rate. Players are never brought below
  one heart: rage hurts them but never kills them.
- **Survival:** buy the Rage upgrade (in the Morph section, after Morph Abilities). **Classic:** everyone has it.

With the mod, you see the tendrils (red, like the red tendrils absorb style), and a meter in the biomass bar's corner
and style: *RAGE* with a segment for each hit, lit as hits come in and going out as they get old (it stops one short
while they all came from one attacker); charged, the segments glow and it says *READY*; while raging, a bar running
down with the seconds left; afterwards, a bar filling back up and the time left. It shows when it changes, while it is
charged, or always, following the biomass bar setting in `/morphclient`. Players without the mod see the tendrils as wavering red lines of particles. Servers set it all up in
`abilities.yml` → `rage`: the hits, the window and the attackers it takes, how long it lasts, its reach, how many
creatures it holds, the drain, what it drains (`targets: all` drains every creature and player around), the lesser
rate and who gets it, the floor for players, and the wait.

### Survival Mode

If the server plays Survival Mode, morphing has to be earned:

1. At first kills give nothing and the selector stays closed.
2. Have **Wither and Regeneration at the same time** ("A Mutation Complication"). From then on every kill absorbs the
   creature's **biomass**, shown in the action bar (and the biomass bar with the mod). Big creatures need upgrades
   before you can absorb them, and kills too far away don't count.
3. Spend biomass on upgrades:
   - **With the client mod:** hold the favourites key (`` ` ``) and click the round **Biomass** button in the middle
     of the wheel, or type `/morph upgrades`, or press the Biomass Menu key if you bound one. (Letting go of the key
     with the mouse in the middle just closes the wheel.) The upgrade tree is in the advancement screen's style, each
     upgrade shown with a status effect icon, in three sections: Biomass, the Morph Ability on its own with the Morph
     section beside it, and the Abilities below. Drag or scroll to move around, hover for details, click to buy.
     Upgrades you can afford right now glow red. In Survival the wheel opens even before you can morph, showing just
     the Biomass button.
   - **Without the mod (and Bedrock):** `/morph upgrades` opens a chest menu with the same sections; click an item
     to buy.
   - Anyone: `/morph upgrade <name>` buys directly, `/morph biomass` lists your progress.
4. Buy the **Morph Ability** to start morphing. Morphs you already had open up, and new kills unlock morphs while
   your collection has room (Morph Capacity, Variant Capacity). The selector shows the room (*Morphs 4/6*,
   *Looks 2/3*), and each kill's action bar says what it did after the biomass: *Morphs 5/6* for a new creature,
   *Sheep looks 2/3* for a new look, or *No room for Cow: morphs full (6/6)*. Without the mod, `/morph list` shows
   the room at the top.
5. **Morphing costs biomass**, paid when the transformation starts; going back to your own body is free. The price
   grows with the creature's size: by default a chicken costs 2, a zombie 8, a cow 12, an iron golem 53. With the
   mod, the selector shows each price after the name and the favourites wheel under it, red when you can afford it
   and grey when you can't. Without
   it, `/morph use` tells you the price if you're short. The admin can turn this off or change it (`/morph cost`).
6. Transformations start at 6.5 seconds and **Morph Efficiency** brings them down to 3.5. Ability cooldowns start
   at ×1.3 and **Morph Abilities** brings them down to ×0.7. **Morph Abilities** also unlocks the ability key and the **Abilities**
   section: each kind of good trait (night vision, water breathing, fire immunity, flight, climbing, camouflage...) is
   bought there once. A morph's drawbacks, like burning in daylight, are always on.

With the mod, a biomass bar sits in the bottom left: a living lump of biomass rather than a box. Its edges ripple,
drops of goo gather underneath and fall, the fill is blood red under the morph's black static, black and red tendrils
sway along the top, and each gain floats up (`+21`) while the whole bar surges. When storage is full, its label reads FULL in
yellow. At large GUI scales it is drawn one scale step smaller, so it stays a small corner readout. It shows for a few seconds after a change by default;
Biomass Bar in `/morphclient` makes it always or never show. A kill that only gives biomass is pulled in by dark red
tendrils, and one that unlocks a new morph by the original black ones (Biomass Gain and New Morph in `/morphclient`).
Players without the mod see red trails for biomass and dark ones for a new morph.

Dying never costs biomass. The whole tree and its numbers are in `SURVIVAL-MODE.md`.

### Abilities

Morphs get the mob's traits while fully transformed, the way the real mob lives:

- **Movement:** bats and phantoms fly; spiders treat any wall like a ladder (walk into it to climb, let go to slide
  down, sneak to hang on) and walk through cobwebs; spiders and cave spiders climb cobwebs like scaffolding (jump to
  go up, stand on top, sneak to go down); rabbits and foxes walk on powder snow; striders stand on lava;
  turtles plod on land (sprinting doesn't help) and swim at full speed; chickens fall slowly and keep their running
  speed in the air.
- **Senses:** bats, cats, foxes, phantoms, glow squid and fish (cod, salmon, tropical fish, pufferfish) see in the dark.
- **Water and air:** fish suffocate on land; axolotls, dolphins and nautiluses dry out away from water (the air bar
  shows how long you have); dolphins hold their breath for 4 minutes.
- **Undead:** they burn in sunlight, and Healing potions hurt them while Harming heals them.
- **Other hazards:** endermen teleport out of water; snowballs hurt blazes; a creaking can't move while someone is
  looking at it.

Abilities are always yours to trigger:

- **Ability key:** creepers explode (a creeper struck by lightning is charged, like a real one: it gets the blue aura
  and its next explosion is twice as big; killing a charged creeper unlocks a charged-creeper morph, which always is); endermen teleport; ghasts shoot fireballs; bees sting once (and die a minute
  later, like a real bee); turtles lay eggs on sand; rabbits hop forward.
- **Bat:** the ability key hangs you upside down from the block above (within 2.5 blocks of your head), like a
  resting bat. Let go with the ability key again, any movement key or jump; getting hurt or the block disappearing
  also drops you. You go back to flying or standing, whichever you were doing.
- **Sneak + jump:** frogs, goats, breezes, slimes and magma cubes leap; foxes, wolves, cats and ocelots pounce; horses
  charge their jump the longer you hold sneak.
- **Witch:** throws or drinks the potion shown in the action bar. Change it with sneak + ability key, or F without
  sneaking if you don't have the mod.
- **Fakeout explosion** (6.5.0): as a creeper, or a sulfur cube with TNT inside, sneak + ability key (F without sneaking if you
  don't have the mod) starts the same fuse, hiss and swell, then fizzles out with a puff of smoke instead of exploding.
  You're back to normal, a lightning charge is kept, and it has its own short cooldown (`explode.fakeout` and
  `fakeout-cooldown-seconds` in `abilities.yml`).
- **Enderman:** picks up a block with an empty-hand right-click and puts it down with the next one.
- **Jockeys:** as a skeleton (or stray, wither skeleton, bogged, parched), right-click a spider or cave spider to ride
  it; as a baby zombie (or baby husk, drowned, zombie villager, zombified piglin, piglin), a chicken; as a baby piglin,
  a hoglin. Steer with your movement keys, jump to jump, sneak to get off. The mount stands still on its own while you
  ride. Works without the mod too.
- **Villager:** other players can right-click you to trade, as with a real villager: your look's profession and level
  decide the trades, each one runs out after its uses and they restock every Minecraft day. The buyer pays; you give
  and get nothing. The admin can turn this off, or hold you in place while someone trades.

Skeletons raise a held bow, pillagers and piglins load their crossbows and drowned wind up a trident, like the real
mobs in combat. Players without the mod see these too, plus:
- each ability's own animation, such as a panda's roll, a polar bear rearing up, a sheep grazing, an illusioner's
  spell and mirror images, and a copper golem sorting;
- changes to the mob: a sheared sheep, a bee that has lost its stinger, an enderman's block.

Dying resets those changes: a bee grows its stinger back and a sheep its wool. The full list is in `ABILITIES.md`.

## Admin commands

All need `morphreborn.admin` (ops by default).

| Command | Does |
|---|---|
| `/morph grant <player> <mob> [snbt]` | Unlock a morph for someone, e.g. `/morph grant Steve sheep {Color:14b}` |
| `/morph revoke <player> <mob, id or all>` | Take morphs away |
| `/morph reset <player>` | Wipe someone's whole collection |
| `/morph force <player> <mob, id or off> [snbt]` | Morph someone right now |
| `/morph borrow <player> <mob, id, self or off> [snbt]` | Give a player a borrowed form (multimorph; on their own body too), or take it away; `self` lends a morphed player their own head |
| `/morph info <player>` | Their form, collection size, and whether they have the mod |
| `/morph clients` | Who online has the mod |
| `/morph ability <player>` | Fire someone's ability |
| `/morph rage <player> [go\|fill]` | For testing: what their Rage key would do, or set their rage off now, charged or not (`go`), or charge it (`fill`) |
| `/morph upgrades <player>` | Open the upgrade tree (or chest menu) for someone |
| `/morph carry <player> <block or none>` | Set the block an enderman morph is holding |
| `/morph setting [name] [value]` | Show or change the world-effect settings below |
| `/morph cost [on\|off \| rate <per-kg> \| minimum <n> \| price <mob> <biomass\|formula> \| gain <multiplier>]` | Survival: show or change morph prices and the biomass gain (saved to `survival.yml`). `price` fixes one mob's price and `formula` puts it back on the size formula; `gain 2` doubles biomass from kills |
| `/morph mode [classic\|survival]` | Show the gameplay mode, or switch it now for everyone (saved to `config.yml`) |
| `/morph reload` | Reload the config files, including a `gameplay-mode` you changed by hand |
| `/morph survival <player> info` | Their biomass and upgrade levels |
| `/morph survival <player> unlock` / `lock` | Unlock or re-lock their Biomass ability |
| `/morph survival <player> give\|take\|set <amount>` | Change their biomass (not limited by capacity) |
| `/morph survival <player> level <upgrade> <level>` | Set an upgrade level directly |
| `/morph survival <player> buy <upgrade>` | Buy for them from their own biomass, by the normal rules |
| `/morph survival <player> reset` | Start their Survival progress over (their collection is kept) |

`/morph survival` also works while the server is in Classic, to prepare progress before switching.

### Permissions

| Permission | Default | Allows |
|---|---|---|
| `morphreborn.use` | everyone | Morphing into unlocked forms |
| `morphreborn.acquire` | everyone | Unlocking morphs by killing |
| `morphreborn.ability` | everyone | Abilities and traits |
| `morphreborn.multimorph` | everyone | Combining two morphs (when `multimorph.enabled` is on) |
| `morphreborn.rage` | everyone | Rage (when `abilities.yml` `rage.enabled` is on) |
| `morphreborn.admin` | ops | Admin commands |
| `morphreborn.type.<namespace>.<mob>` | — | Only checked when `morphs.per-type-permissions: true`, e.g. `morphreborn.type.minecraft.ender_dragon` |

## Settings

**Gameplay mode** (`config.yml`, `gameplay-mode`; or switch in game with `/morph mode classic` or `/morph mode survival`):
- `survival` (the default since 7.2.0; servers that had `classic` keep it): morphing is earned with biomass. Its rules and upgrade tree are in **`survival.yml`**: the unlock
  effects, the biomass formula and gain multiplier, per-creature multipliers or fixed masses, creatures that give
  nothing, morph prices, and each upgrade's cost and values.
- `classic`: the original Morph. Kill a creature to unlock it.

Switching never deletes anything. Collections and Survival progress are both kept in each player's file. Going to
Survival locks existing collections until the player buys the Morph Ability; going back to Classic opens them again.
Switching while players are online applies at once: anyone morphed who can't morph in the new mode turns back,
flight is re-checked (Survival needs Morph Flight), and players with the mod get the new mode's selector and bar.

Everything is commented in the files themselves. The ones you are most likely to want:

**World effects** (`config.yml`, also changeable in game with `/morph setting <name> <value>`, which saves to the file):

| Setting | Values | Default | Controls |
|---|---|---|---|
| `explosion-block-damage` | on, off, gamerule | gamerule | Creeper blasts, ghast fireballs and wither skulls breaking blocks. `gamerule` follows mobGriefing, like the real mobs |
| `fire` | on, off, gamerule | gamerule | Ghast and blaze fireballs starting fires |
| `creeper-explosion-kills-user` | true, false | true | A creeper morph dies in its own blast (a totem still saves you) |
| `bee-sting-kills-user` | true, false | true | A bee morph dies a minute after it stings, like a real bee |
| `villager-trading` | true, false | true | Players can trade with a villager morph (`trading.villagers`) |
| `freeze-trading` | true, false | false | Hold the villager player in place while someone trades with them (`trading.freeze-villager`) |
| `enderman-pickup` | vanilla, any | vanilla | `vanilla`: only blocks real endermen take (grass, dirt, sand, gravel, clay, flowers, mushrooms, pumpkins, melons, cactus, TNT, mud, moss, nether nylium/fungus/roots). `any`: any breakable block except chests, signs and other blocks that hold data, and two-part blocks like doors and beds |

**Other `config.yml` options:**
- `morphs`: transformation length (`transition-ticks`, default 100 = 5 s), kills needed per unlock, mobs that can't be
  morphed, baby morphs, player morphs, a morph cooldown.
- `death`: keep your morph through death (default yes), lose it, or lose the whole collection.
- `appearance`: name tags over morphed players (`morph`, `player` or `hidden`), hitbox scaling, the sound.
- `attributes`: which mob stats a morph copies, and the caps (max health 20, speed no faster than a player, step
  height 1 block, jump strength 0.7). No morph gets less than 2 hearts (`min-max-health`), and the creaking gets
  full health (`health-overrides`), since a real one can't be hurt while its heart lives.

**`abilities.yml`:** every trait and ability, which mobs get it, cooldowns and strength. Set a list to `[]` to turn
something off. `enabled: false` turns off all abilities. Leave `config-version` alone; it's how an update knows the
file is out of date (see Updating).

**`variants.yml`:** which mob data counts as a different look (for example, sheep colour does and sheep health doesn't).

**Update checks** (6.6.0): the plugin asks GitHub at startup and every 12 hours whether a newer RE:Morph release is out.
If one is, the console says so and players with `morphreborn.admin` are told when they join (`update-check.enabled`
in `config.yml`). Players whose client mod is older than the plugin are told when they join, with short [Modrinth] and
[GitHub] links (`client.old-client-message`, `""` turns it off). When a player's client mod is newer than the plugin,
the console says so once per version, since a newer plugin is probably out too (7.2.0).

The client mod tells its player itself, each time they join a server or a world (its Update Check setting, 7.2.0): that
a newer release is out, on any server, with RE:Morph or without (except one whose plugin is newer, which already told
them), and that the server runs an older RE:Morph than the mod. A newer client mod works on an older server; it only
lacks the features the server doesn't have yet. Only GitHub's public "latest release" page is requested; nothing about
the server or its players is sent.

**Multimorph** (`config.yml`, `multimorph`): `enabled` (on by default) and `vanilla-heads` (see the resource pack
above). In Survival, the Multimorph upgrade and `morph-cost.multimorph-extra` are in `survival.yml`.

Player collections are saved in `plugins/RE-Morph/players/`, one file per player.

## Known limitations

- **The server can't install the mod.** Players without it get disguises and `/morph`, not the selector or the smooth
  animations. They see a shrink-swap-grow transition with particles instead.
- **Bedrock players (Geyser) haven't been tested.** Geyser translates the disguise packets, so they should see morphs,
  but some mobs may look off.
- **Hitboxes are scaled, not reshaped.** The hitbox takes the mob's size through the player's scale attribute (0.2× to
  7×, so giants and ender dragons are full size), in one step half-way through the transformation. The shape stays
  player-shaped, so a spider morph's hitbox is narrow and tall rather than wide and flat. A body that has to grow is
  nudged away from walls first, and if there's no room at all (a ghast in a tunnel) it keeps its smaller size until
  there is. With the mod the camera rises or sinks smoothly over the whole transformation; without it, it moves once,
  quickly, at the half-way point.
- **Stats are capped** (see `attributes`): no 300-health ender dragons, no faster-than-player speed, and a camel morph
  steps up 1 block, not 1.5.
- **Survival Mode's "A Mutation Complication"** is a title and a chat message, not a real advancement.
- **Chickens without the client mod** get an invisible Slow Falling effect in the air. Minecraft can't change only a
  player's falling speed from the server, and replacing the whole speed stopped players dead mid-jump. Short drops feel
  like a chicken; long drops end up faster than a real chicken (with the mod they fall exactly like one).
- **Some movement traits need the client mod:** walking through cobwebs and on powder snow, climbing cobwebs, and
  standing on lava.
  Without it the server still stops the freezing and the slowdown on its side, but the player's own game slows them
  in webs and lets them sink into snow and lava (striders float back up).
- **An enderman morph carries only the block itself.** That's why chests, signs and similar blocks can't be picked up.
