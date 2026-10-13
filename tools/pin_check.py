"""Audit every engine ``file:line`` citation in this repository against two Gen1Recomp tags.

    python3 -I tools/pin_check.py refs/gen1recomp v0.3.49 v0.3.85 [--report local/reports/pin_check.json]
    python3 -I tools/pin_check.py refs/gen1recomp v0.3.49 v0.3.85 --restamp --dry-run   # what would be rewritten
    python3 -I tools/pin_check.py refs/gen1recomp v0.3.49 v0.3.85 --restamp             # rewrite the numbers in place

The first step of a Gen1Recomp pin change (docs/specs/ENGINE_UPGRADE_PLAN.md). It scans gen1-bridge, the e2e
drivers and tests, the lupa fakes and fabric-bridge's sources for citations of engine files (``src/core/Game.lua:119``,
``Game.lua:1328-1351``, ``OC:4641`` for OverworldController, and ``, :90-95`` continuations), resolves each against
the old tag, and classes it from ``git diff -U0 <old> <new>``:

* ``same``       the cited lines are identical and still at the same numbers;
* ``shifted``    the lines are unchanged but moved (the comment's numbers are stale);
* ``changed``    the cited lines themselves were edited: re-check the behaviour;
* ``missing``    the file is gone at the new tag;
* ``unresolved`` the name is not an engine file at the old tag (a file that is new at the new tag counts here).

A ``:N`` fragment on a comment line that names no file continues the comment paragraph above it (contiguous comment
lines, no blank or code line between): when exactly one engine file is named above it in that paragraph, and cited
with numbers, the fragment is a citation of that file like a same-line continuation (counted in the summary line as a
paragraph continuation). Otherwise it is an orphan: listed by ``--restamp`` with a guess, never rewritten.

``--restamp`` (UP1) rewrites the numbers of every ``shifted`` citation in place, each in its own syntax: the old start
becomes the new start and a range keeps its length. ``same`` citations already agree; ``changed``, ``missing`` and
``unresolved`` ones are never touched and are listed at the end for hand editing, as are the orphans. On a line with
several citations the rightmost is rewritten first, and the line is re-parsed after every rewrite: if it would parse
differently, the line is left alone and listed. ``--dry-run`` prints the same and writes nothing.

A real restamp also writes ``tools/pin_check.stamp.json``, which says the citations are now at the new tag. Until the
pin itself moves (UP6) that is the only way a later run can tell: an audit against another old tag says so, and a
second ``--restamp`` does nothing. ``--since REV`` leaves alone the citing lines edited after REV (they may already
carry the new numbers); ``--root DIR`` scans another checkout (a scratch copy, a test).

The clone is read only through ``git -C`` (``ls-tree``, ``diff``); nothing is executed from it. The report holds
citations, classes and line numbers only: nothing in it comes from a ROM.
"""

from __future__ import annotations

import argparse
import collections
import datetime
import json
import pathlib
import re
import subprocess
import sys
from typing import NamedTuple

REPO = pathlib.Path(__file__).resolve().parents[1]
SOURCES = ("gen1-bridge/**/*.lua", "tools/e2e/*.py", "tools/e2e/drivers/*.lua", "tools/lua/*.lua", "tools/lua/*.py",
           "tools/fake_gen1/*.py", "fabric-bridge/src/**/*.java")
ABBREV = {"OC": "src/world/OverworldController.lua"}
STAMP = "tools/pin_check.stamp.json"
KINDS = ("same", "shifted", "changed", "missing", "unresolved")
BY_HAND = ("changed", "missing", "unresolved")

