# Versioning

RE:Morph follows [Semantic Versioning](https://semver.org): `MAJOR.MINOR.PATCH`. The plugin and the client mod
always share one version. The releases before this rule were renumbered to match it (see Earlier releases).

## What each number means here

| Bump | When | Examples |
|---|---|---|
| **MAJOR** | Something breaks for server admins or players and needs them to act: a new client protocol (older client mods are treated as unmodded), player data or config files that can't be carried over automatically, a renamed or removed command, permission, setting or download, dropping a Minecraft version | 6.0.0 (client protocol 3: 5.x client mods stopped working with it), 7.0.0 (multimorph; the resource pack's file renamed) |
| **MINOR** | New features, abilities, settings or config sections that work with what's there: config files update themselves and keep every value, older client mods keep working | morph costs (6.3.0), First-person Model support (6.4.0), the fakeout explosion (6.5.0) |
| **PATCH** | Bug fixes only: no new settings, nothing new to learn | a morph facing the wrong way, a crash fix |

A release with features and fixes is a MINOR one. The client protocol number (`Wire.PROTOCOL_VERSION`, 3 since
6.0.0) is separate and only changes with a MAJOR version.

**Compatibility:** from 6.0.0 on, a newer client mod works on every older server, and an older client mod on every newer
server; neither side needs the other to update first. [PROTOCOL.md](PROTOCOL.md) has the rules that keep it so (the
protocol number stays 3, fields are only added at the end of a packet), and `tools/compat_check.py` checks the new
client against every released server before a release. Each config file's `config-version` is the release that last
changed it, without a pre-release suffix.

## Names

| Where | Format | Example |
|---|---|---|
| Plugin version, plugin jar | `MAJOR.MINOR.PATCH` | `6.5.0`, `RE-Morph-paper-6.5.0.jar` |
| Client mod version, mod jar | the same plus the Minecraft version as build metadata | `6.5.0+26.3`, `RE-Morph-fabric-6.5.0+26.3.jar` |
| Test builds handed out before a release | pre-release `-beta.N`, counting up | `6.6.0-beta.1`, `RE-Morph-paper-6.6.0-beta.1.jar` |
| Git tag | `v` + version | `v6.5.0` |
| GitHub release title | `RE:Morph` + version | `RE:Morph 6.5.0` |
| Modrinth version numbers | version + build metadata for the platform | `6.5.0+paper`, `6.5.0+fabric` |
| Modrinth test builds | the pre-release, on the Beta channel | `6.6.0-beta.1+paper` |

The build files (`paper-plugin/build.gradle`, `fabric-client/gradle.properties`) are the only place the version is
written. During development they carry the next version's pre-release; the release itself drops the suffix.

## Earlier releases

Until 6.5.0 the releases were numbered as patches of 6.1 although each one added features. On 2026-11-06 they were
renumbered to their SemVer numbers: SemVer bumps once per release (builds that were never released don't use up a
number), and nothing has broken since the first public release.

| Released as | Now | What it was |
|---|---|---|
| 5.0.0 | 5.0.0 | First build (Morph Reborn), not published |
| 5.1.0, 5.2.0 | – | Built and tested, never released; their changes shipped in 6.0.0 |
| 6.1.2 | **6.0.0** | First public release: client protocol 3 (5.x client mods are treated as unmodded), Survival Mode, renamed to RE:Morph (the plugin moves its own folder) |
| 6.1.3 | – | Skipped (folded into 6.1.0) |
| 6.1.4 | **6.1.0** | `/morph mode`, config.yml updates itself |
| 6.1.5 | **6.2.0** | Smooth movement while transforming, the paper doll |
| 6.1.6 | **6.3.0** | Morph costs, jockeys, villager trading, doll settings |
| 6.1.7 | **6.4.0** | First-person Model support, wheel prices, dragon fix (Modrinth only) |
| 6.1.8 | **6.5.0** | Fakeout explosion, enderman fix |

The renumbering changed the git tags (`v6.1.8` became `v6.5.0` and so on), the GitHub release titles, the Modrinth
version numbers (`6.1.8-paper` became `6.5.0+paper`; the old form read as a pre-release in SemVer), and the jars, which
were rebuilt from each release's own source with the new number and nothing else changed. Some things keep the old
numbers on purpose:

- Commit messages, which can't change without rewriting history.
- The `config-version` values inside the config files, and the backup files named after them
  (`config-old-6.1.4.yml`, `abilities-old-6.1.6.yml`): the plugin compares them to decide when to update a file, and
  the old numbers still sort correctly against new ones.
