# fabric-bridge

The Minecraft half of Kanto Bridge: a Fabric mod (Java) for Minecraft 1.21.1 with Cobblemon 1.8.1.
Mod id `kantobridge`, package `dev.kantobridge`.

**Status: M0 foundations.** Protocol codec and catalog, the client↔server relay skeleton, tile blocks, static
world data and the test harnesses are in place; gameplay arrives milestone by milestone
([`docs/PLAN.md`](../docs/PLAN.md), [`docs/PROGRESS.md`](../docs/PROGRESS.md)).

Generated with Fabric's official template generator (`@fabricmc/cli init`, CC0), using Mojang mappings
and split client/common sources. It was then changed as follows:

- Parchment is layered on the Mojang mappings.
- Cobblemon and Fabric Language Kotlin were added as dependencies.
- Loom is pinned to 1.17.21, the newest Loom that runs on Java 21.
- The example mixins, the template's CC0 `LICENSE` and its nested CI workflow were removed.

All versions live in `gradle.properties` and are recorded, with their sources, in
[`docs/VERSIONS.md`](../docs/VERSIONS.md).

## Requirements

**JDK 21.** Gradle itself must run on Java 21, so point `JAVA_HOME` at a JDK 21 for the build. This
machine's default Java is 17. In Git Bash:

```sh
cd fabric-bridge
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot" ./gradlew build
```

In IntelliJ, set *Settings → Build Tools → Gradle → Gradle JVM* to the JDK 21.

## Layout

| Path | Contents |
|---|---|
| `src/main/java/dev/kantobridge/` | Common code. `proto/` (strict JSON, `Envelope`, `Catalog`, `Redact`; `MsgTypes` is generated), `net/` (the `kantobridge:json` payload and the server `RelayService`), `block/` (tile blocks), `battle/`, `mixin/` |
| `src/main/resources/` | `fabric.mod.json`, `kantobridge.mixins.json`, and static data: the void world preset, biomes, the deeper overworld, battle-proxy NPC classes, the interior spawn rule, recipe overrides, placeholder tile models |
| `src/client/java/dev/kantobridge/client/` | Client-only code: `net/ClientRelay`, `packs/GeneratedPack` and the pack-source mixin (`mixin/client/`) |
| `src/test/java/` | JUnit 5 under Fabric Loader (`fabric-loader-junit`): codec, catalog, redaction vectors, protocol examples, tile blocks, generated pack |
| `src/gametest/` | Server GameTests (mod `kantobridge-gametest`): Cobblemon start-up and mock players, tile blocks, static data, relay, battle-proxy hiding |

The protocol catalog comes from [`../protocol/catalog.json`](../protocol/README.md): the build generates
`MsgTypes.java` from it and bundles it in the jar as `kantobridge/protocol/catalog.json`.

## Tasks

| Command | Result |
|---|---|
| `./gradlew build` | Builds `build/libs/kantobridge-<version>.jar` and runs `check`: JUnit (`test`), the server GameTests (`runGameTest`) and `verifyMixinRemap` |
| `./gradlew test` | JUnit only (reads `../protocol/` for examples and redaction vectors) |
| `./gradlew runGameTest` | Server GameTests, headless, in `build/run/gameTest` (its old world is deleted first; 15-minute timeout). Report: `build/reports/gametest/TEST-gametest.xml` |
| `./gradlew runServer` | A dev server in `../local/minecraft` (git-ignored); needs the user's `eula.txt` there |
| `./gradlew runClient` | A dev client in `../local/minecraft`. Agents never use it for verification: nobody can see it |
| `./gradlew genSources` | Decompiles Minecraft with Parchment names, for reading in the IDE |

## Build notes

- **Kotlin Gradle plugin, no Kotlin sources.** Loom remaps the Kotlin metadata of Kotlin mods only when the
  `org.jetbrains.kotlin.jvm` plugin is applied. Without it, Cobblemon's metadata keeps intermediary names and its
  start-up crashes in every dev runtime (`ClassNotFoundException: net.minecraft.class_2960`). The plugin version
  equals Cobblemon's Kotlin (2.2.20). After changing it, delete `.gradle/loom-cache/remapped_mods` once.
- **ICU4J on server runs.** Minecraft lists ICU4J as a client library, so Loom drops it from server-side runs, but
  Cobblemon's Showdown engine needs it. The build adds Cobblemon's own version to `runServer` and `runGameTest`
  only; real servers use the copy bundled in Cobblemon's jar.
- **Mixin remapping.** Loom 1.17 remaps mixins with tiny-remapper (no refmap). `verifyMixinRemap` fails the build if
  an injector target in the remapped jar still has its Mojang name, which would only break in a real game.
