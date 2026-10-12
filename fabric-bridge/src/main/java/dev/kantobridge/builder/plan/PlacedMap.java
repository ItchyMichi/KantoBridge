package dev.kantobridge.builder.plan;

import dev.kantobridge.world.MapPlacement;

/** A map with its place in the world, its cell model and its tile-pair edges (docs/specs/CAVES_3D_PLAN.md §3.2). */
public record PlacedMap(MapPlacement placement, MapModel model, PairEdges edges) {
	public PlacedMap {
		java.util.Objects.requireNonNull(edges, "edges");
	}

	/** A map without tile-pair edges. */
	public PlacedMap(MapPlacement placement, MapModel model) {
		this(placement, model, PairEdges.NONE);
	}

	public String id() {
		return this.placement.id();
	}

	public boolean interior() {
		return this.placement.kind() == MapPlacement.Kind.INTERIOR;
	}

	/** The atlas image id of the map's tileset. */
	public String atlas() {
		return this.model.tileset().image();
	}

	public String palette() {
		return this.placement.palette();
	}
}
