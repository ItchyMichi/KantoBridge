"""Audit every engine ``file:line`` citation in this repository against two Gen1Recomp tags.

    python3 -I tools/pin_check.py refs/gen1recomp v0.3.49 v0.3.85 [--report local/reports/pin_check.json]

The first step of a Gen1Recomp pin change (docs/specs/ENGINE_UPGRADE_PLAN.md). It scans gen1-bridge, the e2e
drivers and tests, the lupa fakes and fabric-bridge's sources for citations of engine files (``src/core/Game.lua:119``,
``Game.lua:1328-1351``, ``OC:4641`` for OverworldController, and ``, :90-95`` continuations), resolves each against
the old tag, and classes it from ``git diff -U0 <old> <new>``:

* ``same``       the cited lines are identical and still at the same numbers;
* ``shifted``    the lines are unchanged but moved (the comment's numbers are stale);
* ``changed``    the cited lines themselves were edited: re-check the behaviour;
* ``missing``    the file is gone at the new tag;
* ``unresolved`` the name is not an engine file.

The clone is read only through ``git -C`` (``ls-tree``, ``diff``); nothing is executed from it. The report holds
citations, classes and line numbers only: nothing in it comes from a ROM.
"""

from __future__ import annotations

import argparse
import collections
import json
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parents[1]
SOURCES = ("gen1-bridge/**/*.lua", "tools/e2e/*.py", "tools/e2e/drivers/*.lua", "tools/lua/*.lua", "tools/lua/*.py",
           "tools/fake_gen1/*.py", "fabric-bridge/src/**/*.java")
ABBREV = {"OC": "src/world/OverworldController.lua"}

FULL = re.compile(r"(?:refs/gen1recomp/)?((?:src/[A-Za-z0-9_/.-]+|main)\.lua)\s*:(\d+)(?:-(\d+))?")
TILDE = re.compile(r"((?:src/[A-Za-z0-9_/.-]+|main)\.lua)\s*~?L(\d+)(?:-(\d+))?")
BASE = re.compile(r"\b([A-Z][A-Za-z0-9_]*\.lua):(\d+)(?:-(\d+))?")
ABBR = re.compile(r"\b(OC):(\d+)(?:-(\d+))?")
CONT = re.compile(r"(?<![A-Za-z0-9_/.])(?:,|and|\()\s*:(\d+)(?:-(\d+))?")
MODFN = re.compile(r"\b([A-Z][A-Za-z0-9]+):[a-z][A-Za-z0-9_]*")
HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


class Clone:
    def __init__(self, path: str):
        self.path = path

    def git(self, *args: str) -> str:
        return subprocess.run(["git", "-C", self.path, *args], capture_output=True, text=True, check=True).stdout

    def files(self, tag: str) -> set[str]:
        return set(self.git("ls-tree", "-r", "--name-only", tag).splitlines())

    def numstat(self, old: str, new: str, path: str) -> tuple[int, int]:
        line = self.git("diff", "--numstat", old, new, "--", path).strip()
        if not line:
            return (0, 0)
        added, removed = line.split("\t")[:2]
        return (int(added) if added.isdigit() else 0, int(removed) if removed.isdigit() else 0)

    def hunks(self, old: str, new: str, path: str) -> list[tuple[int, int, int, int]]:
        out = []
        for line in self.git("diff", "-U0", old, new, "--", path).splitlines():
            m = HUNK.match(line)
            if m:
                a = int(m.group(1))
                b = int(m.group(2)) if m.group(2) is not None else 1
                c = int(m.group(3))
                d = int(m.group(4)) if m.group(4) is not None else 1
                out.append((a, b, c, d))
        return out


def scan(repo: pathlib.Path, by_base: dict[str, list[str]]) -> list[dict]:
    def resolve_base(name: str) -> str | None:
        paths = by_base.get(name)
        return paths[0] if paths and len(paths) == 1 else None

    sources: list[pathlib.Path] = []
    for pattern in SOURCES:
        sources += sorted(repo.glob(pattern))
    cites: list[dict] = []
    for src in sources:
        rel = src.relative_to(repo).as_posix()
        text = src.read_text(encoding="utf-8", errors="replace")
        for n, line in enumerate(text.splitlines(), 1):
            found: list[tuple[int, str, int, int, bool]] = []
            for m in FULL.finditer(line):
                found.append((m.start(), m.group(1), int(m.group(2)), int(m.group(3) or m.group(2)), False))
            for m in TILDE.finditer(line):
                found.append((m.start(), m.group(1), int(m.group(2)), int(m.group(3) or m.group(2)), True))
            for m in ABBR.finditer(line):
                found.append((m.start(), ABBREV[m.group(1)], int(m.group(2)), int(m.group(3) or m.group(2)), False))
            for m in BASE.finditer(line):
                if line[max(0, m.start() - 4):m.start()].endswith("src/"):
                    continue  # already taken as a full path
                p = resolve_base(m.group(1))
                if p:
                    found.append((m.start(), p, int(m.group(2)), int(m.group(3) or m.group(2)), False))
            found.sort()
            anchors = [(pos, p) for pos, p, *_ in found]
            for m in MODFN.finditer(line):
                p = resolve_base(m.group(1) + ".lua")
                if p:
                    anchors.append((m.start(), p))
            anchors.sort()
            for m in CONT.finditer(line):
                prev = [p for pos, p in anchors if pos < m.start()]
                if prev:
                    found.append((m.start(), prev[-1], int(m.group(1)), int(m.group(2) or m.group(1)), True))
            seen = set()
            for _pos, p, s, e, loose in found:
                key = (p, s, e)
                if key in seen:
                    continue
                seen.add(key)
                cites.append({"src": rel, "srcline": n, "path": p, "start": s, "end": max(s, e), "loose": loose})
    return cites, len(sources)


