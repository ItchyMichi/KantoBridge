package dev.kantobridge.client.zone;

import com.google.gson.JsonObject;
import dev.kantobridge.client.puppet.PuppetManager;
import dev.kantobridge.story.RouteProgress;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * What the client shows and does in a Wild Routes zone (docs/specs/WILD_ROUTES_PLAN.md §4.6, WR5), without Minecraft:
 * which zone a position is in, the zone's puppets for this player's progress (every trainer at its post, each ball not
 * taken yet), the cell of the zone's route-map grid under a position, and the ball an A press or a click reaches.
 * Plain Java.
 */
public final class ZoneView {
	public static final String KIND_TRAINER = "trainer";
	public static final String KIND_ITEM = "item";

	private ZoneView() {
	}

	/** The zone whose box holds block column (x, z), or null. */
	public static @Nullable WorldIndex.Zone zoneAt(WorldIndex index, double x, double z) {
		return index.zoneAt((int) Math.floor(x), (int) Math.floor(z));
	}

	/** A zone object's puppet id. */
	public static String puppetId(WorldIndex.Zone zone, String objectId) {
		return PuppetManager.ZONE_PREFIX + zone.id() + ":" + objectId;
	}

	/** The object id of one of {@code zone}'s puppets, or null for any other puppet id. */
	public static @Nullable String objectOf(WorldIndex.Zone zone, String puppetId) {
		String prefix = PuppetManager.ZONE_PREFIX + zone.id() + ":";
		return puppetId.startsWith(prefix) ? puppetId.substring(prefix.length()) : null;
	}

	/** The zone's puppets for a player with {@code progress}: every trainer at its post, each ball not taken yet. */
	public static List<PuppetManager.ZoneSpec> specs(WorldIndex.Zone zone, RouteProgress progress) {
		List<PuppetManager.ZoneSpec> out = new ArrayList<>();
		for (WorldIndex.ZoneTrainer t : zone.trainers()) {
			out.add(new PuppetManager.ZoneSpec(puppetId(zone, t.id()), zone.routeMap(), t.sprite(), KIND_TRAINER, t.x(), t.y(), t.facing()));
		}
		if (zone.ballSprite() != null) {
			for (WorldIndex.ZoneBall b : zone.balls()) {
				if (!progress.taken(zone.id(), b.id())) {
					out.add(new PuppetManager.ZoneSpec(puppetId(zone, b.id()), zone.routeMap(), zone.ballSprite(), KIND_ITEM, b.x(), b.y(), Dir.DOWN));
				}
			}
		}
		return out;
	}

	/** The cell of the zone's route-map grid under block position (x, z), or null when the index lacks the route map. */
	public static int @Nullable [] cellAt(WorldIndex index, WorldIndex.Zone zone, double x, double z) {
		MapPlacement routeMap = index.map(zone.routeMap());
		if (routeMap == null) {
			return null;
		}
		return new int[] {Math.floorDiv((int) Math.floor(x) - routeMap.x(), Scale.CELL_BLOCKS),
				Math.floorDiv((int) Math.floor(z) - routeMap.z(), Scale.CELL_BLOCKS)};
	}

	/** The ball at cell (cx, cy) of the zone's grid, unless the player took it. */
	public static WorldIndex.@Nullable ZoneBall ballAt(WorldIndex.Zone zone, RouteProgress progress, int cx, int cy) {
		for (WorldIndex.ZoneBall b : zone.balls()) {
			if (b.x() == cx && b.y() == cy && !progress.taken(zone.id(), b.id())) {
				return b;
			}
		}
		return null;
	}

	/** The ball in the cell in front of a player at (x, z) facing {@code facing}, unless they took it. */
	public static WorldIndex.@Nullable ZoneBall facedBall(WorldIndex index, WorldIndex.Zone zone, RouteProgress progress, double x, double z,
			Dir facing) {
		int[] cell = cellAt(index, zone, x, z);
		return cell == null ? null : ballAt(zone, progress, cell[0] + facing.dx(), cell[1] + facing.dy());
	}

	/** True when a player at (x, z) stands in a cell beside cell (cx, cy) of the zone's grid. */
	public static boolean nextTo(WorldIndex index, WorldIndex.Zone zone, double x, double z, int cx, int cy) {
		int[] cell = cellAt(index, zone, x, z);
		return cell != null && Math.abs(cell[0] - cx) + Math.abs(cell[1] - cy) == 1;
	}

	/** {@code route.give}'s data for a ball of the zone. */
	public static JsonObject give(WorldIndex.Zone zone, WorldIndex.ZoneBall ball) {
		JsonObject data = new JsonObject();
		data.addProperty("route", zone.id());
		data.addProperty("id", ball.id());
		data.addProperty("item", ball.item());
		return data;
	}
}
