# Installer design

**Status (M10):** a written design plus a skeleton script, `tools/installer/install.ps1`. The skeleton
prints its plan and can check a downloaded file against its pin, but it **downloads nothing, installs
nothing and bundles no third-party program**: PLAN.md §6 M10 asks for your OK before any of that.
The questions to settle are at the end.

## Goal

DESIGN.md §2 calls for a one-click installer. It sets up Gen1Recomp, the bridge, Minecraft, Fabric
and Cobblemon through a bundled launcher, as SkyCraft does. It asks for the player's ROM, has
Gen1Recomp import it, then runs the world builder. Every version is pinned and updated deliberately.

The model is SkyCraft's (`refs/_research/C_community_mods.md`, "SkyCraft"):

- an unmodified portable **Prism Launcher** runs Minecraft;
- a ready-made Prism instance pins Minecraft and Fabric;
- Prism handles the Microsoft sign-in and downloads Java and Minecraft itself.

We add Gen1Recomp in portable mode with the gen1-bridge mod, and a first-run flow for the ROM import
and the world build.

## What a player gets

Everything lives in one per-user folder, `%LOCALAPPDATA%\KantoBridge\`. The installer changes
nothing system-wide (no Java install, no PATH or registry changes) and needs no administrator rights.

```
KantoBridge\
  launch.ps1, Kanto Bridge.cmd   the launcher (tools/launch.ps1 -Mode player) and a double-click wrapper
  launch.json                    game (red/blue), port, instance name
  install.stamp                  the installed release and the hash of every managed file
  gen1recomp\                    the official Gen1Recomp release, portable (portable.txt)
    mods\gen1-bridge\            the bridge mod (a copy, not a link)
    (saves, roms, import cache:  Gen1Recomp's own data, never touched by the installer)
  prism\                         portable Prism Launcher, unmodified, with its licence
    prismlauncher.cfg            our defaults: automatic Java download and switching, stay open until
                                 the game stops (so launch.ps1 can tell when Minecraft is closed)
    instances\KantoBridge\
      instance.cfg, mmc-pack.json   Minecraft 1.21.1, Fabric Loader 0.19.5; JVM memory
      .minecraft\mods\           Fabric API, Cobblemon, kantobridge (all pinned)
      .minecraft\saves\Kanto\    created by the world builder on first run
  cache\                         downloads, kept after a hash check so updates and repairs can reuse them
```

## The release zip

`KantoBridge-<version>.zip` holds only our own files and the pins:

- `install.ps1` plus `Install Kanto Bridge.cmd` (runs it with `-ExecutionPolicy Bypass` for this
  process only);
- `launch.ps1`;
- the `gen1-bridge\` mod;
- `kantobridge-<version>.jar`;
- `pins.json`: every third-party file with its official URL, size, SHA-256 and licence;
- the Prism defaults and the instance template;
- `THIRD-PARTY-NOTICES.md`.

It contains no ROM data, no Gen1Recomp files and no Minecraft files. Whether it also bundles Prism
(as SkyCraft does) or downloads it at install time is one of the questions below.

## Install steps

1. **Preflight:** Windows 10/11 x64, free disk space (about 3 GB with Minecraft and Java),
   `tar.exe` available, the target folder writable. Refuse an install path inside a OneDrive folder
   (sync locks break saves).
2. **Downloads (pinned):** each file is fetched from its official source into `cache\`. It is
   checked against `pins.json` (size and SHA-256) before use; a mismatch stops the install.
   - **Gen1Recomp** comes only from <https://github.com/bryanthaboi/gen1recomp/releases>.
     Never gen1recomp.com, which is an impersonation site.
   - **Prism Launcher:** a portable Windows build from its GitHub releases.
   - **Fabric API** from maven.fabricmc.net (or Modrinth). **Cobblemon** from its official Modrinth
     page or Maven. Cobblemon carries Fabric Language Kotlin inside its jar.
   - Minecraft, Fabric Loader and Java are left to Prism (pinned through `mmc-pack.json`).
3. **Gen1Recomp:** unpack the release into `gen1recomp\`, write an empty `portable.txt`, copy
   `gen1-bridge\` into `mods\`.
4. **Prism:** unpack it into `prism\`, apply our `prismlauncher.cfg` defaults, create the
   `KantoBridge` instance from the template, and copy the pinned jars into its `.minecraft\mods\`.
5. **Launcher files:** `launch.ps1`, `launch.json` (with the game chosen in step 7) and
   Start-menu and desktop shortcuts to `Kanto Bridge.cmd`.
6. **Sign in:** Prism opens once so the player can sign in with Microsoft. We never see the account.
7. **ROM import:** the installer starts `gen1recomp\gen1recomp.exe --launcher`, and the player
   imports their own ROM in **Gen1Recomp's own importer**. The installer never reads, copies or
   checks the ROM. It then asks which game was imported (Red or Blue) for `launch.json`.
8. **World build:** the first `launch.ps1` run starts Gen1Recomp and Minecraft. On Minecraft's title
   screen, **Create Kanto world** exports the player's own art and builds the world locally (the
   M2/M8 flow, about a minute). Nothing ROM-derived ever leaves the machine.

## Updates and repair

- A new release replaces only managed files: `gen1-bridge\`, the jars in the instance's `mods\`, the
  instance template fields we own, `launch.ps1` and the pins.
- It never touches Gen1Recomp's saves, `roms\`, import cache or `options.lua`, Prism's accounts, or
  Minecraft worlds. `install.stamp` records what was installed, so a repair can re-check every
  managed file against it.
- A Gen1Recomp update is a pin change: the new release's files are swapped in. Then the world may
  need a rebuild (the world shows a version notice when the export hash differs).

## Uninstall

Remove the shortcuts and the managed files. Ask before deleting Gen1Recomp's data (saves and ROM
import) or Minecraft worlds, and default to keeping them.

## Multiplayer

Every player installs the same release. That gives the same kantobridge and gen1-bridge versions,
which the relay handshake (`relay.hello`) and the bridge handshake check. Each player imports their
own ROM. A world is shared by:

- **LAN:** one player opens it to LAN;
- **a server:** a dedicated server with kantobridge, Fabric API and Cobblemon. The server gets no
  ROM data except what players' clients relay.

## Pins

`tools/installer/pins.json` lists every third-party file. Pinned so far, from the official
repositories the build already uses:

- Gen1Recomp v0.3.85 (its release `sha256sums.txt`, see VERSIONS.md; the first-run flow must also switch
  its Union Room off, as INSTALL.md step 6 does);
- Fabric API 0.116.17+1.21.1;
- Cobblemon 1.8.1, the Maven artifact, SHA-256 taken from the Gradle cache.

Prism is listed without a pin until you choose a version.

## Licences

| Component | Licence | How it reaches the player |
|---|---|---|
| Gen1Recomp | its release's `license.txt` (redistribution not checked) | downloaded from the official GitHub release at install time, never bundled |
| Prism Launcher | GPL-3.0 | unmodified portable build; ship its licence text and a source link, as SkyCraft does |
| Fabric API | Apache-2.0 | downloaded (or bundled with its licence) |
| Cobblemon | code MPL-2.0; assets under their own terms | downloaded from official sources at install time; redistribution not checked |
| kantobridge, gen1-bridge | GPL-3.0-only, with Gen1Recomp's credit line ([`LICENSE`](../LICENSE), [`CREDITS.md`](../CREDITS.md)) | in the release zip |
| Minecraft, Java, Fabric Loader | Mojang's and the vendors' terms | downloaded by Prism after sign-in |

## Questions for you before it's built

1. **Prism:** bundle an unmodified portable Prism in the release zip (SkyCraft's way), or download it
   at install time? Either way it's a third-party launcher, which needs your OK. Also: is using
   Prism's Microsoft client ID from another project acceptable to you? (Not checked; SkyCraft
   does it.)
2. **Downloads at install time** (Gen1Recomp, Fabric API, Cobblemon): OK with pinned official URLs
   and hash checks?
3. **Packaging:** a PowerShell installer (the skeleton) or a small native installer (e.g. Inno
   Setup)? PowerShell needs no extra tooling but shows a console window.
4. **Official launcher:** also support the official Minecraft launcher with a manual profile, or
   Prism only?
