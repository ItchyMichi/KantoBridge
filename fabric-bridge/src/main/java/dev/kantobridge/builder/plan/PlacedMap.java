package dev.kantobridge.builder.plan;

import dev.kantobridge.world.MapPlacement;

/** A map with its place in the world and its cell model. */
public record PlacedMap(MapPlacement placement, MapModel model) {
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
