package dev.kantobridge.client.packs;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.pack.ArtKeys;
import dev.kantobridge.builder.pack.Textures;
import dev.kantobridge.builder.plan.PlanException;
import dev.kantobridge.builder.shape.ArtUnitProbe;
import dev.kantobridge.builder.shape.Occupancy;
import dev.kantobridge.builder.shape.ShapeParams;
import dev.kantobridge.builder.shape.ShapePlanner;
import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.builder.shape.geom.TemplateRule;
import dev.kantobridge.builder.shape.geom.VoxelGrid;
import dev.kantobridge.client.shape.vox.VoxFile;
import dev.kantobridge.scene.FrontEnds;
import dev.kantobridge.scene.GameFrontEnd;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.gen1.BundleArtSource;
import dev.kantobridge.world.Hashes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * The pack template (docs/specs/VOXEL_BLOCKS_PLAN.md §5.10, report B4.6), written only when the player asks for it: a
 * ready-to-edit resource pack in {@code <gameDir>/kantobridge/pack-template/<game>/} with the key index, a manifest
 * skeleton, reference textures and the generated units as {@code .vox} to trace over and edit, a calibration model for
 * the axes, and {@code generated.json} listing (by hash) every file made from the player's own game, which "prepare for
 * sharing" ({@link #share}) leaves out. ROM-derived reference files stay on the player's machine.
 */
public final class PackTemplateWriter {
	/** The resource pack format (Minecraft 1.21.1). */
	static final int PACK_FORMAT = 34;
	static final String GENERATED = "generated.json";

	/** What a template holds. */
	public record Result(Path dir, int textures, int models, int tiles, int cells) {
	}

	/** What a shared copy left out. */
	public record Share(Path dir, int copied, int skipped) {
	}

	private PackTemplateWriter() {
	}

	/** The player's export bundle (the one its generated pack was made from), or null without one. */
	public static @Nullable Path bundleDir(Path gameDir) {
		PackInfo pack = PackInfo.read(GeneratedPack.directory(gameDir));
		if (pack == null || !pack.usable()) {
			return null;
		}
		Path dir = gameDir.resolve("kantobridge").resolve("export").resolve(pack.game());
		return Files.isRegularFile(dir.resolve("index.json")) ? dir : null;
	}

