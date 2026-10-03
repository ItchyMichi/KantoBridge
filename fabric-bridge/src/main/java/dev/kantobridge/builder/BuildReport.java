package dev.kantobridge.builder;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * {@code build_report.json} (docs/PLAN.md §4.5 "Finishing up"): counts, hashes, map ids and error codes only, never
 * content, so it may be read from {@code local/reports/} (§0.3). The e2e gate reads {@code maps_built},
 * {@code overlaps}, {@code story_cells}, {@code warnings}, {@code pack_textures} and {@code world_index}; from M6 also
 * {@code habitat_blocks} (habitat blocks configured for the built maps) and {@code pools} (habitat pools in the world's
 * datapack: every wild map of the export, whatever the filter); from M8 {@code maps_unplaced} (maps asked for that got
 * no place) and {@code diagnostics} (whether {@code build_diagnostics.json}, the symbol lists behind the warning counts,
 * was written beside the report). M10 records the builder's speed as {@code throughput}: blocks and chunks placed per
 * second of placement ({@code place_ms}), maps built per second of the whole build ({@code elapsed_ms}).
 */
public final class BuildReport {
	public static final String FILE = "build_report.json";

	public String status = "running";
	public @Nullable String error;
	public String game = "";
	public String exportHash = "";
	public String slotMapHash = "";
	public String worldIndexHash = "";
	public String packHash = "";
	public int mapsRequested;
	public int mapsBuilt;
	public int mapsUnplaced;
	public int surfaceMaps;
	public int interiors;
	public int layers;
	public int overlaps;
	public int edgeMismatches;
	public int storyCells;
	public Map<String, Integer> storyCellsByType = new TreeMap<>();
	public int storyCellBlocks;
	public int ledgeColliders;
	public int lightBlocks;
	public int habitatBlocks;
	public int pools;
	public int warnings;
	public Map<String, Integer> warningCodes = new TreeMap<>();
	public Map<String, Integer> notes = new TreeMap<>();
	public int packTextures;
	public int tileTextures;
	public int npcTextures;
	public int animatedTextures;
	public int slotsFull;
	public int slotsHalf;
	public boolean worldIndex;
	public boolean diagnostics;
	public @Nullable String spawnMap;
	public int blocksPlaced;
	public int blocksCleared;
	public int chunks;
	public int biomeChunks;
	public long elapsedMs;
	public long planMs;
	public long placeMs;

	public boolean ok() {
		return "ok".equals(this.status);
	}

	public JsonObject toJson() {
		JsonObject out = new JsonObject();
		out.addProperty("status", this.status);
		if (this.error == null) {
			out.add("error", JsonNull.INSTANCE);
		} else {
			out.addProperty("error", this.error);
		}
		out.addProperty("game", this.game);
		out.addProperty("maps_requested", this.mapsRequested);
		out.addProperty("maps_built", this.mapsBuilt);
		out.addProperty("maps_unplaced", this.mapsUnplaced);
		out.addProperty("surface_maps", this.surfaceMaps);
		out.addProperty("interiors", this.interiors);
		out.addProperty("layers", this.layers);
		out.addProperty("overlaps", this.overlaps);
		out.addProperty("edge_mismatches", this.edgeMismatches);
		out.addProperty("story_cells", this.storyCells);
		out.add("story_cells_by_type", counts(this.storyCellsByType));
		out.addProperty("story_cell_blocks", this.storyCellBlocks);
		out.addProperty("ledge_colliders", this.ledgeColliders);
		out.addProperty("light_blocks", this.lightBlocks);
		out.addProperty("habitat_blocks", this.habitatBlocks);
		out.addProperty("pools", this.pools);
		out.addProperty("warnings", this.warnings);
		out.add("warning_codes", counts(this.warningCodes));
		out.add("notes", counts(this.notes));
		out.addProperty("pack_textures", this.packTextures);
		out.addProperty("tile_textures", this.tileTextures);
		out.addProperty("npc_textures", this.npcTextures);
		out.addProperty("animated_textures", this.animatedTextures);
		out.addProperty("slots_full", this.slotsFull);
		out.addProperty("slots_half", this.slotsHalf);
		out.addProperty("world_index", this.worldIndex);
		out.addProperty("diagnostics", this.diagnostics);
		if (this.spawnMap == null) {
			out.add("spawn_map", JsonNull.INSTANCE);
		} else {
			out.addProperty("spawn_map", this.spawnMap);
		}
		out.addProperty("blocks_placed", this.blocksPlaced);
		out.addProperty("blocks_cleared", this.blocksCleared);
		out.addProperty("chunks", this.chunks);
		out.addProperty("biome_chunks", this.biomeChunks);
		out.addProperty("elapsed_ms", this.elapsedMs);
		out.addProperty("plan_ms", this.planMs);
		out.addProperty("place_ms", this.placeMs);
		JsonObject throughput = new JsonObject();
		throughput.addProperty("blocks_per_s", rate(this.blocksPlaced, this.placeMs));
		throughput.addProperty("chunks_per_s", rate(this.chunks, this.placeMs));
		throughput.addProperty("maps_per_s", rate(this.mapsBuilt, this.elapsedMs));
		out.add("throughput", throughput);
		out.addProperty("export_hash", this.exportHash);
		out.addProperty("slot_map_hash", this.slotMapHash);
		out.addProperty("world_index_hash", this.worldIndexHash);
		out.addProperty("pack_hash", this.packHash);
		return out;
	}

	public String toText() {
		return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create().toJson(this.toJson()) + "\n";
	}

	public void writeTo(Path file) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.writeString(file, this.toText(), StandardCharsets.UTF_8);
	}

	/** {@code count} per second of {@code ms}, to one decimal; 0 when no time was measured. */
	static double rate(long count, long ms) {
		if (ms <= 0 || count <= 0) {
			return 0.0;
		}
		return Math.round(count * 10_000.0 / ms) / 10.0;
	}

	private static JsonObject counts(Map<String, Integer> map) {
		JsonObject out = new JsonObject();
		new TreeMap<>(map).forEach(out::addProperty);
		return out;
	}
}