FULL = re.compile(r"(?:refs/gen1recomp/)?((?:src/[A-Za-z0-9_/.-]+|main)\.lua)\s*:(\d+)(?:-(\d+))?")
TILDE = re.compile(r"((?:src/[A-Za-z0-9_/.-]+|main)\.lua)\s*~?L(\d+)(?:-(\d+))?")
BASE = re.compile(r"\b([A-Z][A-Za-z0-9_]*\.lua):(\d+)(?:-(\d+))?")
ABBR = re.compile(r"\b(OC):(\d+)(?:-(\d+))?")
# ``, :N``, ``and :N`` or ``(:N`` after a file on the same line. Only ``and`` needs the guard (``hand :3``).
CONT = re.compile(r"(?:,|(?<![A-Za-z0-9_/.])and|\()\s*:(\d+)(?:-(\d+))?")
CONT_LEAD = re.compile(r"^(?:,|and|\()\s*")
MODFN = re.compile(r"\b([A-Z][A-Za-z0-9]+):[a-z][A-Za-z0-9_]*")
HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")
# A ``:N`` or ``:N-M`` in a comment that nothing on its line anchors: continued from the paragraph above.
ORPHAN = re.compile(r"(?<![\w/.:\]\"'`])\s*:(\d+)(?:-(\d+))?(?![\w:])")
# A whole-line comment: the lines of a comment paragraph.
COMMENT_LINE = {".lua": re.compile(r"^\s*--"), ".py": re.compile(r"^\s*#"), ".java": re.compile(r"^\s*(?://|/\*|\*)")}
# An engine file named without a line (``src/mods/Schemas.lua: pokemon :1726``, ``TextBox.paginate, :298-375``):
# a guess for such fragments only.
MENTION = re.compile(r"(?:src/[A-Za-z0-9_/.-]+|main)\.lua|\b[A-Z][A-Za-z0-9_]*\.lua\b|\bOC\b"
                     r"|\b[A-Z][A-Za-z0-9]+(?=[.:][a-z])")
COMMENT = {".lua": "--", ".py": "#", ".java": "//"}


class Found(NamedTuple):
    """One citation on one line: ``nstart:nend`` is the span of its numbers, which ``--restamp`` rewrites."""
    pos: int
    path: str
    start: int
    end: int
    loose: bool
    nstart: int
    nend: int
    para: bool = False  # continues the comment paragraph above (no file on its own line)


def git(path: str | pathlib.Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(path), *args], capture_output=True, text=True, check=True).stdout


class Clone:
    def __init__(self, path: str):
        self.path = path

    def files(self, tag: str) -> set[str]:
        return set(git(self.path, "ls-tree", "-r", "--name-only", tag).splitlines())

    def numstat(self, old: str, new: str, path: str) -> tuple[int, int]:
        line = git(self.path, "diff", "--numstat", old, new, "--", path).strip()
        if not line:
            return (0, 0)
        added, removed = line.split("\t")[:2]
        return (int(added) if added.isdigit() else 0, int(removed) if removed.isdigit() else 0)

    def hunks(self, old: str, new: str, path: str) -> list[tuple[int, int, int, int]]:
        return parse_hunks(git(self.path, "diff", "-U0", old, new, "--", path))


def parse_hunks(diff: str) -> list[tuple[int, int, int, int]]:
    out = []
    for line in diff.splitlines():
        m = HUNK.match(line)
        if m:
            a = int(m.group(1))
            b = int(m.group(2)) if m.group(2) is not None else 1
            c = int(m.group(3))
            d = int(m.group(4)) if m.group(4) is not None else 1
            out.append((a, b, c, d))
    return out


def resolve_base(name: str, by_base: dict[str, list[str]]) -> str | None:
    """``Game.lua`` -> its path at the old tag, when exactly one engine file has that name."""
    paths = by_base.get(name)
    return paths[0] if paths and len(paths) == 1 else None


