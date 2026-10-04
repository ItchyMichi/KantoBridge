package dev.kantobridge.builder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * How to run one build.
 *
 * @param originX       block X of the root map's cell (0, 0); 0 in a real world, far away in GameTests
 * @param originZ       block Z of the root map's cell (0, 0)
 * @param worldDir      where {@code kantobridge/world_index.json}, the slot map, the report and the datapack go; null
 *                      for the level's world folder
 * @param packDir       the generated resource pack folder; null for {@code <gameDir>/kantobridge/resourcepack}
 * @param reportCopy    also copy {@code build_report.json} here (e.g. {@code <repo>/local/reports/build_report.json})
 * @param setWorldSpawn set the level's default spawn on the spawn cell
 * @param writePack     generate the resource pack
 * @param writeDatapack write the per-world datapack
 * @param publishIndex  make the new WorldIndex the one the server sends to joining players (and send it now)
 * @param tickBudgetMs  placement time per server tick (20–30 ms keeps the server responsive)
 * @param shapes        plan the world's shapes (docs/specs/VOXEL_BLOCKS_PLAN.md §5.7): write and publish the shape index
 *                      and shape map; off builds today's world without them (the invariant tests compare the two)
 */
public record BuildOptions(int originX, int originZ, @Nullable Path worldDir, @Nullable Path packDir, @Nullable Path reportCopy,
		boolean setWorldSpawn, boolean writePack, boolean writeDatapack, boolean publishIndex, int tickBudgetMs, boolean shapes) {
	public static final int DEFAULT_TICK_BUDGET_MS = 25;

	public BuildOptions {
		if (tickBudgetMs < 1 || tickBudgetMs > 1000) {
			throw new IllegalArgumentException("tick budget out of range");
		}
	}

	public static BuildOptions defaults() {
		return new BuildOptions(0, 0, null, null, null, true, true, true, true, DEFAULT_TICK_BUDGET_MS, true);
	}

	public BuildOptions withOrigin(int x, int z) {
		return new BuildOptions(x, z, this.worldDir, this.packDir, this.reportCopy, this.setWorldSpawn, this.writePack, this.writeDatapack,
				this.publishIndex, this.tickBudgetMs, this.shapes);
	}

	public BuildOptions withWorldDir(@Nullable Path dir) {
		return new BuildOptions(this.originX, this.originZ, dir, this.packDir, this.reportCopy, this.setWorldSpawn, this.writePack,
				this.writeDatapack, this.publishIndex, this.tickBudgetMs, this.shapes);
	}

	public BuildOptions withPackDir(@Nullable Path dir) {
		return new BuildOptions(this.originX, this.originZ, this.worldDir, dir, this.reportCopy, this.setWorldSpawn, this.writePack,
				this.writeDatapack, this.publishIndex, this.tickBudgetMs, this.shapes);
	}

	public BuildOptions withReportCopy(@Nullable Path path) {
		return new BuildOptions(this.originX, this.originZ, this.worldDir, this.packDir, path, this.setWorldSpawn, this.writePack,
				this.writeDatapack, this.publishIndex, this.tickBudgetMs, this.shapes);
	}

	public BuildOptions withWorldSpawn(boolean set) {
		return new BuildOptions(this.originX, this.originZ, this.worldDir, this.packDir, this.reportCopy, set, this.writePack,
				this.writeDatapack, this.publishIndex, this.tickBudgetMs, this.shapes);
	}

	/** False when the pack is already written, e.g. by the client's "Create Kanto world" flow before the level exists. */
	public BuildOptions withWritePack(boolean write) {
		return new BuildOptions(this.originX, this.originZ, this.worldDir, this.packDir, this.reportCopy, this.setWorldSpawn, write,
				this.writeDatapack, this.publishIndex, this.tickBudgetMs, this.shapes);
	}

	public BuildOptions withPublishIndex(boolean publish) {
		return new BuildOptions(this.originX, this.originZ, this.worldDir, this.packDir, this.reportCopy, this.setWorldSpawn, this.writePack,
				this.writeDatapack, publish, this.tickBudgetMs, this.shapes);
	}

	/** With or without the world's shapes. */
	public BuildOptions withShapes(boolean plan) {
		return new BuildOptions(this.originX, this.originZ, this.worldDir, this.packDir, this.reportCopy, this.setWorldSpawn, this.writePack,
				this.writeDatapack, this.publishIndex, this.tickBudgetMs, plan);
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
}
