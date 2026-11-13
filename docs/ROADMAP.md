# RE:Morph roadmap

The working plan: what's done, what's open, what's planned, and why things were decided. Update it
whenever one of those changes.

Current version: **7.4.0** (2026-11-12). Two gameplay modes: Survival (the default from 7.2.0) and Classic. (5.1.0 and 5.2.0 were
built and tested but never released; their changes ship in 6.0.0.)

## 6.6.1 (released 2026-11-07)

A fix for 6.6.0, nothing else.

- [x] The client mod crashed as soon as its biomass bar showed at its narrowest, exactly 100 pixels wide (a small
      Biomass Capacity, such as a new Survival player's 100): one of the falling drops was placed past the bar's end.
      The drops now stop at the end; every width from 100 to 399 was checked.

## 6.6.0 (released 2026-11-07)

Collected into one release instead of many small ones. Test builds were `6.6.0-beta.N`.

- [x] Paper doll: a Vignette Size slider (0 = off) instead of Off / Soft / Wide; old settings carry over. Bigger sizes
      get more rings so the haze stays smooth.
- [x] Paper doll: its size, distances and vignette are screen pixels, so it keeps its size whatever the GUI scale (it
      has its own Size). Older settings convert once at the GUI scale in use, so nothing changes until the scale does.
- [x] Paper doll: Show in Menus draws the doll on top of every menu (inventories, chat, the pause menu, other mods'
      screens) instead of under the menu's darkened background.
- [x] Update checks: the plugin (console, admins on join) and the client mod (chat on joining a RE:Morph server) ask
      GitHub for the latest release; the plugin also tells players whose client mod is older than it. Every notice
      has short [Modrinth] and [GitHub] links. Both sides can be switched off. The server's handshake now carries its
      version (an extra field at the end; protocol stays 3).
- [x] Semantic Versioning ([VERSIONING.md](VERSIONING.md)): the release script reads the version from the build files
      and stops if the plugin's and the client's differ.
- [x] Survival upgrade tree in sections: Biomass (Capacity, then Efficiency, Reach, Absorbable Mass), the Morph
      Ability on its own (a lump of biomass with tendrils), Morph (Capacity with Variant under it, Efficiency,
      Abilities), and Abilities. The chest menu for players without the mod uses the same sections.
- [x] Abilities section: 13 one-time unlocks for the good traits (night vision, water breathing, swift swimming, fire
      immunity, soft landing, flight, climbing, leaping, mob strength, sure footing, defences, attack effects,
      camouflage), opened by Morph Abilities. Drawbacks stay on. survival.yml lists each unlock's traits.
- [x] Morph Efficiency: transformations from 6.5 seconds down to 3.5. Morph Abilities: ability cooldowns from ×1.3
      down to ×0.7. Players see Survival's own numbers, never a comparison with Classic. Reach 3 to 150 blocks.
      Absorbable Mass has 5 levels (the last is no limit). Value and cost can be a list, one number per level.
- [x] An older survival.yml gets the new upgrades section (the old one is backed up); levels players bought move to
      the first new level at least as good.
- [x] Favourites wheel: letting go of the key in the middle only closes it; a click on the Biomass button opens the
      tree. The button is round, with moving tendrils.
- [x] Spider webs stay (abilities.yml `web.duration: 0`).
- [x] The upgrade tree puts Abilities beside the Morph section when the window is too short to show them below, so
      the whole tree fits without scrolling. A click that opened the tree (from the wheel) no longer buys what is
      under the mouse when it is let go.
- [x] Biomass bar: a living lump of goo instead of a rectangle, all of it animated: rippling edges, rounded ends, a
      pulsing seam where the fill ends, drops that gather and fall, black and red tendrils; the fill keeps the blood
      red under the morph's static. Drawn one GUI scale step smaller at scale 4 and up; the label and the amount no
      longer run into each other (the bar widens for big capacities; FULL replaces the label).
- [x] A player's own morph particles in first person (a blaze's smoke) are spawned well behind the camera, half as
      many, so they no longer cover the view; other players, and third person, show them as before.
- [x] [MOB-MASSES.md](MOB-MASSES.md): every mob's weight, the Absorbable Mass level it needs, its biomass and morph
      cost.
- [x] Tried in game: the wheel centre, the tree, ability unlocks (night vision, fire immunity), the speed wording, the
      goo bar at GUI scales 2, 3 and 5, the doll's size and vignette, the doll over the inventory, an old survival.yml
      updating (see [TESTING.md](TESTING.md)).
- [ ] Still to try in game: the update notices with an older client and an older server; the chest menu; the
      remaining ability unlocks; Show in Menus over another mod's screen.
- [ ] The Modrinth project was still under review at release (https://modrinth.com/plugin/re-morph); until it is
      approved, the update notices' [Modrinth] link only opens for project members. Once it is public, point the
      README's and guide's download links there too.
- [x] Released: Modrinth versions `6.6.0+paper` / `6.6.0+fabric`, GitHub release `RE:Morph 6.6.0` on tag `v6.6.0`.

## Released: 7.4.0, wings and fins, the morph skin and the frenzy's hover for players without the mod

The user asked for the wings and fins of multimorphs to show for players without the mod (until now they saw the head
of a creature known by them), and for extensive testing from a vanilla client so it looks right. Built as
7.4.0-beta.1 (not committed); results in [TESTING.md](TESTING.md).

- [x] What each creature lends is what the mod grafts (HybridMorph.findAppendages): a creature known by its wings, rods
      or fins (bat, phantom, parrot, bee, chicken, allay, vex, blaze, breeze, cod, salmon, tropical fish, dolphin, the
      ender dragon) lends those, its tail and stinger, and no head; an animal with ears and a tail lends those (the tail
      is now one of its parts: a cat's and an ocelot's show both segments, as with the mod); a creature that lends its
      head lends any tail too (the wither's).
- [x] `HeadDump` writes every part as elements around where it joins its body, posed by the creature's own animation
      (at rest a vex's and an allay's wings hang edge-on), plus every mob's whole box, and the ender dragon (parts only,
      from its own renderer). `gen_head_pack.py` writes `models/part/<mob>/<part>` (65 parts) and their numbers;
      `models/tail` is gone.
- [x] `BorrowedHeads` places each part as the mod's Graft does (a port of Graft.place: share of the torso, lying onto
      standing, wings on a standing back at the shoulder blades or a lying body's flanks, rods over the whole height,
      tails set into the back, the dragon's scaled to the body), one display a part.
- [x] Flapping: the dump finds the two poses of the creature's animation in which a part is turned furthest apart,
      a stroke apart (bat, phantom, allay, vex, bee, dragon, the fish's tails, the wither's tail), with the mod's speeds
      for phantoms and the dragon. The plugin swings each part between them with the display's own interpolation: one
      update a stroke for each viewer. Parts move as one piece (a wing tip doesn't fold on its own).
- [x] Flat elements are now drawn from both sides: mobs are drawn without back-face culling, items with it, and a vex's
      wing is painted on one face only, so from behind it vanished. The generator backs each flat element with the
      other face's texture (16 head models and 2 ears models gained back faces too).
- [x] Turning: a disguised creature is drawn facing where the player looks (BodyRotationControl), while the server's
      body yaw lags up to 50 degrees behind; parts, tails and the neck a borrowed head sits on now follow the drawn
      body (a player form keeps the body yaw, as it is drawn as a player).
- [x] A baby body borrows half-size parts from a creature that has babies (a wolf's tail, a chicken's wings), full-size
      from one that hasn't (an allay's wings), as the mod does.
- [x] Without the pack (`vanilla-heads: auto`, pack not loaded) nothing changes: the dragon still lends its vanilla
      skull; the others show nothing.
- [x] The mod's morph-skin animations for players without the mod who have the pack (the user: bring the client
      experience's textures and animations to vanilla through the pack, without changing the game's own particles;
      they chose display entities over a shader keyed on a particle colour). `SkinEffects`, from the mod's own code:
      - Transformations: the old body's boxes, in the skin, melt into the new body's (paired by index as
        ModelCapture.interpolate pairs them, the extra ones growing from or shrinking into the middle), in three eased
        strokes from 12.5% to 87.5%, off at 94%; one display a box, moved by the display's own interpolation. When everyone
        who can see the player draws the skin, the disguise is invisible while it melts. Never in the player's own view
        (from inside, its boxes hid everything; the mod draws none around your camera).
      - Absorbing (AcquisitionEffect): four tendrils reach the creature, the skin covers it (the creature itself goes
        from sight), and the tendrils pull it into the chest, shrinking and turning; black for a new morph, red for
        biomass only. The halves nearest the player aren't drawn for them (the mod fades them in first person).
      - Devouring Frenzy's tendrils (RageTethers): five segments on the mod's wave, every other tick; the player's own
        start in front of them and below their view, thinner, as the mod's.
      - `HeadDump` writes every model's boxes and the player's (`morph-boxes.json`, captured last so heads stay as they
        were); `gen_head_pack.py` copies the mod's skin textures into the pack with box and tendril models.
      - Players without the pack keep the particles; `appearance.vanilla-skin-effects` (config.yml 7.4.0).
