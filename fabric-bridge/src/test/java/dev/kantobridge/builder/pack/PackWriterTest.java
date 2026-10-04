package dev.kantobridge.builder.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.SlotMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The generated resource pack (layout 2, docs/specs/VOXEL_BLOCKS_PLAN.md §4.7) and datapack: metadata, textures by key
 * and art set, animations, NPC sheets; no blockstate and no model.
 */
class PackWriterTest {
	private static ExportBundle bundle;
	private static WorldPlan plan;

	@TempDir
	Path temp;

	@BeforeAll
	static void plan() throws Exception {
		bundle = ExportBundle.open(TestData.syntheticBundle());
		plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, 0, 0, TestData.MIN_BUILD_HEIGHT));
	}

	private static String text(PackFiles files, String path) {
		byte[] bytes = files.files().get(path);
		assertNotNull(bytes, "missing " + path);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	@Test
	void packContents() throws Exception {
		PackFiles files = new PackFiles();
		Warnings warnings = new Warnings();
		ResourcePackWriter.Result result = ResourcePackWriter.generate(plan, bundle, files, warnings);
		assertEquals(0, warnings.total(), warnings.warnings().toString());

		JsonObject meta = ProtoJson.parseObject(text(files, "pack.mcmeta")).getAsJsonObject("pack");
		assertEquals(34, meta.get("pack_format").getAsInt());

		assertEquals(28, result.tileTextures(), "one per (atlas, palette, tile)");
		assertEquals(4, result.npcTextures(), "girl, ball, guard (town) and Mom (house), in the town palette");
		assertEquals(32, result.textures());
		assertEquals(2, result.animated(), "the water tile and the flower tile");
		assertEquals(28, files.count("assets/kantobridge/textures/block/red/tile/", ".png"), "under the art set: the export's game");
		assertEquals(4, files.count("assets/kantobridge/textures/npc/red/", ".png"));

		// Layout 2: no blockstate, no model, no slot number anywhere; the runtime model draws the tile blocks.
		for (String path : files.files().keySet()) {
			assertFalse(path.startsWith("assets/kantobridge/blockstates/"), path);
			assertFalse(path.startsWith("assets/kantobridge/models/"), path);
		}

		// The texture of a slot key is addressed by its key and the art set.
		SlotMap.Key water = new SlotMap.Key("tilesets.test", "TEST_PALETTE", 6, SlotMap.Shape.HALF);
		assertEquals("kantobridge:block/red/tile/tilesets.test/test_palette/0006", ResourcePackWriter.texture("red", water));

		// The water texture is an 8-frame strip with its animation.
		String waterPath = "assets/kantobridge/textures/block/red/tile/tilesets.test/test_palette/0006.png";
		Png.Image strip = Png.decode(files.files().get(waterPath));
		assertEquals(16, strip.width());
		assertEquals(128, strip.height());
		assertEquals(Textures.waterMeta(), text(files, waterPath + ".mcmeta"));
		Png.Image ground = Png.decode(files.files().get("assets/kantobridge/textures/block/red/tile/tilesets.test/test_palette/0000.png"));
		assertEquals(16, ground.width());
		assertEquals(16, ground.height());

		// NPC sheets use the fixed layout.
		Png.Image girl = Png.decode(files.files().get("assets/kantobridge/textures/npc/red/test_palette/sprite_test_girl.png"));
		assertEquals(Textures.SHEET_WIDTH, girl.width());
		assertEquals(Textures.SHEET_HEIGHT, girl.height());

		JsonObject info = ProtoJson.parseObject(text(files, ResourcePackWriter.INFO_FILE));
		assertEquals(2, info.get("layout").getAsInt());
		assertEquals("red", info.get("artSet").getAsString());
		assertEquals("red", info.get("game").getAsString());
		assertEquals(bundle.exportHash(), info.get("exportHash").getAsString());
		assertEquals(28, info.get("tiles").getAsInt());
		assertEquals(4, info.get("sprites").getAsInt());
		assertEquals(32, info.get("textures").getAsInt());
		assertFalse(info.has("slotMapHash"), "the pack no longer follows a slot map");
	}

	/**
	 * M4's check of the NPC sheets against what the puppet renderer needs: for every object of every map the world places,
	 * a sheet exists under the texture id the renderer asks for (the sprite in the palette the WorldIndex gives its map),
	 * 64×32 (standing and walking rows of down, up, left, right), with right exactly left mirrored.
	 */
	@Test
	void everyPuppetTheWorldCanShowHasItsSheet() throws Exception {
		PackFiles files = new PackFiles();
		ResourcePackWriter.generate(plan, bundle, files, new Warnings());
		int checked = 0;
		for (var placement : plan.index().maps().values()) {
			for (var object : bundle.map(placement.id()).objects()) {
				String id = ArtKeys.npcTexture("red", placement.palette(), object.sprite());
				String path = "assets/" + id.replace(":", "/");
				byte[] png = files.files().get(path);
				assertNotNull(png, "no sheet for an object of " + placement.id());
				Png.Image sheet = Png.decode(png);
				assertEquals(Textures.SHEET_WIDTH, sheet.width());
				assertEquals(Textures.SHEET_HEIGHT, sheet.height());
				for (int row = 0; row < 2; row++) {
					for (int y = 0; y < 16; y++) {
						for (int x = 0; x < 16; x++) {
							assertEquals(sheet.get(2 * 16 + x, row * 16 + y), sheet.get(3 * 16 + 15 - x, row * 16 + y), "right mirrors left");
						}
					}
				}
				checked++;
			}
		}
		assertEquals(4, checked, "the town's three objects and Mom");
	}

	@Test
	void generationIsDeterministic() throws Exception {
		PackFiles a = new PackFiles();
		PackFiles b = new PackFiles();
		ResourcePackWriter.generate(plan, bundle, a, new Warnings());
		ResourcePackWriter.generate(plan, bundle, b, new Warnings());
		assertEquals(a.files().keySet(), b.files().keySet());
		assertEquals(a.hash(), b.hash());
	}

	@Test
	void writingReplacesTheWholePack() throws Exception {
		Path pack = this.temp.resolve("kantobridge").resolve("resourcepack");
		ResourcePackWriter.write(plan, bundle, pack, new Warnings());
		assertTrue(Files.isRegularFile(pack.resolve("pack.mcmeta")));
		Path stale = pack.resolve("assets/kantobridge/textures/block/red/tile/stale.png");
		Files.writeString(stale, "old");
		ResourcePackWriter.write(plan, bundle, pack, new Warnings());
		assertFalse(Files.exists(stale), "a rebuild leaves no stale files");
		assertFalse(Files.exists(pack.resolveSibling("resourcepack.tmp")));

		// A folder that isn't a generated pack is never deleted.
		Path foreign = this.temp.resolve("foreign");
		Files.createDirectories(foreign);
		Files.writeString(foreign.resolve("keep.txt"), "mine");
		PackFiles files = new PackFiles();
		files.put("pack.mcmeta", "{}");
		assertThrows(java.io.IOException.class, () -> files.writeTo(foreign, "pack.mcmeta"));
		assertTrue(Files.exists(foreign.resolve("keep.txt")));
	}

	@Test
	void datapackSkeleton() throws Exception {
		PackFiles files = DatapackWriter.generate();
		assertEquals(List.of("pack.mcmeta"), List.copyOf(files.files().keySet()));
		assertEquals(48, ProtoJson.parseObject(text(files, "pack.mcmeta")).getAsJsonObject("pack").get("pack_format").getAsInt());
		DatapackWriter.write(this.temp.resolve("world"));
		assertTrue(Files.isRegularFile(this.temp.resolve("world/datapacks/kantobridge_generated/pack.mcmeta")));
	}

	@Test
	void animationsComeFromTheExportsAnimatedList() throws Exception {
		// The voxel plan's V2: the tileset's `animated` entries decide which tiles move and how (no Java constants).
		PackFiles files = new PackFiles();
		ResourcePackWriter.Result result = ResourcePackWriter.generate(plan, bundle, files, new Warnings());
		assertEquals(2, result.animated(), "the water tile (hshift) and the flower tile (frames)");
		String waterPath = "assets/kantobridge/textures/block/red/tile/tilesets.test/test_palette/0006.png";
		assertEquals("{\"animation\":{\"frametime\":7}}", text(files, waterPath + ".mcmeta"), "period 20 at 60 Hz is about 7 ticks");
		String flowerPath = "assets/kantobridge/textures/block/red/tile/tilesets.test/test_palette/0007.png";
		assertEquals("{\"animation\":{\"frametime\":7,\"frames\":[0,1,2,0,0,1,2,0]}}", text(files, flowerPath + ".mcmeta"));
		Png.Image strip = Png.decode(files.files().get(flowerPath));
		assertEquals(48, strip.height(), "three frames from the export's three images");

		// A frame image the export names elsewhere (an older export's implied frames) is found by its name.
		assertEquals("tilesets.flower1", ResourcePackWriter.frameImage(List.of("fx.flower1", "tilesets.flower1"), "tilesets.flower1"));
		assertEquals("fx.flower2", ResourcePackWriter.frameImage(List.of("fx.flower2", "tilesets.test"), "tilesets.flower2"));
		assertEquals(null, ResourcePackWriter.frameImage(List.of("tilesets.test"), "tilesets.flower3"));

		// Without the images the tile stays still (noted), never a broken strip.
		Map<String, JsonObject> noFlowers = new java.util.TreeMap<>();
		JsonObject tileset = bundle.json("tileset", "TEST").deepCopy();
		JsonObject frames = tileset.getAsJsonArray("animated").get(1).getAsJsonObject();
		frames.add("images", new com.google.gson.JsonArray());
		frames.getAsJsonArray("images").add("tilesets.nowhere");
		noFlowers.put("tileset/TEST", tileset);
		ExportBundle missing = ExportBundle.open(TestData.syntheticCopyWith(this.temp, noFlowers));
		WorldPlan missingPlan = WorldPlanner.plan(missing, new WorldPlanner.Options(null, 0, 0, TestData.MIN_BUILD_HEIGHT));
		Warnings warnings = new Warnings();
		PackFiles still = new PackFiles();
		assertEquals(1, ResourcePackWriter.generate(missingPlan, missing, still, warnings).animated());
		assertEquals(1, warnings.noteCount("animation_image_missing"));
		assertEquals(16, Png.decode(still.files().get(flowerPath)).height());
		assertTrue(!missing.exportHash().equals(bundle.exportHash()));
	}

	@Test
	void pathPartsAreResourceSafe() {
		assertEquals("tilesets.overworld", ArtKeys.pathPart("tilesets.overworld"));
		assertEquals("pallet", ArtKeys.pathPart("PALLET"));
		assertEquals("a_b", ArtKeys.pathPart("A:B"));
		Map<String, byte[]> none = new PackFiles().files();
		assertTrue(none.isEmpty());
		assertThrows(IllegalArgumentException.class, () -> new PackFiles().put("../escape", "x"));
		assertThrows(IllegalArgumentException.class, () -> new PackFiles().put("/abs", "x"));
	}
}
