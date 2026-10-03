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
        self.documents = {self.envelope["$id"]: self.envelope, self.defs["$id"]: self.defs}
        for schema in self.messages.values():
            self.documents[schema["$id"]] = schema
        self.registry = Registry().with_resources(
            (uri, Resource.from_contents(doc, default_specification=DRAFT202012))
            for uri, doc in self.documents.items()
        )
        self._envelope_validator = Draft202012Validator(self.envelope, registry=self.registry)
        self._validators = {
            t: Draft202012Validator(s, registry=self.registry) for t, s in self.messages.items()
        }

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
