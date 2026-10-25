package dev.kantobridge.builder.plan;

import dev.kantobridge.block.EdgeWallBlock;
import dev.kantobridge.builder.export.G3Map;
import dev.kantobridge.builder.export.G3Tiles;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The seam walls (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §14, SC1): where a surface map's edge cell meets the world
 * beyond its side and the game refuses that step, the cell's outer side is walled, as {@link PairEdges#withBorders} walls
 * the sides with no connection. The game refuses it where nothing of a neighbour's lies beyond (a connected side past the
 * end of the neighbour's strip: Gen 2 and Gen 3 stop there; Gen 1's engine clamps onto the neighbour's nearest cell, which
 * the original game's border tiles stop, so it is walled too), where a walker would step onto the neighbour's water below,
 * and where the game's own rule ({@link Refusal}) closes the step between the two maps' cells. Run after the chain, on the
 * maps' final places. Pure Java.
 */
public final class SeamEdges {
	private SeamEdges() {
	}

	/** A game's own reason to refuse the step {@code d} from cell (ax, ay) of {@code a} to cell (bx, by) of {@code b}, two maps' touching cells. */
	@FunctionalInterface
	public interface Refusal {
		boolean refuses(PlacedMap a, int ax, int ay, PlacedMap b, int bx, int by, Dir d);

		Refusal NONE = (a, ax, ay, b, bx, by, d) -> false;
	}

	/** {@code placed}'s surface maps with their seam walls added, in place; notes {@code seam_walls} (sides walled) and {@code seam_walls_unmet} (of them, with nothing beyond). */
	public static void apply(List<PlacedMap> placed, Refusal refusal, Warnings warnings) {
		List<Integer> surface = new ArrayList<>();
		for (int i = 0; i < placed.size(); i++) {
			if (placed.get(i).placement().kind() == MapPlacement.Kind.SURFACE) {
				surface.add(i);
			}
		}
		// 64-block squares -> the surface maps over them
		Map<Long, List<Integer>> index = new HashMap<>();
		for (int i : surface) {
			MapPlacement p = placed.get(i).placement();
			for (int qx = Math.floorDiv(p.x(), 64); qx <= Math.floorDiv(p.x() + p.blockWidth() - 1, 64); qx++) {
				for (int qz = Math.floorDiv(p.z(), 64); qz <= Math.floorDiv(p.z() + p.blockDepth() - 1, 64); qz++) {
					index.computeIfAbsent(square(qx, qz), k -> new ArrayList<>()).add(i);
				}
			}
		}
		int walled = 0;
		int unmet = 0;
		int c = Scale.CELL_BLOCKS;
		for (int i : surface) {
			PlacedMap a = placed.get(i);
			MapPlacement p = a.placement();
			int w = p.w();
			int h = p.h();
			byte[] add = new byte[w * h];
			int here = 0;
			for (int cy = 0; cy < h; cy++) {
				for (int cx = 0; cx < w; cx++) {
					MapModel.Kind kind = a.model().kind(cx, cy);
					if (!standable(kind)) {
						continue;
					}
					for (Dir d : Dir.values()) {
						int nx = cx + d.dx();
						int ny = cy + d.dy();
						if (nx >= 0 && ny >= 0 && nx < w && ny < h) {
							continue;
						}
						int side = side(d);
						if ((a.edges().sides(cx, cy) & side) != 0) {
							continue;
						}
						// the cell beyond: the first block column just past this cell's edge that a surface map holds
						PlacedMap b = null;
						int bx = 0;
						int by = 0;
						for (int k = 0; k < c && b == null; k++) {
							int x = p.x() + cx * c + (d.dx() > 0 ? c : d.dx() < 0 ? -1 : k);
							int z = p.z() + cy * c + (d.dy() > 0 ? c : d.dy() < 0 ? -1 : k);
							for (int j : index.getOrDefault(square(Math.floorDiv(x, 64), Math.floorDiv(z, 64)), List.of())) {
								MapPlacement q = placed.get(j).placement();
								if (j != i && q.containsColumn(x, z)) {
									b = placed.get(j);
									bx = (x - q.x()) / c;
									by = (z - q.z()) / c;
									break;
								}
							}
						}
						boolean refused;
						if (b == null) {
							refused = true;
							unmet++;
						} else {
							MapModel.Kind there = b.model().kind(bx, by);
							refused = walked(kind) && there == MapModel.Kind.WATER && feet(b, bx, by) < feet(a, cx, cy)
									|| refusal.refuses(a, cx, cy, b, bx, by, d);
						}
						if (refused) {
							add[cy * w + cx] |= (byte) side;
							here++;
						}
					}
				}
			}
			if (here > 0) {
				walled += here;
				placed.set(i, new PlacedMap(p, a.model(), PairEdges.plus(a.edges(), a.model(), add), a.layout()));
			}
		}
		warnings.note("seam_walls", walled);
		warnings.note("seam_walls_unmet", unmet);
	}

