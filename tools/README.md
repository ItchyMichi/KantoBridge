# tools/

Test stand-ins, so each half of the bridge can be tested alone (DESIGN.md §9):

- **`fake_gen1`**: pretends to be Gen1Recomp plus `gen1-bridge`. It speaks the protocol to
  `fabric-bridge`, so the Minecraft side can be tested without Gen1Recomp or a ROM.
- **`fake_minecraft`**: pretends to be Minecraft plus `fabric-bridge`. It speaks the protocol to
  `gen1-bridge`, so the Gen1Recomp side can be tested without Minecraft.

Both follow [`docs/PROTOCOL.md`](../docs/PROTOCOL.md) and the schemas in
[`protocol/`](../protocol/README.md). Neither may contain or produce ROM data. Use made-up maps, text
and flags.

Empty for now.