def parse_line(line: str, by_base: dict[str, list[str]], inherit: str | None = None) -> list[Found]:
    """Every citation on one line, duplicates included, in the scanner's order: named files, same-line continuations,
    then (with ``inherit``, the one file the comment paragraph above names) the fragments that continue it."""
    def add(m: re.Match, path: str, loose: bool, g: int = 2, para: bool = False) -> None:
        nend = m.end(g + 1) if m.group(g + 1) else m.end(g)
        found.append(Found(m.start(g) - 1 if para else m.start(), path, int(m.group(g)),
                           int(m.group(g + 1) or m.group(g)), loose, m.start(g), nend, para))

    found: list[Found] = []
    for m in FULL.finditer(line):
        add(m, m.group(1), False)
    for m in TILDE.finditer(line):
        add(m, m.group(1), True)
    for m in ABBR.finditer(line):
        add(m, ABBREV[m.group(1)], False)
    taken = [(f.pos, f.nend) for f in found]
    for m in BASE.finditer(line):
        if any(a <= m.start() < b for a, b in taken):
            continue  # the tail of a full path, already taken
        p = resolve_base(m.group(1), by_base)
        if p:
            add(m, p, False)
    found.sort()
    anchors = [(f.pos, f.path) for f in found]
    for m in MODFN.finditer(line):
        p = resolve_base(m.group(1) + ".lua", by_base)
        if p:
            anchors.append((m.start(), p))
    anchors.sort()
    for m in CONT.finditer(line):
        prev = [p for pos, p in anchors if pos < m.start()]
        if prev:
            add(m, prev[-1], True, g=1)
    if inherit:
        taken = {(f.nstart, f.nend) for f in found}
        for m in ORPHAN.finditer(line):
            if (m.start(1), m.end(2) if m.group(2) else m.end(1)) not in taken:
                add(m, inherit, True, g=1, para=True)
    return found


def paragraphs(lines: list[str], by_base: dict[str, list[str]], suffix: str) -> list[tuple[str | None, set[str]]]:
    """Per line: the engine file an unanchored ``:N`` fragment on it continues (the one file the comment paragraph
    names above it, cited with numbers), or None; and the files the paragraph names above the line, for the orphan
    listing. A blank or code line ends a paragraph; only whole-line comments belong to one."""
    is_comment = COMMENT_LINE.get(suffix)
    out: list[tuple[str | None, set[str]]] = []
    named: set[str] = set()  # files named so far in the paragraph, with numbers or without
    cited: set[str] = set()  # those cited with numbers
    for line in lines:
        if not (is_comment and is_comment.match(line)):
            named, cited = set(), set()
            out.append((None, set()))
            continue
        inherit = next(iter(cited)) if len(named) == 1 and cited else None
        out.append((inherit, set(named)))
        cited |= {f.path for f in parse_line(line, by_base, inherit)}
        named |= cited | {p for _, p in named_files(line, by_base)}
    return out


def fragment(line: str, f: Found, numbers: str | None = None) -> str:
    """The citation as written (``OC:4641``, ``:90-95``), or as it reads with other numbers."""
    return CONT_LEAD.sub("", line[f.pos:f.nstart] + (line[f.nstart:f.nend] if numbers is None else numbers))


def scan(repo: pathlib.Path, by_base: dict[str, list[str]]) -> tuple[list[dict], int]:
    sources: list[pathlib.Path] = []
    for pattern in SOURCES:
        sources += sorted(repo.glob(pattern))
    cites: list[dict] = []
    for src in sources:
        rel = src.relative_to(repo).as_posix()
        # split on newlines only (not splitlines()): editors count lines that way
        lines = src.read_text(encoding="utf-8", errors="replace").split("\n")
        inherits = paragraphs(lines, by_base, src.suffix)
        for n, line in enumerate(lines, 1):
            seen = set()
            for f in parse_line(line, by_base, inherits[n - 1][0]):
                key = (f.path, f.start, f.end)
                if key in seen:
                    continue
                seen.add(key)
                cites.append({"src": rel, "srcline": n, "path": f.path, "start": f.start, "end": max(f.start, f.end),
                              "loose": f.loose, "paragraph": f.para, "text": fragment(line, f)})
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


def read_stamp(root: pathlib.Path) -> dict | None:
    path = root / STAMP
    if not path.is_file():
        return None
    try:
        stamp = json.loads(path.read_text(encoding="utf-8"))
    except ValueError:
        return None
    return stamp if isinstance(stamp, dict) else None


def edited_since(root: pathlib.Path, rev: str, src: str) -> set[int] | None:
    """The line numbers of ``src`` (as on disk) that differ from ``rev``; None when ``src`` is not at ``rev``."""
    if not git(root, "ls-tree", "--name-only", rev, "--", src).strip():
        return None
    lines: set[int] = set()
    for _a, _b, c, d in parse_hunks(git(root, "diff", "-U0", rev, "--", src)):
        lines.update(range(c, c + d))
    return lines


