# Foreign Pokémon: visitors in every hidden game

**Status: proposed with decisions; FP0 done on the pod (2026-10-24, PROGRESS.md "Foreign Pokémon: spike FP0"); FP1
(protocol) done 2026-10-24 (PROGRESS.md "Foreign Pokémon: FP1"); FP2 next (v2.1).** FP0's answers are folded into §3.1, §3.3, §4.12, §4.14, §10 and §14 below. This plan replaces the user's note "Pokémon and
items from outside Gen 1" (FOREIGN_POKEMON_AND_ITEMS.md, 2026-10-13, Gen 1 only, pinned to `2db4b8b` and Gen1Recomp
v0.3.49) and the plan's own first draft (94360f2). It covers every game that can be imported today (Blue, Crystal,
Emerald) at Gen1Recomp v0.3.85 and Cobblemon 1.8.1, and it is written so that a party moves between Kanto, Johto and
Hoenn as if the games had been built to share one. MULTI_GAME_PLAN.md §4.9 names where it plugs in
(`onIncompatible: "visitor"`). Every question of the first draft (Q1–Q14) and every new one (Q15–Q24) has a decision in
§10; the plan proceeds with them unless the user changes one.

Paths: `GB/` = `gen1-bridge/`, `J/` = `fabric-bridge/src/main/java/dev/kantobridge/`, `C/` =
`fabric-bridge/src/client/java/dev/kantobridge/client/`, `R/` = `refs/gen1recomp/` (the engine at the pin, commit
`e50c396`), `E3/` = `R/src/core/game3/`, `P/` = `protocol/`, `T/` = `tools/lua/`. Facts from general game knowledge
that were not read in the engine's code are marked *(unchecked)*; §14 lists them. Facts read in code but not run are
marked *(inference)*.

## 0. The idea in one paragraph

Cobblemon owns the Pokémon, every battle and every wild encounter (habitat blocks). The hidden game is there for its
mechanics and its records: the story, healing, trades, the daycare, the bag, field moves, the Pokédex, the Hall of
Fame. So the hidden game does not need to understand a Pokémon to keep a record of it. Every member of Cobblemon's
party gets a record in the hidden party: a Pokémon the game knows goes in as itself, any other Pokémon goes in as a
**visitor**, one stand-in species per game that carries the Pokémon's name, level, HP, stats, gender and the field
moves it knows. The hidden game's own rules then apply to that record unchanged: a visitor that knows Surf surfs once
the badge is there, a Potion from the hidden bag heals it, the Name Rater renames it, the daycare keeps it, the Hall of
Fame shows it. Nothing is ever written back from a hidden record; the hidden game reports events (healed, item used,
hurt, held, traded, given, deposited, renamed), as today, and Cobblemon decides.

## 1. Today

All three games share one rule, "leave out", in the core (`GB/act/kb_party.lua`):

- `party.sync` (`J/pokemon/PartySync.java:27-97`) sends every Cobblemon member: uid, `give`, national dex, level,
  nickname (cut at 32, not `compat.nick`), Showdown move numbers (compacted, no PP), HP, status (`tox` → `psn`), held
  item (canonical id), shiny, gender, friendship. **The form is always null** (`:96`). A species with no national number
  is dropped from the message (`:50-53`). `kb_party.apply` (`GB/act/kb_party.lua:422-507`) skips a species over the
  region's `compat.maxDex` (Blue 151, Crystal 251, Emerald 386; `GB/lib/kb_games.lua`) or one the engine doesn't know
  (`:431-436`), and drops moves over `compat.maxMove` (165 / 251 / 354; `:439-442`). Warnings
  `party.non_gen1_species` / `party.non_gen1_moves` (named for Gen 1 in every game).
- Evolutions past the **followed** region's ceiling are vetoed (`J/pokemon/PartyService.java:150,509-530`; the stone
  path falls through to `item_no_evolution`, `:388-405`).
- `onIncompatible` accepts `leave_out` and `visitor` (`P/world/region.schema.json:322-350`,
  `J/world/RegionRecord.java:41-60,194-209`); every writer writes `leave_out` (`:356-357`,
  `J/builder/BuildTask.java:525-527`) and nothing reads the value.
- The client fans an untagged `party.sync`/`dex.sync` out to every link (`C/bridge/Links.java:309-330`), so parked
  games receive syncs too.
- Nothing in the HUD says how many members a region leaves out; the count reaches the client only as a forwarded
  `log` warning (`GB/lib/kb_log.lua:97-99`).

What a left-out Pokémon misses in every game: the party screen and summary, field moves, bag items, the Name Rater, the
daycare, the Hall of Fame, and the hidden game's own battles. A gift with Cobblemon's party full waits in the hidden
party because the hidden game sees a free slot. Today's handling differs by game:

| | Blue (`gen1`) | Crystal (`gs`) | Emerald (`game3`) |
|---|---|---|---|
| Record written by | `applyParty`/`updateMon` (`GB/lib/engine/gen1.lua:2653-2766`) | `gen2/party.lua:609-766` | `game3/party.lua:536-690` |
| Stats | computed by the adapter from base stats when species or level change (`gen1.lua:2657-2662`) | recomputed from base stats every sync (`:637-643`) | `Pokemon.applyStats`, the engine's recompute (`:557`) |
| Held items, Cobblemon → game | none (Gen 1) | every sync (`gen2/party.lua:655`) | never written (`game3/party.lua:536-566`) |
| Held items, game → Cobblemon (gifts) | none | sent (`:331-356`) | dropped (`:233-251`) |
| Shiny, gender, friendship | — | both ways (DVs searched to match, `:564-606`) | dropped both ways |
| Stone / Rare Candy / TM from the hidden bag | forwarded as `party.item` (`gen1.lua:3411-3473`) | refused "It won't have any effect." (`gen2/bag.lua:240-259`) | refused (`game3/bag.lua:188-206`) |
| Medicine, vitamins, PP items from the hidden bag | native only; HP/status undone by the next sync, PP and stat exp stay hidden | the same | the same |
| Field moves (`act.field`) | `gen1.lua:4557-4826` | `gen2/fieldmoves.lua:23-98` | **none**: no `fieldAction`, every `act.field` fails (`GB/lib/kb_engine.lua:224`, `GB/act/kb_field.lua:158-160`) |
| The PC | Cobblemon's (`gen1.lua:3671-3754`) | Cobblemon's (`gen2/bag.lua:262-347`) | **the game's own box screen**; hidden deposits are never reconciled *(inference)* |
| GIVE/TAKE inside the hidden game | — | lost at the next sync (`gen2/party.lua:655`) | stays hidden, never reaches Cobblemon |
| Snapshot party | `save.party` | **always empty** (`gen2/boot.lua:370-371`) | `session.party` |
| Native battles announced | safari, ghost, old_man (`gen1.lua:3047-3063`) | contest, old_man, tower (`gen2/battle.lua:73-78`) | safari only; the Frontier, Steven, secret bases, Wally run silently (`game3/battle.lua:52-55,407-414`) |
| Tests past the ceiling | `T/test_lua_party.py:324` | none | a species the fake engine lacks (`T/test_lua_game3_party.py:330`) |

There is no "first healthy member" code anywhere: a native battle uses the hidden party however the engine itself
picks. The first draft's §3.9 said otherwise.

## 2. The rules

Six rules decide every case below. When a case isn't listed, apply them.

- **R1 One record per member, same order, same count.** The hidden party is always Cobblemon's party, member for
  member, in Cobblemon's order. The lead is the same Pokémon in both. Eggs the hidden game holds come after (today's
  rule, `gen2/party.lua:726-760`, `game3/party.lua:655-683`).
- **R2 A visitor is whatever this game can't show as itself.** A species over `compat.maxDex`, a species with no
  national number, or a form the game lacks (Q15). Each link decides at each sync from its own compat, so one Pokémon
  can be a visitor in Blue and native in Emerald.
- **R3 Cobblemon's fields win; the hidden game's own fields persist.** What Cobblemon has (species, level, HP, status,
  moves, nickname, item, friendship, gender, shiny, nature, stats) is rewritten at every sync. What only the hidden game
  has (PP, stat exp/EVs, contest condition and sheen, ribbons, caught data, Pokérus, a held item Cobblemon lacks) is
  kept on the record across syncs, per uid, in the adapter's hidden-only state (§3.5). Nothing is ever lost; the worst
  case is a record that looks wrong until the next sync.
