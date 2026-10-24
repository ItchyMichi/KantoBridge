# Foreign Pokémon: visitors in every hidden game

**Status: proposed, nothing built (2026-10-24).** This plan replaces the user's note "Pokémon and items from outside
Gen 1" (FOREIGN_POKEMON_AND_ITEMS.md, 2026-10-13) and the `FOREIGN_POKEMON_PLAN.md` that note cites, which was never
committed here. The note was Gen 1 only, pinned to `2db4b8b` and Gen1Recomp v0.3.49; this plan covers every game that
can be imported today (Blue, Crystal, Emerald) at Gen1Recomp v0.3.85. MULTI_GAME_PLAN.md §4.9 names where it plugs in
(`onIncompatible: "visitor"`).

Paths: `GB/` = `gen1-bridge/`, `J/` = `fabric-bridge/src/main/java/dev/kantobridge/`. Facts about the games' own
scripts that come from general game knowledge, not from reading code, are marked *(unchecked)*; §11 lists them.

## 0. The idea in one paragraph

Cobblemon owns the Pokémon, every battle and every wild encounter (habitat blocks). The hidden game is there for its
mechanics and its records: the story, healing, trades, the daycare, the bag, field moves, the Pokédex, the Hall of
Fame. So the hidden game does not need to understand a Pokémon to keep a record of it. Every member of Cobblemon's
party gets a record in the hidden party: a Pokémon the game knows goes in as itself, any other Pokémon goes in as a
**visitor**, one stand-in species that carries the Pokémon's essential stats and the field moves it knows. Nothing is
ever written back from a hidden record; the hidden game only reports events (healed, item used, traded, given,
deposited, renamed), as today.

## 1. Today

All three games share one rule, "leave out", in the core (`GB/act/kb_party.lua`):

- `party.sync` (`J/pokemon/PartySync.java:27-97`) sends every Cobblemon member; `kb_party.apply` (`:422-507`) skips a
  species over the region's `compat.maxDex` (Blue 151, Crystal 251, Emerald 386) or one the engine doesn't know, and
  drops moves over `compat.maxMove` (165 / 251 / 354). Warnings `party.non_gen1_species` / `party.non_gen1_moves`
  (named for Gen 1 in every game).
- Evolutions past the followed region's ceiling are vetoed (`J/pokemon/PartyService.java:150,509-530`).
- `onIncompatible` accepts `leave_out` and `visitor` (`protocol/world/region.schema.json:322-350`,
  `J/world/RegionRecord.java:41-60,192-207`), but every region is written `leave_out` and nothing reads `visitor`.

What a left-out Pokémon misses in every game: the party screen and summary, field moves, bag items, the Name Rater, the
daycare, the Hall of Fame, and the hidden game's own battles (an all-foreign team blacks out at once in Blue's Safari
Zone). A gift with Cobblemon's party full waits in the hidden party because the hidden game sees a free slot.

Differences between the games today:

| | Blue | Crystal | Emerald |
|---|---|---|---|
| Held items, Cobblemon → game | none (Gen 1) | sent through the item catalogue (`GB/lib/engine/gen2/party.lua:655,669`) | dropped (`game3/party.lua:536-566`) |
| Held items, game → Cobblemon (gifts) | none | sent (`gen2/party.lua:346`) | dropped (`game3/party.lua:233-251`) |
| Shiny, gender, friendship | — | both ways | dropped both ways |
| Stone / Rare Candy / TM from the hidden bag | forwarded as `party.item` | refused (`gen2/bag.lua:217-260`) | refused (`game3/bag.lua:185-205`) |
| The PC | Cobblemon's | Cobblemon's | the game's own box screen (no `pcPending`/`pcHeld`) |
| GIVE/TAKE inside the hidden game | — | lost at the next sync | probably the same *(unsure)* |
| Tests past the ceiling | `test_lua_party.py:324` | none | none |

## 2. The visitor

### 2.1 What it is

- **One stand-in species per game**, registered by that game's adapter when the game boots (registries freeze when
  loading ends, so it can't be one species per Pokémon). Original art and name, nothing ROM-derived; Q3.
- **A record per party member**, built at each `party.sync` from Cobblemon's data. The hidden save holds only the
  stand-in; the mod's own save (`mod.save`, beside today's `partyUids`) holds the uid → real identity map (species,
  form, Cobblemon uid), so a load always knows which visitor is which.
