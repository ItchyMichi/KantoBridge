package dev.kantobridge.builder;

import dev.kantobridge.world.Games;
import dev.kantobridge.world.RegionTable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * How to run one build.
 *
 * <p>Several games (docs/specs/MULTI_GAME_PLAN.md §4.6, MG3): a build is {@code (bundle, region, slot)}. With a
 * {@link #region()} the build is a <b>region build</b>: its root map's cell (0, 0) sits at {@code originX + pitch · slot}
 * (the region table's slot origin, {@link RegionTable#originX}) and its documents go under
 * {@code kantobridge/regions/<id>/}. Without one (the default) it is a <b>legacy build</b>, which writes exactly what it
 * wrote before regions existed (§4.14).
 *
 * @param originX       block X of the root map's cell (0, 0) for a legacy build; the world offset a region build's slot
 *                      origin is added to: 0 in a real world, far away in GameTests
 * @param originZ       block Z likewise
 * @param worldDir      where {@code kantobridge/world_index.json}, the slot map, the report and the datapack go; null
 *                      for the level's world folder
 * @param packDir       the generated resource pack folder; null for {@code <gameDir>/kantobridge/resourcepack}
 * @param reportCopy    also copy {@code build_report.json} here (e.g. {@code <repo>/local/reports/build_report.json})
 * @param setWorldSpawn set the level's default spawn on the spawn cell (a region build does so at slot 0 only)
 * @param writePack     generate the resource pack
 * @param writeDatapack write the per-world datapack
 * @param publishIndex  make the new WorldIndex the one the server sends to joining players (and send it now)
 * @param tickBudgetMs  placement time per server tick (20–30 ms keeps the server responsive)
 * @param shapes        plan the world's shapes (docs/specs/VOXEL_BLOCKS_PLAN.md §5.7): write and publish the shape index
 *                      and shape map; off builds today's world without them (the invariant tests compare the two)
 * @param region        the region this build makes or rebuilds (MG3), or null for a legacy build
 * @param slot          the region's slot in the region table (0..63), or {@link #NO_SLOT} for a legacy build
 */
public record BuildOptions(int originX, int originZ, @Nullable Path worldDir, @Nullable Path packDir, @Nullable Path reportCopy,
		boolean setWorldSpawn, boolean writePack, boolean writeDatapack, boolean publishIndex, int tickBudgetMs, boolean shapes,
		@Nullable String region, int slot) {
	public static final int DEFAULT_TICK_BUDGET_MS = 25;
	/** The slot of a legacy build (no region). */
	public static final int NO_SLOT = -1;

	public BuildOptions {
		if (tickBudgetMs < 1 || tickBudgetMs > 1000) {
			throw new IllegalArgumentException("tick budget out of range");
		}
		if (region == null) {
			if (slot != NO_SLOT) {
				throw new IllegalArgumentException("a legacy build has no slot");
			}
		} else {
			if (!Games.REGION_ID.matcher(region).matches()) {
				throw new IllegalArgumentException("invalid region id");
			}
			if (slot < 0 || slot > RegionTable.MAX_SLOT) {
				throw new IllegalArgumentException("slot out of range");
			}
		}
	}

	/** The options of a build before regions existed (a legacy build). */
	public BuildOptions(int originX, int originZ, @Nullable Path worldDir, @Nullable Path packDir, @Nullable Path reportCopy,
			boolean setWorldSpawn, boolean writePack, boolean writeDatapack, boolean publishIndex, int tickBudgetMs, boolean shapes) {
		this(originX, originZ, worldDir, packDir, reportCopy, setWorldSpawn, writePack, writeDatapack, publishIndex, tickBudgetMs, shapes, null,
				NO_SLOT);
	}

	public static BuildOptions defaults() {
		return new BuildOptions(0, 0, null, null, null, true, true, true, true, DEFAULT_TICK_BUDGET_MS, true, null, NO_SLOT);
	}

	public BuildOptions withOrigin(int x, int z) {
		return new BuildOptions(x, z, this.worldDir, this.packDir, this.reportCopy, this.setWorldSpawn, this.writePack, this.writeDatapack,
				this.publishIndex, this.tickBudgetMs, this.shapes, this.region, this.slot);
	}

	public BuildOptions withWorldDir(@Nullable Path dir) {
		return new BuildOptions(this.originX, this.originZ, dir, this.packDir, this.reportCopy, this.setWorldSpawn, this.writePack,
				this.writeDatapack, this.publishIndex, this.tickBudgetMs, this.shapes, this.region, this.slot);
	}

	public BuildOptions withPackDir(@Nullable Path dir) {
		return new BuildOptions(this.originX, this.originZ, this.worldDir, dir, this.reportCopy, this.setWorldSpawn, this.writePack,
				this.writeDatapack, this.publishIndex, this.tickBudgetMs, this.shapes, this.region, this.slot);
	}

	public BuildOptions withReportCopy(@Nullable Path path) {
		return new BuildOptions(this.originX, this.originZ, this.worldDir, this.packDir, path, this.setWorldSpawn, this.writePack,
				this.writeDatapack, this.publishIndex, this.tickBudgetMs, this.shapes, this.region, this.slot);
	}

	public BuildOptions withWorldSpawn(boolean set) {
		return new BuildOptions(this.originX, this.originZ, this.worldDir, this.packDir, this.reportCopy, set, this.writePack,
				this.writeDatapack, this.publishIndex, this.tickBudgetMs, this.shapes, this.region, this.slot);
	}

	/** False when the pack is already written, e.g. by the client's "Create Kanto world" flow before the level exists. */
	public BuildOptions withWritePack(boolean write) {
		return new BuildOptions(this.originX, this.originZ, this.worldDir, this.packDir, this.reportCopy, this.setWorldSpawn, write,
				this.writeDatapack, this.publishIndex, this.tickBudgetMs, this.shapes, this.region, this.slot);
	}

	public BuildOptions withPublishIndex(boolean publish) {
		return new BuildOptions(this.originX, this.originZ, this.worldDir, this.packDir, this.reportCopy, this.setWorldSpawn, this.writePack,
				this.writeDatapack, publish, this.tickBudgetMs, this.shapes, this.region, this.slot);
	}

	/** With or without the world's shapes. */
	public BuildOptions withShapes(boolean plan) {
		return new BuildOptions(this.originX, this.originZ, this.worldDir, this.packDir, this.reportCopy, this.setWorldSpawn, this.writePack,
				this.writeDatapack, this.publishIndex, this.tickBudgetMs, plan, this.region, this.slot);
	}

	/**
	 * A region build (MG3): region {@code id} at {@code slot} of the region table. The id must be a region id
	 * ({@link Games#REGION_ID}) and the slot 0..{@link RegionTable#MAX_SLOT}.
	 */
	public BuildOptions withRegion(String id, int slot) {
		if (id == null) {
			throw new IllegalArgumentException("a region build needs a region id");
		}
		return new BuildOptions(this.originX, this.originZ, this.worldDir, this.packDir, this.reportCopy, this.setWorldSpawn, this.writePack,
				this.writeDatapack, this.publishIndex, this.tickBudgetMs, this.shapes, id, slot);
	}

	/** True for a region build (a region and a slot), false for a legacy build. */
	public boolean regionBuild() {
		return this.region != null;
	}

	/**
	 * Parses a map filter: comma-separated map ids ({@code PALLET_TOWN,REDS_HOUSE_1F}). Null or blank means every map.
	 */
	public static @Nullable List<String> parseMaps(@Nullable String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		List<String> out = new ArrayList<>();
		for (String part : text.split(",")) {
			String id = part.trim().toUpperCase(Locale.ROOT);
			if (!id.isEmpty() && !out.contains(id)) {
				out.add(id);
			}
		}
		return out.isEmpty() ? null : out;
	}

	/**
	 * Parses the headless build's region options ({@code -Dkantobridge.build.region}, {@code -Dkantobridge.build.slot}):
	 * both absent (null) for a legacy build, else both present and well formed.
	 *
	 * @return the region id and slot, or null when neither option is given
	 * @throws IllegalArgumentException when only one is given, the id isn't a region id or the slot isn't 0..63
	 */
	public static @Nullable RegionOption parseRegion(@Nullable String region, @Nullable String slot) {
		boolean hasRegion = region != null && !region.isBlank();
		boolean hasSlot = slot != null && !slot.isBlank();
		if (!hasRegion && !hasSlot) {
			return null;
		}
		if (!hasRegion || !hasSlot) {
			throw new IllegalArgumentException("a region build needs both a region id and a slot");
		}
		String id = region.trim();
		if (!Games.REGION_ID.matcher(id).matches()) {
			throw new IllegalArgumentException("invalid region id");
		}
		int number;
		try {
			number = Integer.parseInt(slot.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("the slot is not a whole number");
		}
		if (number < 0 || number > RegionTable.MAX_SLOT) {
			throw new IllegalArgumentException("slot out of range");
		}
		return new RegionOption(id, number);
	}

	/** A parsed region option: the region id and its slot. */
	public record RegionOption(String region, int slot) {
	}
}
