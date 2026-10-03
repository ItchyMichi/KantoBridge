"""Derives protocol/catalog.json and protocol/redaction-vectors.json from the message schemas.

    tools/.venv/Scripts/python.exe -m kb_protocol.catalog --write   (run from tools/)
    tools/.venv/Scripts/python.exe -m kb_protocol.catalog --check

Both files are checked in: Java bundles the catalog, and Java's Redact is tested against the
vectors. tools/tests fails when either is stale.
"""
import argparse
import json
import sys

from .jsonfmt import pretty
from .redact import placeholder, redact_data
from .registry import load_protocol

PROTOCOL_NUMBER = 1
LIMITS = {"maxLineBytes": 4 * 1024 * 1024, "exportPageBytes": 1024 * 1024, "relayMaxBytes": 256 * 1024}
HEARTBEAT = {"pingMs": 2000, "timeoutMs": 10000}
RECONNECT = {"initialMs": 500, "maxMs": 5000}

# Placeholder edge cases shared with the Java and Lua implementations.
PLACEHOLDER_CASES = [
    "",
    "Hello, tester!",
    "é♂♀×¥ <PK><MN>",
    "line\nbreak\ttab \"quoted\" \\ back",
    0,
    -42,
    9007199254740991,
    True,
    False,
    None,
    [],
    {},
    [1, "two", [3], {"four": 4}],
    {"b": 1, "a": [True, None], "é": "x"},
]


def _escape(token):
    return token.replace("~", "~0").replace("/", "~1")


def rom_paths(protocol, schema):
    """JSON Pointers (with * wildcards) of every x-rom-derived location in a message schema."""
    found = set()

    def walk(node, uri, path, stack):
        if not isinstance(node, dict):
            return
        if node.get("x-rom-derived") is True:
            found.add(path)
            return
        if "$ref" in node:
            target_uri, target = protocol.resolve(uri, node["$ref"])
            key = (target_uri, node["$ref"], path)
            if key not in stack:
                walk(target, target_uri, path, stack | {key})
        for name, sub in node.get("properties", {}).items():
            walk(sub, uri, path + "/" + _escape(name), stack)
        for keyword in ("items", "additionalProperties"):
            sub = node.get(keyword)
            if isinstance(sub, dict):
                walk(sub, uri, path + "/*", stack)
        for keyword in ("allOf", "anyOf", "oneOf"):
            for sub in node.get(keyword, []):
                walk(sub, uri, path, stack)
        for keyword in ("then", "else"):
            walk(node.get(keyword), uri, path, stack)

    walk(schema, schema["$id"], "", frozenset())
    return sorted(found)


def build_catalog(protocol=None):
    protocol = protocol or load_protocol()
    messages = {}
    for msg_type in protocol.types:
        schema = protocol.messages[msg_type]
        paths = rom_paths(protocol, schema)
        messages[msg_type] = {
            "legs": list(schema["x-legs"]),
            "re": schema["x-re"],
            "rom": bool(paths),
            "redact": paths,
            "schema": f"messages/{msg_type}.schema.json",
        }
    return {
        "protocol": PROTOCOL_NUMBER,
        "limits": LIMITS,
        "heartbeat": HEARTBEAT,
        "reconnect": RECONNECT,
        "messages": messages,
    }


def load_examples(protocol=None):
    """{type: [envelope, ...]} from protocol/examples/."""
    protocol = protocol or load_protocol()
    out = {}
    for path in sorted((protocol.root / "examples").glob("*.json")):
        with open(path, encoding="utf-8") as fh:
            out[path.name[: -len(".json")]] = json.load(fh)
    return out


def build_vectors(catalog, examples):
    return {
        "about": "Expected redaction of every protocol example, plus placeholder edge cases. "
                 "Derived by tools/kb_protocol/catalog.py; never edit by hand.",
        "placeholders": [{"value": v, "placeholder": placeholder(v)} for v in PLACEHOLDER_CASES],
        "messages": [
            {"type": env["type"], "seq": env["seq"],
             "redacted": redact_data(env["data"], catalog["messages"][env["type"]]["redact"])}
            for msg_type in sorted(examples)
            for env in examples[msg_type]
        ],
    }


def expected_files(protocol=None):
    protocol = protocol or load_protocol()
    catalog = build_catalog(protocol)
    vectors = build_vectors(catalog, load_examples(protocol))
    return {
        protocol.root / "catalog.json": pretty(catalog),
        protocol.root / "redaction-vectors.json": pretty(vectors),
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="rewrite the derived files")
    mode.add_argument("--check", action="store_true", help="fail if a derived file is stale")
    args = parser.parse_args(argv)
    stale = []
    for path, text in expected_files().items():
        current = path.read_text(encoding="utf-8") if path.exists() else None
        if current == text:
            continue
        if args.write:
            path.write_text(text, encoding="utf-8", newline="\n")
            print(f"wrote {path.name}")
        else:
            stale.append(path.name)
    if stale:
        print("stale: " + ", ".join(stale) + " (run python -m kb_protocol.catalog --write from tools/)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
