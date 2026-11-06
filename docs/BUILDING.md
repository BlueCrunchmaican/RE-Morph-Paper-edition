# Building RE:Morph from source

The repository holds two separate Gradle projects, each with its own wrapper:

| Folder | Builds | Output |
|---|---|---|
| `paper-plugin/` | The Paper plugin | `paper-plugin/build/libs/RE-Morph-paper-<version>.jar` |
| `fabric-client/` | The Fabric client mod | `fabric-client/build/libs/RE-Morph-fabric-<version>+26.3.jar` |

## What you need

- **A JDK, version 25 or newer** (Minecraft 26.3 needs Java 25). Builds are tested with JDK 26.
- **Lib's Disguises 26.10.24 (Free)**, for the plugin only. Download it from its
  [SpigotMC page](https://www.spigotmc.org/resources/libs-disguises-free.81/) and put it at
  `paper-plugin/libs/LibsDisguises-26.10.24-Free.jar`. It isn't in the repository because we don't redistribute it.
  The plugin only compiles against it; the jar we build doesn't contain it.

Everything else (Paper API, PacketEvents, Minecraft, Fabric Loader and Fabric API) is downloaded by Gradle on the first
build.

## Build

Windows:

```bat
cd paper-plugin
gradlew.bat build
cd ..\fabric-client
gradlew.bat build
```

Linux and macOS:

```sh
cd paper-plugin && ./gradlew build
cd ../fabric-client && ./gradlew build
```

The plugin's `build` also runs its unit tests (config versioning, variant filters and the Survival Mode curves). The
first Fabric build takes a few minutes while Loom sets up Minecraft.

## Release package

`py -3 tools/package_release.py` (or `python3` on Linux and macOS) collects both jars, the resource pack, the docs, the
default configs and a source zip into `release/RE-Morph-<version>/` and zips it. Build both projects first, and bump
the version in `tools/package_release.py`, `paper-plugin/build.gradle` and `fabric-client/gradle.properties` together.

When a release changes `abilities.yml`, `variants.yml` or `survival.yml`, raise its `config-version` to the new
release's version, so servers replace their old copy (see "Updating" in [GUIDE.md](GUIDE.md)).

## Testing locally

The Fabric project has three dev clients that join a local test server on `localhost:25599`:

- `gradlew runClient`: MorphTester1, with the mod.
- `gradlew runClient2`: MorphTester2, with the mod.
- `gradlew runVanilla`: VanillaViewer, with the mod switched off, so the server treats it as a player without the mod.

The test server itself isn't in the repository: put a Paper 26.3 server in `test-server/` (git-ignored) on port 25599
with online mode off, plus the plugin, Lib's Disguises and PacketEvents. `tools/mobtests/` has RCON scripts that drive
it; see its README. [TESTING.md](TESTING.md) lists what has been tested and how.

## Internal names

Some names still carry the project's old name, Morph Reborn, on purpose. Changing them would break existing servers
and players:

| Name | Where |
|---|---|
| `dev.morphreborn` | Java packages |
| `morphreborn` | Fabric mod id, asset namespace, the morph sound (`morphreborn:morph`), keys of saved player data |
| `morphreborn:main` | Plugin messaging channel between the plugin and the mod |
| `morphreborn.*` | Permissions |
| `config/morphreborn-client.json` | Client settings file |
