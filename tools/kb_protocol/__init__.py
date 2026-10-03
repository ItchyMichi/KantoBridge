"""Shared Python helpers for the Kanto Bridge protocol (docs/PROTOCOL.md, protocol/).

- ``jsonfmt``: canonical and pretty JSON.
- ``registry``: loads the JSON schemas and checks messages against them.
- ``catalog``: derives protocol/catalog.json and protocol/redaction-vectors.json.
- ``redact``: replaces ROM-derived fields with ``<rom:KIND:LEN:SHA8>`` placeholders.
- ``framing``: newline-delimited strict JSON.
- ``builders``: envelopes with per-connection sequence numbers.
"""
from .builders import Sender
from .framing import FramingError, LineBuffer, decode_line, encode_line
from .redact import placeholder, redact_data, redact_message
from .registry import Problem, Protocol, load_protocol

__all__ = [
    "FramingError",
    "LineBuffer",
    "Problem",
    "Protocol",
    "Sender",
    "decode_line",
    "encode_line",
    "load_protocol",
    "placeholder",
    "redact_data",
    "redact_message",
]
