# Landscape models rollout: rock only, all three regions (the plan)

The user's choice (2026-10-22): **rock only, all regions**. Every outdoor map's landscape sections are marked, then the
big rock (cliff faces of 3 cells or more, mountains and plateaus, cave mouths) is modelled as in the pilot
([LANDSCAPE_MODELS.md](LANDSCAPE_MODELS.md), [LANDSCAPE_SECTIONS_PLAN.md](LANDSCAPE_SECTIONS_PLAN.md)), standing on the
reviewed levels ([LEVELS_PIPELINE.md](LEVELS_PIPELINE.md)). Trees, objects and buildings keep the models they have.
Then all three regions are built and pictured. This plan is written to be run step by step by another session: each
phase says what to run, what to check, and what to do when something fails.

## 0. Before you start

**What exists.** 185 outdoor maps (`kb-hub/work/landscape/rollout/outdoor_maps.json`); the pilot marked 8 of them and
modelled the rock round 8 picture spots (`kb-hub/work/landscape/models/<MAP>/`, 36 models, the user liked them). 177
maps are left to mark (`rollout/to_mark.json`: 75 Johto, 63 Hoenn, 39 Kanto). The levels are final: the models table
`kb-hub/work/landscape/models_table_v13.json` (pins and slopes from four review rounds, the pilot's models), dumps
`levels_r7/levels_r7pin` (Hoenn, Johto) and `levels_r9/levels_r9pin` (Kanto), world `LandLevels` on the volume.

**Paths** (all under `/home/claude`): `W = kb-hub/work/landscape` (private: the user's game; nothing in it is ever
committed); the repo `kantobridge` (public: commit locally, push only on the user's word); art
`kb-hub/data/images/maps/<game>/<MAP>_art.png`; pod helpers `kb-runpod/kb-pod`, `kb-runpod/kb-junit`.

**Ready-made for this plan** (in the repo, `tools/landscape_sections/rollout/`, `R` below): `mark-sections.js` and
`model-units.js` (the two workflows), `make_args.py` (their args, balanced into runs, written to `W/rollout/`) and
`pick_spots.py` (the picture spots); and `tools/landscape_sections/units.py` (a map's rock sections grouped into units,
each with a small window) with `package.py`'s unit windows. The data lives in `W/rollout/` (the map lists, the args,
`spots_units.json`, `check/`).

**Rules** (from CLAUDE.md and the project's memory):
- Never read, print or copy `/opt/monolingual-cloud/.env` or `runtime/secrets/`.
- ROM-derived pictures and data stay in `W`, the hub, or on the pod; never in the repo, never sent anywhere but to the user.
- Pods: start or create freely, say the hourly price, **stop each pod as soon as its job is done** (never left running
  while waiting for agents or the user). EU-RO-1 hosts are often full: try the next stopped pod, else create one
  (section 9). Pictures need at least 4 vCPUs (on 2 the client never gets past "Receiving level").
- Never `pkill -f` / `pgrep -f` a pattern that also appears in your own command line (it kills your shell): find the PID
  and `kill <pid>`.
- Docker only as written below: `flock W/.terrain.lock docker run --rm -m 1g ...`, one at a time.
- This server has 2 CPUs and 3.7 GB: a workflow runs about 2 agents at a time; run up to 5 workflows side by side.
  Keep each workflow to about 10 agents (the session's workflow-size guideline): split the work into more runs and
  start the next run whenever one finishes, 5 running at a time.
- Workflow args are a JSON **array** (paste the file's content), never a JSON string.
- The user reads on a phone: short status lines, plain words; send sheets with SendUserFile.
- At each phase's end, add a line to the memory file `kantobridge-heights-all-games.md` (what is done, run ids, next).

## 1. The first pass for the 177 maps (pod, about 20 minutes)

1. Start an 8-vCPU pod: `kb-dev40` (`etu477fpdx3zam`, cpu3g x8, 32 GB, $0.32/hr). From get-pod's `ssh.direct`:
   `kb-runpod/kb-pod host <ip> <port>`, `kb-runpod/kb-pod prep`, then
   `ssh kb-dev 'ln -sfn /workspace/local /root/kantobridge/local'`.
2. Per game, the first pass (`LandscapeRegionsTest`) of that game's maps from `rollout/to_mark.json`:
   ```
   ssh kb-dev 'cd /root/kantobridge/fabric-bridge && export JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH &&
     E=/workspace/local/minecraft/kantobridge/export && O=/workspace/videos/landscape/regions_rollout && mkdir -p $O &&
     timeout 2400 ./gradlew test --tests "dev.kantobridge.builder.shape.LandscapeRegionsTest" "-Pkb.landscape=$E/<game>;$O;<MAP,MAP,...>" -Pkb.testHeap=10g > /root/lr_<game>.log 2>&1; grep BUILD /root/lr_<game>.log'
   ```
   (`<game>`: `emerald` for `EM_*`, `crystal` for `G2_*`, `blue` for the rest; one game a run, never two Gradle runs at
   once.) A game's list: `python3 -c "import json; m=json.load(open('/home/claude/kb-hub/work/landscape/rollout/to_mark.json')); print(','.join(x for x in m if x.startswith('EM_')))"`
   (`'G2_'` for Johto; for Kanto `not x.startswith(('EM_', 'G2_'))`).
3. Fetch: `ssh kb-dev 'cd /workspace/videos/landscape && tar czf /root/regions.tgz regions_rollout'`, `scp` it, unpack,
   move the JSON files into `W/regions/`. **Check:** `W/regions/` holds a file for each of the 185 maps.
4. **Stop the pod.**
5. The marking pictures, per game (local; the maps of that game from `rollout/to_mark.json`):
   ```
   cd /home/claude/kb-hub/work/landscape && flock .terrain.lock docker run --rm -m 1g -u $(id -u):$(id -g) \
     -v /home/claude/kantobridge/tools:/tools:ro -v /home/claude/kb-hub/work/landscape:/w \
     -v /home/claude/kb-hub/data/images/maps:/maps:ro kb-pyimg:1 \
     python /tools/landscape_sections/overlay.py /w/regions /maps/<game> /w/chunks <MAP> <MAP> ...
   ```
   **Check:** `W/chunks/<MAP>_chunks.json` for every map to mark.

## 2. Marking (18 workflows, 5 at a time, about 6 to 8 hours)

1. `python3 R/make_args.py mark auto` writes `W/rollout/mark_run1_args.json` .. `mark_run18_args.json` (about 10
   maps each).
2. Launch the first 5: `Workflow({scriptPath: "/home/claude/kantobridge/tools/landscape_sections/rollout/mark-sections.js",
   args: <the run's array>})`; when one finishes, launch the next, until all 18 have run. Note the run ids in memory as you go.
   Each agent writes `W/marks/<MAP>_sections.json` and checks it with `table.py`.
3. While they run, post a status line every hour or so (`ls W/marks | wc -l`).
4. When all have ended: every map in `to_mark.json` must have a marks file, and
   `python3 /home/claude/kantobridge/tools/landscape_sections/table.py W/regions W/marks W/rollout/marks_table.json <all 185 maps>`
   must print a counts line per map with no error. Maps that fail or are missing: one more workflow with only those
   maps' args (the same script). Still failing after that: list them for the user, model the rest.

## 3. Units and packages (local, about 15 minutes)

1. The units of all 185 maps (the pilot maps leave out the sections their spots already model):
   ```
   cd /home/claude/kb-hub/work/landscape && python3 /home/claude/kantobridge/tools/landscape_sections/units.py \
     regions marks models rollout/spots_units.json $(python3 -c "import json; print(' '.join(json.load(open('rollout/outdoor_maps.json'))))")
   ```
   It prints each map's units (window size: section ids). Maps with no rock print 0 units; that is fine.
2. A package per unit, per game (the levels: `levels_r7/levels_r7pin` for Hoenn and Johto, `levels_r9/levels_r9pin`
   for Kanto; at most about 150 unit names a run):
   ```
   flock .terrain.lock docker run --rm -m 1g -u $(id -u):$(id -g) -v /home/claude/kantobridge/tools:/tools:ro \
     -v /home/claude/kb-hub/work/landscape:/w -v /home/claude/kb-hub/data/images/maps:/maps:ro kb-pyimg:1 \
     python /tools/landscape_sections/package.py /w/regions /w/marks /maps/<game> /w/models /w/rollout/spots_units.json \
     --levels /w/<levels dir> <MAP>__u0 <MAP>__u1 ...
   ```
   **Check:** each unit's `W/models/<MAP>__u<k>/package.json` has a non-empty `"model"` and `"levels"`.

## 4. Modelling (about 18 workflows, 5 at a time, about 8 to 12 hours)

1. `python3 R/make_args.py model auto` writes `W/rollout/model_run1_args.json` .. (about 10 batches each; a batch is up
   to 6 units of one map).
2. Launch them 5 at a time with `scriptPath: "/home/claude/kantobridge/tools/landscape_sections/rollout/model-units.js"`
   and each run's array, the next as one finishes. Note the run ids in memory. Agents write `specs/<uK>_<what>.json`, `out/` pictures
   and `notes.md` in their units' directories.
3. When all end: every unit needs at least one `specs/*.json` and an `out/scene_game.png`. Units without: one more
   workflow with only their batches. Still without: leave them (the map keeps today's rock there) and list them.
4. Look at 5 random units' `out/scene_game.png` against their `drawing.png` yourself: if most models look wrong (not
   like the drawing, or floating), stop and report to the user with those pictures before going on.

## 5. The models table (local)

1. All the models (the pilot's spots and the units) into one table:
   ```
   flock .terrain.lock docker run --rm -m 1g -u $(id -u):$(id -g) -v /home/claude/kantobridge/tools:/tools:ro \
     -v /home/claude/kb-hub/work/landscape:/w kb-pyimg:1 python /tools/landscape_sections/models_table.py /w/models /w/rollout/models_rollout.json
   ```
2. The game's table: v13 with these models:
   ```
   python3 -c "import json; W='/home/claude/kb-hub/work/landscape'; t=json.load(open(W+'/models_table_v13.json')); \
     t['maps']=json.load(open(W+'/rollout/models_rollout.json'))['maps']; json.dump(t, open(W+'/models_table_v14.json','w'), separators=(',',':'))"
   ```
   **Check:** `models_table_v14.json` keeps v13's `pins` and `slopes` unchanged, its `maps` holds the 8 pilot maps and the
   rollout's, and no map has two models of one name.

## 6. Quads and the build (pod, about 1 hour)

1. Start the 8-vCPU pod, `kb-pod host`, `kb-pod prep`, the local link (phase 1), `scp` `models_table_v14.json` to
   `/workspace/videos/landscape/`, then `tools/landscape_sections/pod/prep_pictures.sh` on the pod (copy it over).
2. The quads per game (counts only):
   `./gradlew test --tests "dev.kantobridge.builder.shape.LandscapeModelsQuadsTest" "-Pkb.landscapeQuads=$E/<game>;/workspace/videos/landscape/models_table_v14.json;/workspace/videos/landscape/quads_<game>.json"`.
   Note the models over the 96-quad budget in a block (the gate is lifted, so they still build; report them).
3. The build of all three regions into a new world:
   ```
   ssh kb-dev 'cd /root/kantobridge && (TABLE=/workspace/videos/landscape/models_table_v14.json OUTDOOR=all CHAIN=1 \
     REGIONS="kanto hoenn johto" WORLD=LandRollout setsid nohup tools/landscape_sections/pod/levels_build.sh > /root/build_rollout.log 2>&1 < /dev/null &)'
   ```
   It ends `ALL DONE`; each region must say `build OK`. Warnings to expect (they were there before): Kanto 0, Hoenn 7
   (`layout_edge_mismatch` 6, `layout_overlap` 1), Johto 3 (`layout_edge_mismatch` 2, `object_out_of_bounds` 1)
   (`/workspace/videos/landscape/logs/levels_report_<region>.json`). Anything else: read
   `/workspace/videos/landscape/logs/levels_<region>.log` and report it.

## 7. Pictures and sheets (pod, then local)

1. `python3 R/pick_spots.py 5 W/rollout/spots_rollout` (5 spots a game, each before a big rock unit, facing north),
   copy that folder to `/workspace/videos/landscape/spots_rollout/`.
2. Before and after, the same spots:
   `SPOTS=/workspace/videos/landscape/spots_rollout OUT=/workspace/videos/landscape/shots_rollout_before WORLD=LandLevels tools/landscape_sections/pod/levels_shoot.sh`,
   then the same with `OUT=.../shots_rollout_after WORLD=LandRollout` (each ends `SHOTS DONE`).
3. Fetch both into `W/shots_rollout_before` and `W/shots_rollout_after`; **stop the pod**.
4. The sheets (local):
   ```
   flock .terrain.lock docker run --rm -m 1g -u $(id -u):$(id -g) -v /home/claude/kantobridge/tools:/tools:ro \
     -v /home/claude/kb-hub/work/landscape:/w kb-pyimg:1 python /tools/landscape_sections/levels_sheets.py /w/sheets_rollout \
     /w/shots_rollout_after "Before: levels, no rock models=/w/shots_rollout_before" "After: rock models=/w/shots_rollout_after"
   ```
   Look at every sheet. A spot showing a dialogue (a trainer saw the player) spoils itself and the next ones: drop it
   from `spots_rollout` and retake the rest. Send the good sheets to the user with one line each.

## 8. Record

- `LANDSCAPE_MODELS.md`: a "Rollout" section (maps marked, units, models, quads over budget, builds, what was left out);
  `LANDSCAPE_SECTIONS_PLAN.md` step 5's rollout line; a PROGRESS bullet; commit locally with the usual trailer lines.
- Memory: the results and where everything is. **Push only when the user says so.**

## 9. Pods

| Pod | id | Size | Price |
|---|---|---|---|
| kb-dev40 | `etu477fpdx3zam` | cpu3g x8, 32 GB | $0.32/hr |
| kb-dev41 | `mo8dofpgte64ye` | cpu5g x8, 32 GB | $0.368/hr |
| kb-dev38, 36, 35, 34, 32, 29, 28, 27 | see `list-pods` | cpu3g x8, 32 GB | $0.32/hr |

If every start fails with "not enough free vcpu/memory", check stock (`list-cpu-types` with availability, 8 vCPUs) and
create one: name `kb-devNN`, cloud SECURE, cpu `{id: cpu3g, vcpuCount: 8}`, data centre `EU-RO-1`, image
`eclipse-temurin:21-jdk-noble`, disk 20, ports `["22/tcp"]`, the network volume `jcp4eqtl70` at `/workspace`, env
`PUBLIC_KEY` and the sshd start command copied from kb-dev40's `get-pod` (`entrypoint`, `cmd`, `env`). Note new pods in
the memory file `kantobridge-pod-and-test-rules.md`.

## 10. Size and time

From the pilot: marking a map took one agent 55 to 150 turns; modelling took one agent per spot 90 to 230 turns. The
rollout is about 177 marking agents and, at about 5 units a map in 6-unit batches, about 180 modelling agents: roughly
3 times the four rounds of levels reviews. Wall time: phase 2 about 6 to 8 hours, phase 4 about 8 to 12, the rest about
3. A model builds in 2 to 7 seconds and a unit's scene in about 30 (so the docker lock is not the bottleneck).

## 11. Known pitfalls (from the levels work)

- The kb-hub server stops at a reboot: `cd /home/claude/kb-hub && (setsid nohup python3 server.py > server.log 2>&1 &)`.
- Rebuilding only Kanto into an existing world needs `KEEP=1` (else `--fresh` wipes Hoenn and Johto).
- A sheet's spot inside a trainer's sight starts a battle; its text covers the following pictures.
- A pod's direct ssh port changes at every start: always `kb-pod host` again.
- The levels dump and `units.py`/`package.py` count cells from the map's top-left: x east, y south.
