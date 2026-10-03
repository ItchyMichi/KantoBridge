package dev.kantobridge.builder.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Packs interiors into the layers below the surface (docs/PLAN.md §2.2, §4.5 "Interiors").
 *
 * <p>Each interior wants a spot right under its entrance door, one layer below its parent (so floor chains stack
 * downwards). Interiors on one layer keep at least {@link #GAP} blocks apart, counting their enclosure ring. When the
 * spot is taken the interior spills to a deeper layer at the same position, and only when every layer is taken there
 * does it move sideways, in rings of {@link #SIDEWAYS_STEP} blocks, nearest first.
 *
 * <p>A chain can run out of depth: its parent already sits on the deepest layer (long floor chains pushed down by
 * spills, e.g. under a crowded city). PLAN §8's fallback then puts the interior on that deepest layer, moved sideways
 * to the nearest free spot under the void fill, outside every surface map's footprint ({@link Spot#deep()}).
 */
public final class InteriorPacker {
	/** Minimum horizontal distance between two interiors' outer walls on one layer. */
	public static final int GAP = 16;
	/** The enclosure ring around every interior, in blocks. */
	public static final int RING = 1;
	/** Distance between sideways candidate spots. */
	public static final int SIDEWAYS_STEP = 16;
	/** How many rings of sideways spots to try before giving up. */
	public static final int MAX_RINGS = 64;
	/** Rings tried for a spot under the void fill: enough to leave any surface map (Kanto is about 700 blocks wide). */
	public static final int MAX_DEEP_RINGS = 128;

	/** A horizontal box in blocks, max exclusive. */
	public record Box(int minX, int minZ, int maxX, int maxZ) {
		public boolean separated(Box other, int gap) {
			return this.maxX + gap <= other.minX || other.maxX + gap <= this.minX || this.maxZ + gap <= other.minZ
					|| other.maxZ + gap <= this.minZ;
		}
	}

	/**
	 * Where an interior went: the block origin of its cell (0, 0), and its layer.
	 *
	 * @param moved not under its door (sideways)
	 * @param deep  no layer was deep enough: on the deepest layer, under the void fill
	 */
	public record Spot(int x, int z, int layer, boolean moved, boolean deep) {
		public Spot(int x, int z, int layer, boolean moved) {
			this(x, z, layer, moved, false);
		}
	}

	private record Offset(int dx, int dz) {
	}

	private final int maxLayer;
	private final List<Box> surface;
	private final Map<Integer, List<Box>> occupied = new HashMap<>();

	public InteriorPacker(int maxLayer) {
		this(maxLayer, List.of());
	}

	/**
	 * @param surface the surface maps' footprints (no ring): a deep spill keeps out from under them
	 */
	public InteriorPacker(int maxLayer, List<Box> surface) {
		this.maxLayer = maxLayer;
		this.surface = List.copyOf(surface);
	}

	public int maxLayer() {
		return this.maxLayer;
	}

	/** The footprint of an interior whose cell (0, 0) is at block (x, z), ring included. */
	public static Box footprint(int x, int z, int blockWidth, int blockDepth) {
		return new Box(x - RING, z - RING, x + blockWidth + RING, z + blockDepth + RING);
	}

	/**
	 * Finds and reserves a spot for an interior of {@code blockWidth × blockDepth} blocks that would like its origin at
	 * ({@code x}, {@code z}) on layer {@code minLayer} or deeper. Null when nothing fits.
	 */
	public @Nullable Spot place(int x, int z, int blockWidth, int blockDepth, int minLayer) {
		if (minLayer < 1 || this.maxLayer < 1) {
			return null;
		}
		if (minLayer > this.maxLayer) {
			return this.placeDeep(x, z, blockWidth, blockDepth);
		}
		Spot spot = this.tryAt(x, z, blockWidth, blockDepth, minLayer, false);
		if (spot != null) {
			return spot;
		}
		for (int ring = 1; ring <= MAX_RINGS; ring++) {
			for (Offset offset : ringOffsets(ring)) {
				spot = this.tryAt(x + offset.dx() * SIDEWAYS_STEP, z + offset.dz() * SIDEWAYS_STEP, blockWidth, blockDepth, minLayer, true);
				if (spot != null) {
					return spot;
				}
			}
		}
		return null;
	}

	/**
	 * PLAN §8 "Interior packing can't fit in depth": the deepest layer, at the nearest spot (rings of
	 * {@link #SIDEWAYS_STEP} blocks) whose footprint lies outside every surface map, so under the void fill.
	 */
	private @Nullable Spot placeDeep(int x, int z, int blockWidth, int blockDepth) {
		for (int ring = 0; ring <= MAX_DEEP_RINGS; ring++) {
			for (Offset offset : ring == 0 ? List.of(new Offset(0, 0)) : ringOffsets(ring)) {
				int sx = x + offset.dx() * SIDEWAYS_STEP;
				int sz = z + offset.dz() * SIDEWAYS_STEP;
				Box box = footprint(sx, sz, blockWidth, blockDepth);
				if (this.underSurface(box) || !this.fits(this.maxLayer, box)) {
					continue;
				}
				this.occupied.computeIfAbsent(this.maxLayer, k -> new ArrayList<>()).add(box);
				return new Spot(sx, sz, this.maxLayer, true, true);
			}
		}
		return null;
	}

	private boolean underSurface(Box box) {
		for (Box map : this.surface) {
			if (!box.separated(map, 0)) {
				return true;
			}
		}
		return false;
	}

	private @Nullable Spot tryAt(int x, int z, int blockWidth, int blockDepth, int minLayer, boolean moved) {
		Box box = footprint(x, z, blockWidth, blockDepth);
		for (int layer = minLayer; layer <= this.maxLayer; layer++) {
			if (this.fits(layer, box)) {
				this.occupied.computeIfAbsent(layer, k -> new ArrayList<>()).add(box);
				return new Spot(x, z, layer, moved);
			}
		}
		return null;
	}

	private boolean fits(int layer, Box box) {
		for (Box other : this.occupied.getOrDefault(layer, List.of())) {
			if (!box.separated(other, GAP)) {
				return false;
			}
		}
		return true;
	}

	/** The spots at Chebyshev distance {@code ring}, nearest (Euclidean) first, then north to south, west to east. */
	static List<Offset> ringOffsets(int ring) {
		List<Offset> out = new ArrayList<>(8 * ring);
		for (int dz = -ring; dz <= ring; dz++) {
			for (int dx = -ring; dx <= ring; dx++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
					out.add(new Offset(dx, dz));
				}
			}
		}
		out.sort(Comparator.comparingInt((Offset o) -> o.dx() * o.dx() + o.dz() * o.dz())
				.thenComparingInt(Offset::dz)
				.thenComparingInt(Offset::dx));
		return out;
	}
}
