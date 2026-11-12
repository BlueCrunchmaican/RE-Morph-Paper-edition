# Notices

RE:Morph (formerly Morph Reborn) is an unofficial port of **Morph** by **iChun** (https://github.com/iChun/Morph),
together with algorithms from **iChunUtil** by iChun (https://github.com/iChun/iChunUtil).
Both are licensed under the GNU Lesser General Public License v3.0. RE:Morph is a derivative
work and is distributed under the same licence (see `LICENSE`, which incorporates `LICENSE-GPL-3.0`).

Ported from Morph / iChunUtil (re-implemented for Minecraft 26.3, Paper and Fabric):
- Morph selector HUD layout, timings and easing; radial favourites menu; key handling.
- Variant model (whitelisted entity data defines a variant) and default whitelists.
- Morph transition timeline (12.5% / 75% / 12.5%), box-interpolation transition, acquisition tendrils.
- `ModelHelper` part interpolation and `RenderHelper.createInterimStackEntry` (iChunUtil).
- Survival Mode, from Morph's unfinished Default Mode: the biomass unlock rule and its advancement text ("A Mutation
  Complication"), the biomass formula, the upgrade tree format and its five value formulas, and the upgrade names.

Assets reused unmodified from Morph (LGPL-3.0, (c) iChun):
- `textures/gui/gui_selected.png`, `gui_unselected.png`, `gui_unselected_side.png`, `fav.png`
  (`gui_selected.png` is also the mod icon, `icon.png`, and the resource pack icon, `pack.png`)
- `textures/skin/morphskin.png`
- `sounds/morph1.ogg` ... `morph6.ogg`

Modified from Morph (LGPL-3.0, (c) iChun):
- `textures/skin/morphskin_red.png`: `morphskin.png` tinted dark red, for the red tendril and wisp effects.

Not derived from, and containing no code or assets of:
- "Morph"/"Morphy" Spigot plugin by BumbleBee (no licence published). Server features with similar
  names (e.g. creeper explosion, enderman teleport) were implemented independently.

Runtime dependency (not bundled): Lib's Disguises by libraryaddict, installed by the server admin.
Minecraft is (c) Mojang AB; no Minecraft code or assets are redistributed.
