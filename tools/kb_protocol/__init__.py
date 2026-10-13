"""Shared Python helpers for the Kanto Bridge protocol (docs/PROTOCOL.md, protocol/).

- ``jsonfmt``: canonical and pretty JSON.
- ``registry``: loads the JSON schemas and checks messages against them.
- ``catalog``: derives protocol/catalog.json and protocol/redaction-vectors.json.
- ``redact``: replaces ROM-derived fields with ``<rom:KIND:LEN:SHA8>`` placeholders.
- ``framing``: newline-delimited strict JSON.
- ``builders``: envelopes with per-connection sequence numbers.
- ``games``: the game catalogue (protocol/games.json) and its Lua copy.
- ``items``: the canonical item catalogue (protocol/items.json) and its Lua copy.
"""
from .builders import Sender
from .games import Games, load_games
from .items import Items, load_items
from .framing import FramingError, LineBuffer, decode_line, encode_line
from .redact import placeholder, redact_data, redact_message
from .registry import Problem, Protocol, load_protocol

__all__ = [
    "FramingError",
    "Games",
    "Items",
    "LineBuffer",
    "Problem",
    "Protocol",
    "Sender",
    "decode_line",
    "encode_line",
    "load_games",
    "load_items",
    "load_protocol",
    "placeholder",
    "redact_data",
    "redact_message",
]