	/**
	 * Gen 3's refusal across a seam ({@code Collision.tryConnection}, game3/collision.lua): a walker never lands on a ledge
	 * cell (the engine's collision check refuses a hop from below there) and a side the tiles close stays closed, leaving
	 * (MB_IMPASSABLE_*) or entering; a surfer stays on water of its own elevation. Elevations are no walker's business
	 * at a seam.
	 */
	public static Refusal gen3(Map<String, G3Map> maps, Function<String, G3Tiles> tiles) {
		return (a, ax, ay, b, bx, by, d) -> {
			if (walked(a.model().kind(ax, ay)) && b.model().kind(bx, by) == MapModel.Kind.LEDGE) {
				return true;
			}
			G3Map ma = maps.get(a.id());
			G3Map mb = maps.get(b.id());
			if (ma != null && mb != null && a.model().kind(ax, ay) == MapModel.Kind.WATER && b.model().kind(bx, by) == MapModel.Kind.WATER
					&& PairEdges.elevationsRefuse(ma.elev(ax, ay), mb.elev(bx, by))) {
				// a surfer onto water of another elevation (event_object_movement.c:8346 in the engine's notes)
				return true;
			}
			G3Tiles ta = ma == null ? null : tiles.apply(a.id());
			G3Tiles tb = mb == null ? null : tiles.apply(b.id());
			if (ta == null || tb == null) {
				return false;
			}
			int from = ta.behavior(ma.mid(ax, ay)) & 0xFF;
			int into = tb.behavior(mb.mid(bx, by)) & 0xFF;
			return switch (d) {
				case RIGHT -> PairEdges.eastBlocked(from) || PairEdges.westBlocked(into);
				case LEFT -> PairEdges.westBlocked(from) || PairEdges.eastBlocked(into);
				case DOWN -> PairEdges.southBlocked(from) || PairEdges.northBlocked(into);
				case UP -> PairEdges.northBlocked(from) || PairEdges.southBlocked(into);
			};
		};
	}

	private static int feet(PlacedMap m, int cx, int cy) {
		return WorldPlan.cellOrigin(m.placement(), m.layout(), cx, cy)[1];
	}

	private static int side(Dir d) {
		return switch (d) {
			case UP -> EdgeWallBlock.SIDE_NORTH;
			case DOWN -> EdgeWallBlock.SIDE_SOUTH;
			case LEFT -> EdgeWallBlock.SIDE_WEST;
			case RIGHT -> EdgeWallBlock.SIDE_EAST;
		};
	}

	private static boolean walked(MapModel.Kind kind) {
		return kind == MapModel.Kind.GROUND || kind == MapModel.Kind.LEDGE;
	}

	private static boolean standable(MapModel.Kind kind) {
		return walked(kind) || kind == MapModel.Kind.WATER;
	}

	private static long square(int qx, int qz) {
		return ((long) qx << 32) ^ (qz & 0xffffffffL);
	}
}