- **R4 Combat is Cobblemon's.** No hidden battle is ever fought with a visitor. Each native battle is handed to
  Cobblemon, kept native because it involves no Pokémon of yours, or refused at its door (§4.12).
- **R5 The game's own rules run on the record.** Field moves, badges, item effects, species checks, the Name Rater's
  OT check, the daycare's compatibility: the adapter never fakes a species or short-circuits a check. It gives the
  record what Cobblemon knows and lets the script decide. A visitor is "not that species" to every species check
  (Q11).
- **R6 The hidden game reports, Cobblemon decides.** Every effect a hidden script has on a Pokémon (an item, a heal,
  poison damage, a haircut, a held item given, a Pokémon taken for a quest) goes out as a message; Cobblemon applies
  it with its own amounts and the next sync brings the result. Messages carry an id where a repeat would double an
  effect.

## 3. The visitor record

### 3.1 What it is

- **One stand-in species per game**, registered by that game's adapter in the mod's entry chunk, before the registries
  freeze (`R/src/mods/Loader.lua:2223-2227`; a write after that raises "content is frozen after load",
  `R/src/mods/Registry.lua:33-40`). Registries freeze once per boot, so it can't be one species per Pokémon. Original art
  and name, nothing ROM-derived (Q3).
- **A record per party member**, built at each `party.sync` from Cobblemon's data (§3.2). The hidden save holds only the
  stand-in; the mod's own save (`mod.save`, beside today's `partyUids`) holds the **identity map** uid → {national dex,
  form, species name}, so a CONTINUE, a hot reload, the HUD and the export sweep always know which visitor is which
  before the next sync arrives.