def orphans_on(lines: list[str], n: int, src: str, by_base: dict, anchored: set[tuple[int, int]],
               named: set[str]) -> list[tuple]:
    """``:N`` fragments in line ``n``'s comment that neither the line nor its paragraph anchors, with why, and the
    file guessed from above. ``named`` is what the paragraph names above the line (empty on a code line)."""
    line = lines[n - 1]
    suffix = pathlib.PurePosixPath(src).suffix
    marker = COMMENT.get(suffix)
    at = line.find(marker) if marker else -1
    if at < 0 and marker == "//":
        at = line.find("*")  # a line of a block comment
    if at < 0:
        return []
    frags = [m for m in ORPHAN.finditer(line, at)
             if (m.start(1), m.end(2) if m.group(2) else m.end(1)) not in anchored]
    if not frags:
        return []
    comment = COMMENT_LINE.get(suffix)
    if not (comment and comment.match(line)):
        why = "on a code line, not in a comment paragraph"
    elif len(named) > 1:
        why = f"ambiguous: {', '.join(sorted(named))} named above"
    elif named:
        why = f"{next(iter(named))} named above but never with line numbers"
    else:
        why = "nothing cited above in the paragraph"
    guess = None  # the nearest file named before the fragment: this line, then up to 8 lines above
    for i in range(n - 1, max(-1, n - 10), -1):
        if i < n - 1 and not lines[i].strip():
            break
        before = [(pos, p) for pos, p in named_files(lines[i], by_base) if i < n - 1 or pos < frags[0].start()]
        if before:
            guess = (before[-1][1], i + 1)
            break
    return [(src, n, m.group(0).strip(), int(m.group(1)), int(m.group(2) or m.group(1)), guess, why) for m in frags]


def named_files(line: str, by_base: dict[str, list[str]]) -> list[tuple[int, str]]:
    """Engine files named on the line, with or without line numbers (for guessing what a fragment refers to)."""
    out = []
    for m in MENTION.finditer(line):
        name = m.group(0)
        if name in ABBREV:
            path = ABBREV[name]
        elif "/" in name or name == "main.lua":
            path = name
        else:
            path = resolve_base(name if name.endswith(".lua") else name + ".lua", by_base)
        if path:
            out.append((m.start(), path))
    return out


def restamp(root: pathlib.Path, cites: list[dict], by_base: dict, dry_run: bool, since: str | None) -> dict:
    """Rewrite the numbers of every shifted citation in place; with ``dry_run`` only decide. Returns what happened."""
    by_line: dict[tuple[str, int], dict[tuple[str, int, int], dict]] = collections.defaultdict(dict)
    for c in cites:
        c["rewritten"], c["new_text"] = False, None
        by_line[(c["src"], c["srcline"])][(c["path"], c["start"], c["end"])] = c
    res: dict = {"rewrites": [], "per_file": collections.Counter(), "cites": 0, "by_hand": [], "orphans": []}

    def by_hand(c: dict, why: str) -> None:
        res["by_hand"].append((c["src"], c["srcline"], c["text"], c["new_start"], why, c["path"]))

    for src in sorted({s for s, _ in by_line}):
        path = root / src
        try:
            text = path.read_bytes().decode("utf-8")
        except UnicodeDecodeError:
            for n in sorted(n for s, n in by_line if s == src):
                for c in by_line[(src, n)].values():
                    by_hand(c, "not UTF-8: not rewritten")
            continue
        lines = text.split("\n")
        inherits = paragraphs(lines, by_base, path.suffix)
        suspect = edited_since(root, since, src) if since else set()
        touched = False
        for n in range(1, len(lines) + 1):
            cmap = by_line.get((src, n), {})
            line = lines[n - 1]
            inherit, named = inherits[n - 1]
            found = parse_line(line, by_base, inherit)
            res["orphans"] += orphans_on(lines, n, src, by_base, {(f.nstart, f.nend) for f in found}, named)
            todo = [f for f in found if cmap.get((f.path, f.start, f.end), {}).get("kind") == "shifted"]
            if not todo:
                continue
            keys = {(f.path, f.start, f.end) for f in found}
            if keys != set(cmap):
                why = "the line changed while the audit ran: not rewritten"
            elif suspect is None or n in suspect:
                why = f"edited since {since}: not rewritten"
            else:
                why = ""
            if why:
                for key in {(f.path, f.start, f.end) for f in todo}:
                    by_hand(cmap[key], why)
                continue
            expected = [(f.path, f.start, f.end) for f in found]
            frags: list[tuple[str, str]] = []
            new_line = line
            for f in sorted(todo, key=lambda f: -f.nstart):  # rightmost first: the spans to its left stay valid
                c = cmap[(f.path, f.start, f.end)]
                ns = c["new_start"]
                numbers = f"{ns}-{ns + f.end - f.start}" if "-" in line[f.nstart:f.nend] else f"{ns}"
                candidate = new_line[:f.nstart] + numbers + new_line[f.nend:]
                expected[found.index(f)] = (f.path, ns, ns + f.end - f.start)
                if [(g.path, g.start, g.end) for g in parse_line(candidate, by_base, inherit)] != expected:
                    frags = []
                    break
                frags.append((fragment(line, f), fragment(line, f, numbers)))
                c["new_text"] = frags[-1][1]
                new_line = candidate
            if not frags:
                for key in {(f.path, f.start, f.end) for f in todo}:
                    cmap[key]["new_text"] = None
                    by_hand(cmap[key], "the rewrite would change how the line parses: not rewritten")
                continue
            lines[n - 1] = new_line
            touched = True
            res["rewrites"].append((src, n, frags[::-1]))
            res["per_file"][src] += 1
            for key in {(f.path, f.start, f.end) for f in todo}:
                cmap[key]["rewritten"] = True
                res["cites"] += 1
        if touched and not dry_run:
            path.write_bytes("\n".join(lines).encode("utf-8"))
    for c in cites:
        if c["kind"] in BY_HAND:
            by_hand(c, c["kind"])
    res["by_hand"].sort(key=lambda h: (h[0], h[1]))
    return res


