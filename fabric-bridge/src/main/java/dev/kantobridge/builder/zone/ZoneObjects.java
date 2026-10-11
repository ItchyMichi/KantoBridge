package dev.kantobridge.builder.zone;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.builder.export.ExportRoutes;
import dev.kantobridge.routes.gen.Cell;
import dev.kantobridge.routes.gen.RouteLayout;
import dev.kantobridge.routes.gen.RouteRng;
import dev.kantobridge.routes.gen.RouteSeeds;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * A zone's trainers and item balls (docs/specs/WILD_ROUTES_PLAN.md §4.3, §4.6, WR5): the cells the layout chose, with
 * what the export gives them. Seeded apart from the layout, so a change here never moves a wall.
 *
 * <ul>
 * <li><b>Trainers:</b> a class from the zone's list (one whose sprite the export knows); 1–2 Pokémon in the first
 * area, 2–3 after it; each from the town's neighbour pool by its share of encounters, 0–3 levels above the area's
 * highest wild level (the pool's highest plus the area's level offset). Named {@code a<area>.t<n>}. A zone whose town has
 * no pool gets no trainers.</li>
 * <li><b>Balls:</b> an item from the tiers the area's level offset draws on ({@link ItemTiers}), keeping only the game's
 * own items. Named {@code a<area>.i<n>}.</li>
 * </ul>
 */
public record ZoneObjects(List<Trainer> trainers, List<Ball> balls, @Nullable String ballSprite) {
	public static final ZoneObjects NONE = new ZoneObjects(List.of(), List.of(), null);
	/** Mixed into the zone's seed for its objects. */
	private static final long SALT = 0x6F626A6563747321L;

	/** A trainer, at a cell of the layout. */
	public record Trainer(String id, Cell cell, Dir facing, int sight, String cls, String sprite, List<WorldIndex.PartyMember> party) {
		public Trainer {
			party = List.copyOf(party);
		}
	}

	/** An item ball, at a cell of the layout. */
	public record Ball(String id, Cell cell, String item) {
	}

	public ZoneObjects {
		trainers = List.copyOf(trainers);
		balls = List.copyOf(balls);
	}

	public static ZoneObjects make(ExportRoutes.Zone zone, RouteLayout layout, ZoneContent content, ItemTiers tiers, long worldSeed) {
		RouteRng rng = new RouteRng(RouteRng.mix(RouteSeeds.forRoute(worldSeed, zone.id()) ^ SALT));
		List<ZoneContent.Slot> pool = content.pool(zone.townMap());
		List<String> classes = new ArrayList<>();
		for (String cls : zone.classes()) {
			if (content.classSprites().containsKey(cls)) {
				classes.add(cls);
			}
		}
		int poolTop = 0;
		for (ZoneContent.Slot slot : pool) {
			poolTop = Math.max(poolTop, slot.level());
		}
		List<Trainer> trainers = new ArrayList<>();
		int[] perArea = new int[layout.areas().size()];
		for (RouteLayout.Trainer t : layout.trainers()) {
			if (pool.isEmpty() || classes.isEmpty()) {
				break;
			}
			String cls = rng.pick(classes);
			int top = poolTop + layout.areas().get(t.area()).levelOffset();
			int size = t.area() == 0 ? rng.between(1, 2) : rng.between(2, 3);
			List<WorldIndex.PartyMember> party = new ArrayList<>();
			for (int i = 0; i < size; i++) {
				ZoneContent.Slot slot = weighted(pool, rng);
				party.add(new WorldIndex.PartyMember(slot.species(), Math.min(100, Math.max(1, top + rng.between(0, 3)))));
			}
			perArea[t.area()]++;
			trainers.add(new Trainer("a" + (t.area() + 1) + ".t" + perArea[t.area()], t.cell(), dir(t.facing()), t.sight(), cls,
					content.classSprites().get(cls), party));
		}
		List<Ball> balls = new ArrayList<>();
		int[] ballsPerArea = new int[layout.areas().size()];
		for (Cell cell : layout.items()) {
			int area = layout.areaOf(cell);
			if (area < 0) {
				continue;
			}
			List<String> choices = new ArrayList<>();
			for (String item : tiers.forLevelOffset(layout.areas().get(area).levelOffset())) {
				if (content.items().contains(item)) {
					choices.add(item);
				}
			}
			if (choices.isEmpty()) {
				continue;
			}
			ballsPerArea[area]++;
			balls.add(new Ball("a" + (area + 1) + ".i" + ballsPerArea[area], cell, rng.pick(choices)));
		}
		return new ZoneObjects(trainers, balls, balls.isEmpty() ? null : content.ballSprite());
	}

