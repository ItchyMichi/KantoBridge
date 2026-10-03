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

	/** A horizontal box in blocks, max exclusive. */
	public record Box(int minX, int minZ, int maxX, int maxZ) {
		public boolean separated(Box other, int gap) {
			return this.maxX + gap <= other.minX || other.maxX + gap <= this.minX || this.maxZ + gap <= other.minZ
					|| other.maxZ + gap <= this.minZ;
		}
	}

	/** Where an interior went: the block origin of its cell (0, 0), and its layer. */
	public record Spot(int x, int z, int layer, boolean moved) {
	}

	private record Offset(int dx, int dz) {
	}

	private final int maxLayer;
	private final Map<Integer, List<Box>> occupied = new HashMap<>();

	public InteriorPacker(int maxLayer) {
		this.maxLayer = maxLayer;
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
		if (minLayer < 1 || minLayer > this.maxLayer) {
			return null;
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