	/** Writes the template of the player's export; its folder is replaced. */
	public static Result write(Path gameDir) throws IOException, BundleException, PlanException {
		Path bundleDir = bundleDir(gameDir);
		if (bundleDir == null) {
			throw new PlanException("template.no_export", "no export yet");
		}
		ExportBundle bundle = ExportBundle.open(bundleDir);
		Path out = gameDir.resolve("kantobridge").resolve("pack-template").resolve(ArtKeys.pathPart(bundle.game()));
		deleteTree(out);
		Files.createDirectories(out);
		Map<String, String> generated = new TreeMap<>();
		Files.writeString(out.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":" + PACK_FORMAT + ",\"description\":\"Kanto Bridge pack template ("
				+ bundle.game() + ")\"}}\n", StandardCharsets.UTF_8);
		Files.writeString(out.resolve("README.txt"), readme(bundle.game()), StandardCharsets.UTF_8);
		Path manifest = out.resolve("assets").resolve("kantobridge").resolve("kb_pack").resolve("template.json");
		Files.createDirectories(manifest.getParent());
		Files.writeString(manifest, skeleton(bundle.game()), StandardCharsets.UTF_8);
		// The key index, in the template and (for the pack check) beside the generated pack.
		KeyIndex keys = KeyIndexWriter.write(gameDir, bundle);
		write(out, "keys.json", keys.toText().getBytes(StandardCharsets.UTF_8), generated);
		// Reference textures: the generated pack's tiles by key.
		int textures = 0;
		Path tiles = GeneratedPack.directory(gameDir).resolve("assets").resolve("kantobridge").resolve("textures").resolve("block")
				.resolve(ArtKeys.pathPart(bundle.game())).resolve("tile");
		if (Files.isDirectory(tiles)) {
			try (Stream<Path> files = Files.walk(tiles)) {
				for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
					write(out, "reference/textures/tile/" + tiles.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file), generated);
					textures++;
				}
			}
		}
		// Reference models: every unit the export plans, as this art builds it.
		GameFrontEnd front = FrontEnds.forGame(bundle.game());
		int models = 0;
		if (front != null) {
			Scene scene = front.describe(bundle);
			BundleArtSource art = BundleArtSource.of(bundle);
			ShapeTable table = ShapeTable.build(ShapePlanner.plan(scene, List.of(), ShapeParams.current(), Occupancy.NONE, new ArtUnitProbe(art))
					.index(), art);
			for (Map.Entry<String, CellUnits.Unit> e : table.units().entrySet()) {
				VoxelGrid voxels = e.getValue().voxels();
				if (voxels == null) {
					continue;
				}
				String[] parts = e.getKey().split("\\|", -1);
				int[] ids = java.util.Arrays.stream(parts[1].split(",")).mapToInt(Integer::parseInt).toArray();
				VoxFile.Model model = vox(voxels, parts[0], ids, art);
				String name = "reference/models/cell_" + ArtKeys.pathPart(parts[0]) + "_" + parts[1].replace(',', '-') + "_"
						+ ArtKeys.pathPart(parts[2]) + ".vox";
				write(out, name, VoxFile.write(model), generated);
				models++;
			}
			// Each building a template drew (V7), as voxels in shades: the grid's north-west bottom corner is the model's origin.
			for (Map.Entry<String, TemplateRule.Template> e : table.templates().entrySet()) {
				TemplateRule.Template template = e.getValue();
				if (template.voxels() == null || template.tiles() == null) {
					continue;
				}
				String[] parts = e.getKey().split("\\|", -1);
				VoxFile.Model model = vox(template.voxels(), parts[0], template.tiles(), art);
				write(out, "reference/models/struct_" + ArtKeys.pathPart(parts[0]) + "_" + parts[1] + ".vox", VoxFile.write(model), generated);
				models++;
			}
		}
		write(out, "reference/calibration.vox", VoxFile.write(calibration()), generated);
		JsonObject list = new JsonObject();
		list.addProperty("generated", 1);
		JsonObject files = new JsonObject();
		generated.forEach(files::addProperty);
		list.add("files", files);
		Files.writeString(out.resolve(GENERATED), new GsonBuilder().setPrettyPrinting().create().toJson(list) + "\n", StandardCharsets.UTF_8);
		return new Result(out, textures, models, keys.tiles(), keys.cells());
	}

	private static void write(Path out, String path, byte[] bytes, Map<String, String> generated) throws IOException {
		Path file = out.resolve(path);
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
		generated.put(path, Hashes.sha256(bytes));
	}

	/** A unit's voxels as a {@code .vox} model in shades: vox x east, y north, z up; colours 1..4 the four shades. */
	static VoxFile.Model vox(VoxelGrid grid, String atlas, int[] tiles, BundleArtSource art) {
		ByteArrayOutputStream voxels = new ByteArrayOutputStream();
		int[][] shades = new int[tiles.length][];
		for (int t = 0; t < tiles.length; t++) {
			shades[t] = tiles[t] < 0 ? null : art.tileShades(atlas, tiles[t]);
		}
		int artPx = grid.artPx();
		for (int y = 0; y < grid.height(); y++) {
			for (int z = 0; z < grid.depth(); z++) {
				for (int x = 0; x < grid.width(); x++) {
					int texel = grid.at(x, y, z);
					if (texel == VoxelGrid.EMPTY) {
						continue;
					}
					int slot = VoxelGrid.slot(texel);
					int[] tile = slot < shades.length ? shades[slot] : null;
					int shade = tile == null ? 0 : Math.max(0, tile[VoxelGrid.v(texel) * artPx + VoxelGrid.u(texel)]);
					voxels.write(x);
					voxels.write(grid.depth() - 1 - z);
					voxels.write(y);
					voxels.write(shade + 1);
				}
			}
		}
		int[] palette = new int[256];
		for (int s = 0; s < 4; s++) {
			palette[s + 1] = 0xFF000000 | Textures.GREY_PALETTE[s];
		}
		return new VoxFile.Model(grid.width(), grid.depth(), grid.height(), voxels.toByteArray(), palette, 1);
	}

	/**
	 * The calibration model: an 8 × 8 plate (light grey) with a red post in its north-east corner and a blue strip on its
	 * south edge. Drawn on any cell key, the post must stand north-east and the strip face south.
	 */
	static VoxFile.Model calibration() {
		ByteArrayOutputStream voxels = new ByteArrayOutputStream();
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 8; x++) {
				voxels.writeBytes(new byte[] {(byte) x, (byte) y, 0, 2});
			}
		}
		for (int z = 1; z < 7; z++) {
			voxels.writeBytes(new byte[] {7, 7, (byte) z, 5});
		}
		for (int x = 0; x < 4; x++) {
			for (int z = 1; z < 3; z++) {
				voxels.writeBytes(new byte[] {(byte) x, 0, (byte) z, 6});
			}
		}
		int[] palette = new int[256];
		palette[1] = 0xFFFFFFFF;
		palette[2] = 0xFFAAAAAA;
		palette[3] = 0xFF555555;
		palette[4] = 0xFF000000;
		palette[5] = 0xFFCC2222;
		palette[6] = 0xFF2244CC;
		return new VoxFile.Model(8, 8, 8, voxels.toByteArray(), palette, 1);
	}

	static String skeleton(String game) {
		JsonObject root = new JsonObject();
		root.addProperty("kantobridge_pack", KbPackManifest.FORMAT);
		root.addProperty("id", "my_pack");
		root.addProperty("name", "My pack");
		root.addProperty("version", "1.0.0");
		JsonObject targets = new JsonObject();
		JsonArray games = new JsonArray();
		games.add(game);
		targets.add("games", games);
		targets.addProperty("geometry", KbPackManifest.GEOMETRY);
		root.add("targets", targets);
		for (String section : List.of("textures", "models", "rules", "characters", "dressing")) {
			root.add(section, new JsonObject());
		}
		return new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
	}

	static String readme(String game) {
		return String.join("\n",
				"Kanto Bridge pack template (" + game + ")",
				"",
				"A resource pack: copy this folder to .minecraft/resourcepacks/, rename it, edit",
				"assets/kantobridge/kb_pack/template.json, and enable it above \"Kanto Bridge (generated)\".",
				"docs/PACKS.md (in the mod's repository) describes every key, rule and model option.",
				"",
				"keys.json        every key this game's export has (tile:, sprite:, cell:, map:, class:)",
				"reference/       GENERATED FROM YOUR OWN GAME. DO NOT SHARE.",
				"  textures/      the base textures by key, to trace over",
				"  models/        each generated unit as .vox (shades), to open in MagicaVoxel and improve",
				"  calibration.vox  a plate with a red post (north-east corner) and a blue strip (south edge)",
				"generated.json   the hash of every generated file",
				"",
				".vox: vox z is up, vox x east, vox y north, so MagicaVoxel's front view is the south face.",
				"voxelsPerBlock 8 (one Game Boy pixel), 16 or 32. Check the axes once with calibration.vox;",
				"\"turn\" (0/90/180/270) and \"flip\" fix a model built the other way round.",
				"",
				"Sharing: share only your own drawings, models and rules. Use /kbpack share <folder> to make",
				"a copy without the generated files (everything listed in generated.json).",
				"");
	}

	/**
	 * Copies a pack folder to {@code out}, leaving out every file whose hash {@code generated.json} lists (wherever it was
	 * moved), {@code generated.json} itself and the template's key index.
	 */
	public static Share share(Path pack, Path out) throws IOException {
		java.util.Set<String> hashes = new java.util.HashSet<>();
		Path list = pack.resolve(GENERATED);
		if (Files.isRegularFile(list)) {
			JsonObject files = JsonParser.parseString(Files.readString(list, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("files");
			files.entrySet().forEach(e -> hashes.add(e.getValue().getAsString()));
		}
		deleteTree(out);
		int copied = 0;
		int skipped = 0;
		try (Stream<Path> files = Files.walk(pack)) {
			for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
				String rel = pack.relativize(file).toString().replace('\\', '/');
				if (rel.equals(GENERATED) || rel.equals("keys.json") || hashes.contains(Hashes.sha256(Files.readAllBytes(file)))) {
					skipped++;
					continue;
				}
				Path target = out.resolve(rel);
				Files.createDirectories(target.getParent());
				Files.copy(file, target);
				copied++;
			}
		}
		return new Share(out, copied, skipped);
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		}
	}
}