- **Swapped, never evolved.** A visitor never evolves, levels or learns inside the hidden game. When Cobblemon's
  Pokémon changes (evolves, levels, learns), the next sync rewrites the record; if it becomes a native species the
  visitor record is replaced by a native one with the same uid, and the reverse.
- **Per region.** The same Pokémon can be a visitor in Blue and native in Emerald. Each link decides at each sync from
  its own `compat`.

### 2.2 What it carries

| Field | Why | Default |
|---|---|---|
| uid | record keeping, events back to Cobblemon | always |
| level, experience to the next level | Repel, the summary, level checks in scripts | always |
| HP, max HP, status | healing, the party screen, "is anyone healthy" checks | always |
| the six stats | the summary only; no hidden battle uses them (§3.9) | always (cheap) |
| nickname | the party screen, the Name Rater | always, cut to the game's `compat.nick` |
| field moves it knows | the game's own field-move check (§3.2) | always |
| gender, shiny, friendship, original trainer, Pokérus | only if a script outside battle reads them | Q2 |
| held item, ability, nature | none outside battle (§4) | never |
| other moves | none; combat is Cobblemon's | never |

There are no "label moves" (the old note's 24 renamed moves): a visitor holds only moves the game already has.

### 2.3 Registering the stand-in, per engine

| Engine | Known | To check (FP0) |
|---|---|---|
| gen1 (Blue) | Gen1Recomp documents species registration for mods; its example mod registers three (v0.3.49 refs in the old note) | still true at v0.3.85; that a record's stats stay as set (the engine may recompute stats from base stats on level, PC or load) |
| gs (Crystal) | not documented for mods | does a registered species load, show on the party screen and survive a save? |
| game3 (Emerald) | Gen 3's mod registries are thin; in spike S2 a registered **map** was accepted but never loaded | the same; the risk is highest here |

**Fallback where an engine refuses:** use one of the game's own species as the stand-in, flagged by uid, and keep it
out of the Pokédex, species checks and trades through the adapter. Consequence: it looks like a real Pokémon on that
game's screens, and every script that reads species needs the guard (Q13).

## 3. Each part of handling Pokémon

### 3.1 The party and its records
- The hidden party always has as many members as Cobblemon's. That ends the Safari blackout with an all-foreign team,
  the gift that waits when Cobblemon's party is full (the game now sees it full and sends the gift to the PC), and the
  empty party screen and Hall of Fame.
- Order follows Cobblemon's order, so "the lead" is the same Pokémon in both.
- The HUD shows how many members of each region are visitors (replaces the planned "left out" line).

### 3.2 Field moves
- Cut, Fly, Surf, Strength and Flash are real moves in all three games; Crystal adds Whirlpool, Waterfall, Rock Smash
  and Headbutt; Emerald adds Rock Smash, Waterfall, Dive and Secret Power; Dig, Teleport and Sweet Scent work outside
  battle too *(the exact lists per game unchecked)*.
- A visitor holds the ones its Cobblemon copy knows, so the game's own rule (move + badge) applies unchanged.
- A native Pokémon keeps today's rule (moves within `maxMove`), so it already counts.
- A Cobblemon TM can teach an HM move (Cut and Surf are TM moves for many species in Cobblemon). Whether that counts
  is Q8.

### 3.3 Healing and status
- Pokémon Centers heal both copies (`party.heal`, today).
- Items used from the hidden bag (Potion, Full Heal, Revive, Ether, vitamins) are forwarded to Cobblemon and applied
  with Cobblemon's amounts; the hidden copy follows at the next sync. Today only Blue forwards anything, and only
  stones, Rare Candy and TMs; a Potion heals only the hidden copy and the next sync undoes it (the old note's 2.4.1).
- Whether an item may be used is decided from Cobblemon's state, sent in `party.sync` (EV room, PP Up stages, the TM
  moves its learnset allows, the stones that would evolve it), not from the hidden copy.

### 3.4 Teaching and evolving
- TMs, HMs, stones and Rare Candy from the hidden bag are checked against Cobblemon's learnset and evolutions and
  applied in Cobblemon, for visitors and natives alike, in all three games (today Crystal and Emerald refuse them).
- A TM's forgotten move goes to Cobblemon by move identity, not slot number (the old note's 1.2 bug: a TM used up
  while Cobblemon learned nothing).
