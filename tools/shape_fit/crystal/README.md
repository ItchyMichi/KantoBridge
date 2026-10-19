# Crystal's copy of the production pipeline

A copy of Blue's pipeline scripts (tools/shape_fit/*, as run for Blue: docs/specs/PRODUCTION_PIPELINE_BLUE.md),
taken 2026-10-19 at 49b2977, to be changed for Crystal's artwork without touching Blue's. Run these for Crystal;
Blue keeps running the ones in tools/shape_fit/. The game code (FittedBuildings, geom/BoxBuilding, the planner) is
shared: it builds from the numbers each game's table carries.

Changes from Blue's (docs/specs/PRODUCTION_PIPELINE_CRYSTAL.md has the reasons):

- building_model.py detect(): a run of near-full double lines two rows apart (a striped tile roof, Ecruteak's)
  counts as one line, the roof ending after it (within 70% of the rows); each later run is one band (a storey's
  eave); a building up to two cells deep with no roof line is half roof (a gate), not all wall.
- batch_entities.sh runs from its own folder.
- merge_voxels.py, buildings_table.py: the repository is three folders up.
