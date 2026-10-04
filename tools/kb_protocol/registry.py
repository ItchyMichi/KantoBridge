"""Loads protocol/ and checks messages against it.

Problems are reported as (where, kind) pairs: a JSON Pointer into the message and the failing
schema keyword. They never include values, so they are safe to print even for ROM-derived data
(docs/PLAN.md section 0.3).
"""
import json
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from urllib.parse import urldefrag, urljoin

from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

from .paths import PROTOCOL_DIR

BASE = "https://kantobridge.invalid/protocol/"
LEGS = ("g2m", "m2g", "c2s", "s2c")
RE_POLICIES = ("required", "optional", "never")


@dataclass(frozen=True)
class Problem:
    where: str
    kind: str

    def __str__(self):
        return f"{self.where or '/'}: {self.kind}"


def _pointer(parts):
    out = ""
    for part in parts:
        out += "/" + str(part).replace("~", "~0").replace("/", "~1")
    return out


def _read_json(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


class Protocol:
    """The schemas of one protocol/ directory."""

    def __init__(self, root=PROTOCOL_DIR):
        self.root = Path(root)
        self.envelope = _read_json(self.root / "envelope.schema.json")
        self.defs = _read_json(self.root / "defs.schema.json")
        self.messages = {}
        for path in sorted((self.root / "messages").glob("*.schema.json")):
            schema = _read_json(path)
            self.messages[schema["x-type"]] = schema
        # Export resources (the `data` of export.data, per kind); see docs/PROTOCOL.md "Export".
        self.exports = {}
        for path in sorted((self.root / "export").glob("*.schema.json")):
            schema = _read_json(path)
            self.exports[schema["x-export-kind"]] = schema
        # The builder's WorldIndex (world.index parts, <world>/kantobridge/world_index.json) and slot map.
        self.world_index = _read_json(self.root / "world" / "world_index.schema.json")
        self.slot_map = _read_json(self.root / "world" / "slot_map.schema.json")
        # The voxel plan's shape index (<world>/kantobridge/shape_index.json, world.shapes doc "index").
        self.shape_index = _read_json(self.root / "world" / "shape_index.schema.json")
        self.documents = {self.envelope["$id"]: self.envelope, self.defs["$id"]: self.defs,
                          self.world_index["$id"]: self.world_index, self.slot_map["$id"]: self.slot_map,
                          self.shape_index["$id"]: self.shape_index}
        for schema in (*self.messages.values(), *self.exports.values()):
            self.documents[schema["$id"]] = schema
        self.registry = Registry().with_resources(
            (uri, Resource.from_contents(doc, default_specification=DRAFT202012))
            for uri, doc in self.documents.items()
        )
        self._envelope_validator = Draft202012Validator(self.envelope, registry=self.registry)
        self._validators = {
            t: Draft202012Validator(s, registry=self.registry) for t, s in self.messages.items()
        }
        self._export_validators = {
            k: Draft202012Validator(s, registry=self.registry) for k, s in self.exports.items()
        }
        self._world_index_validator = Draft202012Validator(self.world_index, registry=self.registry)
        self._slot_map_validator = Draft202012Validator(self.slot_map, registry=self.registry)
        self._shape_index_validator = Draft202012Validator(self.shape_index, registry=self.registry)

    @property
    def types(self):
        return sorted(self.messages)

    def legs(self, msg_type):
        return tuple(self.messages[msg_type]["x-legs"])

    def check(self, message, leg=None):
        """Return a list of Problems; empty means the message is valid (for that leg, if given)."""
        problems = [
            Problem(_pointer(e.absolute_path), e.validator)
            for e in sorted(self._envelope_validator.iter_errors(message), key=lambda e: list(e.path))
        ]
        if problems:
            return problems
        msg_type = message["type"]
        schema = self.messages.get(msg_type)
        if schema is None:
            return [Problem("/type", "unknown_type")]
        if leg is not None:
            if leg not in LEGS:
                raise ValueError(f"unknown leg {leg!r}")
            if leg not in schema["x-legs"]:
                problems.append(Problem("/type", "wrong_leg"))
        policy = schema["x-re"]
        if policy == "required" and "re" not in message:
            problems.append(Problem("/re", "re_required"))
        if policy == "never" and "re" in message:
            problems.append(Problem("/re", "re_forbidden"))
        for e in sorted(self._validators[msg_type].iter_errors(message["data"]), key=lambda e: list(e.path)):
            problems.append(Problem("/data" + _pointer(e.absolute_path), e.validator))
        return problems

    def check_export(self, kind, data):
        """Problems of one export resource against protocol/export/<kind>.schema.json."""
        validator = self._export_validators.get(kind)
        if validator is None:
            return [Problem("", "unknown_export_kind")]
        return [Problem(_pointer(e.absolute_path), e.validator)
                for e in sorted(validator.iter_errors(data), key=lambda e: list(e.path))]

    def check_world_index(self, data):
        """Problems of a WorldIndex against protocol/world/world_index.schema.json."""
        return [Problem(_pointer(e.absolute_path), e.validator)
                for e in sorted(self._world_index_validator.iter_errors(data), key=lambda e: list(e.path))]

    def check_slot_map(self, data):
        """Problems of a slot map against protocol/world/slot_map.schema.json."""
        return [Problem(_pointer(e.absolute_path), e.validator)
                for e in sorted(self._slot_map_validator.iter_errors(data), key=lambda e: list(e.path))]

    def check_shape_index(self, data):
        """Problems of a shape index against protocol/world/shape_index.schema.json."""
        return [Problem(_pointer(e.absolute_path), e.validator)
                for e in sorted(self._shape_index_validator.iter_errors(data), key=lambda e: list(e.path))]

    def resolve(self, base_uri, ref):
        """Resolve a $ref against base_uri; returns (uri, subschema)."""
        target = urljoin(base_uri, ref)
        uri, fragment = urldefrag(target)
        node = self.documents[uri]
        if fragment:
            for token in fragment.lstrip("/").split("/"):
                node = node[token.replace("~1", "/").replace("~0", "~")]
        return uri, node


@lru_cache(maxsize=None)
def load_protocol(root=None):
    return Protocol(PROTOCOL_DIR if root is None else root)
