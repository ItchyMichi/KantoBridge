"""FOREIGN_POKEMON_PLAN FP8: the e2e suites with an all-foreign Cobblemon team. With KB_E2E_FOREIGN=1 every party.sync a
test sends names visitors: each member's dex becomes one past every game's ceiling (and no game's own), its moves the
field moves (Cut, Surf, Strength) plus the member's own; uids, gifts, levels and HP stay. Off (the default), nothing
changes. Made-up data only."""

from __future__ import annotations

import os

FOREIGN = os.environ.get("KB_E2E_FOREIGN") == "1"
# national numbers no hidden game has (Gen 4 and later), and Cobblemon's names for the sync's `name`
DEXES = [445, 448, 700, 778, 887, 1000]
NAMES = ["Garchomp", "Lucario", "Sylveon", "Mimikyu", "Dragapult", "Gholdengo"]
FIELD_MOVES = [15, 57, 70]
TEAM_UIDS = ["fa000001-0000-4000-8000-000000000001", "fa000002-0000-4000-8000-000000000002",
             "fa000003-0000-4000-8000-000000000003"]


def member(m: dict, i: int = 0) -> dict:
    """The member as a visitor when FOREIGN, else unchanged."""
    if not FOREIGN:
        return m
    out = dict(m)
    out["dex"], out["name"] = DEXES[i % len(DEXES)], NAMES[i % len(NAMES)]
    own = [n for n in m.get("moves", []) if n not in FIELD_MOVES]
    out["moves"] = (FIELD_MOVES + own)[:4]
    for k in ("gender", "shiny", "form"):
        out.pop(k, None)
    return out


def party(members: list) -> list:
    return [member(m, i) for i, m in enumerate(members)]


def team(level: int = 30) -> list:
    """Three visitors, for a test that has no party of its own to sync."""
    return [{"uid": uid, "dex": DEXES[i], "name": NAMES[i], "level": level, "moves": list(FIELD_MOVES),
             "hp": 90, "maxhp": 90} for i, uid in enumerate(TEAM_UIDS)]
