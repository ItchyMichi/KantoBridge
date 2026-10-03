"""Deterministic JSON text.

``canonical`` is the form hashed by redaction (see protocol/README.md): sorted keys, ``,``/``:``
separators, no whitespace, UTF-8 with non-ASCII characters written as themselves (not escaped).

``pretty`` is the checked-in file format: 2-space indent, and any container whose one-line form
fits within the width stays on one line.
"""
import json

WIDTH = 100


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def pretty(value, width=WIDTH):
    return _fmt(value, 0, 0, width) + "\n"


def _flat(value):
    return json.dumps(value, ensure_ascii=False, separators=(", ", ": "), allow_nan=False)


def _fmt(value, level, prefix_len, width):
    flat = _flat(value)
    if not isinstance(value, (dict, list)) or not value or level * 2 + prefix_len + len(flat) <= width:
        return flat
    pad = "  " * (level + 1)
    if isinstance(value, dict):
        lines = []
        for key, item in value.items():
            head = json.dumps(key, ensure_ascii=False) + ": "
            lines.append(pad + head + _fmt(item, level + 1, len(head), width))
        return "{\n" + ",\n".join(lines) + "\n" + "  " * level + "}"
    lines = [pad + _fmt(item, level + 1, 0, width) for item in value]
    return "[\n" + ",\n".join(lines) + "\n" + "  " * level + "]"