def classify(cite: dict, hunks) -> tuple[str, int | None]:
    if hunks is None:
        return "unresolved", None
    if hunks == "missing":
        return "missing", None
    s, e = cite["start"], cite["end"]
    offset = 0
    changed = inserted_within = False
    for a, b, _c, d in hunks:
        if b > 0:
            lo, hi = a, a + b - 1
            if hi < s:
                offset += d - b
            elif lo <= e:
                changed = True
        else:  # a pure insertion after old line a
            if a < s:
                offset += d
            elif s <= a < e:
                inserted_within = True
    if changed or inserted_within:
        return "changed", s + offset
    return ("shifted" if offset else "same"), s + offset


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("clone", help="a git clone of the official Gen1Recomp repository (read only)")
    parser.add_argument("old", help="the pinned tag, e.g. v0.3.49")
    parser.add_argument("new", help="the candidate tag, e.g. v0.3.85")
    parser.add_argument("--report", help="write the JSON report here (default: no file)")
    args = parser.parse_args(argv)

    clone = Clone(args.clone)
    old_files, new_files = clone.files(args.old), clone.files(args.new)
    by_base: dict[str, list[str]] = collections.defaultdict(list)
    for p in old_files:
        if p.endswith(".lua"):
            by_base[pathlib.Path(p).name].append(p)
    by_base["main.lua"] = ["main.lua"]

    cites, scanned = scan(REPO, by_base)
    hunks: dict[str, object] = {}
    numstat: dict[str, tuple[int, int]] = {}
    for p in sorted({c["path"] for c in cites}):
        if p not in old_files:
            hunks[p] = None
        elif p not in new_files:
            hunks[p] = "missing"
        else:
            numstat[p] = clone.numstat(args.old, args.new, p)
            hunks[p] = clone.hunks(args.old, args.new, p)

    summary: dict[str, collections.Counter] = collections.OrderedDict()
    for c in cites:
        c["kind"], c["new_start"] = classify(c, hunks.get(c["path"]))
        st = summary.setdefault(c["path"], collections.Counter())
        st[c["kind"]] += 1
        st["total"] += 1
    totals = collections.Counter(c["kind"] for c in cites)

    print(f"pin_check {args.old} -> {args.new}: {len(cites)} citations in {scanned} files; "
          + ", ".join(f"{k} {totals[k]}" for k in ("same", "shifted", "changed", "missing", "unresolved")))
    print(f"{'cited file':44} {'cites':>5} {'chg':>4} {'shft':>4} {'same':>4} {'+/-':>12}")
    for p, st in sorted(summary.items(), key=lambda kv: (-kv[1]["changed"], -kv[1]["total"])):
        ns = numstat.get(p, (0, 0))
        flag = "  MISSING" if st["missing"] else ("  UNRESOLVED" if st["unresolved"] else "")
        print(f"{p:44} {st['total']:5} {st['changed']:4} {st['shifted']:4} {st['same']:4} "
              f"{('+%d/-%d' % ns):>12}{flag}")
    changed = sorted((c for c in cites if c["kind"] == "changed"), key=lambda c: (c["path"], c["start"]))
    if changed:
        print("\nchanged citations (cited -> about now; cited by):")
        for c in changed:
            rng = f"{c['start']}" if c["start"] == c["end"] else f"{c['start']}-{c['end']}"
            print(f"  {c['path']}:{rng} -> ~{c['new_start']}  {c['src']}:{c['srcline']}")
    if args.report:
        out = pathlib.Path(args.report)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps({"old": args.old, "new": args.new,
                                   "summary": {p: dict(s) for p, s in summary.items()},
                                   "numstat": {p: list(v) for p, v in numstat.items()}, "cites": cites},
                                  indent=1, sort_keys=True) + "\n", encoding="utf-8")
        print(f"\nreport: {out}")
    return 1 if totals["missing"] else 0


if __name__ == "__main__":
    sys.exit(main())
