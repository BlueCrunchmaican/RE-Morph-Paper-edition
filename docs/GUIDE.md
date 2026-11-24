# RE:Morph

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
| `server/RE-Morph-paper-<version>.jar` | The Paper plugin |
| `client/RE-Morph-fabric-<version>+26.3.jar` | The Fabric client mod (optional, Java players only) |
| `resourcepack/RE-Morph-pack.zip` | Optional server resource pack for players without the mod: the original morph sound, and the heads, ears, tails, wings, fins and rods multimorphs borrow (until 6.6.1 called `RE-Morph-sounds.zip`) |
| `default-configs/` | The config files the plugin writes on first start, for reference |
| `ABILITIES.md` | Every mob's traits and abilities |
| `MOB-MASSES.md` | Survival Mode: every mob's mass, biomass and morph cost |
| `SURVIVAL-MODE.md` | How Survival Mode works: the unlock, biomass, the upgrade tree and its numbers |
| `source/` | Full source code (required by the LGPL licence) |
| `LICENSE`, `LICENSE-GPL-3.0`, `NOTICE.md` | Licence and credits |

## Server install

Needs a **Paper 26.3** server (built against build 235) running Java 25 or newer.

1. Put these three jars in the server's `plugins` folder:
   - `RE-Morph-paper-<version>.jar` (this release)
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
  `abilities-old-pre-5.0.0.yml`; copy back any changes you had made. (`abilities.yml` from 7.4.0 on is updated like
  `config.yml` instead: new settings added, yours kept.)
- **Players' collections** in `players/` stay as they are.

Players with the client mod should swap their old `RE-Morph-fabric-*.jar` for the new one; two copies in the
`mods` folder stop the game from starting.

**Coming from Morph Reborn** (the project's name until 6.0.0): remove `MorphReborn-paper-*.jar` and put
`RE-Morph-paper-<version>.jar` in its place. On first start the plugin moves `plugins/MorphReborn/` to `plugins/RE-Morph/`,
so settings and collections carry over. Permissions (`morphreborn.*`), `/morph` and its old `/morphreborn` alias,
and the client mod's settings file keep their names. Players swap `morphreborn-fabric-*.jar` for
`RE-Morph-fabric-<version>+26.3.jar`.

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
sound, every borrowed head, the ears and tails that animals like wolves, foxes, cats and horses lend, and the wings,
fins and rods of the creatures known by them (bats, phantoms, parrots, bees, chickens, allays, vexes, blazes, breezes,
fish, dolphins, the ender dragon), flapping as they do, and the morph skin: with it they see a transformation as the
old body melting into the new one, an absorbed creature covered in the skin and pulled in by tendrils, and Devouring
Frenzy's tendrils, as the mod draws them (players without the pack see particles; `appearance.vanilla-skin-effects`
in `config.yml` switches it off). Add it to your server resource pack (or use it as the server resource pack), then
set
`appearance.vanilla-clients-have-sound-pack: true` in `config.yml`. `multimorph.vanilla-heads` decides who is sent the
pack's models (`auto`: players whose game loaded the server's pack; `pack`: everyone; `off`: nobody). A
player's head lent to a creature is a vanilla skull either way, so every player without the mod sees it.

