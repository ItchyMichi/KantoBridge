"""Redaction of ROM-derived fields (protocol/README.md, "Redaction").

Each value at a catalog ``redact`` path becomes ``<rom:KIND:LEN:SHA8>``. Inputs are never mutated.
"""
import copy
import hashlib

from .jsonfmt import canonical


def _kind_and_len(value):
    if isinstance(value, bool):
        return "bool", 0
    if value is None:
        return "null", 0
    if isinstance(value, str):
        return "str", len(value)
    if isinstance(value, (int, float)):
        return "num", 0
    if isinstance(value, list):
        return "arr", len(value)
    if isinstance(value, dict):
        return "obj", len(value)
    raise TypeError(f"not a JSON value: {type(value).__name__}")


def placeholder(value):
    kind, length = _kind_and_len(value)
    sha8 = hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()[:8]
    return f"<rom:{kind}:{length}:{sha8}>"


def _tokens(path):
    if path == "":
        return []
    if not path.startswith("/"):
        raise ValueError(f"bad redact path {path!r}")
    return [t.replace("~1", "/").replace("~0", "~") for t in path[1:].split("/")]


def _apply(node, tokens):
    """Return node with the value(s) at tokens replaced."""
    if not tokens:
        return placeholder(node)
    head, rest = tokens[0], tokens[1:]
    if head == "*":
        if isinstance(node, list):
            return [_apply(item, rest) for item in node]
        if isinstance(node, dict):
            return {k: _apply(v, rest) for k, v in node.items()}
        return node
    if isinstance(node, dict) and head in node:
        out = dict(node)
        out[head] = _apply(node[head], rest)
        return out
    return node


def redact_data(data, paths):
    out = copy.deepcopy(data)
    for path in paths:
        out = _apply(out, _tokens(path))
    return out


def redact_message(message, catalog):
    """Redact an envelope using catalog.json's entry for its type.

    Unknown types have their whole ``data`` replaced, since nothing says which parts are safe.
    """
    out = dict(message)
    entry = catalog["messages"].get(message.get("type"))
    if "data" not in message:
        return out
    if entry is None:
        out["data"] = placeholder(message["data"])
    else:
        out["data"] = redact_data(message["data"], entry["redact"])
    return out
