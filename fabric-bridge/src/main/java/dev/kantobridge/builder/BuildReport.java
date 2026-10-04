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
 * second of placement ({@code place_ms}), maps built per second of the whole build ({@code elapsed_ms}). The voxel plan's
 * measurements (docs/specs/VOXEL_BLOCKS_PLAN.md V1a) add {@code viewpoints}: block coordinates of the spawn cell and of
 * the surface box, from which {@code tools/e2e/build_world.py} writes the fixed spectator viewpoints. From V2 the groups
 * Blocks ({@code blocks_by_kind}, {@code cells_by_kind}, {@code solid_cells_by_height}), Shapes (the shape index and map:
 * hashes, sizes, key counts, {@code shaped_blocks}, {@code shape_plan_ms}) and the recorded self-check
 * ({@code selfcheck}: per-map scores aggregated; the per-map numbers go to {@code shape_selfcheck.json}).
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
	/** The spawn cell's north-west block and feet level ({@code x, feetY, z}), or null without a spawn. */
	public int @Nullable [] spawnBlock;
	/** The surface volume ({@code minX, minY, minZ, maxX, maxY, maxZ}, max exclusive), or null without surface maps. */
	public int @Nullable [] surfaceBox;
	public int blocksPlaced;
	public int blocksCleared;
	public int chunks;
	public int biomeChunks;
	public long elapsedMs;
	public long planMs;
	public long placeMs;
	// ---- the voxel plan (docs/specs/VOXEL_BLOCKS_PLAN.md §4.10) ----
	public Map<String, Integer> blocksByKind = new TreeMap<>();
	public Map<String, Integer> cellsByKind = new TreeMap<>();
	public Map<String, Integer> solidCellsByHeight = new TreeMap<>();
	public String shapeIndexHash = "";
	public String shapePlanner = "";
	public String shapeMapHash = "";
	public long shapeMapBytes;
	public long shapeMapWireChars;
	public long shapeIndexBytes;
	public long worldIndexBytes;
	public int shapeKeys;
	public Map<String, Integer> shapeKeysByKind = new TreeMap<>();
	public int shapeInstances;
	public long shapedBlocks;
	public long shapePlanMs;
	public long shapeTableMs;
	public long shapeQuadsTotal;
	// Carriers (V3): new carrier blocks placed, decoration skipped on occupied positions, void-fill hull cells (V4),
	// closed story cells drawn by shapes (V5).
	public long shapeHostsAdded;
	public long shapeDecorSkipped;
	public long voidHullCells;
	/** Void-fill cells within the ring planned as units of their owner's border (V4). */
	public long voidRingUnits;
	public long closedShapeCells;
	// Structures (V4, V7): Tier A regions and unit cells by what they became; templates (V7).
	public long structures;
	public Map<String, Long> structuresByKind = new java.util.LinkedHashMap<>();
	public int shapeTemplates;
	/**
	 * The shape profile the export carried (docs/specs/PROFILE_PLAN.md §5.7): {@code absent}, {@code ok} or
	 * {@code unreadable}; the mod's version, whether Gen1Recomp runs it, a short hash of its text, and counts. Never a
	 * tile id.
	 */
	public String profileStatus = "absent";
	public String profileVersion = "";
	public boolean profileEnabled;
	public String profileHash = "";
	public int profileTilesets;
	public int profilePins;
	public int profileConds;
	public int profileBuildingEntries;
	public Map<String, Long> profileUnknown = new java.util.LinkedHashMap<>();
	/** The map bodies' cell units by why they are units, and pinned cells left as they were (the profile plan). */
	public Map<String, Long> unitsBySource = new java.util.LinkedHashMap<>();
	/** Characters (the voxel plan's V8): sheets by frame count, and the styles' fallbacks; empty without shapes. */
	public Map<String, Long> spritesByFrames = new java.util.LinkedHashMap<>();
	public Map<String, Long> characterFallbacks = new java.util.LinkedHashMap<>();
	// Quads, after culling (V3): per shaped block, per 16×16×16 section, and the densest section's corner.
	public double shapeQuadsMeanBlock;
	public int shapeQuadsMaxBlock;
	public int shapeQuadsP99Block;
	public int shapeQuadsSectionP50;
	public int shapeQuadsSectionP95;
	public int shapeQuadsSectionMax;
	public int @Nullable [] densestSection;
	public long quadCountMs;
	public long packMs;
	/** The recorded self-check: per-map scores aggregated (null when shapes weren't planned). */
	public @Nullable SelfCheckSummary selfCheck;

	/** The self-check's numbers over all checked maps. */
	public record SelfCheckSummary(int maps, long holes, double groundMatchMin, double structureMatchMin, double structureMatchP05,
			double occludedGroundMax, long backFaces, long unitsFailed, long templatesFailed, long ms) {
	}

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
		out.add("viewpoints", this.viewpoints());
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
		out.add("blocks_by_kind", counts(this.blocksByKind));
		out.add("cells_by_kind", counts(this.cellsByKind));
		out.add("solid_cells_by_height", counts(this.solidCellsByHeight));
		out.addProperty("shape_index_hash", this.shapeIndexHash);
		out.addProperty("shape_planner", this.shapePlanner);
		out.addProperty("shape_map_hash", this.shapeMapHash);
		out.addProperty("shape_map_bytes", this.shapeMapBytes);
		out.addProperty("shape_map_wire_chars", this.shapeMapWireChars);
		out.addProperty("shape_index_bytes", this.shapeIndexBytes);
		out.addProperty("world_index_bytes", this.worldIndexBytes);
		out.addProperty("shape_keys", this.shapeKeys);
		out.add("shape_keys_by_kind", counts(this.shapeKeysByKind));
		out.addProperty("shape_instances", this.shapeInstances);
		out.addProperty("shaped_blocks", this.shapedBlocks);
		out.addProperty("shape_plan_ms", this.shapePlanMs);
		out.addProperty("shape_table_ms", this.shapeTableMs);
		out.addProperty("shape_quads_total", this.shapeQuadsTotal);
		out.addProperty("shape_hosts_added", this.shapeHostsAdded);
		out.addProperty("shape_decor_skipped", this.shapeDecorSkipped);
		out.addProperty("void_hull_cells", this.voidHullCells);
		out.addProperty("void_ring_units", this.voidRingUnits);
		out.addProperty("closed_shape_cells", this.closedShapeCells);
		out.addProperty("structures", this.structures);
		JsonObject byKind = new JsonObject();
		this.structuresByKind.forEach(byKind::addProperty);
		out.add("structures_by_kind", byKind);
		out.addProperty("shape_templates", this.shapeTemplates);
		JsonObject profile = new JsonObject();
		profile.addProperty("status", this.profileStatus);
		profile.addProperty("mod_version", this.profileVersion);
		profile.addProperty("enabled", this.profileEnabled);
		profile.addProperty("hash", this.profileHash);
		profile.addProperty("tilesets", this.profileTilesets);
		profile.addProperty("pins", this.profilePins);
		profile.addProperty("conds", this.profileConds);
		profile.addProperty("building_entries", this.profileBuildingEntries);
		JsonObject unknown = new JsonObject();
		this.profileUnknown.forEach(unknown::addProperty);
		profile.add("unknown", unknown);
		out.add("profile", profile);
		JsonObject bySource = new JsonObject();
		this.unitsBySource.forEach(bySource::addProperty);
		out.add("units_by_source", bySource);
		JsonObject byFrames = new JsonObject();
		this.spritesByFrames.forEach(byFrames::addProperty);
		out.add("sprites_by_frames", byFrames);
		JsonObject fallbacks = new JsonObject();
		this.characterFallbacks.forEach(fallbacks::addProperty);
		out.add("character_fallbacks", fallbacks);
		out.addProperty("shape_quads_mean_block", round4(this.shapeQuadsMeanBlock));
		out.addProperty("shape_quads_max_block", this.shapeQuadsMaxBlock);
		out.addProperty("shape_quads_p99_block", this.shapeQuadsP99Block);
		out.addProperty("shape_quads_section_p50", this.shapeQuadsSectionP50);
		out.addProperty("shape_quads_section_p95", this.shapeQuadsSectionP95);
		out.addProperty("shape_quads_section_max", this.shapeQuadsSectionMax);
		JsonObject densest = new JsonObject();
		if (this.densestSection != null) {
			densest.addProperty("quads", this.shapeQuadsSectionMax);
			densest.addProperty("x", this.densestSection[0]);
			densest.addProperty("y", this.densestSection[1]);
			densest.addProperty("z", this.densestSection[2]);
		}
		out.add("densest_section", densest);
		out.addProperty("quad_count_ms", this.quadCountMs);
		out.addProperty("pack_ms", this.packMs);
		if (this.selfCheck == null) {
			out.add("selfcheck", JsonNull.INSTANCE);
		} else {
			SelfCheckSummary c = this.selfCheck;
			JsonObject check = new JsonObject();
			check.addProperty("maps", c.maps());
			check.addProperty("holes", c.holes());
			check.addProperty("ground_match_min", round4(c.groundMatchMin()));
			check.addProperty("structure_match_min", round4(c.structureMatchMin()));
			check.addProperty("structure_match_p05", round4(c.structureMatchP05()));
			check.addProperty("occluded_ground_max", round4(c.occludedGroundMax()));
			check.addProperty("back_faces", c.backFaces());
			check.addProperty("units_failed", c.unitsFailed());
			check.addProperty("templates_failed", c.templatesFailed());
			check.addProperty("ms", c.ms());
			out.add("selfcheck", check);
		}
		out.addProperty("export_hash", this.exportHash);
		out.addProperty("slot_map_hash", this.slotMapHash);
		out.addProperty("world_index_hash", this.worldIndexHash);
		out.addProperty("pack_hash", this.packHash);
		return out;
	}

	/** Block coordinates only: where the spawn cell and the surface box are, for fixed measuring viewpoints. */
	private JsonObject viewpoints() {
		JsonObject out = new JsonObject();
		if (this.spawnBlock != null) {
			out.addProperty("spawn_x", this.spawnBlock[0]);
			out.addProperty("spawn_feet_y", this.spawnBlock[1]);
			out.addProperty("spawn_z", this.spawnBlock[2]);
		}
		if (this.surfaceBox != null) {
			String[] names = {"surface_min_x", "surface_min_y", "surface_min_z", "surface_max_x", "surface_max_y", "surface_max_z"};
			for (int i = 0; i < names.length; i++) {
				out.addProperty(names[i], this.surfaceBox[i]);
			}
		}
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

	/** A ratio to four decimals, so the report stays readable. */
	public static double round4(double value) {
		return Math.round(value * 10_000.0) / 10_000.0;
	}

	private static JsonObject counts(Map<String, Integer> map) {
		JsonObject out = new JsonObject();
		new TreeMap<>(map).forEach(out::addProperty);
		return out;
	}
}
