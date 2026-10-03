# fabric-bridge

The Minecraft half of Kanto Bridge: a Fabric mod (Java) for Minecraft 1.21.1 with Cobblemon 1.8.1.
Mod id `kantobridge`, package `dev.kantobridge`.

**Status: skeleton.** The mod initializer only logs `kantobridge loaded`.

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

- `src/main/java/` holds common code that runs on both server and client: `dev.kantobridge.KantoBridge`.
- `src/client/java/` holds client-only code: `dev.kantobridge.client.KantoBridgeClient`. Rendering, screens and the
  client-side NPC puppets will go here.
- `src/main/resources/fabric.mod.json` declares the dependencies: Fabric Loader, Minecraft ~1.21.1, Java 21,
  Fabric API and Cobblemon ~1.8.1.

## Tasks

| Command | Result |
|---|---|
| `./gradlew build` | Builds `build/libs/kantobridge-<version>.jar` (plus a sources jar). |
| `./gradlew runClient` | Starts a development Minecraft client with this mod, Fabric API and Cobblemon. |
| `./gradlew genSources` | Decompiles Minecraft with Parchment names, for reading in the IDE. |
