# Versions

Pinned versions for Kanto Bridge, all looked up from official sources on **2026-10-03**. Change
them only deliberately:

1. Update `fabric-bridge/gradle.properties`, and `fabric.mod.json` `depends` if needed.
2. Update this file, including where the new value came from.
3. Rebuild.

## Minecraft side (`fabric-bridge`)

| Component | Version | Where it was found | Notes |
|---|---|---|---|
| Minecraft | **1.21.1** | Pinned by you. Listed as stable by Fabric meta: <https://meta.fabricmc.net/v2/versions/game> | |
| Java | **21** | Pinned by you. Minecraft 1.20.5+ needs Java 21 (official template generator, `scripts/src/lib/template/java.ts` in <https://github.com/FabricMC/fabricmc.net>). | Installed here: Eclipse Temurin **21.0.12.1+1** (winget `EclipseAdoptium.Temurin.21.JDK`) at `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`. This machine's default `JAVA_HOME`/PATH Java stays **17**, so point builds at 21 explicitly. |
| Fabric Loader | **0.19.5** | First `"stable": true` entry at <https://meta.fabricmc.net/v2/versions/loader>; also `fabric versions 1.21.1` from the official Fabric CLI | Latest stable |
| Fabric API | **0.116.17+1.21.1** | Highest `+1.21.1` version in <https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml>; also `fabric versions 1.21.1` | Latest for 1.21.1 |
| Fabric Loom | **1.17.21** | <https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml>. Its Gradle module metadata (`fabric-loom-1.17.21.module`) requires JVM ≥ 21 and Gradle ≥ 9.5. | Fabric's template now uses `1.18-SNAPSHOT`, but **Loom 1.18.x needs Gradle to run on Java 25** (`"Dependency requires at least JVM runtime version 25"`). You chose 1.17.21 to keep Java 21. Moving to 1.18+ later means building on JDK 25. |
| Loom plugin ID | `net.fabricmc.fabric-loom-remap` | Template generator, `templates/gradle/groovy/build.gradle.eta` (`-remap` for obfuscated versions such as 1.21.1); published for 1.17.21 at <https://maven.fabricmc.net/net/fabricmc/fabric-loom-remap/> | |
| Gradle (wrapper) | **9.7.1** | Template generator, `templates/gradle/wrapper/gradle/wrapper/gradle-wrapper.properties` | The wrapper jar's SHA-256 (`7a9ce74c…262c5d`) matches Gradle's published checksum for 9.7.1 (<https://services.gradle.org/versions/all>) |
| Mappings | Mojang official + Parchment | `loom.layered() { officialMojangMappings(); parchment(…) }`, per <https://parchmentmc.org/docs/getting-started> | Never Yarn |
| Parchment | **parchment-1.21.1 : 2024.11.17** | `<release>` in <https://maven.parchmentmc.org/org/parchmentmc/data/parchment-1.21.1/maven-metadata.xml> (the maven linked from parchmentmc.org) | Latest for 1.21.1 |
| Cobblemon | **1.8.1** (Fabric), maven `com.cobblemon:fabric:1.8.1+1.21.1` | Modrinth (official mod page): version `gBW3vLC7` "Cobblemon v1.8.1 ~ Fabric", 2026-09-12, file `Cobblemon-fabric-1.8.1+1.21.1.jar`, only required dependency Fabric API (<https://modrinth.com/mod/cobblemon/version/gBW3vLC7>). Maven: Cobblemon's own <https://artefacts.cobblemon.com/releases> (mirrored at <https://maven.impactdev.net/repository/development/>). Source: tag `1.8.1` at <https://gitlab.com/cable-mc/cobblemon>, commit `0bc5bb5c`, which matches the POM's `gitCommit`. | |
| Fabric Language Kotlin | **1.13.6+kotlin.2.2.20** | Cobblemon 1.8.1 `gradle/libs.versions.toml:30`; bundled jar-in-jar in Cobblemon and listed in its POM | The library Cobblemon needs on Fabric. Players get it inside Cobblemon's jar. Kotlin 2.2.20. |
| Cobblemon's bundled libraries | GraalJS 22.3.0, ICU4J 71.1, MongoDB driver 4.10.2 | Cobblemon 1.8.1 POM | Arrive transitively through the POM; bundled for players |
| Kotlin Gradle plugin | **2.2.20** (`kotlin_version`) | The Kotlin of Cobblemon 1.8.1 (`refs/cobblemon/gradle/libs.versions.toml`) and of the pinned Fabric Language Kotlin `1.13.6+kotlin.2.2.20` | **Build only; there are no Kotlin sources.** Added in M0: Loom remaps the Kotlin metadata of mods only when this plugin is applied, and without that Cobblemon crashes in every dev runtime (GameTest, `runServer`, `runClient`) with `ClassNotFoundException: net.minecraft.class_2960`. After changing it, delete `fabric-bridge/.gradle/loom-cache/remapped_mods` once. `kotlin.stdlib.default.dependency=false` keeps it from adding a stdlib |
| ICU4J (dev server runs) | **71.1** (`cobblemon_icu4j_version`) | Cobblemon 1.8.1's POM | Added in M0 to the `runServer` and `runGameTest` classpaths only. Loom strips Minecraft's client-only ICU4J from server runs, and Cobblemon's Showdown (GraalJS) needs it, so the server waited for Showdown forever. Real servers get the copy bundled in Cobblemon's jar |

For reference, Cobblemon 1.8.1 itself builds against:
- Fabric Loader 0.17.2
- Fabric API 0.116.6+1.21.1
- Fabric Language Kotlin 1.13.6+kotlin.2.2.20
- Parchment `parchment-1.21:2024.07.28`
- Architectury Loom 1.11-SNAPSHOT

These come from `refs/cobblemon/gradle/libs.versions.toml` and `gradle.properties`. Our Loader and
Fabric API meet Cobblemon's minimums (`>=0.17.2`, `>=0.116.6+1.21.1`).

Tooling used once to create `fabric-bridge`: the official Fabric CLI, `@fabricmc/cli` **0.1.466**
from npm (source `FabricMC/fabricmc.net`).

## Gen1Recomp side (`gen1-bridge`)

| Component | Version | Where it was found | Notes |
|---|---|---|---|
| Gen1Recomp | **v0.3.49**: the official Windows release zip `gen1recomp-0.3.49-windows.zip`, SHA-256 `7816b1fd93a06197aa395658f73d7e8087ee6421b3da6452f7be8ff69f3ae878` | <https://github.com/bryanthaboi/gen1recomp/releases/tag/v0.3.49> (2026-10-02, the latest release when pinned on 2026-10-03). The checksum matches the release's own `sha256sums.txt`. Official source only; never gen1recomp[.]com. | You chose the latest release; I chose the release zip over a source checkout, because it needs no LÖVE install and is the exact build players would get. Installed portable in `local/gen1recomp/`. The references were studied at `dev` @ `cb02aeb`, which v0.3.49 contains. `gen1-bridge/manifest.json` declares `"game_version": "0.0.0-dev \|\| >=0.3.49 <0.4.0"`. |
| Gen1Recomp mod API | **2** | `src/core/Version.lua` (`modApi = 2`) | `"api": 2` in the manifest |
| LÖVE | **11.5**, bundled | Gen1Recomp `conf.lua:64`; the release's `gen1recomp.exe` is fused with LÖVE (`love.dll`) | No separate install needed for the release build |
| Dramaless Shape (voxel mod) | **2.0.4** | Your zip in `refs/`; GitHub release `v2.0.4` (2026-09-01) at <https://github.com/artyrambles/DRAMALESS_SHAPE> | Reference only; not pinned for players yet |

## Test tooling (`tools/`)

Installed only into the project-local venv `tools/.venv`, wheels only, from `tools/requirements.txt`
(exact pins, including dependencies, from `pip freeze` on 2026-10-03). Python itself is the
machine's **3.12.7**.

| Component | Version | Where it was found | Notes |
|---|---|---|---|
| pytest | **9.1.1** | pypi.org (latest at pinning) | Plus pytest-timeout **2.4.0** |
| jsonschema | **4.26.0** | pypi.org | With referencing 0.37.0, jsonschema-specifications 2025.9.1, rpds-py 2026.6.3 (cp312 win_amd64 wheel) |
| lupa | **2.8** | pypi.org, `lupa-2.8-cp312-cp312-win_amd64.whl` | Runs gen1-bridge's Lua on its bundled **LuaJIT 2.1** (`import lupa.luajit21`, never bare `import lupa`) |
| fabric-loader-junit | **0.19.5** | Same version as Fabric Loader, from <https://maven.fabricmc.net/> | JUnit 5 with Minecraft classes on the classpath |

## This project

| Component | Version |
|---|---|
| `fabric-bridge` (mod id `kantobridge`) | 0.1.0 |
| `gen1-bridge` (mod id `gen1-bridge`) | 0.1.0 |
| Protocol | **1** ([PROTOCOL.md](PROTOCOL.md), schemas in `protocol/`): `127.0.0.1:27151`, newline-delimited strict JSON, 72 message types |