def print_restamp(res: dict, old: str, new: str, dry_run: bool, new_files: set[str], hunks_for) -> None:
    tag = " (dry run)" if dry_run else ""
    print(f"\nrestamp {old} -> {new}{tag}: {'would rewrite' if dry_run else 'rewrote'} {res['cites']} citations on "
          f"{len(res['rewrites'])} lines in {len(res['per_file'])} files; {len(res['by_hand'])} by hand; "
          f"{len(res['orphans'])} orphan continuations")
    for src, n, frags in res["rewrites"]:
        print(f"  {src}:{n}  " + ", ".join(f"{o} -> {w}" for o, w in frags))
    if res["per_file"]:
        print(f"lines {'to rewrite' if dry_run else 'rewritten'} per file:")
        for src, count in sorted(res["per_file"].items()):
            print(f"  {src} {count}")
    if res["by_hand"]:
        print("by hand (citing_file:line  old_cite -> new_start?):")
        for src, n, text, new_start, why, path in res["by_hand"]:
            if why == "unresolved" and path in new_files:
                why = f"unresolved: new at {new}, nothing to do"
            print(f"  {src}:{n}  {text} -> {'?' if new_start is None else f'{new_start}?'}  {why}")
    if res["orphans"]:
        print("orphan continuations (no file on the line and none certain from the paragraph; by hand):")
        for src, n, text, s, e, guess, why in res["orphans"]:
            if guess:
                kind, new_start = classify({"start": s, "end": max(s, e)}, hunks_for(guess[0]))
                print(f"  {src}:{n}  {text} -> {'?' if new_start is None else f'{new_start}?'}  {why}; "
                      f"guess {guess[0]} (line {guess[1]}), {kind}")
            else:
                print(f"  {src}:{n}  {text} -> ?  {why}; no guess")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("clone", help="a git clone of the official Gen1Recomp repository (read only)")
    parser.add_argument("old", help="the pinned tag, e.g. v0.3.49")
    parser.add_argument("new", help="the candidate tag, e.g. v0.3.85")
    parser.add_argument("--report", help="write the JSON report here (default: no file)")
    parser.add_argument("--restamp", action="store_true", help="rewrite the numbers of the shifted citations in place")
    parser.add_argument("--dry-run", action="store_true", help="with --restamp: print the rewrites, write nothing")
    parser.add_argument("--since", metavar="REV", help="with --restamp: leave alone the citing lines edited after REV")
    parser.add_argument("--root", default=str(REPO), metavar="DIR", help="the checkout to scan (default: this one)")
    args = parser.parse_args(argv)
    if (args.dry_run or args.since) and not args.restamp:
        parser.error("--dry-run and --since go with --restamp")
    root = pathlib.Path(args.root).resolve()
    if args.since:
        try:
            git(root, "rev-parse", "--verify", "--quiet", args.since + "^{commit}")
        except subprocess.CalledProcessError:
            print(f"--since {args.since}: not a commit of {root}")
            return 2

    stamp = read_stamp(root) or {}
    at = stamp.get("citations_at")
    if at and at != args.old:
        print(f"note: {STAMP} says the citations are at {at} (restamped from {stamp.get('from')} on "
              f"{stamp.get('on')}); against {args.old} the classes below are not meaningful")

    clone = Clone(args.clone)
    old_files, new_files = clone.files(args.old), clone.files(args.new)
    by_base: dict[str, list[str]] = collections.defaultdict(list)
    for p in old_files:
        if p.endswith(".lua"):
            by_base[pathlib.Path(p).name].append(p)
    by_base["main.lua"] = ["main.lua"]

    cites, scanned = scan(root, by_base)
    hunks: dict[str, object] = {}
    numstat: dict[str, tuple[int, int]] = {}

    def hunks_for(p: str):  # cached per engine file: the guesses for orphan fragments ask about uncited files too
        if p not in hunks:
            if p not in old_files:
                hunks[p] = None
            elif p not in new_files:
                hunks[p] = "missing"
            else:
                hunks[p] = clone.hunks(args.old, args.new, p)
        return hunks[p]

    for p in sorted({c["path"] for c in cites}):
        if p in old_files and p in new_files:
            numstat[p] = clone.numstat(args.old, args.new, p)
        hunks_for(p)

    summary: dict[str, collections.Counter] = collections.OrderedDict()
    for c in cites:
        c["kind"], c["new_start"] = classify(c, hunks[c["path"]])
        st = summary.setdefault(c["path"], collections.Counter())
        st[c["kind"]] += 1
        st["total"] += 1
    totals = collections.Counter(c["kind"] for c in cites)

    print(f"pin_check {args.old} -> {args.new}: {len(cites)} citations in {scanned} files; "
          + ", ".join(f"{k} {totals[k]}" for k in KINDS)
          + f"; paragraph continuations {sum(1 for c in cites if c['paragraph'])}")
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

    code = 1 if totals["missing"] else 0
    restamped: dict | None = None
    if args.restamp and at and at != args.old:
        if at == args.new:
            print(f"\nrestamp: nothing to do, the citations are already at {at}")
        else:
            print(f"\nrestamp: refused, the citations are at {at}: pass it as the old tag")
            code = max(code, 2)
    elif args.restamp:
        restamped = restamp(root, cites, by_base, args.dry_run, args.since)
        print_restamp(restamped, args.old, args.new, args.dry_run, new_files, hunks_for)
        if not args.dry_run:
            stamp = {"citations_at": args.new, "from": args.old, "on": datetime.date.today().isoformat(),
                     "rewritten": restamped["cites"], "by_hand": len(restamped["by_hand"])}
            (root / STAMP).write_text(json.dumps(stamp, indent=1) + "\n", encoding="utf-8")
            print(f"stamp: {STAMP}: the citations are at {args.new}")
    if args.report:
        out = pathlib.Path(args.report)
        out.parent.mkdir(parents=True, exist_ok=True)
        report = {"old": args.old, "new": args.new, "summary": {p: dict(s) for p, s in summary.items()},
                  "numstat": {p: list(v) for p, v in numstat.items()}, "cites": cites}
        if restamped is not None:
            report["restamp"] = {"dry_run": args.dry_run, "since": args.since, "rewritten": restamped["cites"],
                                 "lines": dict(restamped["per_file"]), "by_hand": len(restamped["by_hand"]),
                                 "orphans": len(restamped["orphans"])}
        out.write_text(json.dumps(report, indent=1, sort_keys=True) + "\n", encoding="utf-8")
        print(f"\nreport: {out}")
    return code


if __name__ == "__main__":
    sys.exit(main())
