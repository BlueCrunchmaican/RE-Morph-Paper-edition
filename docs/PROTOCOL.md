# RE:Morph network protocol

The client mod and the Paper plugin talk over one plugin channel, `morphreborn:main`. Both sides have the same codec
(`Wire.java` in each project); this file is the shared specification. Versions below are release numbers (6.0.0 was the
first public release; see [VERSIONING.md](VERSIONING.md) for the old numbers).

## The compatibility rule

**A new client mod must work on every older server, and a new server on every older client mod.** Players update the
mod and admins update the plugin at different times, so any pairing of releases can meet. Everything below follows from
that:

1. **The protocol number stays 3.** A server treats a client on any other protocol as a player without the mod (and an
   older server would tell a newer client it is "outdated"). Raise it only for a change that cannot be made additively,
   and then the new client must still speak protocol 3 to servers that answer with 3.
2. **Fields are only ever added, at the end of a packet.** Never remove a field, reorder fields or change what one
   means. A reader stops when it has what it knows, so older readers ignore the new field without noticing.
3. **Readers check before reading an added field** (`in.hasMore() ? ... : default`), with the default matching what
   older peers meant. The client does this for every field listed with a version below.
4. **New packets get new ids.** Both sides skip ids they don't know (the server since 6.0.0, the client always), so a
   new packet is simply unused by an older peer.
5. **Don't send what the other side can't act on.** A request an older server would misread or half-handle has to be
   gated on something the server said: its version (in `S_HELLO` since 6.6.0, empty before) or a flag in `S_HELLO`.
   Multimorph is the example: the client only sends the borrowed form when `S_HELLO` says multimorph is on, which
   servers before 7.0.0 never do.
6. **Check it before a release:** `py -3 tools/compat_check.py <every released server jar>` runs the new client against
   each released server and drives every server packet over RCON (see [TESTING.md](TESTING.md)).

## Encoding

Every message is a varInt packet id followed by its fields.

| Type | Encoding |
|---|---|
| varInt | Minecraft's VarInt (7 bits per byte, low first) |
| bool | one byte, 0 or 1 |
| string | varInt byte length, then UTF-8 (at most 262,144 bytes; lists at most 65,536 entries) |
| uuid | two big-endian longs (most significant first) |
| ref | a morph: string entity type, string variant id, string SNBT, bool has skin, then if so: uuid profile id, string name, string texture value, string texture signature |
| optional ref | bool present, then a ref if so |

## Client to server

| Id | Packet | Fields | Since |
|---|---|---|---|
| 0 | `C_HELLO` | varInt protocol, string mod version | 6.0.0 |
| 1 | `C_SELECT` | string variant id; then string borrowed variant id (multimorph) | 6.0.0; borrowed 7.0.0 |
| 2 | `C_FAVOURITE` | string variant id, bool favourite | 6.0.0 |
| 3 | `C_DELETE` | string variant id | 6.0.0 |
| 4 | `C_ABILITY` | (none) | 6.0.0 |
| 5 | `C_REQUEST_SYNC` | (none; the server answers with a full sync, unused by the current client) | 6.0.0 |
| 6 | `C_OPEN_UPGRADES` | (none) | 6.0.0 |
| 7 | `C_BUY_UPGRADE` | string upgrade id | 6.0.0 |
| 8 | `C_RAGE` | (none: set off a charged rage; sent only when `S_HELLO` says the server has rage) | 7.2.0 |

The server ignores every packet but `C_HELLO` until the handshake succeeded.

## Server to client