- Evolution happens only in Cobblemon. A visitor may always evolve. Whether a native species may evolve into one its
  game lacks (Golbat → Crobat in Blue) is Q9.
- The Link Cable evolves without the evolution test and so gets past the veto (the old note's 2.4.3, unchecked since):
  refuse it on the server when the result breaks the rule.

### 3.5 Trades
- In-game trades ask for a species, so a visitor can't be offered; the game's own species check refuses it.
- Player-to-player trades are Cobblemon's and unchanged.

### 3.6 Daycare and eggs (Crystal, Emerald; Blue's daycare has no eggs)
- Today the hidden daycares hold Pokémon and eggs stay hidden until they hatch into a gift.
- Q10: refuse visitors at the hidden daycare, or move all breeding to Cobblemon. Moving breeding to Cobblemon also
  removes the one ability that matters outside battle (Flame Body / Magma Armor hatch speed, §4).

### 3.7 The PC
- Always Cobblemon's PC. Emerald needs the work Crystal already has (`gen2/bag.lua:262-316`).

### 3.8 The story, the Pokédex and species checks
- The Pokédex counts native species only (`withinDex`, today); a visitor never counts.
- Scripts that check a species (Bill's grandfather and the Unown report in Crystal, Oak's aides in Blue, trade quests)
  see a visitor as "not that species" *(unchecked per script)*. Q11.
- Scripts that check other fields, if Q2 adds them: friendship checkers and the Return/Frustration givers (Crystal,
  Emerald), the Lottery Corner's ID numbers and Mirage Island's personality check (Emerald) *(unchecked)*.
- The Hall of Fame and the trainer card show the whole team, visitors as the stand-in with the real nickname.
- The Name Rater renames visitors too and forwards `party.rename`, as today.

### 3.9 The hidden game's own battles
Combat is Cobblemon's, so these must not run on the hidden copy (today they do, and their damage and experience are
lost at the next sync):

| Game | Battle | Today |
|---|---|---|
| Blue | Safari Zone, Pokémon Tower ghosts | native, first healthy hidden member |
| Crystal | Bug-Catching Contest, catching tutorial, Battle Tower | native, on the shadow party |
| Emerald | Battle Frontier, Steven's partner battle, secret bases, Safari Zone | native (`game3/battle.lua:52-55`) |

Q1: hand each to Cobblemon where it can be, or skip/refuse it. A Safari Zone has no fighting of your own, so it can stay
native as long as the hidden party has a healthy member, which visitors now guarantee.

### 3.10 Saves opened without the bridge, and cartridge export
- Gen1Recomp moves unknown species to a lost-Pokémon box when a save is opened without the mod, and puts them back when
  the mod returns (v0.3.49; unchecked for gs and game3 at v0.3.85). The adapter sweeps visitors out again on load; the
  uid map in `mod.save` rebuilds them.
- Cartridge export drops what has no cartridge index. A save exported for a real cartridge has no visitors. That is
  acceptable: Cobblemon holds the real Pokémon.

## 4. Held items, abilities and natures: not synced

Battles are Cobblemon's and encounters come from habitat blocks, so nearly everything these do never happens in the
hidden game:

- **Held items:** battle effects (Leftovers, berries, Lucky Egg, Amulet Coin) and Cobblemon's own mechanics (Soothe
  Bell, Everstone, trade-evolution items) all run in Cobblemon. Cleanse Tag only changes wild encounters.
- **Abilities (Emerald only):** the effects outside battle are wild-encounter rules (Intimidate, Keen Eye, Static,
  Magnet Pull, Synchronize, Cute Charm, Illuminate, Stench, Sand Veil) or after a battle (Pickup).
- **Natures:** Pokéblock taste in Emerald, only for Contests.
- **Blue** has none of them.

So a visitor carries no held item, a fixed neutral ability and nature (Q6), and Cobblemon never sends items into the
game. Exceptions, each with a decision:

1. **Items in a quest.** Crystal's Kenya (a Spearow carrying Mail to a man on Route 31) checks the held item *(unchecked)*.
   That needs game → Cobblemon only, which Crystal's gifts already do; Emerald needs the same for its gifts. (Q4)
2. **GIVE/TAKE in the hidden party menu.** With no sync the hidden party shows nothing held, and a hidden GIVE would be
   lost. Hide/refuse the menu, or forward GIVE/TAKE to Cobblemon. (Q5)
