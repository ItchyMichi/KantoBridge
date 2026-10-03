# protocol/

The shared JSON message schemas for both halves of the bridge: `gen1-bridge` (Lua, inside
Gen1Recomp) and `fabric-bridge` (Java, inside Minecraft), plus the test stand-ins in
[`tools/`](../tools/README.md).

- [`docs/PROTOCOL.md`](../docs/PROTOCOL.md) is the human-readable description. These schemas are the
  machine-readable one, and the two must always agree.
- To change the protocol, change PROTOCOL.md and the schemas first, then both sides of the bridge.
- **Schemas and examples never contain ROM data.** Examples use made-up maps (`TEST_TOWN`), text and
  flags.

## Layout

| Path | Contents |
|---|---|
| `envelope.schema.json` | The envelope every message travels in |
| `defs.schema.json` | Shared definitions (`$defs`): ids, directions, cells, Pokémon fields |
| `messages/<type>.schema.json` | One schema per message type, describing its `data` object |
| `examples/<type>.json` | A JSON array of complete, synthetic example envelopes for that type |
| `export/<kind>.schema.json` | The `data` of one export resource kind (`export.data`); docs/PROTOCOL.md "Export resources". The synthetic world in `tools/fake_gen1/synth.py` exercises every kind |
| `catalog.json` | **Derived** from the message schemas by `tools/kb_protocol/catalog.py`. Checked in so Java can bundle it. A test fails when it is stale |

All schemas are JSON Schema **2020-12**. Their `$id`s live under
`https://kantobridge.invalid/protocol/` (the `.invalid` TLD is reserved, so nothing is ever
fetched). Message schemas reference shared definitions as `../defs.schema.json#/$defs/<name>`.

## The envelope

```json
{"type": "player.pos", "seq": 42, "re": 7, "data": {}}
```

| Field | Meaning |
|---|---|
| `type` | The message type: lowercase dotted words, e.g. `ui.open` |
| `seq` | Integer ≥ 1, counted per sender and per connection |
| `re` | Present on replies only: the `seq` of the message being answered |
| `data` | An object, validated by `messages/<type>.schema.json`. Empty data is `{}` |

## Custom keywords in message schemas

| Keyword | Where | Meaning |
|---|---|---|
| `x-type` | top level | The message type; equals the file name and `title` |
| `x-legs` | top level | The hops the message travels: `g2m` (gen1-bridge → Minecraft client), `m2g` (client → gen1-bridge), `c2s` (client → Minecraft server, Fabric payload), `s2c` (server → client) |
| `x-re` | top level | `"required"`, `"optional"` or `"never"`: whether the envelope's `re` must be set |
| `x-rom-derived` | any property | `true` marks ROM-derived content (text, names, trainer teams, map and tile data, images, encounter data). Logs and test output must redact it |

## catalog.json

Generated, sorted and deterministic:

```json
{
  "protocol": 1,
  "limits": {"maxLineBytes": 4194304, "exportPageBytes": 1048576, "relayMaxBytes": 262144},
  "heartbeat": {"pingMs": 2000, "timeoutMs": 10000},
  "messages": {
    "player.pos": {"legs": ["g2m"], "re": "never", "rom": false, "redact": [], "schema": "messages/player.pos.schema.json"},
    "ui.open": {"legs": ["g2m"], "re": "never", "rom": true, "redact": ["/items", "/pages"], "schema": "messages/ui.open.schema.json"}
  }
}
```

- `rom` is true when the message has at least one `x-rom-derived` field.
- `redact` lists the ROM-derived locations inside `data` as JSON Pointers, where `*` matches every
  array element or every object value (e.g. `/list/*/name`). They are sorted.

## Redaction

Redaction replaces each value found at a `redact` path with a placeholder string:

```
<rom:KIND:LEN:SHA8>
```

- `KIND` is `str`, `arr`, `obj`, `num`, `bool` or `null`.
- `LEN` is the string length in Unicode code points, the array length or the object's key count; 0
  otherwise.
- `SHA8` is the first 8 hex digits of the SHA-256 of the value's canonical JSON: sorted keys, `,` and
  `:` separators, no whitespace, UTF-8 without `\u` escapes for non-ASCII. The protocol carries no
  fractional numbers, so the hash is the same in Python, Java and Lua.

Missing paths are skipped. Redaction never changes anything outside the listed paths.