**`pack` requires every player to have the pack.** It sends the pack's models to everyone without the client mod, without
checking that their game loaded the pack, so use it only when the pack is a server-enforced resource pack
(`require-resource-pack=true` in `server.properties`). A player whose game lacks the pack sees the models as purple and
black cubes (the game's missing model). The arm forms follow the same setting: with `pack` all twelve arm forms are the
pack's models for everyone without the mod (and so purple and black cubes without the pack), with `auto` only for those
whose game loaded the pack (everyone else sees a plain iron, diamond or netherite tool, or a bow), and with `off` for
nobody. `appearance.vanilla-arm-models: false` switches the arm models off on their own. Bedrock players never get the
arm models. The morph-skin effects (transformations, absorbing, Devouring Frenzy's tendrils) follow the same setting too:
with `pack`, players without the pack see them as those cubes as well, and with `off` everyone sees the particles
instead (`appearance.vanilla-skin-effects: false` does that on its own). When the pack is optional, leave
`vanilla-heads` on `auto`.

## Client install (Java players, optional)

1. Install **Fabric Loader** 0.20.1 or newer for Minecraft 26.3: https://fabricmc.net/use/installer/
2. Put these in the `.minecraft/mods` folder:
   - `RE-Morph-fabric-<version>+26.3.jar` (this release)
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
| Devouring Frenzy (unbound at first) | Set off your frenzy once it is charged (see Devouring Frenzy below). Also `/morph frenzy` |
| `J` (Arsenal) | Hold it for the arm wheel, point at a tool and let go; a quick tap puts the arm away, or forms the last tool again (see The arm below) |
| `Z` (Spyglass zoom) | Hold it to zoom, while the Tendril Spyglass is switched on (see The arm below). You can rebind it in Controls under RE:Morph |
| `F` (the game's swap-hands key) | With an arm formed: its special; `Sneak` + `F` its second move |

Keys can be changed in **Options → Controls → Key Binds**, under RE:Morph (mouse side buttons work too). "Return to player form" has no key by
default. You can also pick your own player form in the selector.

**Room in your collection:** where a limit applies, the selected row in the selector has a second line under its name:
*Looks 2/3* for a creature that comes in more than one look, and in Survival *Morphs 4/6* for kinds of creature. Each
turns gold with "full" once a kill can't add another. Creatures with only one look (an iron golem, a blaze) never get
a look count. In Classic the line only appears when a creature has reached the server's look limit.

**Client settings:** type `/morphclient` (or bind the "RE:Morph Settings" key). Only your own screen changes. The page is in sections, the two sub-pages first:

| Section | Setting | Choices |
|---|---|---|
| General | Tooltips | **On** (default): every description explains itself: how to use a thing, which key or button, what it does in detail. **Off** keeps only names, levels, state, biomass costs and rates. It covers the arm wheel, the upgrade tree, the favourites wheel's and the multimorph panel's hints, the messages that say what to do next, and these settings' own hover text (the Tooltips switch itself always keeps its hover text, so it is easy to turn back on). The mod tells the server, so the upgrade tree's descriptions (and a formed arm's item text) follow it too |
| | Update Check | On (default): each time you join a server or a world, the mod says in chat if a newer RE:Morph release is out, and if the server runs an older RE:Morph than the mod (7.2.0). It asks GitHub at game start and every 6 hours |
| More Settings | Paper Doll... | Opens the paper doll's own page (below) |
| | Arm Forms... | Opens the arm's own page: your arm's look, colour, pulse speed and lantern brightness, the hotbar bar and the wheel, and how arms are drawn for you (see The arm below) |
| Morphing | New Morph | What unlocking a new morph looks like. **Black Tendrils** (default): the original, black tendrils pull the defeated mob in. **Red Tendrils**: the same, tinted dark red. **Red Wisps**: short red streams from the body into you; the mob isn't taken. **Plain**: dark particle trails, as players without the mod see it. **Off**. |
| | Biomass Gain | Survival Mode: what a kill that only gives biomass looks like. Same choices; **Red Tendrils** by default, so it can't be mistaken for a new morph. |
| | Transforming | **Morph**: the body turns black and melts into the new shape. **Plain**: the old body shrinks into dark smoke and the new one grows back, as players without the mod see it. |
| | Name Tags | On (default) / off: the name tag above morphed players, when the server shows them |
| Selector | Mouse in Selector, Invert Scroll | On / off. With the mouse on, the wheel scrolls the selector, left click picks, right click closes and middle click favourites; Invert Scroll flips the wheel's direction |
| On Screen | Biomass Bar | Survival Mode: **On Change** (default), **Always** or **Off** |
| | Frenzy Meter | Servers with Devouring Frenzy: **On Change** (and while charged or running), **Always** or **Off**. Until 7.4.0 it followed the biomass bar, so it starts with that setting |
| | Unlock Toasts | On (default) / off: the toast when you gain a new morph |

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
| | Vehicles | Show what you ride, with its passengers (default on) |
| | Stays After | How long a While Active doll lingers, 0–5 s (default 1 s) |
| Placement | Corner, Size | Top left (default) or any corner; 40–800 screen pixels (default 100). The doll keeps its size whatever the GUI scale (6.6.0; older settings are converted once at the GUI scale in use) |
| | Side Distance, Edge Distance | How far in from the corner (default 16 pixels) |
| Look | Turn, Tilt | Which way it faces, -180° to 180° (default 25°); how far you look down on it, -60° to 60° (default 10°) |
| | Head | **Follows You** (default; turns and looks as you do), **Turns Only**, **Looks Up/Down Only**, **Straight**, or **Looks at You** (faces out of the screen) |
| | Sizing | **True Size** (default): a player fills the box, smaller morphs are drawn smaller, only bigger ones are shrunk. **Fill the Box**: every morph is scaled to fill it |
| | Level Head: Elytra, Level Head: Riptide | Keep the head level while gliding or spinning with a trident (both on) |
| Style | Lighting, Opacity | **Bright** (default) or **Like the World**; 10–100% |
| | Vignette Size, Vignette Darkness | How far the haze reaches out, 0–32 screen pixels (0 = off, default 6); how dark it is next to the doll, 10–100% (default 70%) |
| Hide | Show in Menus | Off (default). On: the doll stays while a menu is open, drawn on top of it: your inventory, chests, chat, the pause menu, other mods' screens (not the upgrade tree, the multimorph preview or the arm preview, which draw their own figure) |
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

**7.3.0** renames rage to Devouring Frenzy and gives it three levels (see Devouring Frenzy below). `/morph frenzy` is
the new command; `/morph rage` still works. Its settings, its permission (`morphreborn.rage`) and its Survival upgrade's
id (`rage`) keep the old name. On first start `abilities.yml` is replaced (its `rage` section now has `levels` and
`classic-level`; copy back any changes from the backup), and `survival.yml` gets the new three-level upgrade in place of
the old one (your other values are kept). A player who bought Rage has level 1. Older client mods keep working; they
call it Rage.

**7.4.0** shows players without the mod the wings, fins and rods a multimorph borrows (see Playing), flapping, and,
with the pack, transformations, absorbing and Devouring Frenzy's tendrils in the morph skin as the mod draws them.
Devouring Frenzy takes every creature in reach and lets you hover on its tendrils (see Devouring Frenzy). Swap in the
7.4.0 `RE-Morph-pack.zip` if you serve it (with an older pack, players whose game loaded it see placeholder cubes). On
first start `abilities.yml` is replaced (its `rage` levels gain `hover`, `max-targets` becomes 0 and `hover-speed` is
new; copy back any changes from the backup), `survival.yml` gets the Devouring Frenzy upgrade's new description in place
of the old one (your other values are kept), and `config.yml` gains `appearance.vanilla-skin-effects`. The mod's frenzy
meter has a setting of its own now.

**7.5.0** makes babies work in multimorphs: a baby body borrows from the creature's baby, and players without the mod
see babies' heads, ears, tails, wings and fins at their own size (see Playing). A bee's ability key with nothing in reach
makes it angry or calms it down. For players without the mod, borrowed heads, ears and tails sit where the mod puts
them, and a borrowed head is drawn at most 1.5 times the mod's size to cover the body's own head. Swap in the 7.5.0
`RE-Morph-pack.zip` if you serve it (with an older pack, players whose game loaded it see placeholder cubes on baby
multimorphs). No settings files change.

**8.0.0** makes Devouring Frenzy take 8 hits from at least three attackers to charge (it was 6 from two) and its third
level last 15 seconds (it was 10); every hit its tendrils land heals you half a heart; a charge keeps 30 seconds, then
fades; hovering goes at your walking pace (sprinting no longer doubles it), and the legs walk as a spider's do. On
first start the new settings are added to your `abilities.yml` at their defaults and everything you set stays (your old
file is kept beside it as `abilities-old-7.4.0.yml`), so a server that kept Frenzy's old numbers keeps them: change
`rage` by hand (or delete that section) to get the new ones. A file from before 7.4.0 is replaced as before.

**8.0.0 also adds the arm** (your own arm reshaped into twelve tools and weapons, with four accessories) **and the
biomass passives** (see The arm and Biomass passives below). The arm's numbers are in `abilities.yml` → `arms`; what it
costs, the Arm Shaping and tool upgrades and the passives' prices are in `survival.yml` (`arm-cost`, `passives` and the
upgrade tree). Older clients keep working: they see the tools as plain items, with the server's particles for the
effects, and only the 8.0.0 client mod draws the new tools and the accessories on the body and gives the wheel.

**First-person Model (optional compatible mod):** it works with the RE:Morph client mod, not without it: a player on
Fabric without the client mod is told, a few seconds after their game has loaded the world (once per join, and never to a player whose game has the mod, however slowly it starts), that it gives the full experience
(`client.no-client-message` in `config.yml`, "" turns it off). With [First-person Model](https://modrinth.com/mod/first-person-model)
installed, your body in its first-person view is your morph. Its head is left out and the camera sits just in front of its body, so the view ahead stays clear
and looking down shows its front legs and back (a spider sees its legs on both sides). Humanoid morphs such as zombies
look like a player's body does. First-person Model's own toggle stays in charge: switch its view off and first person
is plain again. Transformations play on your body. As a morph with no head to look out of (slimes, magma cubes,
ghasts, squid), first person is plain. As the ender dragon the camera is at its head, so the rest of it stays behind
you. A multimorph's borrowed parts are part of your body there (blaze rods around your legs, wings behind your
shoulders); its borrowed head is left out like your own. RE:Morph doesn't need First-person Model: without it, first
person is unchanged.

### Multimorph: two morphs at once

A multimorph is a main body with a second form it borrows from. In Survival it needs the **Multimorph** upgrade first;
without it (switching to Survival, or losing the upgrade) a combination comes apart with a message.

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
  whole, no bigger than the head it replaces. The ender dragon's wings and tail are scaled to the body, and on a giant or a (happy) ghast every
  borrowed creature's wings, fins, rods and tail grow with it (an allay's wings were a few pixels there); on any other
  body they keep their own size. Parts morph in with the transformation.
- **Babies** (7.5.0): a baby body borrows from the creature's baby (a baby husk with a wolf has a baby wolf's ears and
  tail), and a baby borrowed lends as a baby; a creature that has no baby (a bat, a phantom) lends its grown parts.
- **A shulker body** (8.0.0) keeps what it borrows inside its shell: a creature's head, shrunk to the shulker's own
  little head and in its place, hidden while the shell is shut and popping out (looking where you look) when it opens
  to use an ability; an animal's ears on the shulker's own head, inside too. Wings, tails and other parts aren't drawn
  on a shulker body.
- **A giant borrowing a player** (your own body or another player's look, 8.0.0) is that player at a giant's size,
  for everyone: no zombie body, no head on it.
- **Traits and abilities:** the combination has every trait either form has (good ones still need their Survival
  unlocks), and every drawback. With two different abilities, the second input (sneak + ability key with the mod,
  `F` without) steps through both; the action bar shows which one the ability key uses. A borrowed creeper's blast
  grows with the body: a ghast's is huge, a chicken's a pop (`multimorph-max-power` under `explode` in
  `abilities.yml` caps it).
  Two forms with the same ability keep the main form's version, except the dash: with a camel (or camel husk) and a
  dolphin (or nautilus) it follows the place, the swim dash in water and the land dash out of it (8.0.0). Fixed-range
  abilities (sting, toss, scare, tongue) are measured from the edge of a big body, not its eye, and the range itself is
  not scaled.
- **Price (Survival):** each form that is new to your body costs its price, plus a quarter
  (`morph-cost.multimorph-extra` in `survival.yml`).
- Picking your body again (normally) drops what it borrows. The combination is saved with you and comes back when you
  log in.
- `/morphclient preview [main] [borrowed]` shows any two creatures combined, without owning or becoming them.
- Players without the mod see the body with the borrowed head on it (drawn up to 1.5 times bigger to hide the body's
  own head, which they always see), an animal's ears on its head and its tail at its back, or a creature's wings, fins or rods where the mod puts them, beating as that creature's do (see the resource
  pack above). Each part moves as one piece: a wing's tip doesn't fold on its own as it does with the mod. Without the
  pack they see only the borrowed heads the game has a skull for (the dragon's, a player's).

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
| `/morph frenzy` | Set off your frenzy once it is charged (see Devouring Frenzy below); otherwise it says how far along it is. `/morph rage` does the same |
| `/morph arm [<form>\|off\|special]`, `/morph arm lantern\|shield\|spyglass\|flint [on\|off]`, `/morph arm lantern bright 1-5` | The arm: form a tool, put it away, use its special, switch an accessory (see The arm below) |
| `/morph passive [name] [on\|off]` | Your biomass passives: what each is doing; with a name, switch it (see Biomass passives below) |
| `/morph tooltips [on\|off]` | The Tooltips setting for players without the mod (permission `morphreborn.tooltips`): on shows how-to lines in every description the server builds, off keeps only names, levels, costs and rates. No word says which it is now. With the mod, the setting is in `/morphclient` and the command only tells you what it is. Also a **Tooltips** button in the bottom right corner of every `/morph upgrades` page. On by default |
| Sneak + swap hands (`F`) | Use the morph's ability (`abilities.yml` → `activation.vanilla-trigger`). With an arm formed, `F` is the arm's special instead |

`/morph ability` also works for everyone.

### Devouring Frenzy

Devouring Frenzy (called Rage in 7.2.0) evens the odds when a crowd turns on you. Take 8 hits within 6 seconds from at
least three attackers (three skeletons juggling you with arrows, a ring of zombies) and your frenzy is charged. It doesn't
go off by itself: you choose when, with the Devouring Frenzy key (unbound at first, set it in Controls) or
`/morph frenzy`, and it stays charged for 30 seconds (`charge-lasts`), even through death, then fades. You're told when it is charged: READY on the
meter with the mod, a chat line without it (or with a mod older than 7.2.0).

Set off, tendrils latch onto every creature you can see within its reach (`max-targets` in `abilities.yml` can cap it) and
hit each one every half second while it lives, for as long as the frenzy lasts. Every hit they land heals you half a
heart (`heal-per-hit`), so the more they hold, the faster you heal. Each one that dies counts as your kill
and is absorbed with the usual animation, giving biomass and a morph under the usual rules (room in your collection,
Absorbable Mass in Survival; reach doesn't apply, the tendrils reach them), and its tendril moves on to the next. Armour
doesn't lessen the hits (Protection and Resistance do). When it ends, the action bar sums it up ("Devouring Frenzy:
absorbed 4 creatures, +86.2 biomass, 1 new morph"), and hits don't count toward the next one for 20 seconds.

While it runs you can rise on its tendrils: jump, and keep Space held to climb, up to the level's height above the
ground; let go and you stay there, walking over the ground on six writhing tendril legs that spread wider the higher you
stand and step as a spider's do, in turns; crouch to come down, and crouch on the ground to walk again. You go at your
walking pace (your morph's: a faster morph or multimorph goes faster), and sprinting doesn't speed it up. The upgrade's description says
how; nothing on screen reminds you. It's up to you: nothing lifts you unless you jump. When the frenzy ends in the air the legs
stay and lower you to the ground, still stepping wherever you walk, with no fall damage; once you're down they draw back out of
the ground into you (crouching to the ground draws them back too). A flying morph flies as usual instead, and creative and
spectator players aren't lifted.

| Level | Reach | Damage a hit | Lasts | Hover | A zombie (20 health) lasts | Survival cost |
|---|---|---|---|---|---|---|
| 1 | 10 blocks | 2 | 5 seconds | 4 blocks | 5 seconds | 1,500 |
| 2 (Classic) | 16 blocks | 2.5 | 8 seconds | 6 blocks | 4 seconds | 4,000 |
| 3 | 20 blocks | 3.5 | 15 seconds | 8 blocks | 3 seconds | 10,000 |

- **What it drains:** hostile creatures, anything that is after you, and whoever hit you (players too). Endermen,
  zombified piglins, piglins and spiders only when they're after you.
- **Never:** your own pets, and anything wearing a saddle, a harness or armour (a saddled pig or horse, an armoured
  wolf, a harnessed happy ghast), whoever put it on.
- **Less:** the ender dragon, the wither, the warden, the elder guardian, villagers, wandering traders, name-tagged
  creatures, other players' pets and players are drained at a quarter of the rate. Players are never brought below
  one heart: a frenzy hurts them but never kills them.
- **Survival:** buy the Devouring Frenzy upgrade (in the Morph section, after Morph Abilities), one level at a time.
  **Classic:** everyone has level 2.

With the mod, you see the tendrils (red, like the red tendrils absorb style), and a meter in the biomass bar's corner
and style: *FRENZY* with a segment for each hit, lit as hits come in and going out as they get old (hits count only once
at least three attackers are hitting you: one or two don't fill it, or bring it up); charged, the segments glow and it says *READY*; while raging, a bar running
down with the seconds left; afterwards, a bar filling back up and the time left. Frenzy Meter in `/morphclient` shows it
when it changes (and while it is charged or running), always, or never. Players without the mod see the tendrils and
the hover's legs in the morph skin with the resource pack, and as wavering red lines of particles without it. Servers
set it all up in `abilities.yml` → `rage`: the hits, the window and the attackers it takes, each level's reach, damage a
hit, length and hover height (`levels`), how long a charge keeps (`charge-lasts`), how fast a hovering player moves
as a share of their walking speed (`hover-speed`), the level everyone has in
Classic (`classic-level`), how many creatures it holds (`max-targets`, 0 for all), what it drains
(`targets: all` drains every creature and player around), the lesser rate and who gets it, the floor for players, and
the wait. `report: true` tells the console and the player, for each frenzy, when each creature was absorbed and how much
health it drained in all, for balancing.

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
     to buy. The two doors on its bottom row open the **Arsenal** (the arm forms) and the **Passives**: there each
     passive you have grown has an On/Off pane under it (a left click on the passive does the same) and a buy button
     for its next level under that (a right click on the passive does it too). A passive costs nothing until it acts.
     (With the mod the tree shows an On/Off switch on each passive you have grown; `/morph passive` works too.)
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
7. **Arm Shaping** (also under Morph Ability) opens the **Arsenal**: your own arm as tools and weapons. Each tool and
   accessory is bought on its own, three levels each, and what you use costs biomass (see The arm below).
8. **Passives** (under Biomass Capacity): abilities of your body that you switch on and off, and that use no biomass until
   they act (see Biomass passives below).

With the mod, a biomass bar sits in the bottom left: a living lump of biomass rather than a box. Its edges ripple,
drops of goo gather underneath and fall, the fill is blood red under the morph's black static, black and red tendrils
sway along the top, and each gain floats up (`+21`) while the whole bar surges. When storage is full, its label reads FULL in
yellow. At large GUI scales it is drawn one scale step smaller, so it stays a small corner readout. It shows for a few seconds after a change by default;
Biomass Bar in `/morphclient` makes it always or never show. A kill that only gives biomass is pulled in by dark red
tendrils, and one that unlocks a new morph by the original black ones (Biomass Gain and New Morph in `/morphclient`).
Players without the mod see red trails for biomass and dark ones for a new morph.

Dying never costs biomass. The whole tree and its numbers are in `SURVIVAL-MODE.md`.

### The arm: tools and accessories (8.0.0)

Your own arm can reshape itself into a tool or weapon of morph tendrils: the **Arsenal**. A formed arm takes the
hotbar slot you hold (the item that was there waits safely and comes back when the arm goes), works as the real thing
for everyone, and has one special move on **F**. There are twelve tools and four accessories. The arm is your own body's:
turning into a morph puts it away. Servers can switch it off (`abilities.yml` → `arms.enabled`) or take a form out
(`forms.<name>.enabled: false`), and each form has a permission (`morphreborn.arm.<form>`, everyone by default).

**Getting it.** Survival: buy **Arm Shaping** (600 biomass, after the Morph Ability), which opens the Arsenal, then each
tool and accessory on its own. Every one has three levels, **iron, diamond and netherite**: a stronger arm, a longer
reach and a stronger special. Classic: every form is there from the start at diamond level, free (`classic-level`).

| Buy | Level 1 / 2 / 3 |
|---|---|
| Axe | 350 / 1,200 / 4,000 |
| Sickle, shovel, claws | 250 / 900 / 3,000 |
| Katana, pickaxe, spear | 400 / 1,500 / 5,000 |
| Whip | 400 / 1,400 / 4,800 |
| Bow (the Tendril Recurve; "Recurve" on the wheel), mace, trident, crossbow | 450 / 1,600 / 5,500 |
| Lantern | 300 / 1,000 / 3,000 |
| Shield | 350 / 1,200 / 4,000 |
| Spyglass | 250 / 900 / 3,000 |
| Flint and steel | 200 / 700 / 2,400 |

**Forming it (with the mod).** Hold **J** (the "Arsenal (Arm Forms)" key). The arm wheel opens: the **12 tools** round the
outside, each with a pip for every level you have bought, and the **accessory ring** inside it. Point at a tool and let
go, and your arm grows into it. Pointing at a tool shows what its special does, what it costs and when it is ready. The lump
of biomass in the middle puts the arm away ("Retract"). A quick **tap** of J puts away the arm that is out, or forms the
last tool again; if there is nothing to put away or re-form, the wheel stays open until you click, tap J again, or press Esc. `Esc` or a right click closes the wheel with nothing changed. The wheel's size is a setting.

**Without the mod (and Bedrock).** `/morph arm <form>` forms a tool, `/morph arm off` puts it away, `/morph arm special`
uses its special (F does the same), `/morph arm lantern|shield|spyglass|flint [on|off]` switches an accessory and
`/morph arm lantern bright 1-5` sets the lantern. The Arsenal page of the `/morph upgrades` chest menu (below) is where
you buy. Everything but the spyglass's zoom and marks works the same; players without the mod see your tool as the game's
item (the tendril models of the resource pack, if they have it; the pack also shows the crossbow loaded or empty and the
trident's drawn throw, but not a thrown trident away from the hand), and your shield as a plain shield in your off hand (raised with right-click, as above).

**F: the special.** F is the game's swap-hands key; while an arm is formed it does the tool's special instead. It
costs biomass in Survival only when it finds something to act on, and it has its own cooldown. **Sneak + F** is the tool's second move
(the whip's Whirl, the bow's arrow tip). With the lantern lit and no tool out, Sneak + F steps the lantern's brightness.

**Slots.** Forming binds the slot you hold to that form, one slot for each form. Scroll away and the arm goes back into your
body, the slot showing its real item with the arm's icon drawn over it (the item small in the corner, with its count);
scroll back onto the slot and the arm forms again at once. Putting the arm away on purpose (a tap of J, the wheel's
middle, `/morph arm off`) frees the slot; `/morph arm off all` frees every slot. Bindings survive death, quitting and a change
of world. **While your inventory (or any screen) is open the arm stays on your body but the slot gives you its real item**, so you can
see and move it, and the arm comes back over the slot when the screen closes (with the mod; without it the arm stays in its slot and can't be moved).
Commands that act on the item in your hand (`/sell`, `/hat`, `/repair`...) put the arm away first, so they get your real item (`arms.command-guard`).

#### The tools

Levels are iron / diamond / netherite. Costs are Survival biomass for one use; a special that finds nothing costs nothing.

| Tool | F, and Sneak + F | What it does | Costs, cooldown |
|---|---|---|---|
| **Katana** | **Draining Cut** | An arc swipe 4.5 / 5 / 5.5 blocks long and 120 / 135 / 150 degrees wide, cutting up to 4 / 6 / 8 creatures for 6 / 7 / 8. The tendrils stay latched (diamond: one more pulse, netherite: two) and what they drink heals you. The blade reaches 0.5 / 0.75 / 1 block further than a sword | 12 / 15 / 18; 7 / 6 / 5 s |
| **War axe** | **Fell** at a tree's trunk, **Cleave** at anything else | Fell brings a whole tree down from the top, up to 32 / 64 / 128 logs (real trees only, never a log building). Cleave is a heavy blow, 12 / 14 / 16 damage, that puts a blocking player's shield down for 5 seconds. Cut logs sometimes give one more (20 / 30 / 40%) | 10 / 12 / 14 each; 8 / 7 / 6 s |
| **Bow** (Recurve) | **Triple Arrow**; Sneak + F cycles the arrow tip | Looses bolts of biomass, no arrows needed (1 biomass a bolt; a full draw hits for about 6 / 8 / 9, a shorter draw or a long flight for less). F readies Triple Arrow for 30 seconds: your next full draw looses three bolts in a 15 degree fan (F again puts it away). The tip cycles **Bleed** (default), **Slowness**, **Poison**, and **Fire** (flint and steel on) and **Glowing** (spyglass on): it rides every bolt except the one that kills | 6 / 8 / 10 when the volley goes; 6 / 5 / 4 s |
| **Whip** | **Snare**, **Disarm**, **Crack**; Sneak + F **Whirl** | Right click casts it like a fishing rod. Reel in with the hook in a block and the line drags you there (up to 12 / 16 / 20 blocks); reel in a creature and it is yanked to you, or you to it if it is far heavier. Left click is a normal hit with a long reach (6 / 6.5 / 7 blocks). F at a creature within 8 / 10 / 12 blocks **snares** it where it stands for 2 / 2.5 / 3 seconds (never a boss); F at a snared creature **disarms** it: its held item is ripped away and flung to you; F at nothing **cracks** a sonic cone, 6 / 7 / 8 blocks, that hurts a little, knocks back and scares mobs away for 3 seconds; Sneak + F **whirls** the lash round you, 4 / 4.5 / 5 blocks out, for 4 / 5 / 6 damage and a knockback. Diamond adds Lure and netherite Luck of the Sea for fishing, and a fish now and then gives a little biomass | Snare 6 / 7 / 8, Disarm 4 / 5 / 6, Crack 6 / 7 / 8, Whirl 10 / 12 / 14; each its own cooldown (3 to 10 s); a cast costs 0.25, a reel 0.5 |
| **Sickle** | **Reap** | Tills like a hoe. Reap harvests every ripe crop within 3 / 5 / 7 blocks and replants each from its own seeds | 8 / 10 / 12; 6 / 5 / 4 s |
| **Spade** (shovel) | **Burrow** | For 10 / 15 / 20 seconds every block you dig takes the eight round it, across the face you dig | 8 / 10 / 12; the window plus 10 s |
| **Pickaxe** | **Vein Rip** | Aimed at an ore: tendrils tear the whole vein out of the rock, ore by ore (up to 8 / 16 / 32, from 5 / 6 / 7 blocks away), and carry the drops to your feet | 8 / 10 / 12; 8 / 6 / 4 s |
| **Spear** | **Impale** | The game's own jab and charge, with iron, diamond or netherite stats. F at a creature is a lunge along the ground, 3 / 4 / 5 blocks, that skewers it for 4 / 5 / 6, then spikes erupt from the ground round it and pin it where it stands for 1.5 / 2 / 2.5 seconds. With no creature in reach F does nothing | 10 / 12 / 15; 8 / 7 / 6 s |
| **Mace** | **Skyward Leap** | The game's mace, whose smash grows with the fall. F on the ground: tendrils launch you straight up 6 / 8 / 10 blocks, you land with no fall damage, and the fall powers the smash of your next mace hit | 8 / 10 / 12; 10 / 9 / 8 s |
| **Trident** | **Recall** | Right click throws it as the game does (8 / 9 / 10 damage), and a tendril line stays attached and **reels it back** to your hand, never you to it. No Riptide, no Loyalty, and it can't be picked up. F **recalls** it at once: it snaps back and hits every creature along its path for 4 / 5 / 6. Until it is back you can't throw again | Throw 0.5, Recall 4 / 5 / 6; 4 / 3.5 / 3 s |
| **Crossbow** | **Spine Burst** | It loads itself: a click fires the bolt at once (7 / 8 / 9 damage), and a new one regrows out of the weapon over the game's own charge time, 1.25 seconds. No ammunition. F needs the whole bolt: it fires three bone spines one after another in a tight line, each piercing up to 3 creatures for 3 / 4 / 5 | 10 / 12 / 15; 8 / 7 / 6 s |
| **Claws** | **Rake** | Shears underneath: quick on leaves, wool and cobwebs, and they shear sheep. F is a three-slash sweep in front of you (a 100 degree fan, 3.5 / 4 / 4.5 blocks, 1.5 / 2 / 2.5 a slash, up to 4 / 6 / 8 creatures) and what it hits **bleeds**, 1 damage a second for 3 / 4 / 5 seconds (armour doesn't stop it) | 8 / 10 / 12; 7 / 6 / 5 s |

The digging forms (axe, spade, pickaxe, sickle) carry Fortune and Efficiency at 1 / 2 / 3 and reach a little further than the
real tool. The specials that break blocks (Vein Rip, Fell, Burrow, Reap) follow the world's mob griefing (`arms.block-specials`).
Draining Cut and Cleave may hit players (the server's PvP rules and plugins still decide), never below one heart; the
whip's Snare and Disarm and the spear's pin leave players alone unless the server allows it. Bosses and other players' pets are
never held or pinned.

#### The accessories

Four accessories sit on the arm's inner ring, beside any tool or a bare arm, **several at once**. Each is its own tendril
on your body, drains a little biomass a minute while it is on (in Survival), and is switched with a click on its lump in
the wheel (or `/morph arm <accessory>`); they all go off when you die, morph, change world or run out of biomass.

| Accessory | Drain | What it does |
|---|---|---|
| **Lantern** | 0.5 a minute | A glowing ball on a chain hangs from your arm and lights the area round you for everyone. Its brightness is 1 to 5 (8, 11, 14, 20 and 30 blocks of light; level 1 reaches brightness 3, level 2 brightness 4, level 3 brightness 5): scroll over it in the wheel, Sneak + F with no tool out, the Lantern Brightness setting, or `/morph arm lantern bright N`. With the mod everyone sees a soft wash in your arm's colour over what it lights (a setting) |
| **Shield** | 0.5 a minute, and 1 for each blow it blocks | **Hold right-click to raise it** (any tool, a fraction of a second to come up; let go to lower it). The item in your main hand comes first, as it does for a vanilla shield: a click that a bow draw, a thrown trident, food, a block to place, a door or a villager uses stays that item's, and only a click the main hand has no use for raises the shield. While the shield is on, the item in your off hand is never used (it is not touched or moved either). Players without the mod are shown the shield in their own off hand and raise it with right-click the same way (their off-hand slot shows a shield while it is on). Their game only says when the key goes down and up, so a window opening (a chest, the inventory), a change of world, or five minutes of holding on one press lowers it, and the next click raises it again. In creative mode, on Bedrock, on a game from before 1.9 (through a protocol plugin), or without PacketEvents on the server they hold Sneak instead. Blows from in front (a 100 degree cone) lose 50 / 60 / 70% of their damage, and arrows, tridents, fireballs and other projectiles from in front **fly back at whoever shot them**. To players with the mod your off hand morphs into the shield (picked over a shield wrapping round from the back, 2026-11-16); players without the mod see a plain shield in your off hand. The item that is really in your off hand is never touched |
| **Spyglass** | 0.25 a minute | Needs the client mod and your own body. **How to use it:** switch it on (click its lump in the wheel, or `/morph arm spyglass`), then **hold Z** (the Spyglass zoom key; Controls, under RE:Morph) and look at a creature. Your view zooms, and what you look at within 64 / 96 / 128 blocks (up to 1 / 2 / 3 at once) is **marked for you alone**: an outline and its distance on the screen, for 5 seconds after you last looked. Let go of Z and the zoom ends; the marks end with it. The outline needs PacketEvents on the server, and other players are marked only where you could strike them. It also gives the bow its Glowing tip |
| **Flint and steel** | 0.25 a minute, and 0.5 for each ignition | It has no key of its own. **How to use it:** switch it on (click its lump in the wheel, or `/morph arm flint`), and from then on, in your own body, **every melee hit you land sets the target alight** for 3 / 4 / 5 seconds, and **every arrow you shoot burns**, from any bow or crossbow (vanilla ones too), and so does a thrown trident: it sets what it hurts alight as Flame would, for 4 / 5 / 6 seconds, and an ignition is billed only for a creature that actually catches (a miss costs nothing, nor does an arrow into something already alight). A bow that has Flame on it is left to Flame. To light a block, **right-click a campfire, a candle, TNT or a portal frame with a formed arm in your main hand**: it lights what flint and steel lights (a click on anything else is the arm's normal use). It gives the bow its Fire tip. Nothing burns that is fire-immune or in water, and a protection plugin can stop the fire |

**In Survival the arm runs on your biomass.** Its durability bar is your biomass (living goo in the mod, a plain bar if you
set it so), and when it runs out the arm melts back and your item returns. Forming needs at least 1 biomass. A formed arm
drains 1 a minute; each block it breaks costs 0.1 (blocks that break at once, like grass, are free), each melee hit 0.25
(a sweep's other creatures are free), a strip, till or path 0.1, plus the specials above. The arm also **steps down a level
as your biomass falls below each third of the bar**, and back up as it refills, never above the level you bought
(`arms.tier-follows-biomass`). Classic and creative players pay nothing. Servers change the prices in `survival.yml` →
`arm-cost`, or in game with `/morph cost arm`.

**The Arsenal page.** `/morph upgrades` (the chest menu) has two doors at the bottom of the main page: the netherite sword
is the **Arsenal**, the sculk sensor the **Passives**; Arm Shaping, which unlocks the Arsenal, is bought on the main page next
to them. The Arsenal page shows the 12 tools in two rows and the 4 accessories on a row under them, each in its fixed place
with its level and price; click one to buy its next level. The arrow at the top left goes back. It is a chest menu, so
players without the mod and Bedrock players have all of it. With the mod the upgrade tree has the same Arsenal section.

**Arm settings (with the mod).** `/morphclient` → **Arm Forms...** (also under More Settings). Three sections, the ones you use most first:

| Section | Setting | Choices |
|---|---|---|
| Your Arm, Seen by Others | Arm Look | **Tendril Arm** (default), **Plain Hand** (your own arm with a few small tendrils round the hand) or **Armour** (the arm in chitin plates). Everyone with the mod sees your choice; the shield is always the off hand morphing into it |
| | Arm Colour... | The colour of your arm's red parts and the mod's other red accents: red, green and blue sliders or a hex colour, with a Reset. Other players with the mod see you in it, and the lantern's wash takes it. Players without the mod always see the default red |
| | Pulse Speed | How fast the light pulses run along your arm and lantern, from a quarter to three times the usual (1.00x). Everyone with the mod sees your pulses at this speed |
| | Lantern Brightness | Your lantern's brightness, 1 to 5. Everyone sees it: it is sent to the server, the same as Sneak+F with the lantern lit |
| Hotbar and Wheel | Arm Bar | **Living Goo** (default): the biomass bar's moving static under the form in your hotbar. **Plain**: the ordinary durability bar |
| | Arsenal Wheel Size | How big the wheel is, as a share of the screen (75% by default) |
| Effects | Arm Detail, Arm Glow | How finely arms are drawn from far away, and the glowing red accents. Only change what you see |
| | Arm Fizz, Arm Drips | The fizzing edge where a form grows or melts (Auto turns it off under a shader pack), and drops of biomass |
| | Vein Outline | The outline of Vein Rip's tendrils through the stone |
| | Lantern Light, Lantern Motes, Coloured Lantern Light | The lantern's light in the world with LambDynamicLights; the warm motes in its beam; the soft wash in your arm's colour over what it lights |

### Biomass passives (8.0.0)

Passives are abilities of your body that you **buy once and then switch on and off at will**. They are a Survival upgrade
(in the tree under Biomass Capacity; **Sustenance** comes first, and each of the others needs it).

- **With the client mod:** you buy passives in the upgrade tree (under Biomass Capacity), and each one you have grown carries a small
  **On/Off switch** at the bottom left of its icon (needs a server on 8.0.0 or newer; on an older one use the command). Click the
  switch to turn it on or off (a click anywhere else on the icon buys the next level, as before); hover it to see what the passive is doing.
  The switch is coloured as the chest menu's panes are: green running, yellow on but idle, red on but out of biomass, grey off. The same switches
  work with `/morph passive <name> [on|off]`, and the choice is saved with you. The chest menu below is for players without the mod.
- **Without the mod (and Bedrock): buying and switching:** open the chest menu's **Passives** page (the sculk sensor door at the bottom of the
  `/morph upgrades` main page, next to the Arsenal's). Each passive you have has an **On/Off** pane under it, and a
  **buy button** under that for its next level. A left click on the passive does what the pane does; a right click (or shift click) does what the
  buy button does, and the button is there for Bedrock players, who have no right click. A pane tells you how it stands: green is
  running, yellow is on but idle (nothing to do right now), red is on but out of biomass, grey is off, black is not bought. `/morph passive` lists them and
  `/morph passive <name> [on|off]` switches one (no word toggles). The choice is saved with you.
- **The billing rule (2026-11-16): a passive uses no biomass until it acts.** It is billed in one of three ways.
  *Triggered*: a price for each thing it does. *Acting*: it drains only while it is doing something. *Constant*: it
  drains the whole time it is on. With too little biomass to pay, it does not act and **nothing is spent**: the fire
  burns, the poison poisons. Servers change all of it in `survival.yml` → `passives`.

| Passive | Buy | What it does | When it costs |
|---|---|---|---|
| **Sustenance** | 600 (Capacity 2) | Fills your hunger a point at a time, so you never need to eat | 2 for each point of hunger it fills and 0.5 for each point of saturation with it; nothing when you are full |
| **Night Eyes** | 400 | Night vision | 1.5 a minute, only in the dark |
| **Gills** | 3,000 (Capacity 6) | Air under water | 0.15 a second, only with your head under water; free if your form already breathes there |
| **Fire Shedding** | 5,000 (Capacity 7) | Fire, lava and hot floors can't burn you | 0.5 for each second of fire it stops |
| **Antitoxin** | 3,500 (Capacity 6) | Poison, wither, hunger and nausea wear off at once | 1 for each effect it stops |
| **Tendril Sense** | 3,000 (Capacity 6) | Creatures within 24 blocks glow for you alone (needs PacketEvents) | 30 a minute, only with a creature in range |
| **Scavenger** | 800 / 1,600 / 3,200 | Small tendrils fetch the items lying within 6 / 10 / 16 blocks | 0.05 for each item, once per item; an item you have no room for is left alone |
| **Borrowed Scent** | 1,500 (Capacity 4) | The creatures of the kind lent to your own body, or worn with your own head, leave you alone. A morph of creatures alone is Camouflage's job, not this one | Constant: 6 a minute while on and a creature that can target you is combined with you; nothing in your own body, in a morph of creatures alone, or with a creature that never targets players (a cow) |

### Abilities

Morphs get the mob's traits while fully transformed, the way the real mob lives:

- **Movement:** bats and phantoms fly; spiders treat any wall like a ladder (walk into it to climb, let go to slide
  down, sneak to hang on) and walk through cobwebs; spiders and cave spiders climb cobwebs like scaffolding (jump to
  go up, stand on top, sneak to go down); rabbits and foxes walk on powder snow; striders stand on lava;
  turtles plod on land (sprinting doesn't help) and swim at full speed; chickens fall slowly and keep their running
  speed in the air.
- **Senses:** bats, cats, foxes, phantoms, glow squid and fish (cod, salmon, tropical fish, pufferfish) see in the dark.
- **Quiet steps:** soft-footed forms (foxes, rabbits, cats and the like) raise no walking, swimming or soft-landing
  vibrations, so sculk sensors and wardens do not hear them move, as if you were sneaking. Standing directly on a
  sculk sensor, a calibrated sculk sensor or a sculk shrieker still sets it off, as it does for a sneaking player.
  Heavy or noisy forms (creepers, skeletons, ...) are heard as usual. Everything else you do is as loud as ever, and the usual footstep
  sound is unchanged. It is there from the first morph in both modes (no unlock, no biomass, nothing to switch on), and
  it is separate from Camouflage, which is about mobs seeing you. The body decides: a fox with a creeper head is quiet,
  a creeper with a fox head is not, and neither is your own body with a fox head. A fall of more than 3 blocks is still
  heard. The forms are listed in `abilities.yml` (`passive.quiet-steps`).
- **Water and air:** fish suffocate on land; axolotls, dolphins and nautiluses dry out away from water (the air bar
  shows how long you have); dolphins hold their breath for 4 minutes.
- **Undead:** they burn in sunlight, and Healing potions hurt them while Harming heals them.
- **Other hazards:** endermen teleport out of water; snowballs hurt blazes; a creaking can't move while someone is
  looking at it.

Abilities are always yours to trigger:

- **Ability key:** creepers explode (a creeper struck by lightning is charged, like a real one: it gets the blue aura
  and its next explosion is twice as big; killing a charged creeper unlocks a charged-creeper morph, which always is); endermen teleport; ghasts shoot fireballs; bees sting once (and die a minute
  later, like a real bee), and with nothing in reach the key makes a bee angry (red eyes) or calms it down; turtles lay eggs on sand; rabbits hop forward.
- **Bat:** the ability key hangs you upside down from the block above (within 2.5 blocks of your head), like a
  resting bat. Let go with the ability key again, any movement key or jump; getting hurt or the block disappearing
  also drops you. You go back to flying or standing, whichever you were doing.
- **Shulker** (8.0.0): you are a block, as a shulker is. Becoming one, you cling to the block face your crosshair is
  on if it's in reach, otherwise the floor under you, square in that block's space: on a floor, a wall or upside down
  on a ceiling (vanilla's rules: a face with a full collision side, and room for the lid to open). No key moves you;
  **sneak + jump** teleports you onto the face you look at, up to 8 blocks away. Others bump into you and can stand on
  you. The shell is shut until you use an ability (the ability key shoots a shulker bullet, homing in on the creature
  in your crosshair or flying straight where you look): shut, it gives 20 armour
  and arrows and tridents bounce off; it opens for 3 seconds when you use an ability. With nowhere to cling to (in the
  air, in water) you move freely until you land. A shulker that is only borrowed by another body isn't a block. In
  First-person Model's view the shell is invisible while it's shut and shows round you while it's open.
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
  decide the trades, each one runs out after its uses and they restock every Minecraft day (on a world whose time
  stands still, after a day's worth of play, 20 minutes). The buyer pays; you give
  and get nothing. The admin can turn this off, or hold you in place while someone trades.

Skeletons raise a held bow, pillagers and piglins load their crossbows and drowned wind up a trident, like the real
mobs in combat. Players without the mod see these too, plus:
- each ability's own animation, such as a panda's roll, a polar bear rearing up, a sheep grazing, an illusioner's
  spell and mirror images, and a copper golem sorting;
- changes to the mob: a sheared sheep, a bee that has lost its stinger or is angry, an enderman's block.

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
| `/morph frenzy <player> [go [level]\|fill]` | For testing: what their Devouring Frenzy key would do, or set their frenzy off now, charged or not, at their level or the one given (`go`), or charge it (`fill`) |
| `/morph upgrades <player>` | Open the upgrade tree (or chest menu) for someone |
| `/morph carry <player> <block or none>` | Set the block an enderman morph is holding |
| `/morph setting [name] [value]` | Show or change the world-effect settings below |
| `/morph cost [on\|off \| rate <per-kg> \| minimum <n> \| price <mob> <biomass\|formula> \| gain <multiplier>]` | Survival: show or change morph prices and the biomass gain (saved to `survival.yml`). `price` fixes one mob's price and `formula` puts it back on the size formula; `gain 2` doubles biomass from kills |
| `/morph cost arm [on\|off \| block\|hit\|transform\|bolt <biomass> \| drain\|lantern-drain\|shield-drain\|spyglass-drain\|flint-drain <biomass a minute> \| special <key> <level 1> <level 2> <level 3>]` | Survival: show or change what arm forms cost (saved to `survival.yml` `arm-cost`; formed arms are put away first) |
| `/morph arm <player> <form\|off\|lantern\|shield\|spyglass\|flint> [level] [free]`, `info`, `special`, `brightness`, `recover` | Form, inspect and test someone's arm (needs `morphreborn.arm.admin`): `level` and `free` ignore what they own and what it costs; `recover` gives back the item an arm replaced, if it could not be returned |
| `/morph mode [classic\|survival]` | Show the gameplay mode, or switch it now for everyone (saved to `config.yml`) |
| `/morph reload` | Reload the config files, including a `gameplay-mode` you changed by hand |
| `/morph survival <player> info` | Their biomass and upgrade levels |
| `/morph survival <player> unlock` / `lock` | Unlock or re-lock their Biomass ability |
| `/morph survival <player> give\|take\|set <amount>` | Change their biomass: `give` stops at their capacity (the answer says so), `set` is not limited by it |
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
| `morphreborn.rage` | everyone | Devouring Frenzy (when `abilities.yml` `rage.enabled` is on) |
| `morphreborn.tooltips` | everyone | `/morph tooltips`: switch the explanation lines on or off for yourself |
| `morphreborn.arm` | everyone | The arm forms (when `abilities.yml` `arms.enabled` is on) |
| `morphreborn.arm.<form>` | everyone | One form: `katana`, `pickaxe`, `axe`, `shovel`, `sickle`, `bow`, `whip`, `spear`, `mace`, `trident`, `crossbow`, `claws`, or an accessory: `lantern`, `shield`, `spyglass`, `flint` |
| `morphreborn.arm.admin` | ops | `/morph arm <player> ...` |
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
