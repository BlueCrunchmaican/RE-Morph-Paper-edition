# RE:Morph 6.1.5

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
| `server/RE-Morph-paper-6.1.5.jar` | The Paper plugin |
| `client/RE-Morph-fabric-6.1.5+26.3.jar` | The Fabric client mod (optional, Java players only) |
| `resourcepack/RE-Morph-sounds.zip` | Optional server resource pack with the original morph sound, for players without the mod |
| `default-configs/` | The config files the plugin writes on first start, for reference |
| `ABILITIES.md` | Every mob's traits and abilities |
| `SURVIVAL-MODE.md` | How Survival Mode works: the unlock, biomass, the upgrade tree and its numbers |
| `TESTING.md` | What has been tested, and a checklist for live testing |
| `source/` | Full source code (required by the LGPL licence) |
| `LICENSE`, `LICENSE-GPL-3.0`, `NOTICE.md` | Licence and credits |

## Server install

Needs a **Paper 26.3** server (built against build 235) running Java 25 or newer.

1. Put these three jars in the server's `plugins` folder:
   - `RE-Morph-paper-6.1.5.jar` (this release)
   - **Lib's Disguises** 26.10.24 or newer: https://www.spigotmc.org/resources/libs-disguises-free.81/
   - **PacketEvents** 2.14.0 or newer (Lib's Disguises needs it): https://modrinth.com/plugin/packetevents
2. Start the server once. The plugin creates `plugins/RE-Morph/` with `config.yml`, `abilities.yml` and
   `variants.yml`.
3. Change settings if you want (see below), then run `/morph reload` or restart.

Geyser and Floodgate need nothing extra. Bedrock players count as players without the mod.

### Updating

Replace the old `RE-Morph-paper-*.jar` with the new one (don't keep both) and restart the server.

- **`config.yml`** keeps every value you set. When a release adds settings, the plugin updates the file on startup:
  the new settings appear in their place with their defaults and explanations, and the console lists them. Your
  old file is kept next to it (for example `config-old-6.1.4.yml`), along with any notes you wrote in it.
- **`abilities.yml` and `variants.yml`** are replaced automatically when they come from an older release. Each one
  says which release it comes from in its `config-version` line. The console names the backup it keeps, for example
  `abilities-old-pre-5.0.0.yml`; copy back any changes you had made.
- **Players' collections** in `players/` stay as they are.

Players with the client mod should swap their old `RE-Morph-fabric-*.jar` for the new one; two copies in the
`mods` folder stop the game from starting.

**Coming from Morph Reborn** (the project's name until 6.1.2): remove `MorphReborn-paper-*.jar` and put
`RE-Morph-paper-6.1.5.jar` in its place. On first start the plugin moves `plugins/MorphReborn/` to `plugins/RE-Morph/`,
so settings and collections carry over. Permissions (`morphreborn.*`), `/morph` and its old `/morphreborn` alias,
and the client mod's settings file keep their names. Players swap `morphreborn-fabric-*.jar` for
`RE-Morph-fabric-6.1.5+26.3.jar`.

**6.1.2 changes the client protocol.** On a 6.1.2 server, an older client mod (5.x) is told to update and is treated
as a player without the mod until it does: they still see and use morphs, just without the selector and animations.

6.1.2 also adds `survival.yml` and the `gameplay-mode` setting (see Survival Mode).

**6.1.4 updates `config.yml` for you.** Before 6.1.4 the plugin never touched an existing `config.yml`, so settings
added since your first install (such as `gameplay-mode`) were missing from it and silently used their defaults. On
the first start with 6.1.4 they are added, at their defaults, and your values stay.

**6.1.4 also adds `/morph mode`**, so admins can switch between Classic and Survival in game, at once, without a
restart (see Admin commands).

**6.1.5** fixes players getting stuck or jittering against walls and rough ground while transforming, and adds the
paper doll to the client mod. Nothing to change in your config files. Players should update the client mod to get the
doll and the smooth camera; 6.1.4 clients still work.

### Optional: the morph sound for players without the mod

The original Morph sound is built into the client mod. Players without the mod hear a vanilla stand-in instead. To give
them the real sound, add `resourcepack/RE-Morph-sounds.zip` to your server resource pack (or use it as the server
resource pack), then set `appearance.vanilla-clients-have-sound-pack: true` in `config.yml`.

## Client install (Java players, optional)

1. Install **Fabric Loader** 0.20.1 or newer for Minecraft 26.3: https://fabricmc.net/use/installer/
2. Put these in the `.minecraft/mods` folder:
   - `RE-Morph-fabric-6.1.5+26.3.jar` (this release)
   - **Fabric API** 0.165.0+26.3 or newer: https://modrinth.com/mod/fabric-api
3. Start Minecraft with the Fabric profile and join the server.

The mod only does something on a server running the RE:Morph plugin. If the mod and the plugin don't speak the
same protocol version, the player is told to update and is treated as a player without the mod until they do.

## Playing

**Unlocking:** kill a mob to unlock it as a morph. Different looks (sheep colours, villager jobs, babies, cat breeds and
so on) unlock separately. Killing a player unlocks their form; killing a morphed player unlocks the morph they're using.

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
| `B` | Survival Mode: the upgrade tree (also the Biomass button in the middle of the favourites wheel) |

Keys can be changed in **Options → Controls → Key Binds**, under RE:Morph (mouse side buttons work too). "Return to player form" has no key by
default. You can also pick your own player form in the selector.

**Client settings:** type `/morphclient` (or bind the "RE:Morph Settings" key). Only your own screen changes:

| Setting | Choices |
|---|---|
| Absorbing | **Morph**: black tendrils pull the defeated mob in. **Plain**: dark particle trails, as players without the mod see it. |
| Transforming | **Morph**: the body turns black and melts into the new shape. **Plain**: the old body shrinks into dark smoke and the new one grows back, as players without the mod see it. |
| Unlock toasts, Name tags, Mouse in selector, Invert scroll | On / off |
| Biomass Bar | Survival Mode: **When It Changes** (default), **Always** or **Off** |
| Paper Doll... | Opens the paper doll's own settings (below) |

Unlocking and transforming work the same either way, and other players see their own setting, not yours. The
settings are saved in `config/morphreborn-client.json`.

**Paper doll:** a live miniature of yourself in a corner of the screen. It shows exactly what the world shows: your
morph with its variant and baby size, its own animations (a rabbit's hop, a chicken's flapping, a fish flopping), your
pose (walking, sneaking, swimming, sleeping, sitting in a vehicle), the transformation as it happens, and, while you
ride, the boat, minecart or mount with everyone on it. A player fills the doll's box; smaller morphs are drawn smaller,
and only something bigger than a player is shrunk to fit. Settings (RE:Morph Settings → Paper Doll...):

| Setting | Choices |
|---|---|
| Paper Doll | On (default) / off. The **Show/Hide Paper Doll** key (Controls, unbound at first) flips it. |
| Show | **Always** (default), **While Morphed**, or **While Active** (moving, jumping, sneaking, swimming, flying, riding, transforming, attacking or hurt, and a moment after) |
| Corner, Side Distance, Edge Distance | Where it sits (default top left, 8 pixels in) |
| Size, Opacity | 20–200 GUI pixels (default 50); 10–100% |
| Turn | Which way it faces, -180° to 180° (default 25°) |
| Head | **Follows You** (turns and looks as you do) or **Straight** |
| Lighting | **Bright** (default) or **Like the World** |
| Vehicles | Show what you ride, with its passengers (default on) |
| In Menus, Hide in Third Person, Hide with F3 | When it steps aside (defaults: hidden in menus, in third person and with F3) |

It also steps aside while the selector or the favourites wheel is open.

### Without the client mod (plain Java and Bedrock)

| Command | Does |
|---|---|
| `/morph list [player]` | Your morphs (click one to morph), or someone else's |
| `/morph use <id or mob>` | Morph, e.g. `/morph use pig` |
| `/morph off` | Back to your own body |
| `/morph fav <id>` | Favourite / unfavourite |
| Sneak + swap hands (`F`) | Use the morph's ability (`abilities.yml` → `activation.vanilla-trigger`) |

`/morph ability` also works for everyone.

### Survival Mode

If the server plays Survival Mode, morphing has to be earned:

1. At first kills give nothing and the selector stays closed.
2. Have **Wither and Regeneration at the same time** ("A Mutation Complication"). From then on every kill absorbs the
   creature's **biomass**, shown in the action bar (and the biomass bar with the mod). Big creatures need upgrades
   before you can absorb them, and kills too far away don't count.
3. Spend biomass on upgrades:
   - **With the client mod:** hold the favourites key (`` ` ``) and click the **Biomass** button in the middle of the
     wheel (or let go of the key over it), or press `B`, or type `/morph upgrades`. The upgrade tree is laid out like
     the advancement screen, each upgrade shown with a status effect icon. Drag or scroll to move around, hover for
     details, click to buy. Upgrades you can afford right now glow red. In Survival the wheel opens even before you
     can morph, showing just the Biomass button.
   - **Without the mod (and Bedrock):** `/morph upgrades` opens a chest menu; click an item to buy.
   - Anyone: `/morph upgrade <name>` buys directly, `/morph biomass` lists your progress.
4. Buy the **Morph Ability** to start morphing. Morphs you already had open up, and new kills unlock morphs while
   your collection has room (Morph Capacity, Variant Capacity).
5. **Morph Abilities** unlocks the ability key, **Morph Flight** lets flying morphs fly (until then they glide), and
   **Morph Efficiency** makes transformations faster.

With the mod, a biomass bar sits in the bottom left: a blood-red fill under the morph's black static, tendrils of it
swaying along the top, and each gain floating up (`+21`). It shows for a few seconds after a change by default;
Biomass Bar in `/morphclient` makes it always or never show.

Dying never costs biomass. The whole tree and its numbers are in `SURVIVAL-MODE.md`.

### Abilities

Morphs get the mob's traits while fully transformed, the way the real mob lives:

- **Movement:** bats and phantoms fly; spiders treat any wall like a ladder (walk into it to climb, let go to slide
  down, sneak to hang on) and walk through cobwebs; rabbits and foxes walk on powder snow; striders stand on lava;
  turtles plod on land (sprinting doesn't help) and swim at full speed; chickens fall slowly and keep their running
  speed in the air.
- **Senses:** bats, cats, foxes, glow squid and fish (cod, salmon, tropical fish, pufferfish) see in the dark.
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
- **Enderman:** picks up a block with an empty-hand right-click and puts it down with the next one.

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
| `/morph info <player>` | Their form, collection size, and whether they have the mod |
| `/morph clients` | Who online has the mod |
| `/morph ability <player>` | Fire someone's ability |
| `/morph carry <player> <block or none>` | Set the block an enderman morph is holding |
| `/morph setting [name] [value]` | Show or change the world-effect settings below |
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
| `morphreborn.admin` | ops | Admin commands |
| `morphreborn.type.<namespace>.<mob>` | — | Only checked when `morphs.per-type-permissions: true`, e.g. `morphreborn.type.minecraft.ender_dragon` |

## Settings

**Gameplay mode** (`config.yml`, `gameplay-mode`; or switch in game with `/morph mode classic` or `/morph mode survival`):
- `classic` (default): the original Morph. Kill a creature to unlock it.
- `survival`: morphing is earned with biomass. Its rules and upgrade tree are in **`survival.yml`**: the unlock
  effects, the biomass formula, per-creature multipliers or fixed masses, creatures that give nothing, and each
  upgrade's cost and values.

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
- **Some movement traits need the client mod:** walking through cobwebs and on powder snow, and standing on lava.
  Without it the server still stops the freezing and the slowdown on its side, but the player's own game slows them
  in webs and lets them sink into snow and lava (striders float back up).
- **An enderman morph carries only the block itself.** That's why chests, signs and similar blocks can't be picked up.