3. **Hatch speed abilities** (Flame Body, Magma Armor), only if eggs keep hatching in the hidden game. (Q10)
4. **Emerald's Contests:** moves, condition and nature matter there. (Q7)
5. **The hidden game's own battles** (§3.9), gone once Q1 is settled.

Consequence of "game → Cobblemon only": Crystal's current item sync into the game (`gen2/party.lua:655,669`) can be
removed, which also ends Crystal's lost GIVE/TAKE.

## 5. Per-game notes

- **Blue.** The old note's design (stand-in + label moves) is replaced by §2. No held items, abilities or eggs. Native
  battles: Safari Zone (keeps native, §3.9) and the Tower ghosts (Q1).
- **Crystal.** Already the most complete (held items, shiny/gender, friendship both ways, Cobblemon's PC). Work: the
  stand-in, bag items forwarded, the held-item direction (Q4/Q5), the Unown letter (forms are never sent into the game,
  `PartySync.java:96`; Unown is native, so the letter matters), the contest and Battle Tower (Q1).
- **Emerald.** The most work: the stand-in (highest risk, §2.3), Cobblemon's PC, gifts keeping held item, shiny,
  gender and friendship, bag items forwarded, Contests (Q7), the Frontier and Steven's battle (Q1).

## 6. Phases

Each phase is a commit or a few. Feature tests per phase; the full suite at ✔.

| Phase | What | Tests |
|---|---|---|
| **FP0** spike (pod) | per engine: register a stand-in species at boot, put one in the party, open the party screen and summary, level it, save, reload, open without the mod and back; check that set stats survive | spike notes in PROGRESS.md; decides §2.3's fallback per engine |
| **FP1** protocol | `party.sync` members gain `visitor` fields (§2.2: stats, field moves, eligibility lists for §3.3–3.4); `party.item` gains medicine/vitamin/PP kinds and a move identity; warning codes renamed `party.foreign_*`; `compat.nick` read instead of `MAX_NICK` | schema tests, JUnit for PartySync |
| **FP2** core + Blue | `kb_party.apply` visitor path when the region says `visitor`; uid map in `mod.save`; gen1 adapter registers and fills the stand-in; HUD count | lupa: all-foreign team, mixed team, swap on evolution, reload |
| **FP3** Crystal, Emerald | the same in gen2 and game3 (or the fallback) | lupa per engine, incl. species and moves past the ceiling (none today) ✔ |
| **FP4** bag items | forwarding for every item kind in all three games; Cobblemon decides refusals; TM by move identity; Link Cable veto | lupa + GameTests |
| **FP5** hidden battles | Q1's rule per battle in §3.9 | lupa + e2e on the real engines |
| **FP6** story guards | species checks, Pokédex, trades, daycare (Q10, Q11) | lupa; story checks (`tools/e2e/g*_story_check.py`) ✔ |
| **FP7** Emerald parity | Cobblemon's PC; gifts keep item/shiny/gender/friendship; Q4/Q5 in Crystal and Emerald | lupa + GameTests |
| **FP8** e2e | an all-foreign team through each game's opening, a gym, a Center, a field move, a PC, a crossing | e2e on the pod, all three ✔ |

FP0 comes first because its answer can change §2.3 for Crystal and Emerald. FP4 and FP7 don't depend on visitors and
could go first if wanted.

## 7. Questions

Each has a suggested default; the plan proceeds with it unless changed.

| # | Question | Options and their consequences | Suggested |
|---|---|---|---|
| Q1 | The hidden game's own battles (§3.9) | **Hand to Cobblemon:** the most faithful, the most work per battle, and the Frontier's rules (level 50, banned species) must be redone in Cobblemon. **Skip/refuse:** simple, those features are lost (the Frontier is most of Emerald's post-game). **Native with visitor stats:** contradicts "combat is Cobblemon's" and needs real moves | Safari Zones stay native; story battles (Steven, the contest) go to Cobblemon; the Battle Tower and Frontier are refused for now |
| Q2 | Extra fields on a visitor (§2.2) | Each field is cheap to send, but each needs the stand-in record to hold it in that engine. Without friendship, friendship checkers read a fixed value | Friendship and gender; not shiny, OT, Pokérus |
| Q3 | The stand-in's look and name | One original picture everywhere is one asset; one per art style (Gen 1 / 2 / 3) is three | One original picture, "VISITOR", No.999 |
| Q4 | Held items' direction | **Game → Cobblemon only:** quests and gifts keep their items, Crystal's item sync into the game goes. **Both ways:** the hidden game shows real items, needs every item mapped per game and keeps the lost-GIVE problem | Game → Cobblemon only |
| Q5 | The hidden GIVE/TAKE menu | **Hide/refuse:** simple, players use Cobblemon's wheel. **Forward to Cobblemon:** feels native, more code, each game's menu hooked | Hide/refuse |
| Q6 | Abilities and natures | Never synced; a visitor gets a fixed neutral one. Only Contests (Q7) and hatch speed (Q10) notice | Never synced |
| Q7 | Emerald's Contests | **Play natively:** the visitor needs its real moves and condition, which breaks §2.2. **Skip:** Contests and their ribbons are lost. **Natives only:** native Pokémon enter, visitors are refused | Natives only, for now |
| Q8 | HM moves from a Cobblemon TM | **Count (move + badge):** the game's own rule; players can skip HM hunts. **Also need the HM in the bag:** stricter than any game | Count |
| Q9 | A native species evolving past its game's ceiling | **Never:** today's veto. **Always:** it becomes a visitor in that game. **A game rule:** the player decides | A game rule, on by default, now that visitors exist |
| Q10 | The daycare and eggs | **Refuse visitors at the hidden daycare:** eggs only from natives, as today. **All breeding in Cobblemon:** one system, the hidden daycares become storage or are refused | Refuse visitors at the hidden daycare |
| Q11 | Species checks and the Pokédex | Natives only (a visitor is never "that species"), or let a visitor of the right real species count (needs every check hooked) | Natives only |
| Q12 | Same rule in every region? | All regions `visitor`, or per region (`onIncompatible` already exists per region) | Every region |
| Q13 | The fallback stand-in where an engine can't register one | A native species with no story role, chosen per game; it shows as that species on screen | Decide after FP0 |
| Q14 | Where in the roadmap | Before or after the remaining items of SEVERAL_GAMES_FOLLOWUPS.md | After FP0's spike, as its own stage |

## 8. Consequences and risks

- **Engine support is the main risk.** If Crystal or Emerald can't register a species, those games use the fallback
  (Q13) and every species check needs the adapter's guard.
- **Stats recomputed by the engine.** If an engine recomputes stats from base stats on level, PC or load, the
  stand-in's stats need a hook or are shown wrong (display only; no battle reads them).
- **Fewer features, by choice:** with Q1, Q5 and Q7 at their defaults the Battle Tower, the Frontier, Contests for
  visitors and the hidden GIVE/TAKE menu are off.
- **Saves outside the bridge** show visitors in the lost box until the bridge returns.
- **Every story gate keyed to species stays native-only** (trades, Bill's grandfather, Oak's aides): a fully foreign
  team can finish each story but not those side quests.
- **Shared with today's design:** Cobblemon stays the only real copy, so no choice here can lose a Pokémon; the worst
  case is a hidden record that looks wrong until the next sync.

## 9. Independent fixes from the old note

These don't need visitors; status as of `2db4b8b`, to recheck before FP4:

- 2.4.1 Gen 1 medicine, vitamins and PP items used from the hidden bag never reach Cobblemon (FP4).
- 2.4.2 The bag mirror deletes Cobblemon copies of Gen 1 items (marked stacks, VOXEL_WORLD_REPORT A7.3).
- 2.4.3 The Link Cable gets past the evolution veto (FP4).
- 2.4.4 Cobblemon TMs teach HM moves (Q8).

## 10. Not in this plan

- Foreign items in the hidden bag (the old note's 2.3: possible, not worth it; items stay in Minecraft).
- Real pictures of foreign Pokémon on the hidden screens (the old note's F6).
- Level caps or a region-only party (SEVERAL_GAMES_FOLLOWUPS.md).

## 11. Unchecked facts

From game knowledge, to confirm in the engines' scripts before the phase that needs them: the field-move lists per game
(§3.2); which scripts read species, friendship, ID numbers or personality (§3.8); Kenya's held-item check (§4); that
Gen 2 and Gen 3 saves use a lost-Pokémon box at v0.3.85 (§3.10); whether Emerald's GIVE/TAKE is lost like Crystal's
(§1); which battles in §3.9 are complete for each game.
