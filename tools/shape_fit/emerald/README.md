# Emerald's copy of the production pipeline

A copy of Crystal's pipeline scripts (tools/shape_fit/crystal/, itself Blue's with Crystal's changes), taken
2026-10-19 at 7f7811c, to be changed for Emerald's artwork without touching Blue's or Crystal's. Run these for
Emerald. The game code (FittedBuildings, geom/BoxBuilding, the planner) is shared.

Inherited from Crystal (crystal/README.md): striped tile roofs as one line, storey eaves, low gates half roof.
Emerald's own changes (docs/specs/PRODUCTION_PIPELINE_EMERALD.md has the reasons):

- building_model.py detect(): roof from wall by colour (Emerald has no black outline); buildings_table.py gives
  "outline": 2.
- parts_model.py: buildings as parts (copied from tools/shape_fit/parts_model.py at 5aeaf0f): Emerald's building
  method (PRODUCTION_PIPELINE_EMERALD.md, "Buildings as parts").
- render_maps.py: the map pictures and cell sprites drawn from the export's quarter pages, not screenshots.
