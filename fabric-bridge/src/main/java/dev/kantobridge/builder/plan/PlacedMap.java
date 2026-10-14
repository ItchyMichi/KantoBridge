package dev.kantobridge.builder.plan;

import dev.kantobridge.scene.gen3.QuarterPages;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import org.jetbrains.annotations.Nullable;

/**
 * A map with its place in the world, its cell model, its tile-pair edges (docs/specs/CAVES_3D_PLAN.md §3.2) and, for a
 * cave built in 3D, its layout: each cell's level and the ramps (§3.4); null when every cell sits at the placement.
 */
public record PlacedMap(MapPlacement placement, MapModel model, PairEdges edges, @Nullable MapLayout layout) {
	public PlacedMap {
		java.util.Objects.requireNonNull(edges, "edges");
	}

	/** A map without a layout. */
	public PlacedMap(MapPlacement placement, MapModel model, PairEdges edges) {
		this(placement, model, edges, null);
	}

	/** A map without tile-pair edges. */
	public PlacedMap(MapPlacement placement, MapModel model) {
		this(placement, model, PairEdges.NONE);
	}

	/** A cell's level: blocks above the placement's feet (0 without a layout). */
	public int level(int cx, int cy) {
		return this.layout == null ? 0 : this.layout.level(cx, cy);
	}

	public String id() {
		return this.placement.id();
	}

	public boolean interior() {
		return this.placement.kind() == MapPlacement.Kind.INTERIOR;
	}

	/**
	 * The atlas the map's art tiles index: its tileset's image (Gen 1), or the whole Gen 3 art's virtual atlas
	 * ({@link QuarterPages#VIRTUAL}, whose tiles are quarter ids).
	 */
	public String atlas() {
		return this.model.gen3() ? QuarterPages.VIRTUAL : this.model.tileset().image();
	}

	/** The atlas a slot or texture key names for one of the map's art tiles: its page for Gen 3 art. */
	public String atlasOf(int tile) {
		return QuarterPages.atlasOf(this.atlas(), tile);
	}

	/** The tile index a slot or texture key names for one of the map's art tiles: its index within its page for Gen 3. */
	public int indexOf(int tile) {
		return QuarterPages.tileOf(this.atlas(), tile);
	}

	public String palette() {
		return this.placement.palette();
	}
}