- [x] The frenzy's start burst of particles is gone for players without the mod (the mod has none; the user asked for
      one or the other).
- [x] Frenzy Meter, a setting of its own in `/morphclient` (When It Changes, Always, Off): it followed the biomass bar,
      and showed whenever the bar did. Starts from the biomass bar's setting.
- [x] The frenzy takes every creature in reach and in sight (the user: "basically infinite"): `max-targets: 0`.
- [x] The frenzy's hover (the user's idea): jump to rise on its tendrils up to the level's height (4, 6, 8 blocks: the
      user's "start at 4 and go higher"), crouch to come down, crouch on the ground to walk; the game's own flight,
      capped above whatever is below; legs from the hips to the ground, each foot planted until the body moves past it
      (the mod draws them from S_RAGE_DRAIN's new hover flag; the skin; particles). At the end, slow falling and no fall
      damage. Not on top of a flight the player has anyway (a flying morph, a resting bat), nor in creative or spectator.
- [x] The hover's legs (the user: animated, and more than four): six, set evenly round the body, each arched up and out
      and writhing, stepping (a foot lifts and swings ahead over 4 ticks) when the body has moved on, and spreading half
      a block for every block above the ground (at 8 blocks the legs had bunched into a cage under the player). Drawn the
      same by the plugin and the mod.
- [x] No "Jump to rise" on the action bar at each frenzy (the user: it belongs in the upgrade screen only).
- [x] Players on Fabric without the client mod are told, a few seconds after joining, to install it for the full
      experience (`client.no-client-message`); the user doesn't want First-person Model without the client mod tested.
- [ ] Not yet: a dragon body still gets nothing from a borrowed form for players without the mod (no neck data); blaze
      rods and breeze rods don't spin (they move without turning); variant textures (one texture per mob); the skin
      doesn't fade in and out as the mod's does (displays can't fade), so it comes on and off at once.
- [ ] Seen while testing, not part of this: with the mod, a baby body borrowing a wolf showed neither ears nor tail;
      `/morph force` without a variant drew a wolf and a horse in different colours for the mod and for Lib's Disguises.
- [x] compat_check: the 7.4.0 client mod against all 15 released servers, 6.0.0 to 7.3.0: every one OK.

## Released: 7.3.0, Devouring Frenzy

The user: "Rage" was only a placeholder name; it should combine devour and frenzy, so **Devouring Frenzy**. Mobs made
friendly by taming or by putting a saddle or armour on them must not be affected; it should cut off at a radius, give
new morphs while it runs, be expensive, do damage over time until kills happen or it stops; and have three levels for
the radius, the damage per hit and how long it lasts, with tests to find a balance (the user didn't know the numbers).
User calls: one upgrade with three levels (each raises all three), Classic gets level 2.

- [x] Name: Devouring Frenzy wherever players see it (upgrade, key, meter label FRENZY, messages, `/morph frenzy` with
      `/morph rage` kept as an alias). Internal ids keep "rage", as the RE:Morph rename kept `morphreborn`: the
      `abilities.yml` section, the permission `morphreborn.rage`, the upgrade id, saved data, packets, class names.
      The new client's bind hint says `/morph rage` on servers before 7.3.0.
- [x] Levels: `abilities.yml` `rage.levels` (radius, damage a hit, duration) and `classic-level` (2); `survival.yml`
      `upgrades.rage` has three levels at 1,500 / 4,000 / 10,000, shown in the tree and `/morph survival info` as
      "20 blocks, 3.5 damage a hit, 10 seconds". Level 2 is 7.2.0's numbers, so Classic plays as before. A 7.2.0-style
      section without `levels` still loads as one level. `survival.yml` from before 7.3.0 gets the new
      `upgrades.rage` (the section is replaced on update; a player who bought Rage keeps level 1).
- [x] Never drained: anything wearing a saddle, harness or armour (the game doesn't record who put it on), besides
      your own pets. Other players' unarmoured pets stay at the lesser rate.
- [x] The reach is a real circle (the creature search is a box; its corners used to latch and be let go at once).
- [x] `rage.report` (off by default): console and chat lines per kill and per frenzy, for balancing.
- [x] `/morph frenzy <player> go [level]` sets one off at a chosen level, for testing.
- [x] Balance: the starting numbers above were measured (TESTING, 7.3.0-beta.1) and the user kept them as they are
      (2026-11-12).
- [x] compat_check: the 7.3.0 client mod against all 14 released servers, 6.0.0 to 7.2.0: every one OK.
- [x] Released 2026-11-12: tag `v7.3.0`, GitHub release `RE:Morph 7.3.0`, Modrinth `7.3.0+paper` / `7.3.0+fabric`
      (the resource pack is 7.2.0's, unchanged).

## Released: 7.2.0, rage, room in the collection, ears and tails, version notices

The user asked for players to hear about versions wherever they join: an outdated client mod told on joining a server,
a client mod newer than the server still working and saying so, the mod's own "newer release" line on any server with
RE:Morph or without, and the server finding out about updates at some point. Before, the mod's line came once per game
start and only on RE:Morph servers, and nothing told a player their mod was newer than the server.

- [x] Client mod: on every join (a server, a world, a proxy switch), the "RE:Morph X is out" line when GitHub has a
      newer release. Not on a RE:Morph server newer than the mod, which already tells the player (old-client message).
      GitHub is asked at game start and again on a join once the answer is 6 hours old (10 minutes after a failure).
- [x] Client mod: "This server runs RE:Morph X; your client mod is Y. Everything works, but features added after X
      aren't on this server" on a server older than the mod (servers before 6.6.0 don't send their version, so that
      one says "from before 6.6.0"). An older server can't say this itself; only the mod knows it's newer.
- [x] Plugin: a client mod newer than the plugin is logged to the console once per version, with the download links.
      The existing check (GitHub at startup and every 12 hours, console and admins on join) is unchanged.
- [x] The admin line and the mod's line now say "this server's plugin is" and "your client mod is", so an admin with an
      old mod on an old server can tell the two apart.
- [x] Backwards compatibility, asked for "for sure" from here on: a new client mod must work on every older server.
      Checked by reading every packet change since 6.0.0 (all additive; the client reads each added field only when
      it's there; old servers skip unknown packets and extra bytes) and written down as rules in
      [PROTOCOL.md](PROTOCOL.md), which `Wire.java` already pointed to but didn't exist. `tools/compat_check.py` runs
      the client against every released server jar and drives each server packet over RCON; run it before every
      release.
- [x] Test hooks for the GitHub re-check: `-Dmorphreborn.updateCheck.refreshSeconds` / `retrySeconds`; the mod logs
      each answer ("the latest release is X") and each failure.
- [x] A `runSingleplayer` dev run (Client 2 opening the world "RE-Morph test").
- [x] Survival is the default gameplay mode for new servers (the user, 2026-11-09): `gameplay-mode: survival` in
      config.yml (config-version 7.2.0), and a missing or unknown value means Survival. Every released config.yml
      names its mode, so an updated server keeps the one it had.
- [x] Credits corrected: Morph's textures and sounds ship unchanged (checked byte for byte against the original), but
      `morphskin_red.png` is our red tint of `morphskin.png` and NOTICE.md didn't list it. NOTICE.md and the README
      say so now.
- [x] The Modrinth description rewritten to read like a person wrote it (`docs/MODRINTH.md` is its source): shorter
      sections like other mod pages, a "Do players need the mod?" answer, a step-by-step "How Survival works", the
      paper doll cut down to one line, and the credits above.
- [x] Posted on Modrinth 2026-11-09 (the saved description matches `docs/MODRINTH.md` exactly). Change the page by
      editing that file and pasting it into the project's Description settings.
- [ ] The [Modrinth] link in every notice only works once the Modrinth project is approved (still not public on
      2026-11-08); the [GitHub] link works now.

Next for 7.2.0, asked for by the user on 2026-11-11:

- [x] A clear sign whether a kill can still give you a new morph (Survival's Morph Capacity, kinds of creature) and a
      new look of one you have (Variant Capacity, looks per kind) (7.2.0-beta.3):
      - Selector: the selected row gets a second line under its name, *Looks 2/3* (creatures with more than one look
        only) and *Morphs 4/6* (Survival), each gold with "full" at the limit. Collapsed rows and one-look creatures
        (iron golem, blaze) stay as they were. Classic shows the line only when a creature has reached
        `max-variants-per-type`.
      - Which creatures have looks: worked out on the server from `variants.yml` and a default mob's save data (a kept
        look key, a per-type key, or babies when allowed; custom names ignored; wandering traders are never babies),
        plus any row that already holds more than one. `single-variant-types` collapses a creature to one look.
      - `max-variants-per-type` is now part of the look limit (the lower of it and Variant Capacity): checked before
        `required-kills` counts the kill, and said on the action bar (it used to refuse silently after counting).
      - Kill messages: Survival's biomass line now carries what the kill did to the collection, *+21 biomass (140 /
        400) · Morphs 5/6* / *· Sheep looks 3/3 full* / *· No room for Cow: morphs full (6/6)* / *· No room for this
        Sheep look (3/3)*. Before, the "collection full" line was replaced by the biomass line at once and never seen.
        Classic says the refusals with a short hint. `/morph list` shows the room in Survival.
      - Protocol: `S_COLLECTION` ends with the kind limit, the look limit and a has-looks flag per row (PROTOCOL.md);
        the collection is resent when a capacity changes. Older servers send none of it and the selector shows nothing.
      - Tried in game at full and not full for both counts, in Survival and Classic; compat_check OK on all 13
        released servers; the 7.1.3 mod on this server without warnings ([TESTING.md](TESTING.md)). Counts stay on the
        selected row only (a count on every row was offered as the alternative).
- [x] Rage, evening the odds against a crowd (7.2.0-beta.4, the user's design, 2026-11-11). Beta.3 had a first design
      (charged by absorbed kills or a wait, lifting the player and pulling in every absorbable creature at once); the
      user then described what they wanted and it was rebuilt:
      - Charged by hits: 6 within 6 seconds from at least 2 attackers (the user's example: three skeletons juggling you
        with arrows). A hit counts when it hurt (blocked or fully resisted ones don't) and came from a creature or
        another player (projectiles count for their shooter). A wait of 20 seconds after each rage.
      - Set off when the player chooses (beta.5; the user: "a choice not automatic, when it sets off and starts doing
        damage"; beta.4 set it off by itself): the Rage key (`C_RAGE`, unbound at first, a one-time chat hint when it
        is first charged) or `/morph rage`. It stays charged until used, through death and logging out
        (`PlayerMorphData.rageCharged`). Charged: READY on the meter, or a chat line for players without the mod.
      - For 8 seconds, tendrils latch onto up to 8 creatures within 16 blocks in sight (whoever hit you first, then the
        nearest) and drain 5 health a second each while they live (`GENERIC` damage from the player every half second,
        so protection plugins and team rules still apply; the last of it a `GENERIC_KILL`, so the kill is the player's).
        Each that dies goes through the usual kill handling (biomass, morphs, room, the absorb animation); Survival's
        reach doesn't apply to rage kills. The action bar sums it up at the end.
      - What it drains (`targets: enemies`): hostile creatures, anything after you, and whoever hit you; neutral hostiles
        (endermen, zombified piglins, piglins, spiders) only when after you. `targets: all` drains everything around.
        Never your own pets. At a quarter of the rate (the user: the creatures first proposed as never taken may be
        affected, "just to a lesser extent"): the dragon, the wither, the warden, the elder guardian, villagers,
        wandering traders, name-tagged creatures, other players' pets, and players. Players never below one heart (the
        user: hurt players, never outright kill them). No hover.
      - Radius 10 at first; a skeleton shooting from 12 blocks was never caught and the player died, so 16 (a
        skeleton shoots from up to 15).
      - With the mod: red morph-skin tendrils from the raging player to each drained creature, wavering, with swellings
        running into the player (`render/RageTethers`, `S_RAGE_DRAIN` 13, sent to players with the mod within 96
        blocks); in first person your own start in front of you and below the view (from the chest they filled the
        screen). The meter: a segment per hit (one short while all from one attacker), a bar running down while
        raging, a bar filling back up for the wait. `S_RAGE` now carries hits and the rage's time left.
      - Without the mod: wavering lines of red dust from the player to each creature, red motes running back, a red
        burst when it starts.
      - A player whose client mod is older than 7.2.0 has no meter (it skips `S_RAGE`), so they get the "charged" chat
        line too (found at release). Their client draws no tendrils either, and isn't sent the dust lines; left as is.
      - Survival: the Rage upgrade (Morph section, 1,500 biomass, needs Morph Abilities 1). Classic: everyone. Settings
        in `abilities.yml` → `rage`; permission `morphreborn.rage`. `/morph rage` sets it off or says why not;
        `/morph rage <player> [go|fill]` for testing (their key press, set it off now, charge it);
        `/morph upgrades <player>` opens someone's tree.
      - Tried in game (TESTING.md).
- [x] For players without the mod (found in the 7.1.3 run), fixed in 7.2.0-beta.3:
      - A player's head lent to a creature wasn't seen: every vanilla skull on a head display was drawn at half size and
        centred on the neck, inside the body's own head. Measured in game, a skull item (26.3, transform NONE) is drawn at
        real size, hanging down from the display's origin and facing backwards; `BorrowedHeads` now uses that (scale 1,
        anchor at the back of the hat's bottom, turned round). This also fixes every skull players without the pack get
        (zombie, creeper, piglin... heads). A skull with no pack model beside it (player forms, the dragon) is now shown
        to every viewer without the mod; with `vanilla-heads: auto` it went only to viewers with the pack.
      - A borrowed ghast's tentacles lay spread around the feet, and the giant's head was a zombie's floating above its
        neck: `HeadDump` wrote models enlarged by their root part (ghast 4.5, giant 6, happy ghast 4, elder guardian,
        and slightly scaled ones: illagers, villagers, witch, husk, wither skeleton, polar bear, cat, cave spider, horse
        family) with cubes at their unscaled size, and as upside-down boxes the generator measured below the neck. It
        now takes the scale out first. A fresh dump changed exactly those 19 heads, each by its own scale.
      - `gen_head_pack.py`: a hat's brim (villager, wandering trader, witch, zombie villager) no longer decides where the
        head meets the neck or how big it is; its overhang pushed those heads forward.
      - The resource pack's head models changed: servers that send RE-Morph-pack.zip should send the 7.2.0 one.
- [x] Ears and tails for players without the mod (7.2.0-beta.4): an animal that lends its ears and tail with the mod
      (wolf, fox, cat, ocelot, rabbit, armadillo, camel and camel husk, the horse family, and by the same test the
      axolotl's gills and the parrot's crest) now lends those to players without it too, from the pack, instead of its
      whole head. `HeadDump` marks the ears among the head's elements (the client's own `HybridMorph.lentEars`) and
      writes the tail as a model of its own, plus every mob's torso box; `gen_head_pack.py` writes `models/ears/<mob>`
      and `models/tail/<mob>` and their numbers. `BorrowedHeads` places them as the mod does: ears on the body's own
      head at the size its width asks for (half to twice), their middle over it and their lowest third in it, turning
      with the head; the tail at the same share of the body's torso as on its own (a lying animal's rear to the small of
      a standing body's back), just reaching into it, turning with the body. The head models are unchanged. Wings, fins
      and rods are still shown only with the mod.
- [ ] Still open: in offline mode a lent own head shows a default skin (offline accounts have no skin data; online
      servers are fine).
- [x] Released 2026-11-11: tag `v7.2.0`, GitHub release `RE:Morph 7.2.0`, Modrinth `7.2.0+paper` / `7.2.0+fabric`
      (with the 7.2.0 resource pack).

## Released: 7.1.3, your own body in multimorphs

The user found that multimorph refused your own body ("Your own body can't be combined"), a restriction they hadn't
meant: you should be able to combine yourself with a bat or a horse as you can another player's form. Asked to be
pushed right away, as a patch on 7.1.2 (the 7.2.0 work stays separate), after an extensive visual run of the player
combinations with anything that looked wrong fixed.

- [x] Your own body as a multimorph's main body or borrowed form. The server lends it as a player form with your
      skin (`MorphRef.selfBody`, id `self-body`, never in a collection), so the disguise, the hybrid model and the
      borrowed head all work as for another player's form. It costs nothing itself; going from your own body to your
      own body plus a creature (and back) is instant. It is saved and restored on login like any multimorph.
- [x] The client offers it only on servers from 7.1.3 (the server's version in its hello); an older server would just
      have turned you back into yourself. `/morph combine bat` in your own body, `/morph combine self` / your name,
      and `/morph borrow <player> <mob|self>` for admins (on an unmorphed player it combines their own body).
- [x] The user's call, from the run: an animal with a tail and ears standing out of its head (wolf, fox, cat,
      ocelot, rabbit, armadillo, camel, the horse family) lends its ears, on the body's own head, and its tail, not
      its head. Ears are found by name (camel) or as small blocks standing out of the top or sides of the head (a
      wolf's and a cat's are boxes in the head part).
- [x] Ears and tails join the body (the user saw them floating, slightly off it): a tail starts at the back's surface,
      set into it (its pivot sat past the end of its own body, and kept that distance), but never hidden in it (a
      camel's is a flat strip); each ear reaches into the head by a third of its height, at least a pixel, with its
      middle over the head (the head's top is the hat layer, and a tilted ear's box bottom isn't where it joins).
      Ears are drawn at most twice their size (an armadillo's were huge at 2.5).
- [x] A creature lent whole (no head part: ghast, slime, squid) is no bigger than the head it replaces; a full-size
      ghast on a person buried the body and the observer under its tentacles.
- [x] The user's call on giants: a borrowed head is drawn at most 1.5 times the head it replaces (a giant's on a person
      is bigger than a zombie's, not a giant's), and a giant body, like the dragon, grows the head it borrows to its own
      head's size (a player's head at giant size).
- [x] Players without the mod: a creature lent whole stands on the middle of the head (it sat behind it) and is
      measured by its body, not its tentacles (`tools/gen_head_pack.py`; a ghast sat above the head); a player's head
      lent to a creature is scaled to cover the creature's head.
- [x] Known, for players without the mod (7.2.0): a player's head lent to a creature isn't seen although its display is
      there (the same path as another player's head in 7.1.2); a borrowed ghast's tentacles show down by the feet;
      the giant's head data (`morph-heads.json`) treats its head as a zombie's and floats it above the head; an
      ears-and-tail animal shows its whole head (the pack has heads only, as for wing creatures). All fixed in 7.2.0.
- [ ] Still open: in offline mode (no skins) a self-body disguise shows a default skin.
- [x] Released 2026-11-11: tag `v7.1.3`, GitHub release `RE:Morph 7.1.3`, Modrinth `7.1.3+paper` / `7.1.3+fabric`
      (the resource pack is 7.1.2's, unchanged).

## Released: 7.1.2, sulfur cubes as vanilla's

The user asked whether a sulfur cube morph holding TNT bounces when hit and can be lit. Neither worked: a hit hurt it as
any player (4 health to 2), and nothing lit it. Now, as vanilla's SulfurCube (hurtServer, knockback, mobInteract):

- [x] Holding a block, it takes no damage from hits, projectiles, falls, thorns, berries, freezing or blasts
      (#sulfur_cube_with_block_immune_to) and bounces away from whoever hit it instead: the archetype's knockback,
      0.4125 across and 0.09 up, by the square root of the damage, with the archetype's hit sound.
- [x] Holding TNT, it is lit by flint and steel or a fire charge (another player's right click; the flint wears, the
      charge is used up), by fire or a burning arrow (the 6 s fuse), or by a blast nearby (a short fuse, as primed
      TNT's). It blows up as its ability does, without a cooldown or a Survival unlock: it isn't its choice.
- [x] Lit, nothing hurts it until it goes off.
- [x] Under `conversions.sulfur-cube` with the feeding.
- [x] Released: tag `v7.1.2`, GitHub release `RE:Morph 7.1.2`, Modrinth `7.1.2+paper` / `7.1.2+fabric`.

## Released: 7.1.1 (fixes)

- [x] A mooshroom struck back to the colour it was picked as ends its conversion (it stayed listed as converted to a
      red mooshroom, in `/morph info`, though it looked right).
- [x] Released: tag `v7.1.1`, GitHub release `RE:Morph 7.1.1`, Modrinth `7.1.1+paper` / `7.1.1+fabric`.

## Released: 7.1.0, vanilla conversions

The user's request: whatever turns a mob into another in vanilla turns a morph of it too, per body (the GUI and the
saved morph don't change, the model does), the way lightning already charged a creeper. Multimorphs are left out.

- [x] Per body, not saved: `ActiveMorph.converted`, the creature the body is now, carrying the saved form's variant id,
      so the collection, the selector's current form and what is saved stay as picked; the body, abilities, disguise
      and what clients are sent follow it. A new form, a respawn or logging out ends it. A short transformation (40
      ticks) plays into it, with the vanilla conversion sound instead of the morph sound. Older clients see it too.
- [x] Villager killed by a zombie (lethal hit, by difficulty) -> zombie villager, at full health, job kept.
- [x] Zombie villager cured (Weakness + golden apple, fed or eaten): shakes 3 minutes (`cure-seconds`) -> villager.
- [x] Zombie / zombie villager 30 s under water -> 15 s shaking -> drowned; husk -> zombie.
- [x] Skeleton 7 s in powder snow -> 15 s shaking -> stray.
- [x] Piglin, piglin brute, hoglin outside the Nether: shaking for 15 s -> zombified piglin, zoglin (the user: on by
      default, a toggle). Morphs had been immune to this since 5.1.0, when the shake looked like a bug.
- [x] Lightning: pig -> zombified piglin, villager -> witch (not on Peaceful), mooshroom -> the other colour.
- [x] Sulfur cube given a block by another player (the `sulfur_cube_archetype` item tags): holds it; TNT makes it
      explosive, and its blast spends it.
- [x] Shaking shows for both kinds of player (a `MorphShaking` look flag: Lib's Disguises' converting/shaking flags,
      piglins and hoglins no longer kept immune on modded clients); the action bar tells the player.
- [x] `conversions` in config.yml: on/off overall and per kind, the cure's length, the transformation's length.
- [x] First-person Model: a multimorph's borrowed parts show on your body in its view (the borrowed head never does).
- [x] Released: tag `v7.1.0`, GitHub release `RE:Morph 7.1.0`, Modrinth `7.1.0+paper` / `7.1.0+fabric` (First-person Model an
      optional dependency of the Fabric file, as before).

## Released: 7.0.0, Multimorph (combining morphs)

Two forms at once: the first one picked is the main body, the second is borrowed and lends it parts and powers. Designed
for any number of forms, shipped with two (three only if it turns out simple: the data, traits and cost don't care
how many, but every extra form multiplies the part placement and testing).

Decided:

- [x] Traits: a combined form is a list of types (AbilityManager.types: main, then borrowed). Every trait check is
      "any of the forms has it", still gated by Survival's `traits:` unlocks; drawbacks combine too. Where a number
      combines: drying out takes the shorter limit, holding breath the longer, land speed the slower, magic resistance
      the better; effects from both. Size, health and the body's own abilities (carrying blocks, jockeys, villager
      trading) stay the main form's. Changing only the borrowed form re-applies the traits.
- [x] Active abilities: one ability among the forms keeps its own second input (a witch's next potion, a creeper's
      fakeout). Two different abilities share one rotation: every thing the key can do (each witch potion, Explode,
      Fake explosion...), stepped by the second input (sneak + ability key with the mod, F without); the action bar
      shows the selected one every 2 seconds. Each ability runs as its own form (tipped arrows, the elder guardian's
      beam, water-only dashes); cooldown keys stay per ability. A borrowed creeper (or explosive sulfur cube) explodes
      without changing the body's look, and its blast grows with the body: the cube root of the body's mass against
      the creeper's (the weight chart), capped by `multimorph-max-power` (16) under `explode` in abilities.yml (the
      user's idea).
- [x] Looks (client mod), first step: the borrowed form's head replaces the main body's own (hidden at draw time),
      posed with it so it turns and nods, at its real size; mobs without a head part lend their whole body. Shown
      in the world and on the paper doll. Tried: zombie + cow, chicken + cow (a giant cow head), cow + chicken (a tiny
      chicken head), looking down.
- [x] Looks, second step: the borrowed form's wings, tails, fins, blaze and breeze rods and stinger are grafted onto
      the main body at their real size, with their own animation. Each goes to the same place on the main torso as
      on its own, measured in the body's frame; a lying torso maps onto a standing one as if it stood up (front to
      the top, top to the back), so a wolf's tail joins a zombie at the small of its back. Wings go on the back (on
      top of a lying body) and wings and tails stand clear of the body so they don't cut through it or the arms;
      anything past the borrowed torso (blaze rods) keeps its distance. Tried on a zombie: bat, phantom, wolf, blaze,
      bee (whole body, upright); on a wolf: phantom. Headless mobs' whole bodies now stand upright (they were drawn
      upside down).
- [x] What a borrowed form lends (client mod): a creature known by its wings, rods or fins (bat, phantom, parrot,
      bee, chicken, allay, vex, blaze, breeze, fish, dolphin) lends only those, with its tail and stinger, and the main
      body keeps its own head: a zombie with bat wings, not a zombie with a bat's head and wings. Everything else lends
      its head (and any tail). Players without the mod still see the borrowed head (an item display can only show a
      head). Borrowed phantom wings beat 4.5 times as fast as a phantom's own (it glides; on a body it read as a
      stutter, and two or three times as fast still looked slow on a dragon).
- [x] The ender dragon, either way round (its renderer isn't a living-entity one, so the hybrid drawing handles both
      kinds of body). Borrowed, it lends its wings and tail, scaled to the body they go on (the user's choice): the
      cube root of the two torsos' volume ratio, so a zombie gets wings about as big for its body as a dragon's are
      for its own; its 12 tail segments move as one tail from the first; its flap (wings and tail) runs at 0.4 of a
      hovering dragon's, which beats about four times a second and looked frantic. As the main body it takes a
      borrowed head on its neck (a cow's, at a cow's size) and borrowed wings on its back. Borrowed parts now take the
      main body's facing too, so they line up when the two bodies face slightly differently (previews, the dragon).
      Tried: zombie + dragon (wings on the back, tail from the small of the back), dragon + cow. Players without the
      mod see no borrowed head for a dragon either way yet (no head model, no neck data for it).
- [x] Animation: multimorph parts take part in the morph animation (the user's idea). Captures include them (the main
      head collapsed where it was, so the other boxes keep their places; borrowed head and parts appended, growing out
      of the body); the old borrowed form morphs out with the old body; changing only the borrowed form plays a short
      transformation of its own (half the server's, at least half a second).
- [x] Heads sit where the main head meets its neck (the bottom of each head, at the back, lined up), not pivot to
      pivot: a wolf's head sank into an iron golem's chest (the user saw it). Same for players without the mod.
- [x] Looks across size groups (TESTING.md, 7.0.0): a zombie with every borrowed form, then cows, pigs, horses, camels,
      llamas, ravagers, sniffers, spiders, slimes, bats, ghasts, iron golems, phantoms, the dragon and a baby. Fixed on
      the way: wings on a lying body go on its flanks (on its back they hid in it), on a standing one at the shoulder
      blades; a neck point inside the torso (camel, llama) moves out of it; a borrowed head on a dragon is scaled to the
      dragon's; a borrowed head is posed at rest and turned once by the body's look (it turned twice as far); a baby
      borrows a baby; the dragon body is framed and turned properly in previews.
- [x] Fullscreen: while a pair is being picked in the favourites wheel, the wheel slides left to clear the panel.
- [x] Protocol: the borrowed form rides at the end of the morph-state packet, which older clients stop reading
      before, so they keep working; with saved data and settings only added, multimorph could have been a MINOR
      release (6.7.0). It shipped as 7.0.0, the user's call for the headline feature; the resource pack's file was
      renamed (RE-Morph-sounds.zip to RE-Morph-pack.zip), which admins who serve it have to swap (VERSIONING.md).
- [x] Survival: the Multimorph upgrade, a one-time unlock under Morph Ability (1,200 biomass, needs Morph Capacity 4).
      A combination costs each form that is new to the body (both for a new pair, the borrowed one when the body
      stays) plus 25% (`morph-cost.multimorph-extra`); the client's price panel includes it. A saved combination is
      dropped at login without the upgrade.
- [x] Saved data: the borrowed form is saved with the player's current morph (`borrowed` next to `current`) and
      comes back after a relog or a server restart, if the player still owns it and may combine. A new body drops it
      (it borrows only what it was picked with); deleting or revoking the borrowed morph drops it too.
- [x] Picking: the normal pick is unchanged. Hold Shift when picking a creature in the selector or the favourites
      wheel and it becomes the main body, with a pulsing red ring on it; the selector (or wheel) stays open and a
      preview panel shows the main body with the morph under the cursor on it (and the Survival price); the next
      pick combines them, picking the main body again calls it off. In the wheel, letting go of the key with Shift held
      keeps the wheel open for the second pick. Picking the body you're in drops what it borrows. Without the mod:
      `/morph combine <main> <borrowed>`, `/morph combine <borrowed>` (onto the body you're in), `/morph combine off`.
      Switched by `multimorph.enabled` and the `morphreborn.multimorph` permission (both on by default). The highlight
      is the whole tile in red with goo tendrils (a ring in the wheel); picking the first creature again makes you just
      it; right click, Esc and Backspace close (the user's rules). Player forms can be combined both ways.
- [x] Preview: `/morphclient preview [main] [borrowed]` shows any two creatures combined, as the world draws them,
      without owning or becoming them: arrows change the forms, drag turns, scroll zooms. Built for checking that
      combinations look right; the selector's panel uses the same drawing.
- [x] Checked live: the Shift-pick in the selector and the wheel, the panel and its price, the preview screen,
      `/morph combine`, a Survival purchase of a pair, saving across relogs and restarts.

Open:

- [x] Decided: players without the mod see the main body (Lib's Disguises) with the borrowed creature's head on its
      neck, at that creature's real size. The head is an unsaved item display that follows the neck, showing a head
      model from RE:Morph's resource pack; `multimorph.vanilla-heads` in config.yml (auto: players whose game loaded the
      pack; pack: everyone; off). Prototype working (zombie with a cow's head, seen from a vanilla client), with
      `/morph borrow <player> <type|off>` for testing.
- [x] Head models for 89 mobs: the dev client dumps every mob's head from the game's own models
      (`/morphclient dumpheads`, or MORPHREBORN_DUMPHEADS=1), `tools/gen_head_pack.py` turns that into the pack's head
      models and the plugin's neck table (`morph-heads.json`). Mobs without a head part lend their whole body.
- [x] Vanilla skulls (zombie, skeleton, wither skeleton, creeper, piglin, dragon, a player form's head with its skin)
      for players without the pack, and for the forms the pack has no head for (the dragon, player forms). A body
      that wears things on its head (CustomHeadLayer: zombies, skeletons, piglins, villagers, illagers, copper golems,
      players) wears the skull in its head slot: it covers the body's own head and turns with it exactly.
- [x] A borrowed head smaller than the body's own is drawn just big enough to cover it for players without the mod
      (their disguise always draws the body's head; the wolf hid inside the iron golem's).
- [ ] Variant textures for borrowed heads (sheep colours, cat breeds...): the pack has one head per mob; players with
      the mod see the right variant.
- [ ] Players without the mod: no borrowed head on an ender dragon body (no neck data for it).
- [x] The Survival node: a one-time unlock of its own (above).
- [x] Released: tag `v7.0.0`, GitHub release `RE:Morph 7.0.0`, Modrinth `7.0.0+paper` / `7.0.0+fabric`.

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
- [x] Paper doll (6.2.0): built on the same render path as the world, so it shows the morph, its
      animations, the transformation, poses and vehicles; its own settings page and toggle key.
- [x] More paper doll settings (6.3.0): head modes (follow, turn only,
      look only, straight, looks at you), tilt, true size or fill, level head while gliding or spinning, auto-hide for
      the music toast, and "show when" triggers (crouching, sprinting, elytra, swimming, riding, blocking, using an
      item, swinging, damage, fire, powder snow and more, including transforming and using an ability). A soft
      vignette around the doll (a blurred dark silhouette; a hard outline was tried and looked wrong).
- [x] Separate looks for a new morph and a biomass-only kill (6.3.0): Black Tendrils (the original), Red Tendrils
      (dark red tint), Red Wisps, Plain, Off, chosen separately. Defaults: black for a new morph, dark red for biomass
      (a bright red was tried first and was too strong).
- [x] [First-person Model](https://modrinth.com/mod/first-person-model) (tr7zw) as an optional compatible mod: the morph is
      the body in its first-person view, through its public API by reflection, nothing bundled. The head is hidden while that one body is drawn; the body is
      moved back so its front edge is just behind the camera (aiming stays clear, looking down shows the front legs
      and back); morphs with the head on top of the body keep the mod's own placement. The mod's toggle stays in
      charge. Transformations play on the body, its placement sliding from the old form's to the new one's. The body
      view pauses for headless forms (slime, magma cube, ghast, squid, pufferfish). The ender dragon gets the same
      placement, measured as it is drawn, and is raised so its head is at the camera.
- [x] Fakeout explosion (6.5.0): creepers and sulfur cubes with TNT inside get the second input (sneak + ability key,
      or F without sneaking without the mod), the same fuse and build-up as exploding, then a fizzle and back to
      normal. Own cooldown (4 s), keeps a lightning charge, can be switched off in `abilities.yml`.
- [ ] A sulfur cube's fuse shows no build-up on its body (only the TNT hiss); a real primed cube swells a little in
      its last half second (no white flash). Left as is for now.
- [ ] First-person Model's body view: check more mobs (tall ones like the iron golem and ender dragon, swimmers, flyers, babies),
      and what non-humanoid morphs show of held items with the body view on.
- [ ] Humanoid morphs don't take the player's swimming or elytra pose (the player renderer turns the body for those;
      mob renderers don't). Sitting in vehicles is copied since 6.2.0.
- [ ] Optional Mod Menu entry for the settings screen (needs Mod Menu as an optional dependency).
- [ ] The Plain transformation always uses the shrink/grow version. If an admin turns `vanilla-transition` off,
      unmodded players see an instant swap instead. The server could tell clients that setting in the handshake.

## Priority 3: Survival Mode

Design: [SURVIVAL-MODE.md](SURVIVAL-MODE.md), from iChun's unfinished 1.16 code and his own description.

Built for 6.0.0 (2026-11-05). How it works, the tree and its numbers: [SURVIVAL-MODE.md](SURVIVAL-MODE.md).

- [x] `gameplay-mode: classic | survival`, `GameplayMode` with Classic and Survival, progress saved in both modes.
- [x] Biomass unlock (Wither + Regeneration together), biomass per kill (iChun's formula), reach and mass limits.
- [x] Upgrade tree in `survival.yml`, chest menu for everyone, `/morph upgrades`, `/morph upgrade`, `/morph biomass`,
      admin `/morph survival`, a Biomass Menu key (B until 6.2.0, unbound since) and biomass bar with the mod.
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
- [x] Biomass values for every mob: [MOB-MASSES.md](MOB-MASSES.md), generated by `tools/gen_mob_masses.py` (6.6.0).

- [ ] Singleplayer: everything runs in the Paper plugin, so the client mod does nothing without a server running it.
      Supporting singleplayer would mean running the server side inside the mod's integrated server.

## Priority 4: long-term compatibility

- [ ] Singleplayer: run the whole mod from the client in a singleplayer world, without a Paper server. A big job (the
      plugin's work would have to run in the integrated server); later.

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
- [x] GitHub release for 6.0.0 with the jars attached (the repository only holds source).
- [x] GitHub release for 6.1.0.
- [x] GitHub release for 6.2.0.
- [x] GitHub release for 6.3.0.
- [x] 6.4.0: Modrinth only, no GitHub release (its changes are listed in the 6.5.0 release).
- [x] GitHub release for 6.5.0.
- [ ] Split GUIDE.md into a server guide and a client guide.
- [ ] Mob reference: add columns for "needs the client mod" and special interactions to ABILITIES.md.
- [ ] Mob spreadsheet: generate `mobs.csv` (and an .xlsx) from abilities.yml with `tools/gen_abilities_doc.py`, so
      it can't drift from the real config.
- [ ] `docs/PROTOCOL.md`: both `Wire.java` files say it's the shared spec, but it doesn't exist.

## Decisions log

| Date | Decision | Why |
|---|---|---|
| 2026-11-03 | Server templates don't set NoAI | Unmodded clients froze dragon wings and copper golems |
| 2026-11-11 | Rage is charged by many hits from several attackers and drains the creatures around while they live; what dies is absorbed | The user's design, replacing beta.3's (charged by kills or a wait, lifting the player and taking everything at once) |
| 2026-11-11 | A charged rage waits for the player (Rage key or /morph rage) and stays charged until used | The user: setting it off is a choice, not automatic (beta.4 went off by itself) |
| 2026-11-11 | Rage drains bosses, villagers, named creatures, others' pets and players at a quarter of the rate; players never below one heart; never your own pets | The user: those may be affected "just to a lesser extent"; hurt players but never kill them |
| 2026-11-12 | Frenzy levels kept at 10/16/20 blocks, 2/2.5/3.5 a hit, 5/8/10 s, costing 1,500/4,000/10,000 | The user kept the measured starting numbers |
| 2026-11-12 | Players without the mod get the parts the mod grafts, one item display per part from the pack, placed by a port of Graft.place | The user asked for wings and fins for players without the mod; matching the mod's placement keeps one look for everyone |
| 2026-11-12 | Parts flap by display interpolation between two poses of the creature's own animation | The user wants the client experience's animations for players without the mod; one update a stroke, no new particles or entities |
| 2026-11-12 | Flat model elements are backed with the opposite face's texture | Items are drawn with back-face culling and mobs without; a vex's wing vanished from behind |
| 2026-11-12 | Parts and necks follow the look yaw on creature disguises, the body yaw on player forms | That is how each is drawn; the server's body yaw left wings off a turned creature's back |
| 2026-11-12 | The mod's skin animations for players without the mod as item displays from the pack | The user chose this over a shader keyed on a particle colour: no change to the game's own particles, works with shader mods |
| 2026-11-12 | No frenzy start burst for players without the mod | The mod has none; the user asked for one or the other |
| 2026-11-12 | Frenzy Meter is its own setting | The user asked for it to show always or when it changes, apart from the biomass bar |
| 2026-11-12 | The frenzy takes every creature in reach | The user: "however many were around you so basically infinite" |
| 2026-11-12 | The frenzy's hover: jump to rise up to 4, 6 or 8 blocks by level, crouch to come down | The user's idea and numbers ("start at 4 and go higher"); the game's own flight, so moving stays smooth; you have to jump |
| 2026-11-12 | Six animated hover legs, spreading with the height | The user asked for them animated and more than four |
| 2026-11-12 | First-person Model without the client mod isn't tested; Fabric players without the client mod are told to install it | The user's call |
| 2026-11-11 | Rage renamed Devouring Frenzy; one upgrade with three levels (reach, damage a hit, length), Classic level 2 | The user's calls; internal ids keep "rage" so no settings, permissions or saved data move |
| 2026-11-11 | Frenzy never touches anything wearing a saddle, harness or armour | The user: friended mobs (tamed, saddled or armoured) aren't affected; the game doesn't record who saddled a mob, so it applies to all |
| 2026-11-11 | Room counts only on the selected selector row | Keeps the other rows as they were; offered the alternative, no change asked |
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
| 2026-11-05 | config.yml is updated on startup when its `config-version` is older: new settings merged in, every value kept, old file backed up (6.1.0) | Settings added after a server's first install (gameplay-mode among them) never reached its config.yml; config files should follow new releases |
| 2026-11-05 | `/morph mode classic\|survival` switches live, saves to config.yml; /morph reload applies a hand-edited mode (6.1.0) | Admins needed an in-game command; every rule already asks `plugin.mode()`, so only online players need bringing in line |
| 2026-11-06 | The hitbox and copied attributes change in one step at the transformation's half-way point, growing only into free space | Easing them every tick desynced client and server movement (corrections, jitter); vanilla never moves a growing player out of blocks |
| 2026-11-06 | The modded client eases the camera height over the transformation itself | The one-step hitbox change made the camera jump; it should follow the animation |
| 2026-11-06 | Paper doll drawn from `EntityRenderDispatcher.extractEntity`, the world's own path, into its own off-screen texture | The doll shows exactly what the world shows, with no animation code of its own and no effect on the real entities |
| 2026-11-06 | Survival: morphing costs biomass, the form's mass x 0.01 (at least 1), with per-mob prices and a gain multiplier, all in `/morph cost` (6.3.0) | Morphing should have a price; scaling by size keeps small morphs cheap and dragons expensive. Abilities stay free |
| 2026-11-06 | survival.yml is merged on update like config.yml, not replaced (6.3.0) | It now holds admin choices (`/morph cost` saves there) that a replacement would throw away |
| 2026-11-06 | Morph prices sent to the client in a new packet; the acquire packet gains a "new morph" flag at the end (6.3.0) | Older clients stop reading before the new fields, so protocol 3 stays |
| 2026-11-06 | Jockeys: the rider steers, the mount's AI is switched off with `Mob#setAware(false)` while ridden | Simplest to use; works without the client mod because steering reads the player's input on the server |
| 2026-11-06 | Villager trades come from a stand-in villager that is never added to the world, kept per player and look, restocked daily | The game's own trade tables for every profession and level, without copying them into the plugin |
| 2026-11-06 | Trades are copied through MerchantRecipe's getters, never its copy constructor | Paper's copy constructor reads raw fields, which the server's recipes leave at 0: every copy was sold out |
| 2026-11-06 | Cobweb scaffolding for spiders is client-side (block collision and climbing mixins) | The top of a web has to be solid only for that player, which the server can't do |
| 2026-11-06 | The music-toast check uses reflection | A Mixin accessor can't name the private toast class it returns |
| 2026-11-06 | First-person Model is supported through its public offset and activation handlers, registered by reflection with Java proxies | No compile dependency and none of its code; the mod keeps drawing and toggling the body, RE:Morph only places it and hides the head |
| 2026-11-06 | In the first-person body view the camera sits just in front of the morph's body (head, face and legs left out), not at its head or neck | At the head the body was out of sight; at the neck a pig's back covered the crosshair, and a pig's back is higher than its eyes |
| 2026-11-06 | The docs name First-person Model as an optional compatible mod | It is the mod the support was built and tested against, so players know which one to install |
| 2026-11-06 | A model part that is hidden is measured where it rests | First-person Model hides a humanoid model's head by moving it 5000 units away; measured there, zombies, husks, skeletons and endermen were pushed forward past the camera instead of keeping that mod's placement (villager-type models aren't humanoid models and were right). A part more than 1000 units out counts as hidden too: the enderman's model shows its head again straight after, still that far away (fixed in 6.5.0) |
| 2026-11-06 | The ender dragon morph's yaw is turned 180 degrees | The dragon's yaw points the other way from every other mob's (it flies along sin/-cos of it), so it was drawn tail first, in third person too |
| 2026-11-07 | Multimorph: the first form is the body, the second lends parts and powers; traits are "any form has it", drawbacks combine | Simple to explain and to check; combining shouldn't be free |
| 2026-11-07 | Multimorph abilities join the existing ability rotation, with the selected one always on the HUD | An ability-cycle bind already exists; sneak-to-cycle would clash with alternate inputs |
| 2026-11-07 | Multimorph looks graft iChun-style body parts onto the main body, tested in size groups | Keeps the testing manageable: one placement fits every mob of that size |
| 2026-11-07 | Multimorph in Survival: an expensive upgrade; a combination costs the sum of its forms plus an extra | It is the strongest thing a morph can do |
| 2026-11-07 | Beneficial traits are bought one kind at a time in Survival; drawbacks are always on | Morphing alone shouldn't hand over every perk at once, and a downside isn't something to buy away |
| 2026-11-07 | Survival speeds start 30% slower than Classic and end 30% faster, shown to players as seconds and multipliers; reach 3 to 150 blocks; 5 mass levels | Survival should start harder than Classic and end a little better; ten mass levels were too many steps |
| 2026-11-07 | Fish, squid, axolotls and nautiluses always breathe underwater | Without it they could breathe nowhere |
| 2026-11-07 | Letting go of the favourites key in the middle only closes the wheel | Opening the tree there by accident got in the way of simply closing the wheel |
| 2026-11-06 | Releases collect more changes; test builds are pre-releases (`-beta.N`) | Many small releases were slow, and two test jars with the same name got mixed up |
| 2026-11-06 | Update checks ask GitHub's public latest-release page only, and can be switched off on both sides; notices link to Modrinth and GitHub | Enough to compare versions; nothing about the server or players leaves it |
| 2026-11-06 | Releases renumbered to Semantic Versioning: 6.1.2-6.1.8 became 6.0.0-6.5.0, tags renamed, jars rebuilt with the new numbers | Each of those releases added features, so each was a minor version; nothing broke after the first public release ([VERSIONING.md](VERSIONING.md)) |
| 2026-11-06 | The fakeout explosion uses the second input (sneak + ability key) rather than a new key | The input already exists for the witch, works without the mod (F without sneaking), and needs no client change |
| 2026-11-06 | Transformations play in first person instead of pausing the body view | Asked for; the body slides from the old form's placement to the new one's as the boxes move, and head boxes are left out |
| 2026-11-06 | The head is hidden in ModelFeatureRenderer, between posing and drawing that one submitted model | Rendering is deferred in 26.3, so a flag set while submitting would be gone (or hit other models) by draw time |
| 2026-11-06 | The favourites wheel's Biomass button is sized to its text; wheel prices sit under each name, in light grey when unaffordable | The fixed-size square was too big; dark grey didn't read on the wheel's dark ring |