	private static ZoneContent.Slot weighted(List<ZoneContent.Slot> pool, RouteRng rng) {
		double total = 0;
		for (ZoneContent.Slot slot : pool) {
			total += slot.weight();
		}
		double roll = rng.nextDouble() * total;
		for (ZoneContent.Slot slot : pool) {
			roll -= slot.weight();
			if (roll < 0) {
				return slot;
			}
		}
		return pool.get(pool.size() - 1);
	}

	static Dir dir(dev.kantobridge.routes.gen.Dir d) {
		return switch (d) {
			case NORTH -> Dir.UP;
			case SOUTH -> Dir.DOWN;
			case WEST -> Dir.LEFT;
			case EAST -> Dir.RIGHT;
		};
	}

	/**
	 * The item tiers (data/kantobridge/wild_routes/items.json): lists of the game's item ids, and which tiers an area
	 * draws on by its level offset (the last entry serves any higher offset).
	 */
	public record ItemTiers(List<List<String>> tiers, List<List<Integer>> byLevelOffset) {
		public static final String RESOURCE = "/data/kantobridge/wild_routes/items.json";

		public ItemTiers {
			tiers = tiers.stream().map(List::copyOf).toList();
			byLevelOffset = byLevelOffset.stream().map(List::copyOf).toList();
			if (byLevelOffset.isEmpty()) {
				throw new IllegalArgumentException("no tiers by level offset");
			}
			for (List<Integer> pick : byLevelOffset) {
				for (int tier : pick) {
					if (tier < 0 || tier >= tiers.size()) {
						throw new IllegalArgumentException("unknown tier");
					}
				}
			}
		}

		/** The items an area with this level offset may hold, in tier order. */
		public List<String> forLevelOffset(int offset) {
			Set<String> out = new LinkedHashSet<>();
			for (int tier : this.byLevelOffset.get(Math.max(0, Math.min(offset, this.byLevelOffset.size() - 1)))) {
				out.addAll(this.tiers.get(tier));
			}
			return List.copyOf(out);
		}

		/** The mod's own table. */
		public static ItemTiers bundled() {
			try (InputStream in = ItemTiers.class.getResourceAsStream(RESOURCE)) {
				if (in == null) {
					throw new IllegalStateException("missing " + RESOURCE);
				}
				return parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		static ItemTiers parse(JsonObject root) {
			if (root.get("version").getAsInt() != 1) {
				throw new IllegalArgumentException("unsupported version");
			}
			List<List<String>> tiers = new ArrayList<>();
			for (JsonElement tier : root.getAsJsonArray("tiers")) {
				List<String> ids = new ArrayList<>();
				for (JsonElement id : tier.getAsJsonArray()) {
					ids.add(id.getAsString());
				}
				tiers.add(ids);
			}
			List<List<Integer>> byOffset = new ArrayList<>();
			for (JsonElement pick : root.getAsJsonArray("byLevelOffset")) {
				List<Integer> list = new ArrayList<>();
				for (JsonElement tier : (JsonArray) pick) {
					list.add(tier.getAsInt());
				}
				byOffset.add(list);
			}
			return new ItemTiers(tiers, byOffset);
		}
	}
}