| Id | Packet | Fields | Since |
|---|---|---|---|
| 0 | `S_HELLO` | varInt protocol, bool accepted, string message (why not), varInt transformation ticks, bool deleting allowed, bool selector allowed, bool abilities on, string name tag mode, string gameplay mode; then string plugin version; then bool multimorph on; then bool rage on | 6.0.0; version 6.6.0; multimorph 7.0.0; rage 7.2.0 |
| 1 | `S_COLLECTION` | varInt morph count, then per morph: string type, varInt variant count, then per variant: ref, bool favourite; then varInt kind limit + 1, varInt look limit + 1 (0: no limit), then per morph, in the same order: bool has looks | 6.0.0; limits and looks 7.2.0 |
| 2 | `S_MORPH_STATE` | uuid player, varInt entity id, optional ref from, optional ref to, varInt time, varInt duration, string overlay; then optional ref borrowed (multimorph) | 6.0.0; borrowed 7.0.0 |
| 3 | `S_ACQUIRE` | varInt player entity id, varInt acquired entity id; then bool new morph (false: biomass only) | 6.0.0; new morph 6.3.0 |
| 4 | `S_RESULT` | varInt code (0 OK, 1 denied, 2 unknown morph, 3 busy), string message | 6.0.0 |
| 5 | `S_UNLOCK` | ref | 6.0.0 |
| 6 | `S_FORGET` | uuid player | 6.0.0 |
| 7 | `S_ANIMATE` | uuid player, string ability | 6.0.0 |
| 8 | `S_TRAITS` | varInt bit set: 1 climb, 2 web, 4 powder snow, 8 lava, 16 berry bush, 32 slow fall | 6.0.0 |
| 9 | `S_SURVIVAL` | bool biomass unlocked, varInt biomass, varInt capacity, bool can morph | 6.0.0 |
| 10 | `S_UPGRADES` | bool open, string message, bool unlocked, varInt biomass, varInt capacity, varInt count, then per upgrade: string id, name, description, icon item, parent; varInt level, max level; string value now, value next; varInt cost + 1 (0 when maxed); string missing requirement; bool affordable | 6.0.0 |
| 11 | `S_COSTS` | varInt count, then per variant: string variant id, varInt biomass cost (empty: morphing is free); then varInt multimorph extra percent | 6.3.0; extra 7.0.0 |
| 12 | `S_RAGE` | bool available, varInt hits (within the window), varInt hits needed, varInt wait ticks left, varInt wait ticks in all (0: none), bool raging now, varInt rage ticks left, varInt rage ticks in all | 7.2.0 |
| 13 | `S_RAGE_DRAIN` | varInt the raging player's entity id, varInt count, varInt entity id per drained creature (empty: the tendrils end); bool the player hovers on the tendrils' legs (7.4.0) | 7.2.0 |

A repeated `S_HELLO` (the gameplay mode changed) is an update of the server's settings, not a new connection.

`S_COLLECTION`'s limits (7.2.0) are what a kill checks: the kind limit is the most kinds of creature the collection may
hold (Survival's Morph Capacity), the look limit the most looks of one (Survival's Variant Capacity or config.yml's
`max-variants-per-type`, whichever is lower). "Has looks" says the creature can come in more than one look under the
server's variant rules (custom names aside), or the player already holds more than one of it; the selector shows a look
count only for those. The server sends the collection again when a limit changes (a capacity upgrade, a reload, a mode
switch). From an older server the client reads no limits and shows no counts.

Rage (7.2.0) follows rule 5: the client sends `C_RAGE` (the Rage key) only when the server's `S_HELLO` ends with rage on,
and on an older server the key just says the server has no rage. The server sets the rage off only when it is charged
and says why not otherwise. A charged rage shows as hits equal to hits needed. `S_RAGE` is sent on the handshake and whenever the meter changes (a hit, a hit
growing old, a rage starting or ending, an upgrade, a mode switch), and once a second while raging; the client counts
the wait and the rage down itself between packets. `S_RAGE_DRAIN` goes to every player with the mod within 96 blocks
of the raging player when the drained creatures change, and once a second for anyone who just came near; older clients
skip both packets.

## What each pairing gets

| Pairing | Result |
|---|---|
| Client and server on the same release | Everything |
| Newer client, older server | Everything the server has; the client says so in chat on each join (7.2.0). Checked live against every release from 6.0.0 on with `tools/compat_check.py` |
| Older client, newer server | Everything the client has; servers from 6.6.0 on tell the player to update on each join (`client.old-client-message`). Covered by the rules above (every client skips unknown packets and stops reading at the fields it knows), but not run through the tool; the 7.1.3 client was tried by hand on 7.2.0-beta.3 |
| A client on another protocol | Treated as a player without the mod (Lib's Disguises only); the server says why |
