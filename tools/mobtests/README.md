# Mob test scripts

Run against the local test server (RCON on 127.0.0.1:25598, password from `test-server/server.properties`) with
MorphTester1 and VanillaViewer online. Use 20-tick transitions while testing (`morphs.transition-ticks` in the test
server's config.yml, then `/morph reload`) and put it back to 100 afterwards.

- `mobtest.py <morph-port root> [mob ...]`: morphs MorphTester1 into every mob (or the ones listed), records size,
  health, speed, flight and effects, fires the ability and checks what it did. Note: aiming at the target dummy is
  calculated from the feet, so tall and tiny morphs can miss it; re-check failures with the aim corrected for eye height.
- `hardlock.py`: automatic traits on VanillaViewer (sunburn, breathing, water, lava, falls, sinking, poison,
  powder snow, flight). A tester that dies respawns at full health, so check the server log for deaths before reading a
  "no damage" result.
- `deathdrops.py`: dies eight ways in full netherite with keepInventory off and counts what drops.

See `docs/MOB-REVIEW.md` for the 2026-11-02 results.
- `fixtests.py`: one check per fix from the 2026-11-02 review (armor-safe creeper death, sulfur cube, webs, powder snow,
  lava, drying out, undead potions, creaking, witch, guardian, new abilities). MorphTester1 must run the client mod.
  Two checks are timing-sensitive (a hit right before Harming, and an ability still on cooldown); re-run those alone
  if they fail.
