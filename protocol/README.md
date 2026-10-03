# protocol/

This folder will hold the shared JSON message schemas used by both halves of the bridge:
`gen1-bridge` (Lua, inside Gen1Recomp) and `fabric-bridge` (Java, inside Minecraft).

- [`docs/PROTOCOL.md`](../docs/PROTOCOL.md) is the human-readable source of truth. The schemas here
  must always match it.
- Both halves, and the test stand-ins in [`tools/`](../tools/README.md), validate against these schemas.
- Schemas describe message shapes only. They never contain ROM data.

Empty for now.