- **Swapped, never evolved.** A visitor never evolves, levels or learns inside the hidden game (no hidden path gives it
  experience: Rare Candy is forwarded, the daycare's steps are forwarded, hidden battles never run on it). When
  Cobblemon's Pokémon changes, the next sync rewrites the record; if it becomes native the visitor record is replaced
  by a native one with the same uid, and the reverse.
- **Stats pinned.** gs rebuilds stats from base stats whenever the party menu or summary opens and at every battle
  start (`R/src/battle/gen2/Mon.lua:347-363`; `R/src/ui/gen2/PartyMenu.lua:141-145`, `SummaryMenu.lua:293,746,764`,
  `Battle.lua:340-345`); game3 at every save load (`E3/save_mon.lua:44-60`), at `giveMon`, and in battle when a stat
  is missing (`E3/battle/damage.lua:66-75`); gen1 only fills missing stats (`R/src/pokemon/Stats.lua:58-74`) and
  recomputes at level-up, Rare Candy, vitamins, evolution and daycare withdraw. So each adapter wraps its engine's
  recompute (`Mon.refreshStats`, `Pokemon.applyStats`, `Stats.calc`) to return the synced stats for any record with a
  uid: the summary shows Cobblemon's numbers for natives and visitors alike (Q20). **FP0 confirmed it:** without the
  wrapper gs and game3 overwrote the pinned numbers at once; with it the numbers held through the party menu, the
  summary, a save and a CONTINUE (game3 calls `applyStats` on load, so the wrapper covers it; gen1's `Stats.ensure`
  keeps a complete block and needs no wrapper).

### 3.2 What it carries

| Field | Why | Sent by Cobblemon | Decision |
|---|---|---|---|
| uid | record keeping, events back | always (today) | always |
| level | Repel (`R/src/world/OverworldController.lua:4934-4938`), the summary, level checks in scripts (Gen 2's `FindPartyMonAboveLevel`, `R/src/script/gen2/Specials.lua:1278`) | always (today; clamped 1–100) | always |
| experience | the summary's "to next level" only | no | written from the **stand-in's** growth table at the record's level, never Cobblemon's exp, so no engine path ever derives a different level |
| HP, max HP, status | healing, the party screen, "last healthy member" checks (the daycare refuses it, `R/src/core/gen2/Breeding.lua:761-794`; Kenya, `R/src/core/gen2/Mail.lua:394-420`), Safari entry | always (today) | always |
| the six stats | the summary only; no hidden battle reads them (R4) | **new** `stats{hp,atk,def,spa,spd,spe}` | always, pinned (§3.1) |
| nickname / display name | the party screen, the Name Rater | always (today) | always; a visitor with no Cobblemon nickname shows its real species' name (§3.4) |
| field moves it knows | the game's own check: move id in the moveset plus the badge, in all three engines (`R/src/world/FieldDefaults.lua:220-224`, `R/src/world/gen2/FieldMoves.lua:232-261`, `E3/field_moves.lua:313-331`) | all moves (today) | the adapter keeps the ones its game lists (§4.2) |
| gender | the summary; Gen 2's NPC trades check it (`R/src/core/gen2/NpcTrade.lua:111-129`) | always (today) | Crystal, Emerald (Q2) |
| shiny | the summary; one extra palette per stand-in | always (today) | Crystal, Emerald (Q2) |
| friendship | Return/Frustration tutors, happiness checkers (`Specials.lua:1712-1722`), Mania's Shuckie (`:1386-1440`), Gen 3's lead friendship (`E3/scripting/natives_field_rse.lua:883`) | always (today) | Crystal, Emerald (Q2) |
| original trainer name | the summary; quests that give a Pokémon and ask it back (Shuckie: species, `otId`, happiness) | **new** `ot?` only when it isn't the owner | Crystal, Emerald; the trainer *id* stays the player's, so an OT check on id never matches a visitor (Q2) |
| nature | Emerald's summary and Pokéblock taste (`E3/rse/pokeblock.lua:221-223`) | **new** `nature?` | Emerald only, display and taste (Q6) |
| types | the summary's TYPE line; Gen 1 keeps types on the record, Gen 2 and 3 on the species | **new** `types[]` | Blue writes them on the record, mapped (Dark/Steel/Fairy → the game's "???"/Normal); Crystal and Emerald show the stand-in's "???" type (Q3) |
| form | R2: a form the game lacks makes a visitor; Unown's letter in Crystal/Emerald | **new**: `form` is finally sent (`PartySync.java:96`) | always (Q15) |
| machine moves Cobblemon would accept | the hidden TM/HM compatibility check for visitors (§4.4) | **new** `canLearn[]` (move numbers) | always |
| stones that evolve it | the hidden stone check for visitors (§4.5) | **new** `stones[]` (canonical ids) | always |
| held item, ability, Pokérus, trainer id, EVs, IVs, PP | none outside battle (§5), or hidden-only (R3) | item (today) | item where the game has it (§5); the rest never |

There are no "label moves" (the old note's 24 renamed moves): a visitor holds only moves the game already has, and only
its field moves.

### 3.3 Registering the stand-in, per engine

The call is the same everywhere, `mod.content.pokemon:register(id, record)` (the worked example
`R/mods/examples/example_mini_conversion/main.lua:33-75` registers three original species with `cries:register` and
`icons:register`). What differs:

| Engine | Required record (`R/src/mods/Schemas.lua`) | Art and name | Dex number | Known risks (FP0 checks them) |
|---|---|---|---|---|
| gen1 (Blue) | `id, name, dex, types, baseStats{hp,attack,defense,speed,special}, catchRate, baseExp, level1Moves, growthRate, learnset, evolutions, spriteFront, spriteBack, frontSize` (`:1732-1768`); `index` optional | sprites through `Sprites.path` (`R/src/pokemon/Sprites.lua:24-60`), icon through `icons` or the `pokemon.icon` hook (`:114-140`); the Hall of Fame draws `def.name` with kind `hof` (`R/src/ui/HallOfFame.lua:157-164`) | `dex` above `constants.dexSize` is never listed (`R/src/ui/PokedexMenu.lua:55-99`); the summary prints it `%03d` (`SummaryMenu.lua:175`): use 999 | no `index` → a cartridge export refuses the whole save (`R/src/save_convert/GenSave.lua:664-669`): the export sweep (§4.14) |
| gs (Crystal) | `id, name, dex, types, baseStats` (six), `catchRate, baseExp, growthRate, levelMoves, evolutions, spriteFront, spriteBack, picSize` (`:1782-1824`); `eggGroups, genderRatio, cry` optional | the party icon is `icons.species[species]` (`R/src/ui/gen2/PartyMenu.lua:788-792`), the summary pic `def.spriteFront` (`SummaryMenu.lua:1039-1050`); the name is the record's own `mon.name` | the Pokédex walks `gen2Pokedex.entries`, which no registry writes: never listed; summary and Hall of Fame print `def.dex` (`SummaryMenu.lua:474`, `HallOfFame.lua:158`): 999 | only a schema test exists upstream (`R/tests/engine/gate_gen2_mod_api.lua:276-291`); whether a registered species loads, draws and survives a save is **unverified**; no `index` means `checkpoke` never matches it (`R/src/script/gen2/Vm.lua:632-638`), which is what we want |
| game3 (Emerald) | `id, name, types, baseStats` (six), `catchRate, baseExp, growthRate, learnset, evolutions, spriteFront, spriteBack` (`:1829-1857`); **`index` (1..1023) is required in practice**: without a number `monWrite` leaves the record in `modRecords` and nothing reads it (`:1366-1385`); 412 is EGG and 413+ the Unown letters (`E3/pokemon.lua:537,1432-1439`), so 440 upward *(inference)* | the name comes from the names table `writeMon` fills; front/back overrides resolve through `Pokemon._byName`, built only in `Pokemon.install` (`:177`), so a new name probably never resolves *(inference)*: draw through the `pokemon.sprite` and `pokemon.icon` hooks, which pass the numeric species (`R/src/mods/Gen3Compat.lua:2095-2109,2168-2182`); a missing pic falls back to nil without crashing (`E3/pokemon.lua:1545-1565,1627-1650`) | no national number (`writeMon` never writes the national table, `E3/pokemon.lua:293-301`): not in any dex list; the summary prints "000" (`R/src/ui/game3/summary_menu.lua:660-675`), the Hall of Fame the index or "???" (`hall_of_fame.lua:526-532`) | the merge is re-applied on every species-pack reload (`Gen3Compat.lua:300-357`); spike S2's registered map never loaded (PROGRESS.md "Several games: spikes"), so a registered species is the single biggest unknown |

**FP0 (2026-10-24, all three engines on the pod's imports):** the public registration was accepted in every engine and
the stand-in loaded, drew on the party screen, the summary and the Hall of Fame screen, used Cut, saved and came back
from CONTINUE, with the engine's own record builders (`Pokemon.new`, `Mon.new`, `Party.giveMon`). No fallback route
is needed. Details: gen1's `dex` 999 stays off the list (`dexSize` 151) and its sprite path and icon resolve; gs's
record gets no `index` (so `checkpoke` never matches) and its Pokédex ignores it; game3's `index` 440 resolves by
`Pokemon.name` (not by `speciesFromName`, as inferred), has no national number, prints "000" on the summary, and its
art comes only through the `pokemon.sprite`/`pokemon.icon` hooks (hit on every draw). Icons: gen1 takes the
`{image, frames}` record; **gs wants a sheet registered under an `ICON_*` id with `image, width, height, frames`
(`Schemas.lua:2892-2960`) and the species mapped to that name** (the mapping was accepted; the sheet record is still to
be registered in that shape); game3 through the hook. Extra fields on a record (the pin marker) survived every
engine's save and load, so hidden-only state (§3.5) can live on the record itself in all three.

Every engine: the stand-in's `evolutions` empty, `learnset` empty, `tmhm` empty (the sync's `canLearn` list replaces
it, §4.4), `eggGroups` undiscovered (Q10), `genderRatio` "either", catch rate 0, a neutral growth rate, a silent or
generic cry, a fixed harmless ability on game3 (Q6). **Never set a Pokédex flag for it:** every engine counts dex keys
(`R/src/script/Commands.lua:213-220` Oak's aides, `R/src/core/gen2/Save.lua:1007`, `E3/dex.lua:284-327`). The game3
adapter already builds fresh records on a scratch session so `giveMon`'s own dex writes never touch the live dex
(`game3/party.lua:569-581`; `E3/party.lua:278-286`).

`engine_internals` is only an attribution warning (`Loader.lua:85-90`); the freeze blocks `mod.content`, not the
engine's tables. If a public registration fails on gs or game3, the adapter may write the species tables directly
(inside `lib/engine/**`, CLAUDE.md), redone from `Pokemon.onReload` on game3 (`E3/pokemon.lua:188-213`). That is FP0's
second route before the fallback.

**Fallback where an engine refuses both routes: `leave_out`,** today's behaviour, reported in `hello.ok` so the HUD
says so (Q13). Not a borrowed native species: that would pass `checkpoke`, NPC trades and the Pokédex as a real
Pokémon and need a guard in every script.

### 3.4 The display name

A hidden game shows a Pokémon's nickname everywhere, and the species name when there is none. A visitor with no
Cobblemon nickname would read "VISITOR" six times on the party screen. So the record's name is, in this order: the
Cobblemon nickname; else the real species' display name in the game's case (upper case in all three), through
`Party.engineNick` (control characters out, the game's charset, `compat.nick` glyphs: `GB/act/kb_party.lua:161-180`,
which stops reading `MAX_NICK` and reads `compat.nick`). The Name Rater then works as it does: a name typed there goes
out as `party.rename`; a name cleared there clears Cobblemon's nickname and the record falls back to the species name
at the next sync. `watchNicks` (`:600-611`) compares against what the adapter wrote, so the fallback name is never
reported as a rename.

### 3.5 Hidden-only state (R3)

Each adapter keeps, per uid, in `mod.save` (`"uidState"`), the fields the hidden game writes to a record that Cobblemon
has no home for, and merges them back after every rebuild: PP and PP Ups (today already kept in place:
`gen1.lua:2681`, `gen2/party.lua:622-636`, `game3/party.lua:544-556`), stat exp / EVs, Gen 2's caught data and Pokérus,
Gen 3's contest condition, sheen and ribbons, and a held item the catalogue can't name for Cobblemon (Mail, §5).
Cobblemon's field always wins where both exist: a held item from Cobblemon replaces a hidden-only item, which goes
back to the hidden bag (Mail to the mailbox). The state of a uid that leaves Cobblemon's party and PC for good (released,
traded to another player) is dropped after 30 days.

## 4. Each part of handling Pokémon

### 4.1 The party and its order
- The hidden party always has as many members as Cobblemon's (R1). That ends the gift that waits when Cobblemon's
  party is full (the game now sees it full and sends the gift to the PC, which is Cobblemon's), the empty party screen
  and Hall of Fame, and the all-visitor Safari that blacked out at once.
- **A SWITCH in the hidden party menu** is reported as `party.order{uids[]}` (new, G→M) and Cobblemon reorders its
  party to match (Q16); until the sync confirms it the adapter keeps the new order. The same for Gen 2's and 3's party
  screens. Today a hidden reorder is undone by the next sync *(inference)*.
- **Dex 0.** A Cobblemon species with no national number (a custom species) is no longer dropped from `party.sync`
  (`PartySync.java:50-53`): it goes out as `dex: 0` and is a visitor in every game (Q21).
- The HUD shows, per region, how many members are visitors, from `game.status.visitors` (new, §8); it replaces the
  planned "left out" line (MULTI_GAME_PLAN §4.9).
- Crystal's snapshot party is filled (`gen2/boot.lua:370-371` builds it empty) so `partySize`/`partyKnown` and the
  e2e checks read alike in all three games.

### 4.2 Field moves
- The game's own rule, "the move id is in the moveset, plus the badge", applies in all three engines; nothing checks
  the learnset or where the move came from, so a Cut taught by a Cobblemon TM counts (Q8; `R/src/ui/PartyMenu.lua:861-887`,
  `R/src/world/gen2/FieldMoves.lua:110-124,587-594`, `E3/field_moves.lua:21-37,131-139`).
- The lists, as coded: **Blue** FLY, FLASH, CUT, SURF, STRENGTH, SOFTBOILED, TELEPORT, DIG. **Crystal** CUT, FLY, SURF,
  STRENGTH, FLASH, WATERFALL, WHIRLPOOL, DIG, TELEPORT, SOFTBOILED, HEADBUTT, ROCK_SMASH, MILK_DRINK, SWEET_SCENT
  (`R/src/ui/gen2/PartyMenu.lua:66-73`). **Emerald** CUT, FLY, SURF, STRENGTH, FLASH, ROCK_SMASH, WATERFALL, DIVE, DIG,
  TELEPORT, SOFTBOILED, MILK_DRINK, SWEET_SCENT, HEADBUTT (no menu handler), SECRET_POWER (`E3/field_moves.lua:21-37,786-802`).
- A visitor's record holds exactly the moves from its game's list that its Cobblemon copy knows (at most 4 anyway). A
  native keeps today's rule (every move within `maxMove`).
- **Emerald has no field actions at all today** (`GB/lib/kb_engine.lua:224`): `act.field` for cut, surf, strength,
  flash, rock_smash, waterfall, dive, dig, teleport, fly, bicycle, fish and sweet_scent must be written for the game3
  adapter (`E3/field_moves.lua:313-331` `partyMoveUser`; `fly` through the region map; `dive` new in `act.field`).
  Without it a Hoenn visitor can't use Surf no matter what this plan does. It is FP7's first item and is useful on its
  own.
- A field move acts in the followed region only; Fly never crosses regions (the pass is the only crossing,
  PROTOCOL.md "Crossings").
- The old HM lock ("an HM move can't be forgotten") is not re-created: Cobblemon's summary can bench any move (P13).
  Forgetting Surf while on the water changes nothing in the hidden game (its surf state is the map's).

### 4.3 Healing, status and damage outside battle
- Pokémon Centers, Mom, the lab, the Hall of Fame and a blackout heal both copies (`party.heal`, today).
- **Poison on the overworld.** All three engines hurt a poisoned member every few steps and black out when nothing
  healthy is left *(the exact rules per engine unchecked)*. Today the hidden copy loses the HP and the next sync gives it
  back, and an all-hidden faint blacks out the real game. With R6 the hidden step's damage goes out as
  `party.change{uid, hp}` (new, G→M, §8) and Cobblemon sets the HP; the blackout, when it comes, is the engine's own
  and already reaches Minecraft (Q17).
- Friendship changes a hidden script makes (Goldenrod's haircut brothers `Specials.lua:1847-1926`, Slateport's massage
  *(unchecked)*, a level-up or an item in the hidden game) go out the same way: `party.change{uid, friendship}`.
- Items used from the hidden bag follow §4.4.

### 4.4 Items used on a party member from the hidden bag
One path for every item kind, in all three games (today only Blue forwards anything, and only stones, Rare Candy and
TMs; a Potion heals only the hidden copy and the next sync undoes it):

- **Who decides.** The hidden game's own "it won't have any effect" check runs on the record, which mirrors
  Cobblemon's HP and status as of the last sync (syncs are debounced 10 ticks and never sent in a battle, and nothing
  changes a Pokémon's HP in Cobblemon while the player is in the hidden bag menu), so it is current enough. No
  acknowledgement round trip (Q19). The item is consumed natively, the native effect runs on the record too (harmless:
  the sync rewrites it), and `party.item` tells Cobblemon what to apply with its own amounts.
- **`party.item` gains an `id`** (idempotent, re-sent after snapshots until a sync follows, like gifts) and a `kind`:
  `stone`, `machine`, `candy`, `medicine` (HP, status, Revive), `pp` (Ether, Elixir, PP Up, PP Max), `vitamin` (EVs),
  `other` (anything new: Cobblemon warns, the hidden effect stands).
- **Refusals reach the game.** Today a refused `party.item` is a counted server warning and nothing else
  (`PartyService.java:358-385`). Each refusal goes back as `act.result{kind:"item", ok:false, reason}` so the
  adapter can log it against the id; the next sync is forced (`markDirty`) so the record stops showing an effect
  Cobblemon didn't apply.
- **Machines.** The hidden compatibility check (`R/src/inventory/ItemEffects.lua` for gen1, `Game2:usePartyItem`,
  `E3/item_use.lua`) is answered, for every record with a uid, from the sync's `canLearn[]`: the TM/HM moves
  Cobblemon's learnset allows for that Pokémon, filtered by the adapter to the machines its game has. One rule for
  natives and visitors (today Crystal and Emerald refuse machines outright). The forget prompt stays the game's; the
  message carries the move taught and **the move forgotten** (`forget`, by move number) instead of a slot: the
  hidden slot and the Cobblemon slot don't match once a left-out move was compacted (`PartySync.java:67-74`), the
  old note's 1.2 bug.
- **Stones** are answered from `stones[]`: the canonical ids of the stones that would evolve this Pokémon in Cobblemon
  (with the veto of §4.5 applied). Cobblemon evolves it at once (`forceEvolve`, today).
- **Rare Candy** at level 100 is refused by the game's own level check on the record.
- **Vitamins and PP items** are forwarded; Cobblemon's EV and PP-stage rules decide, a refusal comes back as above.
- **Medicine** is forwarded; the hidden Revive text plays, Cobblemon revives.
- Items used from Cobblemon's side (the Minecraft inventory on a Pokémon) are unchanged: `bag.use` debits the ledger.

### 4.5 Teaching, forgetting and evolving
- **Move Tutors** (Crystal's Goldenrod tutor, Emerald's many *(unchecked)*), the **Move Deleter** and Emerald's **Move
  Reminder** run natively on the record and go out as `party.learn{uid, move, forget?, id}` and
  `party.forget{uid, move, id}` (new, G→M; Q18). The Reminder's list is the record's hidden list; Cobblemon's benched
  moves already allow more from its own summary, so the Reminder teaches nothing Cobblemon can't.
- **Evolution happens only in Cobblemon.** A visitor may always evolve. A native species evolving into one its game
  lacks (Golbat → Crobat in Blue, Eevee → Espeon in Blue, Chansey → Blissey, Onix → Steelix, dozens more with every
  generation Cobblemon has) is a **game rule, on by default** (`evolveBeyond`, in `rules.compat`): it becomes a
  visitor in that game (Q9). Off, today's veto applies (`PartyService.java:509-519`), which uses the followed region's
  ceiling: the rule reads the same record.
- **The Link Cable** (the old note's 2.4.3) does not exist in `P/items.json` or in Java; trade evolutions are Cobblemon's
  own prompt (`GT/PlayerTradeGameTests.java:79`). If an engine or a mod adds a cable item, its evolution runs through
  the same `EVOLUTION_TESTED` veto. Nothing to do unless FP0 finds one.
- Level-ups and evolutions made in the hidden game: there are none left once §4.12 is done (hidden battles never run
  on your Pokémon; the daycare's experience is forwarded as steps, today).

### 4.6 Trades
- In-game trades ask for a species (`R/src/script/Commands.lua:1093-1170`; `NpcTrade.lua:111-129` checks species and
  gender; `E3/scripting/natives_trade.lua:474-478`): a visitor is never offered, the game's own check refuses it (R5).
  The member sent is a take, the one received a gift, as today.
- Player-to-player trades are Cobblemon's; both players' syncs follow (`PartyService.java:488-505`). A Pokémon traded
  from a player in Johto to a player in Kanto is a visitor there at once; nothing else changes.

### 4.7 The daycare and eggs (Crystal, Emerald; Blue's daycare has no eggs)
- A visitor may board (Q10). The daycare keeps the Cobblemon Pokémon on the server as today (`DaycareService.deposit`)
  and the stand-in record in the hidden daycare; the stand-in's egg group is undiscovered, so the compatibility check
  (`R/src/core/gen2/Breeding.lua:315-362`, `E3/breeding.lua:148-181`) never produces an egg with it. Natives breed as
  today. Withdraw forwards the steps as experience (today); the stand-in's level may rise meanwhile in the hidden
  record and is rewritten at the next sync.
- Eggs never cross: the hidden game keeps them, parked in a hidden box when the party is full, and a hatch is a gift
  (`party.give{source:"egg"}`, today). A hatch with Cobblemon's party full goes to Cobblemon's PC (`party.add`).
- Hatch speed reads Flame Body / Magma Armor on the party (`E3/daycare.lua:389-404`): a visitor never speeds hatching
  (abilities aren't synced, §5). Everstone, incense and Light Ball are read from the hidden record's held item
  (`E3/breeding.lua:241-268,344-357,522-531`): natives carry Cobblemon's item (§5), so they work.
- Gen 2's deposit refuses a Pokémon holding Mail (`Breeding.lua:761-794`): the hidden-only item (§3.5) is on the
  record, so the game's own refusal shows.

### 4.8 The PC
- Always Cobblemon's PC. Emerald gets what Crystal has (`gen2/bag.lua:262-347`): the storage rows of Lanette's PC open
  Cobblemon's PC under the `pc.open`/`pc.closed` hold; the item PC and the mailbox stay native. Until then an Emerald
  deposit in the hidden box leaves a stale record there while the next sync recreates the member *(inference)*; FP7.
- The hidden boxes hold only eggs (parked, §4.7) and, in Gen 1, nothing: gifts and catches that land in a box are taken
  out (today). The engine's "box full" checks therefore never fire for your Pokémon; `box.count` stays informational.

### 4.9 The story and species checks
- **Species checks see a visitor as "not that species"** (Q11): Gen 2's `checkpoke` matches on `def.index`, which the
  stand-in lacks (`Vm.lua:632-638`); Bill's grandfather (`Specials.lua:1446-1458`), `FindPartyMonThatSpecies` (`:1278-1317`),
  NPC trades; Gen 3's `GetPartyMonSpecies` (`natives_queries.lua:240`) and `GetTradeSpecies`; Gen 1's trades. A fully
  foreign team finishes every story but not the side quests keyed to a species. Real-species counting ("let a visitor
  of the right species count") would need every one of these hooked and is not done.
- **Quests that take a Pokémon away.** Kenya's delivery removes the Spearow from the party once the Mail matches
  (`Mail.lua:394-420`); Mania takes Shuckie back (`Specials.lua:1386-1440`). Today the removal isn't a trade, so the next
  sync would put the Pokémon back *(inference)*. Each is wrapped and goes out as `party.take{reason:"quest"}` (new
  reason). Only natives can reach these (species checks), but the fix is needed regardless of visitors.
- **Readers of other fields** (Crystal: `FindPartyMonAtLeastThatHappy`, `GetFirstPokemonHappiness`, `CheckPokerus`
  `:1460-1466`, the lucky number by `otId` `:1575-1600`, `CheckMagikarpLength` `:478-495`; Emerald: the Lottery
  Corner by `otId` `E3/rse/lottery.lua:56-110`, Mirage Island by personality `natives_field_rse.lua:664-673`, the lead's
  friendship): they read the record's values. Friendship is Cobblemon's; `otId` and personality are the ones the
  adapter stamped (the player's id; a random personality kept across syncs through the hidden-only state), so the
  lottery always sees your own id, which is the common case anyway.
- **The Pokédex** counts native species only (`withinDex`, today); a visitor never counts, the stand-in never gets a
  flag (§3.3).
- **The Hall of Fame and the trainer card** show the whole team, visitors as the stand-in with their display name. Gen
  1's save validation sets an unknown Hall of Fame species to nil permanently when the mod is absent
  (`R/src/core/SaveData.lua:3114-3127`); see §4.14.
- **The Name Rater** renames visitors too and forwards `party.rename` (today); Gen 2's and 3's OT check reads the
  record's `otId`, the player's, so every synced Pokémon is renamable, including ones Cobblemon says were traded.
  Accepted.
- **Repel** reads the lead's level: the record's. The ghost of Pokémon Tower reads the Silph Scope, not the party.

### 4.10 Abilities read outside battle (Emerald)
Hyper Cutter on Cut (`E3/field.lua:1155`), Lightning Rod in Match Call (`rse/match_call.lua:193`), Swarm in ambient
cries, the wild-encounter abilities (`E3/encounter_rules/rse.lua:106-206`) and hatch speed: every one is cosmetic or
about wild encounters, which are Cobblemon's. Natives carry the ability the adapter's `giveMon` rolled; visitors a
fixed one. Nothing is synced (Q6).

### 4.11 Contests (Emerald)
Contests are a scene, not a battle (`E3/scripting/natives_contest.lua:98`), reading contest stats, sheen, moves and
personality (`E3/rse/contest.lua:330-347`). Natives enter as today and their condition, sheen and ribbons persist as
hidden-only state (§3.5), so Pokéblocks are no longer wasted by the next sync. Visitors are refused at the entry menu
by the adapter (they have at most four field moves and no condition; a record with no moves in an appeal is an
unknown) with the game's own "can't enter" line *(the menu seam unchecked)*. Q7: natives only, for now.

### 4.12 The hidden game's own battles (R4)
Today each runs natively on whatever the hidden party holds, its damage and experience lost at the next sync; with
visitors in the party it must not run on them. Per battle:

| Game | Battle | Involves your Pokémon? | Decision |
|---|---|---|---|
| Blue | Safari Zone (`OverworldController.lua:4925-4958`) | no fighting; the game needs a party | **native**, as today; visitors make the party non-empty |
| Blue | Pokémon Tower ghost without the Silph Scope (`:4946-4951`) | your lead is shown and can't fight | **native**; the stand-in needs a back sprite |
| Blue | the old man's demo | his Pokémon | native |
| Crystal | Bug-Catching Contest (`World.lua:4675,5729`; the party is masked to the lead, `BugContest.lua:632-660`) | yes, the lead only | **Cobblemon**: each contest encounter is a held wild battle with `battle.start.use:[lead uid]` (new field: the uids Cobblemon may use); the catch result feeds the contest's score (`BugContest.lua:403`); the 20-minute clock and judging stay native |
| Crystal | the catch tutorial (`World.lua:7271-7300`, an empty party) | no | native (`old_man`) |
| Crystal | Battle Tower (`World.lua:3950`; rules `BattleTower.lua:160-275`) | yes, three chosen at a level cap | **refused at the desk** for now, with a HUD line; FP9 sketches it in Cobblemon |
| Emerald | Safari Zone (`E3/battle_bridge.lua:27-38`) | no fighting | native, as today |
| Emerald | Steven's partner battle at the Space Center (Steven's mons appended to `session.party`, `natives_frontier_story.lua:258-287`) | yes, a multi battle, on the story's path *(that it is mandatory is unchecked)* | **Cobblemon**, as a held multi battle: `battle.start{format:"multi", ally{team}}` (new fields). Cobblemon 1.8.1 has `BattleTypes.MULTI` (two actors a side, one slot each; `common/.../battles/BattleTypes.kt`); whether the server can seat an NPC actor beside the player is FP5's first check; if not, a double battle with your first two Pokémon against the two foes |
| Emerald | Battle Frontier, Battle Tent, Trainer Hill (`NATIVE_OPTS`, `game3/battle.lua:52-55`; bans from an imported pack, `E3/rse/frontier/util.lua:423-528`, so a visitor would count as eligible) | yes, with rules and rentals | **refused at each desk** for now (the Frontier is most of Emerald's post-game: FP9) |
| Emerald | secret-base battles, link, Union Room, e-Reader, spectate | recorded or linked teams | refused / never reachable |
| Emerald | Wally's tutorial, the Pokédude | their Pokémon | native |

A refused desk shows the game's own "you can't enter" style line through the adapter and a HUD note naming the feature
and this plan (Q1). Every native battle is announced with `battle.native` so the client holds (`game3/battle.lua:407-414`
reports only Safari today: FP5 adds the rest).

### 4.13 Trainer and wild battles
Unchanged: every trainer, scripted, static, surf and fish battle is held for Cobblemon, which fights with the real
Pokémon, visitors included. The foe's team is always native. `Battle.teamMon` still declines a foe team over the
ceilings to a native battle (`GB/act/kb_battle.lua:128-152`), which can't happen for an imported game's own trainers.

### 4.14 Saves opened without the bridge, and cartridge export
- **Blue:** Gen 1's `SaveData.validate` (`R/src/core/SaveData.lua:3067-3135`, run at `Game.lua:1556`) moves unknown
  species in the party, boxes and daycare to the LOST box (`save.orphaned.mons`), deletes unknown dex ids, sets an
  unknown Hall of Fame species to nil for good, and shows a `QuarantineReport`. **FP0:** with the mod off the
  visitor went to the LOST box (party 0, orphaned 1, the report on screen, no crash); with the mod back it was
  **in the party again** at its old slot with its stats and marker, nothing in a box (`reclaim` fills the party
  first). So nothing to sweep in the common case; the adapter still checks the boxes on load for a full-party
  reclaim. A Hall of Fame entry lost this way is lost; the adapter writes a copy of each Hall of Fame team into
  `mod.save` so it can be restored *(FP6)*.
- **Crystal and Emerald have no such pass** (`R/src/core/gen2/Save.lua:842-868`; `E3/save_mon.lua:48`). **FP0:** with
  the mod off, both kept the record in the party and **drew the party screen, the summary and the Hall of Fame
  screen without an error** (gs shows the stored `mon.name`; game3's screens tolerate the missing name, though
  `Pokemon.name(440)` itself raises "no ROM species name"); with the mod back, everything was as saved. So opening
  such a save alone is safe; the earlier inference of a crash is withdrawn, and Q24's marker file is not needed.
- **Cartridge export** (gen1 `GenSave.lua:664-669`, gs `Gen2Save.lua:1080-1087`) refuses the whole save when a species has
  no index; game3 writes an internal index raw (`Gen3Save.lua:1602-1606`). **FP0:** gen1's converter refused with
  "no Gen 1 species index" as read; gs's returned without raising (its refusals come back as a value through
  `guarded`, `Gen2Save.lua:48-54`, which the spike didn't capture); game3's `exportPort` returned a buffer with the
  raw index. The adapter wraps each converter: visitors
  are left out of the party, boxes and daycare for the export only, and Hall of Fame entries with the stand-in are
  skipped (gen1 skips them itself, `:1841-1849`; gs *(unchecked)*; game3 would write index 440+ raw: skipped too). A
  save exported for a real cartridge has no visitors. That is acceptable: Cobblemon holds the real Pokémon.

### 4.15 Parked games and crossings
- Every link receives every `party.sync` (`Links.java:309-330`), so a parked game's shadow party is rebuilt while
  parked, visitors included, whenever `partySafe` allows under its hold (a park is a hold state over the overworld,
  PROTOCOL.md "Region rules"); otherwise at resume. Crossing a pass therefore shows the right party at once.
- A first start (`party.stash`, D5) moves every member into Cobblemon's PC before NEW GAME; after the starter, the
  player withdraws them at the first Center's PC, where each one that the new game can't show is a visitor. Nothing
  about the stash changes.
- A region's `rules.compat.onIncompatible` is sent to the link at bind (`session.bind.incompatible`, new, optional,
  default `visitor`), so a world can keep a region at `leave_out` (Q12). `hello.ok.compat.visitor` (new) reports
  whether the stand-in registered; the server stores the effective value per region and the HUD shows it.

## 5. Held items, abilities and natures

Battles are Cobblemon's and encounters come from habitat blocks, so nearly everything these do never happens in the
hidden game:

- **Held items:** battle effects (Leftovers, berries, Lucky Egg, Amulet Coin) and Cobblemon's own mechanics (Soothe
  Bell, Everstone, trade-evolution items) all run in Cobblemon. Outside battle the hidden games read a held item in
  quests (Kenya's Mail), the daycare (Everstone, incense, Light Ball, §4.7) and Mail itself.
- **Abilities (Emerald only):** §4.10. **Natures:** Pokéblock taste only.

Decisions:

1. **Direction: both ways through the catalogue, where the game has the item** (Q4). This is Crystal's rule today
   (`gen2/party.lua:655`, gifts `:331-356`) extended to Emerald's sync and gifts (`P/items.json` needs `symbols.gen3`
   on its 48 `held` items, which have gen2 symbols only today). Blue has no held items. A Cobblemon item the game
   lacks shows as nothing held; a hidden GIVE on a member that really holds something invisible is refused by the
   adapter with the game's "it's already holding an item" line.
2. **GIVE/TAKE in the hidden party menu is forwarded** (Q5): `party.hold{uid, item|null, id}` (new, G→M). Cobblemon
   sets the held item from the ledger's mirror when it has the item (the hidden bag already removed it; the ledger
   diff reads that back, MG6); a TAKE puts it in the hidden bag and clears Cobblemon's. Until the sync confirms, the
   adapter keeps the hidden choice. Today Crystal's GIVE is lost at the next sync and Emerald's never reaches
   Cobblemon.
3. **Items Cobblemon can't hold** (Mail; anything the catalogue maps to no Cobblemon item) are hidden-only state per
   uid (§3.5): the record keeps them across syncs, Mail's quests and the mailbox work, nothing goes to Cobblemon.
4. **Abilities and natures are never synced** (Q6): a visitor gets the stand-in's fixed ability; Emerald records carry
   Cobblemon's nature for the summary and Pokéblock taste (`nature?` in the sync).
5. **Hatch speed** from a visitor's ability: none (§4.7).

## 6. Per-game notes

- **Blue.** The old note's design (stand-in + label moves) is replaced by §3. No held items, abilities or eggs. Native
  battles: Safari and the ghost stay native (§4.12). Blue's own daycare (one slot, no eggs) takes visitors. New work
  beyond the core: the stand-in (front, back, icon), types on the record, the export sweep, the LOST-box sweep, the
  Hall of Fame copy.
- **Crystal.** Already the most complete (held items, shiny/gender, friendship both ways, Cobblemon's PC, field moves).
  Work: the stand-in (sprite, icon sheet, shiny palette, `dex` 999, no `index`), the snapshot party, the Bug-Catching
  Contest in Cobblemon with `use`, the Battle Tower desk, Kenya and Shuckie as quest takes, GIVE/TAKE forwarded, the
  haircut brothers as `party.change`, Mail as hidden-only state, the Move Tutor and Deleter, the Unown letter (forms
  are now sent, `PartySync.java:96`), the no-mod warning.
- **Emerald.** The most work, half of it owed regardless of visitors: **field actions** (none today), **Cobblemon's PC**,
  gifts and syncs carrying item/shiny/gender/friendship/nature, `symbols.gen3` for held items, the native battles
  announced, then the stand-in (index 440+, art through hooks, the highest risk), Steven's multi battle, the Frontier
  and Tent desks, contests' hidden-only state and the visitor refusal, the Move Tutors, Deleter and Reminder, the
  massage as `party.change`, the no-mod warning.

### 6.1 Use cases by game

| Use case | Blue | Crystal | Emerald |
|---|---|---|---|
| Six Hoenn Pokémon walk into Kanto | six visitors; Oak's lab rival battle is Cobblemon's; the Safari takes them | — | natives |
| A Kanto team walks into Hoenn | natives (all 151 are within 386) | natives | natives |
| Golbat levels up in Blue | becomes Crobat in Cobblemon (Q9 on) and a visitor in Blue; native in Crystal and Emerald | | |
| Alolan Vulpix (dex 37, a form) | visitor (R2) | visitor | visitor |
| Unown Q from Cobblemon | visitor | native, letter from the form | native |
| Garchomp knows Surf and Cut | surfs and cuts with the badges | the same | the same, once field actions exist |
| A Potion on a visitor from the hidden bag | forwarded, Cobblemon heals | the same | the same |
| TM Earthquake on a visitor | the game's TM; `canLearn` answers; Cobblemon learns, `forget` by move | the same | the same |
| Water Stone on a visitor Eevee-line | `stones` answers; Cobblemon evolves | the same | the same |
| A visitor at the daycare | kept; steps forwarded; no egg | the same | the same; no hatch-speed ability |
| Kenya's Mail | — | the hidden-only item; delivery is a quest take | — |
| GIVE a Leftovers to a visitor | — | forwarded; Cobblemon holds it | forwarded |
| GIVE Mail to a native | — | hidden-only item | hidden-only item |
| A visitor as the Bug-Catching Contest lead | — | fights in Cobblemon alone | — |
| The Battle Tower / Frontier desk | — | refused for now | refused for now |
| Steven's Space Center battle | — | — | Cobblemon multi/double |
| A contest with a visitor | — | — | refused at entry; natives keep their condition |
| The Name Rater on a nickname-less visitor | shows the species name; a typed name becomes the Cobblemon nickname | the same | the same |
| SWITCH in the hidden party menu | `party.order` | the same | the same |
| Poison while walking | `party.change{hp}` | the same | the same |
| Bill's grandfather / NPC trades / Oak's aides | natives only | natives only | natives only |
| Hall of Fame with visitors | drawn as the stand-in with their names; a copy kept in `mod.save` | the same | the same |
| Cartridge export | visitors left out | the same | the same |
| The save opened with the mod off | LOST box, then a PC box, then swept | **don't** (no validation pass) | **don't** |
| A custom Cobblemon species (dex 0) | visitor | visitor | visitor |

## 7. Walkthroughs across regions

1. **Hoenn first, then Kanto.** A player starts in Hoenn (once "starting in Hoenn" exists, SEVERAL_GAMES_FOLLOWUPS) with
   Mudkip and five Hoenn Pokémon, crosses the pass into Kanto. Blue was never started: the stash moves the six into
   the PC, Oak's intro runs, the Bulbasaur gift lands. At Viridian's Center the player withdraws Swampert, Breloom and
   Gardevoir from Cobblemon's PC: in Blue they are visitors (named SWAMPERT, BRELOOM, GARDEVOIR on the party screen),
   Bulbasaur is native. Brock is a Cobblemon battle with all four. Swampert surfs on Route 19 once the Soul Badge is
   there; the Safari lets the party in. Bill's grandfather in Johto later asks for an Oddish: Gardevoir doesn't count.
2. **Kanto first, then Johto and Hoenn.** Everything from Blue is native everywhere; Golbat's evolution into Crobat
   (Q9 on) makes it a visitor only when the player is back in Kanto. Crobat still flies there.
3. **A Potion in Pewter, used in Hoenn.** The ledger projects it into Emerald's bag (MG6); using it on a visitor in
   Emerald's bag menu heals in Cobblemon and debits the ledger through `bag.sync`.
4. **A friend in Johto trades a Houndoom to a player in Kanto.** Cobblemon's trade; the receiver's Blue shows a visitor
   at the next sync; its Crystal shows Houndoom.
5. **Playing Crystal's Bug-Catching Contest with a Hoenn lead.** The contest starts natively; each encounter is a held
   Cobblemon battle with that one Pokémon; a catch counts for the score; the judging is native.
6. **A CONTINUE after a crash with three visitors in the party.** `partyUids` and the identity map come from
   `mod.save`; the records are rebuilt by position; the last sync re-applies (today's `party.reapply`).
7. **Opening Blue alone, mod off, for a look.** The three visitors go to the LOST box with a report; with the mod on
   again they land in a PC box, the adapter sweeps them, the next sync rebuilds the party. The Hall of Fame team that
   held them lost those entries in the hidden save; the `mod.save` copy restores them.

## 8. Protocol changes (protocol stays 1: new types and optional fields, D10)

| Message | Change |
|---|---|
| `party.sync` member | `dex` may be 0 (no national number); `form` sent (Cobblemon's form/aspect id); `stats{hp,atk,def,spa,spd,spe}`; `types[]` (1–2 Showdown type names); `nature?`; `ot?` (only when not the owner); `canLearn[]` (machine move numbers); `stones[]` (canonical ids) |
| `party.item` | `id` (idempotent), `kind`, `forget?` (move number replaced; `slot` kept for old senders) |
| `act.result{kind:"item"}` (S→C, M→G) | the answer to a refused `party.item`: `ok:false, reason` (`unknown_uid`, `no_slot`, `move_known`, `no_evolution`, `max_level`, `no_effect`, `unsupported`) |
| `party.order` (G→M) | `uids[]`: the hidden party's new order |
| `party.change` (G→M) | `uid, id, hp?, status?, friendship?`: an effect the hidden game had on a Pokémon outside items |
| `party.hold` (G→M) | `uid, item|null, id`: GIVE/TAKE in the hidden menu |
| `party.learn` / `party.forget` (G→M) | `uid, move, forget?, id` / `uid, move, id`: tutors, the Deleter, the Reminder |
| `party.take` | `reason` gains `quest` |
| `battle.start` | `use?[uids]` (the player's Pokémon Cobblemon may use: the contest's lead); `format?` (`singles`, `double`, `multi`) and `ally?{name, team[]}` (Steven) |
| `game.status` | `visitors` (how many party members this game shows as visitors) |
| `hello.ok.compat` | `visitor: true|false` (the stand-in registered) |
| `session.bind` | `incompatible?` (`visitor`\|`leave_out`, the region record's value) |
| warnings | `party.non_gen1_*` renamed `party.foreign_species` / `party.foreign_moves` |
| `P/items.json` | `symbols.gen3` on held items and Emerald's own held items; a `hiddenOnly` flag for items no Cobblemon item exists for |
| `P/games.json` / region `rules.compat` | `evolveBeyond` (default true), `onIncompatible` default `visitor` |

## 9. Phases

Each phase is a commit or a few, `FP<n>: <title>`. Feature tests per phase; the full suite (`tools/check`, GameTests)
at ✔. Pods per the standing rules (stop after each job).

| Phase | What | Tests |
|---|---|---|
| **FP0** spike (pod) | **done 2026-10-24** (PROGRESS.md "Foreign Pokémon: spike FP0", ~20 min of pod time): every engine registers and runs the stand-in through the public route; the stats pin works; the mod-off saves are safe; no fallback needed. Left for FP3: gs's icon sheet record; for FP5: Cobblemon's multi battle with an NPC ally | nine runs, all three engines, exit 0 |
| **FP1** protocol | §8 (schemas, examples, `catalog.json`, both stand-ins `tools/fake_gen1`/`fake_minecraft`); `PartySync` sends form, stats, types, nature, ot, canLearn, stones, dex 0; `compat.nick` read by `engineNick` | schema tests; JUnit `PartySync`/`PartyItem`; lupa `validSync` |
| **FP2** core + Blue | `kb_party.apply` visitor path (R1, R2, the display name, the field-move filter); identity map and `uidState` in `mod.save`; the gen1 stand-in (art, types on the record, exp from its growth table, stats pinned); `party.order`; `game.status.visitors` and the HUD line; the LOST-box sweep and the export sweep; the Hall of Fame copy | lupa: all-foreign team, mixed team, swap on evolution both ways, form → visitor, dex 0, CONTINUE, reorder; Java HUD |
| **FP3** Crystal, Emerald | the same in gen2 and game3 (or `leave_out` per FP0); Crystal's snapshot party; the no-mod marker file | lupa per engine, incl. species, forms and moves past the ceiling ✔ |
| **FP4** items, moves, effects | `party.item` with id/kind/forget in all three games; `canLearn`/`stones` answer the hidden checks; refusals back as `act.result`; `party.change` (poison, haircut, massage); `party.learn`/`party.forget` (tutors, Deleter, Reminder); `evolveBeyond` rule; `party.hold` and `symbols.gen3`; hidden-only items | lupa + GameTests (every kind applied and refused; Cobblemon holds from the mirror) |
| **FP5** hidden battles | §4.12: the contest with `use`, Steven as multi/double, the Tower/Frontier/Tent desks refused with lines, every native battle announced | lupa; e2e on the real engines (the contest, the Space Center) |
| **FP6** story guards and state | `party.take{quest}` (Kenya, Shuckie); hidden-only state round trip (PP, EVs, condition, ribbons, Mail); contests' visitor refusal; daycare with visitors; Pokédex never counts the stand-in | lupa; story checks (`tools/e2e/g*_story_check.py`) ✔ |
| **FP7** Emerald parity | field actions for game3; Cobblemon's PC on Lanette's PC; gifts and syncs with details; native battle announcements | lupa + GameTests; e2e Hoenn Surf and PC |
| **FP8** e2e | an all-foreign team through each game's opening, a gym, a Center, a field move, a PC, a daycare, the Name Rater, a crossing both ways; a save/CONTINUE with visitors | e2e on the pod, all three ✔; PLAYTEST.md gets the user's checks |
| **FP9** later | the Battle Tower and the Frontier in Cobblemon: each facility's battle as a held trainer battle with `use` (the chosen three), a level clamp in `battle.start.rules`, the bans applied by the desk, rentals (the Factory) sent as a temporary team; not scheduled | |

FP0 comes first because its answer can change §3.3 for Crystal and Emerald. FP7's field actions and PC are
independent of visitors and could go first if wanted (Hoenn needs them anyway). FP4's items are independent too.

## 10. Decisions

The first draft's questions keep their numbers; new ones follow. "Decided" is what the plan does; the user changes any
line and the plan follows.

| # | Question | Options and consequences | Decided | Why |
|---|---|---|---|---|
| Q1 | The hidden game's own battles (§4.12) | hand to Cobblemon (most faithful, most work per battle); skip/refuse (simple, features lost); native with visitor stats (contradicts R4) | **per battle:** Safaris, the ghost and tutorials native; the Bug-Catching Contest and Steven in Cobblemon; the Battle Tower, the Frontier, the Tent and Trainer Hill refused at the desk until FP9 | the contest and Steven are on the story's path or are everyday Johto play; the Frontier is a post-game with its own rule set |
| Q2 | Extra fields on a visitor (§3.2) | each field is cheap to send but must have a home on the stand-in | gender, shiny, friendship, OT name (not the id), nature (Emerald), types; not Pokérus, trainer id, EVs, IVs | they are what the summary and the outside-battle scripts read; the others have no reader or stay hidden-only |
| Q3 | The stand-in's look and name | one picture everywhere (one asset, three encodings) vs one per art style | **one original drawing** rendered to each engine's format (Gen 1 2-bit 56×56, Gen 2 4-colour with a shiny palette, Gen 3 16-colour 64×64) plus an icon per engine; species name "VISITOR", dex 999 (none on game3); type "???" on Crystal and Emerald, real types mapped on Blue | one asset to maintain; the display name rule (§3.4) means "VISITOR" is rarely seen |
| Q4 | Held items' direction | game → Cobblemon only (quests work, no hidden view of items); both ways (the hidden game shows real items, needs symbols per game) | **both ways where the game has the item**; items Cobblemon lacks are hidden-only per uid | Crystal already does it; Emerald's symbols are a catalogue edit; the daycare and quests read the record |
| Q5 | The hidden GIVE/TAKE menu | hide/refuse; forward | **forward** (`party.hold`) | the menu is where a Gen 2/3 player expects to hold items; the ledger already reads the bag back |
| Q6 | Abilities and natures | never synced; visitor gets a fixed one | **never synced**; nature carried for Emerald's display and taste | no reader outside battle except cosmetics and hatch speed |
| Q7 | Emerald's Contests | play natively (needs real moves); skip; natives only | **natives only**, with condition kept as hidden-only state | a visitor has no contest moves; natives now keep their Pokéblocks |
| Q8 | HM moves from a Cobblemon TM | count; also need the HM | **count** | it is the engines' own rule (move in moveset + badge) |
| Q9 | A native species evolving past its game's ceiling | never (today's veto); always; a game rule | **a game rule, on by default** (`evolveBeyond`) | visitors make it safe; a player who wants Gen 1 purity switches it off per world |
| Q10 | The daycare and eggs | refuse visitors; all breeding in Cobblemon; let visitors board | **visitors board, never breed** (undiscovered egg group) | zero hooks: the engine's compatibility check does it; steps still train the Pokémon |
| Q11 | Species checks and the Pokédex | natives only; visitors of the right species count | **natives only** | `checkpoke` and friends match on an index the stand-in doesn't have; counting would hook every script |
| Q12 | Same rule in every region? | all `visitor`; per region | **`visitor` by default everywhere**, `leave_out` per region record if a world wants it | the record already has the field |
| Q13 | The fallback where an engine can't register | a borrowed native species; `leave_out` | **`leave_out`**, reported in `hello.ok`; **FP0: not needed by any of the three engines** | a borrowed species passes species checks and the Pokédex as real |
| Q14 | Where in the roadmap | before/after SEVERAL_GAMES_FOLLOWUPS' leftovers | **after FP0, as its own stage**; FP7's Emerald items may go first | FP0 decides the shape; Hoenn's field actions are owed anyway |
| Q15 | Forms the game lacks (regional forms, Deoxys/Castform in Blue) | write the base species; visitor | **visitor** | the game's art, types and moves would be wrong; Unown/Castform/Deoxys stay native where the game has them |
| Q16 | SWITCH in the hidden party menu | undone by the next sync (today); refused; forwarded | **forwarded** (`party.order`) | R1: the lead must be the same Pokémon; one small message |
| Q17 | Overworld poison and other hidden effects on HP/friendship | undone by the next sync (today); suppressed; forwarded | **forwarded** (`party.change`) | the blackout from poison is a real rule of all three games; haircuts and massages are features |
| Q18 | Tutors, the Deleter, the Reminder | refused; forwarded | **forwarded** (`party.learn`/`party.forget`) | they are the games' move services; Cobblemon's benching already allows the Reminder's result |
| Q19 | Acknowledging `party.item` before the effect | a request/answer hold; fire-and-forget with an id | **fire-and-forget with an id**, refusals answered after the fact | the record mirrors Cobblemon's HP closely enough; a hold would add latency to every item |
| Q20 | Stats on the record | let each engine recompute from the stand-in's base stats (the summary shows nonsense for visitors); pin Cobblemon's numbers for every synced record | **pin for every record with a uid** | one wrapper per engine, and natives' summaries become right too |
| Q21 | A Cobblemon species with no national number | dropped (today); visitor | **visitor** (`dex: 0`) | R1 |
| Q22 | PP in the hidden game | synced; hidden-only | **hidden-only** (today), Ether/Elixir forwarded as `pp` | Cobblemon's PP are battle state; the hidden copy's only matter to its own screens |
| Q23 | Where the identity map lives | Java per player; `mod.save` per game | **`mod.save`**, beside `partyUids` | the hidden game is the one that needs it before the first sync |
| Q24 | Opening a Crystal/Emerald save with visitors and the mod off | guard it in the engine (impossible from a mod that isn't loaded); warn and mark; nothing | **nothing: FP0 showed both engines keep the record and draw every screen with the mod off** (the inference of a crash was wrong); a PLAYTEST.md line says a visitor then shows as "VISITOR" with no art | no risk left to guard |

## 11. Consequences and risks

- **Engine support is the main risk.** If Crystal or Emerald can't register a species by either route, those games
  fall back to `leave_out` and the HUD says so; everything else in this plan still lands (items, order, effects,
  quests, PC, field actions).
- **gs and game3 recompute stats aggressively**; the pin is a wrapper on an engine internal and must be re-checked at
  every pin change (CLAUDE.md's rule for adapters).
- **Saves opened with the mod off are safe in all three engines** (§4.14, FP0): Blue quarantines and restores,
  Crystal and Emerald just show the stand-in without art.
- **Fewer features, by choice:** the Battle Tower, the Frontier, the Tent, Trainer Hill, secret-base battles and
  contests for visitors are off (Q1, Q7).
- **Every story gate keyed to species stays native-only** (Q11): a fully foreign team can finish each story but not
  those side quests.
- **Hidden-only state grows `mod.save`** by a few hundred bytes per uid; it is pruned after 30 days of absence.
- **Shared with today's design:** Cobblemon stays the only real copy, so no choice here can lose a Pokémon; the worst
  case is a hidden record that looks wrong until the next sync.

## 12. Independent fixes found on the way

These don't need visitors; each is a small commit before or inside the phase named:

- The old note's 2.4.1: medicine, vitamins and PP items used from the hidden bag never reach Cobblemon (FP4).
- The old note's 2.4.2: the bag mirror deletes Cobblemon copies of Gen 1 items (marked stacks, MG6; recheck).
- The old note's 2.4.4: Cobblemon TMs teach HM moves (Q8: nothing to fix).
- `party.sync` cuts nicknames at 32 (`PartyGive.MAX_NAME`) and Lua at a fixed 10: both read `compat.nick` (FP1).
- `party.give`/`party.take` count save-pair operations and board the daycare on the **legacy** region even when the
  message carries a `region` (`PartyService.java:240-284,331-334`): use the tag (FP4).
- Emerald: no field actions, the game's own PC, gifts without details, native battles unannounced (FP7).
- Crystal: the snapshot party is empty (FP3); Kenya and Shuckie come back after their quests *(inference)* (FP6);
  GIVE/TAKE lost (FP4).
- gen2's gift diff reads only the party, so a gift that lands in a hidden box is never seen *(inference)* (FP3).
- `party.item` refusals are invisible to the hidden game (FP4).
- The `dex.sync` schema's description still says "dex <= 151" (FP1).

## 13. Not in this plan

- Foreign items in the hidden bag (the old note's 2.3: items stay in Minecraft and the ledger).
- Real pictures of foreign Pokémon on the hidden screens (one stand-in per game; a handful of stand-ins by type or
  shape would be the next step, not real art).
- Counting a visitor as its real species in story checks (Q11).
- The Battle Tower and the Frontier in Cobblemon (FP9, sketched only).
- Level caps or a region-only party (SEVERAL_GAMES_FOLLOWUPS.md).
- Starting in Hoenn (SEVERAL_GAMES_FOLLOWUPS.md).

## 14. Unchecked facts

From game knowledge, to confirm in the engines' scripts before the phase that needs them: the overworld poison rules
per engine (damage interval, whether Emerald faints or stops at 1 HP) and Slateport's massage (§4.3, FP4); Emerald's
Move Tutors and the contest entry menu's seam (§4.5, §4.11, FP4/FP6); that Steven's Space Center battle is on the
mandatory path and whether Cobblemon's server can seat an NPC ally in its `MULTI` format (§4.12, FP5); gs's Hall of
Fame export with an unknown species and the value `Gen2Save.encode` returns for the stand-in (§4.14, FP2); the gs icon
sheet record (§3.3, FP3). Settled by FP0: the gs runtime for a registered species, Emerald's `index` 440, the stats
pin, the mod-off behaviour of every engine.
