# Hoenn and the pass to it (MG8's execution plan)

MULTI_GAME_PLAN.md §5 MG8 asks for the Kanto ↔ Hoenn pass in `routes.json`, the pod build of Hoenn beside Kanto, the
budgets per region, videos and a PLAYTEST "Hoenn" section. MG5 built the crossing machinery (connecting routes as two
halves, gate houses, the pass service, the client's choreography, the stash; PROGRESS.md "Several games" note 20) and
proved it with stand-ins; MG7 made Emerald followable and built Hoenn (GEN3_BRIDGE_PLAN.md). What is missing is **the
Hoenn half of a route**: Gen 3 can't take Gen 1's route end (no new maps, no map scripts, no patched exits: spike S2),
so the end is the adapter's, and the Gen 3 export and builder must carry it. This plan says how, in phases.

Paths: `GB/` = `gen1-bridge/`, `J/` = `fabric-bridge/src/main/java/dev/kantobridge/`, `R/` = `refs/gen1recomp` (the
engine source, v0.3.85). Research: `refs/_research/mg7_g3f_battles_party_bag.md` §4 (git-ignored).

## 1. Decisions

| # | Question | Decision |
|---|---|---|
| K1 | Where are the pass's exits? | **Temporary test exits** (the user, 2026-10-14: "The first wild route exit can just be a temporary/test one so pick a corner of the first or second town where it won't interfere with the game"). Chosen from the export's cells on the pod (the clearing rules of `Routes.plan`, reachable on foot, at least six cells from any object, warp, sign or door): **Kanto**: `VIRIDIAN_CITY` (6, 4) facing up, depth 2, the north-west corner (Pallet Town has no spot that fits); **Hoenn**: `EM_LITTLEROOT_TOWN` (18, 14) facing down, depth 2, the south-east corner, a side with no route connection. The route is `kanto_hoenn_test`, "TEST PASS". F7 and an edit of `routes.json` move either end later |
| K2 | The hidden Emerald player while the player is in the Hoenn half (GEN3 E6) | **Parked on the clearing's last block, reported as the route map**: the adapter reports `KB_ROUTE_<ID>` at x1 (1, 0) facing right while parked, so the core, the client's zone following and the route acts work as on Gen 1 (a virtual route map). A step left from x1 (onto A) is the way back: the adapter lets go and puts the player on the clearing's back cell, facing the town |
| K3 | The clearing on Gen 3 | `replaceBlock` per cleared cell **on every load of the town** (Gen 3 rebuilds a map's cells on each load): each cleared 2×2 block copies the exit block's four metatiles and its elevation (the arithmetic of `Routes.plan`, so the builder's units stay). The export bakes the same cells into the town's `g3map`; the story part counts them as the map's own (no `story.cells` for them) |
| K4 | The zone's acts on Gen 3 | **give**: `Bag.add` and the found-item line in the field's message box. **battle**: `BattleBridge.start` with the first trainer of the class in the engine's table as the template and the request's party (species and moves by Emerald's constant names), held by G3F's wrapper (Cobblemon fights it) and answered from its `done`. **blackout**: G3F's `blackout` |
| K5 | Species of a Hoenn half's trainers | `g3encounters` slots gain `species`, Emerald's `SPECIES_*` name without its prefix; the builder's Gen 3 zone content pools the land slots of the town's connected maps |
| K6 | Trainer classes and sprites of the Hoenn end | Emerald's `TRAINER_CLASS_*` symbols in `routes.json`; sprites are placeholders, as every Gen 3 puppet today |
| K7 | Music of the Hoenn end | none (`null`): Gen 3 has no music registry for mods (spike S2) |
| K8 | The world | one world, Kanto at slot 0 and Hoenn at slot 1, both region builds, each with its half (MULTI_GAME_PLAN MG8) |

## 2. Phases

| Phase | Delivers | Check |
|---|---|---|
| **HP1** route list | `routes.json` v2 with `kanto_hoenn_test` (K1, K6, K7) beside the Viridian zone | Lua parse tests; the Gen 1 end installs on the real Blue (e2e) |
| **HP2** core hooks (inert on Gen 1) | `kb_routes` hands an end to the adapter when it has `routeInstall`; `world.stepped` reaches `Routes.stepped`; `kb_move` asks the adapter for a step on a virtual route map; `kb_region`'s resume onto a route map asks `routeEnter`; `kb_route`'s acts use `routeGive`/`routeBattle` when the adapter has them | lupa; Gen 1 suites unchanged |
| **HP3** the game3 route end | part `routes.lua` (K2–K4): the plan and its check on the live map, the clearing at each load, the trigger, the virtual route map in observe and the save view, the way back, the resume, the acts | lupa on the fake engine (a test town with a tree band) |
| **HP4** the Gen 3 export | the `routes` resource, the clearing baked into `g3map`, `species` in `g3encounters` (schemas, PROTOCOL.md) | lupa; the pod's export of the real Emerald |
| **HP5** the builder | Gen 3 zone content (pools, sprites, items) and zones for a Gen 3 region; the synthetic Gen 3 bundle gains a route end | JUnit, goldens, a GameTest of the synthetic Hoenn's half (its gate house and pass) |
| **HP6** server and client | route trainers on a Hoenn half (classes, species), items by Gen 3 symbols; the pass onto a Gen 3 half | GameTests |
| **HP7** on the pod | Kanto and Hoenn in one world, each half built; e2e: the Emerald half on the real engine (into the zone through the clearing, a give, a battle, back); **the crossing between two real engines** (Blue and Emerald, a fake Minecraft as the client: park, resume on the route map, back) | e2e; the build gates |
| **HP8** wrap-up | PLAYTEST "Hoenn"; PROGRESS; budgets per region; MG7's carry-overs as per-map items | — |

## 3. What stays as it is

- Gen 1's route ends (Viridian, the Kanto half): unchanged, their bytes and goldens too.
- The client: it follows the hidden player onto the route map and back exactly as for a Gen 1 half (K2).
- The server's pass service: a resume onto the route map is the client's request, whatever the far game is.

## 4. Risks

| Risk | Mitigation |
|---|---|
| A Gen 3 map load wipes the clearing between the trigger and the way back | the clearing is put back on every `map.entered` of the town and checked before the way back lands |
| The virtual route map disagrees with the engine (a script moves the parked player) | the park holds a field lock as `region.lua`'s does; a lost lock ends the virtual map (the client sees the town again) |
| A template trainer's own data leaks into a zone battle (its prize, its name) | the battle's foe is ours (party, class); the prize comes from the template's class, as on Gen 1's `KB_<CLASS>` copies |
| The two real engines run out of memory on a 2-vCPU pod | S1 measured about 600 MB together; the crossing e2e runs on a 16 GB pod |
