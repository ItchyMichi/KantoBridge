package dev.kantobridge.client.dev;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.dev.GalleryGeometry;
import dev.kantobridge.dev.GalleryGeometry.Bounds;
import dev.kantobridge.dev.GalleryShutdown;
import dev.kantobridge.mixin.dev.GalleryChunkMapAccessor;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.builder.shape.FittedShapes;
import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.builder.shape.geom.ShapeMesh;
import dev.kantobridge.builder.shape.geom.ShapeQuad;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.builder.shape.geom.TextureKey;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.scene.FrontEnds;
import dev.kantobridge.scene.gen3.G3ArtSource;
import dev.kantobridge.server.KantoGameRules;
import dev.kantobridge.world.ShapeIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.phys.Vec3;

/**
 * Opt-in offline model inspection. The gallery uses the normal ShapeTable geometry builder and its tile UVs;
 * Minecraft draws those quads in an isolated flat world. No hidden-game connection or playthrough is required.
 * Instances are assembled from all their block fragments, so multi-cell entities retain their complete shape.
 */
public final class ModelGallery {
	private static final String PROPERTY = "kantobridge.gallery";
	private static final int COLUMNS = 10;
	private static final int[] TINTS = {255, 216, 176, 136};
	private static final ResourceKey<WorldPreset> PRESET = ResourceKey.create(Registries.WORLD_PRESET,
			ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "model_gallery"));
	private final Minecraft minecraft;
	private final Path manifest;
	private final Path output;
	private final String world;
	private final List<String> views;
	private final List<Exhibit> exhibits = new ArrayList<>();
	private final JsonArray captures = new JsonArray();
	private final JsonObject report = new JsonObject();
	private ResourceLocation texture;
	private int atlasWidth;
	private int tileWidth;
	private int atlasColumns;
	private boolean opened;
	private boolean prepared;
	private boolean backupConfirmed;
	private boolean finished;
	private boolean failed;
	private boolean pendingCapture;
	private int ticks;
	private int shot;
	private int settle;
	private int renderedShot = -1;
	private int renderedFrames;
	private volatile int positionedShot = -1;
	private int hiddenEntities;
	private net.minecraft.server.MinecraftServer galleryServer;
	private volatile GalleryShutdown shutdown;
	private boolean shutdownRequested;
	private Vec3 targetEye;
	private float targetYaw;
	private float targetPitch;
	/** The mod extends the overworld below vanilla's -64; derive the flat surface from its real minimum. */
	private double floor;
	private final int settleTicks = Math.max(2, Integer.getInteger(PROPERTY + ".settleTicks", 15));
	private final boolean autoCapture = !"false".equals(System.getProperty(PROPERTY + ".capture"));

	private record Piece(int x, int y, int z, ShapeMesh mesh) { }
	private record Exhibit(String id, String label, double width, double height, double depth,
			double x, double z, List<Piece> pieces, Bounds bounds) { }

	private ModelGallery(Minecraft minecraft, Path manifest) throws Exception {
		this.minecraft = minecraft;
		this.manifest = manifest.toAbsolutePath().normalize();
		JsonObject root = JsonParser.parseString(Files.readString(this.manifest)).getAsJsonObject();
		String game = root.get("game").getAsString();
		if (root.get("schema").getAsInt() != 1 || !List.of("blue", "emerald", "crystal").contains(game)) {
			throw new IllegalArgumentException("model gallery requires schema 1 and game blue, emerald or crystal");
		}
		this.output = resolve(root.get("output").getAsString());
		this.world = root.get("world").getAsString();
		this.views = root.has("views") ? List.copyOf(strings(root.getAsJsonArray("views"))) : List.of("front", "side");
		if (!List.of(List.of("front", "side"), List.of("front", "side", "three_quarter"),
				List.of("front", "side", "north"), List.of("front", "side", "three_quarter", "north")).contains(this.views)) {
			throw new IllegalArgumentException("gallery views must be front, side, optional three_quarter, then optional north");
		}
		if (!this.world.matches("[A-Za-z0-9 _-]{1,80}")) throw new IllegalArgumentException("unsafe gallery world name");
		Files.createDirectories(this.output.resolve("screenshots"));
		ExportBundle bundle = ExportBundle.open(resolve(root.get("bundle").getAsString()));
		if (!bundle.game().equals(game)) throw new IllegalArgumentException("gallery bundle does not match selected game");
		ArtSource art = FrontEnds.artSource(bundle);
		List<String> atlases = strings(root.getAsJsonArray("atlases"));
		List<String> palettes = strings(root.getAsJsonArray("palettes"));
		// ShapeKey atlas indices refer to these sorted lists. Reject a silent reorder that would paint the wrong art.
		if (!atlases.equals(atlases.stream().sorted().distinct().toList())) throw new IllegalArgumentException("atlases must be sorted and unique");
		JsonArray entries = root.getAsJsonArray("entries");
		if (entries.isEmpty()) throw new IllegalArgumentException("empty gallery");
		List<ShapeIndex.Use> uses = new ArrayList<>();
		for (JsonElement element : entries) {
			for (JsonElement fragment : element.getAsJsonObject().getAsJsonArray("fragments")) {
				JsonObject f = fragment.getAsJsonObject();
				uses.add(new ShapeIndex.Use(ShapeKey.parse(f.get("key").getAsString()), f.get("palette").getAsString()));
			}
		}
		ShapeIndex index = ShapeIndex.of("kb-gallery/1", new JsonObject(), atlases, palettes, uses,
				root.has("templates") ? root.getAsJsonArray("templates") : new JsonArray());
		List<String> comparisonTables = new ArrayList<>();
		JsonObject source = root.has("source") ? root.getAsJsonObject("source") : new JsonObject();
		Path currentSnapshot = this.manifest.getParent().resolve("fitted-current.json");
		// The conventional adjacent filename also lets older captures acquire a matching
		// snapshot without changing their manifest bytes (and invalidating capture proof).
		if (Files.exists(currentSnapshot) || source.has("table_snapshot")) {
			comparisonTables.add(verifiedTable(currentSnapshot, source, "table_sha256"));
			this.report.addProperty("fitted_current_sha256", source.get("table_sha256").getAsString());
		}
		if (root.has("fitted_tables")) {
			for (JsonElement file : root.getAsJsonArray("fitted_tables")) {
				Path path = resolve(file.getAsString());
				if (source.has("baseline_table_snapshot") && path.equals(resolve(source.get("baseline_table_snapshot").getAsString()))) {
					comparisonTables.add(verifiedTable(path, source, "baseline_table_sha256"));
				} else comparisonTables.add(Files.readString(path));
			}
		}
		ShapeTable table = FittedShapes.withComparisonTables(comparisonTables, () -> {
			for (ShapeIndex.Use use : uses) {
				ShapeKey key = use.key();
				if (key.kind() == ShapeKey.Kind.CELL) {
					CellUnits.Context context = CellUnits.Context.parse(key.variant());
					if (FittedShapes.VOXELS.equals(context.pinned()) && FittedShapes.voxels(index.atlas(key.atlas()),
							new int[] {key.number(0), key.number(1), key.number(2), key.number(3)}, context.params()) == null) {
						throw new IllegalArgumentException("gallery voxel hash is unavailable; restore the matching fitted-current.json snapshot or prepare a new gallery");
					}
				}
			}
			return ShapeTable.build(index, art);
		});
		JsonObject templateResults = new JsonObject();
		for (var built : table.templates().entrySet()) {
			var result = built.getValue();
			JsonObject detail = new JsonObject();
			detail.addProperty("kind", result.kind().id());
			if (result.failed() != null) detail.addProperty("failed", result.failed().id());
			detail.addProperty("match", result.match());
			detail.addProperty("max_quads", result.maxQuads());
			templateResults.add(built.getKey(), detail);
		}
		this.report.add("template_results", templateResults);
		for (JsonElement element : entries) {
			JsonObject entry = element.getAsJsonObject();
			if (!entry.has("kind") || !"building".equals(entry.get("kind").getAsString())) continue;
			if (entry.has("revision") && "before".equals(entry.get("revision").getAsString())) continue;
			for (JsonElement fragment : entry.getAsJsonArray("fragments")) {
				ShapeKey key = ShapeKey.parse(fragment.getAsJsonObject().get("key").getAsString());
				var built = table.template(index.atlas(key.atlas()), key.sig());
				if (built == null || built.failed() != null || !"hull".equals(built.kind().id())) {
					throw new IllegalArgumentException("current building failed its normal geometry gate: " + entry.get("id").getAsString());
				}
			}
		}
		Map<String, Integer> ids = new HashMap<>();
		for (int id = 1; id <= index.size(); id++) ids.put(index.key(id).text() + "@" + index.palette(id), id);
		java.util.Set<String> names = new java.util.HashSet<>();
		for (JsonElement element : entries) {
			JsonObject e = element.getAsJsonObject();
			String id = e.get("id").getAsString();
			if (!id.matches("[A-Za-z0-9_-]{1,120}") || !names.add(id)) throw new IllegalArgumentException("invalid or duplicate exhibit id");
			JsonArray bounds = e.getAsJsonArray("bounds");
			double w = bounds.get(0).getAsDouble(), h = bounds.get(1).getAsDouble(), d = bounds.get(2).getAsDouble();
			GalleryGeometry.validateDimensions(w, h, d);
			List<Piece> pieces = new ArrayList<>();
			int quads = 0;
			for (JsonElement fragment : e.getAsJsonArray("fragments")) {
				JsonObject f = fragment.getAsJsonObject();
				ShapeMesh mesh = table.mesh(ids.get(f.get("key").getAsString() + "@" + f.get("palette").getAsString()));
				if (mesh == null) throw new IllegalArgumentException("unbuilt gallery fragment: " + id);
				pieces.add(new Piece(f.get("x").getAsInt(), f.get("y").getAsInt(), f.get("z").getAsInt(), mesh));
				quads += mesh.size();
			}
			if (quads == 0) throw new IllegalArgumentException("exhibit has no visible geometry: " + id);
			double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
			double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
			for (Piece piece : pieces) {
				for (int q = 0; q < piece.mesh.size(); q++) for (int v = 0; v < 4; v++) {
					double x = piece.x + piece.mesh.x(q, v) / 16.0, y = piece.y + piece.mesh.y(q, v) / 16.0, z = piece.z + piece.mesh.z(q, v) / 16.0;
					minX = Math.min(minX, x); maxX = Math.max(maxX, x);
					minY = Math.min(minY, y); maxY = Math.max(maxY, y);
					minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
				}
			}
			Bounds meshBounds = new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
			GalleryGeometry.validateDimensions(meshBounds.width(), meshBounds.height(), meshBounds.depth());
			// Mesh offsets also influence pad allocation; reject distant fragments even if their span is small.
			if (minX < -1 || minY < -1 || minZ < -1 || maxX > w + 1 || maxY > h + 1 || maxZ > d + 1) {
				throw new IllegalArgumentException("mesh exceeds declared exhibit bounds: " + id);
			}
			this.exhibits.add(new Exhibit(id, e.get("label").getAsString(), w, h, d, 0, 0, List.copyOf(pieces), meshBounds));
		}
		int minPadX = 0, maxPadX = 0, minPadZ = 0, maxPadZ = 0;
		for (Exhibit e : this.exhibits) {
			minPadX = Math.min(minPadX, GalleryGeometry.padMin(e.bounds.minX()));
			maxPadX = Math.max(maxPadX, GalleryGeometry.padMax(e.width, e.bounds.maxX()));
			minPadZ = Math.min(minPadZ, GalleryGeometry.padMin(e.bounds.minZ()));
			maxPadZ = Math.max(maxPadZ, GalleryGeometry.padMax(e.depth, e.bounds.maxZ()));
		}
		int spacingX = GalleryGeometry.spacing(minPadX, maxPadX), spacingZ = GalleryGeometry.spacing(minPadZ, maxPadZ);
		JsonArray placements = new JsonArray();
		for (int number = 0; number < this.exhibits.size(); number++) {
			Exhibit e = this.exhibits.get(number);
			Exhibit placed = new Exhibit(e.id, e.label, e.width, e.height, e.depth,
					(number % COLUMNS) * spacingX, (number / COLUMNS) * spacingZ, e.pieces, e.bounds);
			this.exhibits.set(number, placed);
			JsonObject placement = new JsonObject();
			placement.addProperty("id", placed.id); placement.addProperty("x", placed.x); placement.addProperty("z", placed.z);
			placements.add(placement);
		}
		this.report.addProperty("spacing_x", spacingX); this.report.addProperty("spacing_z", spacingZ);
		this.report.add("placements", placements);
		makeTexture(table.textureKeys(), art, bundle);
		this.report.addProperty("schema", 1);
		this.report.addProperty("game", game);
		this.report.addProperty("world", this.world);
		this.report.addProperty("manifest", this.manifest.toString());
		this.report.addProperty("manifest_sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(this.manifest))));
		this.report.addProperty("export_hash", bundle.exportHash());
		this.report.addProperty("shape_hash", index.hash());
		this.report.addProperty("geometry_version", ShapeTable.GEOMETRY);
		this.report.addProperty("renderer", "Minecraft ShapeTable gallery");
		this.report.addProperty("backdrop", "gallery-only uniform floor plane");
		this.report.addProperty("capture_readiness", "server teleport acknowledged and two correctly posed rendered frames");
		this.report.addProperty("fov", GalleryGeometry.FOV);
		this.report.addProperty("exhibits", this.exhibits.size());
		JsonArray views = new JsonArray(); this.views.forEach(views::add); this.report.add("views", views);
		this.report.add("captures", this.captures);
		writeReport("ready");
	}

	private Path resolve(String path) {
		Path parsed = Path.of(path);
		return (parsed.isAbsolute() ? parsed : this.manifest.getParent().resolve(parsed)).normalize();
	}

	private static String verifiedTable(Path path, JsonObject source, String hashProperty) throws Exception {
		if (!source.has(hashProperty)) throw new IllegalArgumentException("missing gallery snapshot checksum: " + hashProperty);
		byte[] bytes = Files.readAllBytes(path);
		String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
		if (!actual.equals(source.get(hashProperty).getAsString())) {
			throw new IllegalArgumentException("gallery snapshot checksum does not match manifest: " + path);
		}
		return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
	}

	private static List<String> strings(JsonArray array) {
		List<String> out = new ArrayList<>();
		for (JsonElement element : array) out.add(element.getAsString());
		return out;
	}

	/** Does nothing in ordinary clients. */
	public static void init() {
		String path = System.getProperty(PROPERTY);
		if (path == null || path.isBlank()) return;
		// Defer loading until Minecraft's texture manager exists and initial resource loading has completed.
		boolean[] started = {false};
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			if (started[0] || !(minecraft.screen instanceof TitleScreen) || minecraft.getOverlay() != null) return;
			started[0] = true;
			try {
				ModelGallery gallery = new ModelGallery(minecraft, Path.of(path));
				ClientTickEvents.END_CLIENT_TICK.register(ignored -> gallery.tick());
				ServerTickEvents.END_SERVER_TICK.register(gallery::drainShutdown);
				WorldRenderEvents.START.register(ignored -> gallery.clearVisualEntities());
				WorldRenderEvents.AFTER_ENTITIES.register(gallery::render);
				WorldRenderEvents.END.register(ignored -> gallery.capture());
				KantoBridge.LOGGER.info("gallery: loaded {} exhibits", gallery.exhibits.size());
			} catch (Exception error) {
				KantoBridge.LOGGER.error("gallery: load failed", error);
				try {
					Path manifest = Path.of(path).toAbsolutePath();
					JsonObject request = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
					Path out = Path.of(request.get("output").getAsString());
					if (!out.isAbsolute()) out = manifest.getParent().resolve(out);
					Files.createDirectories(out);
					JsonObject failure = new JsonObject();
					failure.addProperty("status", "failed"); failure.addProperty("error", error.toString());
					String reportName = "false".equals(System.getProperty(PROPERTY + ".capture")) ? "view-session.json" : "capture.json";
					Files.writeString(out.resolve(reportName), failure.toString());
				} catch (Exception ignored) { }
				minecraft.setScreen(new GenericMessageScreen(Component.literal("Model gallery failed: " + error.getMessage())));
				if (Boolean.getBoolean(PROPERTY + ".quit")) minecraft.stop();
			}
		});
	}

	private void makeTexture(List<String> keys, ArtSource art, ExportBundle bundle) throws Exception {
		this.tileWidth = art.artPx();
		this.atlasColumns = Math.max(1, (int) Math.ceil(Math.sqrt(keys.size())));
		this.atlasWidth = this.atlasColumns * this.tileWidth;
		int height = Math.max(1, (keys.size() + this.atlasColumns - 1) / this.atlasColumns) * this.tileWidth;
		NativeImage pixels = new NativeImage(this.atlasWidth, height, true);
		for (int i = 0; i < keys.size(); i++) {
			String text = keys.get(i);
			TextureKey key = TextureKey.parse(text);
			boolean baked = art instanceof G3ArtSource;
			int[] argb = key != null && baked ? ((G3ArtSource) art).tileArgb(key.atlas(), key.tile()) : null;
			int[] shades = key == null || baked ? null : art.tileShades(key.atlas(), key.tile());
			int[] colours = key == null || baked ? null : bundle.palettes().colours(key.palette());
			if (key != null && (baked ? argb == null : shades == null || colours == null)) {
				throw new IllegalArgumentException("missing gallery texture " + text);
			}
			int flat = key == null ? (int) Long.parseLong(text.substring("color:".length()), 16) : 0;
			for (int y = 0; y < this.tileWidth; y++) for (int x = 0; x < this.tileWidth; x++) {
				int shade = shades == null ? 0 : shades[y * this.tileWidth + x];
				// G3 terrain retains its full baked colour, as BakedTextures.tile does.
				int rgb = key == null ? flat : baked ? argb[y * this.tileWidth + x] : shade < 0 ? 0 : colours[shade];
				int alpha = shade < 0 ? 0 : 255;
				int abgr = (alpha << 24) | ((rgb & 255) << 16) | (rgb & 0xff00) | ((rgb >>> 16) & 255);
				pixels.setPixelRGBA((i % this.atlasColumns) * this.tileWidth + x, (i / this.atlasColumns) * this.tileWidth + y, abgr);
			}
		}
		DynamicTexture dynamic = new DynamicTexture(pixels);
		dynamic.setFilter(false, false);
		this.texture = this.minecraft.getTextureManager().register("kantobridge-model-gallery", dynamic);
	}

	private void tick() {
		if (this.failed) return;
		if (this.galleryServer != null && this.minecraft.getSingleplayerServer() != this.galleryServer) return;
		try {
			if (++this.ticks > 20 * 600 && !this.prepared) throw new IllegalStateException("gallery startup timed out");
			if (!this.opened) {
				if (!(this.minecraft.screen instanceof TitleScreen)) return;
				this.opened = true;
				this.minecraft.options.pauseOnLostFocus = false;
				this.minecraft.options.hideGui = true;
				this.minecraft.options.setCameraType(CameraType.FIRST_PERSON);
				this.minecraft.options.fov().set((int) GalleryGeometry.FOV);
				this.minecraft.options.bobView().set(false);
				this.minecraft.options.cloudStatus().set(CloudStatus.OFF);
				double aspect = this.minecraft.getWindow().getWidth() / (double) this.minecraft.getWindow().getHeight();
				int renderDistance = this.exhibits.stream().mapToInt(e -> GalleryGeometry.renderDistance(e.bounds, aspect)).max().orElse(8);
				this.minecraft.options.renderDistance().set(renderDistance);
				this.report.addProperty("render_distance_chunks", renderDistance);
				this.minecraft.options.gamma().set(1.0);
				Path folder = this.minecraft.getLevelSource().getBaseDir().resolve(this.world);
				if (this.minecraft.getLevelSource().levelExists(this.world)) {
					if (!Files.isRegularFile(folder.resolve("model-gallery.json"))) throw new IllegalArgumentException("refusing to change a world without the model-gallery marker");
					this.minecraft.createWorldOpenFlows().openWorld(this.world, () -> this.minecraft.setScreen(new TitleScreen()));
				} else {
					LevelSettings settings = new LevelSettings(this.world, GameType.SPECTATOR, false, Difficulty.PEACEFUL, true,
							KantoGameRules.create(), WorldDataConfiguration.DEFAULT);
					this.minecraft.createWorldOpenFlows().createFreshLevel(this.world, settings, new WorldOptions(0, false, false),
							registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(PRESET).value().createWorldDimensions(),
							new GenericMessageScreen(Component.literal("Creating model gallery")));
					Files.writeString(folder.resolve("model-gallery.json"), "{\"schema\":1,\"manifest\":" + new com.google.gson.Gson().toJson(this.manifest.toString()) + "}");
				}
				return;
			}
			if (!this.prepared && !this.backupConfirmed && this.minecraft.screen instanceof BackupConfirmScreen backup) {
				// Only our explicitly opened, marked gallery is eligible. Invoke vanilla's backup-and-load button,
				// preserving its backup semantics instead of bypassing the experimental-world warning callback.
				Path marker = this.minecraft.getLevelSource().getBaseDir().resolve(this.world).resolve("model-gallery.json");
				if (!Files.isRegularFile(marker)) throw new IllegalStateException("gallery marker missing before backup");
				for (var widget : backup.children()) {
					if (widget instanceof Button button && button.getMessage().getContents() instanceof TranslatableContents text
							&& "selectWorld.backupJoinConfirmButton".equals(text.getKey())) {
						this.backupConfirmed = true;
						button.onPress();
						KantoBridge.LOGGER.info("gallery: backing up and reopening owned gallery world");
						return;
					}
				}
			}
			if (this.minecraft.player == null || this.minecraft.level == null || this.minecraft.getSingleplayerServer() == null) return;
			if (!this.prepared) {
				var activeServer = this.minecraft.getSingleplayerServer();
				Path expectedWorld = this.minecraft.getLevelSource().getBaseDir().resolve(this.world).toAbsolutePath().normalize();
				Path activeWorld = activeServer.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
				if (!activeWorld.equals(expectedWorld) || !Files.isRegularFile(activeWorld.resolve("model-gallery.json"))) {
					throw new IllegalStateException("active world is not the marked gallery directory");
				}
				this.galleryServer = activeServer;
				this.prepared = true;
				this.floor = this.minecraft.level.getMinBuildHeight() + 4;
				this.report.addProperty("floor_y", this.floor);
				this.minecraft.setScreen(null);
				var server = this.minecraft.getSingleplayerServer();
				server.execute(() -> {
					server.overworld().setDayTime(6000);
					server.overworld().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, server);
					server.overworld().setWeatherParameters(0, 1000000, false, false);
					for (Exhibit e : this.exhibits) {
						for (int x = GalleryGeometry.padMin(e.bounds.minX()); x < GalleryGeometry.padMax(e.width, e.bounds.maxX()); x++)
							for (int z = GalleryGeometry.padMin(e.bounds.minZ()); z < GalleryGeometry.padMax(e.depth, e.bounds.maxZ()); z++) {
							server.overworld().setBlock(new BlockPos((int) e.x + x, (int) this.floor - 1, (int) e.z + z), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 3);
							// Clean only pads produced by the initial gallery prototype's vanilla-height assumption.
							BlockPos oldPad = new BlockPos((int) e.x + x, -61, (int) e.z + z);
							if (this.floor != -60 && server.overworld().getBlockState(oldPad).is(Blocks.WHITE_CONCRETE)) {
								server.overworld().setBlock(oldPad, Blocks.AIR.defaultBlockState(), 3);
							}
						}
					}
				});
				position();
				if (!this.autoCapture) {
					this.finished = true;
					this.minecraft.options.hideGui = false;
					writeReport("viewing");
				}
				return;
			}
			if (this.finished) return;
			lockCamera();
			int required = this.shot == 0 ? Math.max(100, this.settleTicks) : this.settleTicks;
			if (++this.settle >= required && this.minecraft.screen == null && this.minecraft.getOverlay() == null
					&& this.positionedShot == this.shot && this.renderedShot == this.shot
					&& this.renderedFrames >= 2) this.pendingCapture = true;
			if (this.settle > 20 * 120) throw new IllegalStateException("gallery capture timed out waiting for acknowledged camera/render frames: shot="
					+ this.shot + ", server=" + this.positionedShot + ", rendered=" + this.renderedShot + ", frames=" + this.renderedFrames);
		} catch (Exception error) {
			fail(error);
		}
	}

	private void position() {
		Exhibit e = this.exhibits.get(this.shot / this.views.size());
		String view = this.views.get(this.shot % this.views.size());
		Bounds b = e.bounds;
		double aspect = this.minecraft.getWindow().getWidth() / (double) this.minecraft.getWindow().getHeight();
		GalleryGeometry.Camera camera = GalleryGeometry.camera(b, view, aspect);
		this.targetEye = new Vec3(e.x + camera.x(), this.floor + camera.y(), e.z + camera.z());
		this.targetYaw = camera.yaw();
		this.targetPitch = camera.pitch();
		var server = this.minecraft.getSingleplayerServer();
		if (server == null || server != this.galleryServer) return;
		var uuid = this.minecraft.player.getUUID();
		Vec3 eye = this.targetEye;
		float yaw = this.targetYaw;
		float pitch = this.targetPitch;
		int positioned = this.shot;
		this.positionedShot = -1;
		server.execute(() -> {
			List<net.minecraft.world.entity.Entity> entities = new ArrayList<>();
			server.overworld().getAllEntities().forEach(entity -> {
				if (!(entity instanceof net.minecraft.world.entity.player.Player)) entities.add(entity);
			});
			entities.forEach(net.minecraft.world.entity.Entity::discard);
			var player = server.getPlayerList().getPlayer(uuid);
			if (player != null) {
				player.setGameMode(GameType.SPECTATOR);
				player.teleportTo(server.overworld(), eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, pitch);
				this.positionedShot = positioned;
			}
		});
		this.settle = 0;
		this.renderedShot = -1;
		this.renderedFrames = 0;
		lockCamera();
	}

	private void lockCamera() {
		if (this.targetEye == null || this.minecraft.player == null) return;
		var player = this.minecraft.player;
		player.setPos(this.targetEye.x, this.targetEye.y - player.getEyeHeight(), this.targetEye.z);
		player.xo = player.getX(); player.yo = player.getY(); player.zo = player.getZ();
		player.setDeltaMovement(Vec3.ZERO);
		player.setYRot(this.targetYaw); player.yRotO = this.targetYaw;
		player.setXRot(this.targetPitch); player.xRotO = this.targetPitch;
	}

	private void render(WorldRenderContext context) {
		if (!this.prepared || this.failed || context.consumers() == null || this.minecraft.getSingleplayerServer() != this.galleryServer) return;
		PoseStack stack = context.matrixStack();
		Vec3 camera = context.camera().getPosition();
		renderBackdrop(stack, camera);
		double browseRange = Math.max(128, this.minecraft.options.renderDistance().get() * 16);
		int height = ((this.minecraft.getTextureManager().getTexture(this.texture) instanceof DynamicTexture t) ? t.getPixels().getHeight() : this.atlasWidth);
		for (Exhibit exhibit : this.exhibits) {
			// Keep the photographed object isolated; the complete display returns when the capture pass ends.
			if (!this.finished && exhibit != this.exhibits.get(this.shot / this.views.size())) continue;
			// The current subject must survive even when fitting a large building puts the camera >100 blocks away.
			// Interactive browsing measures distance from the nearest mesh face instead of the exhibit's centre.
			if (this.finished && exhibit.bounds.distanceSquared(camera.x - exhibit.x, camera.y - this.floor, camera.z - exhibit.z) > browseRange * browseRange) continue;
			stack.pushPose();
			stack.translate(exhibit.x - camera.x, this.floor - camera.y, exhibit.z - camera.z);
			VertexConsumer consumer = context.consumers().getBuffer(RenderType.entityCutoutNoCull(this.texture));
			for (Piece piece : exhibit.pieces) {
				stack.pushPose();
				stack.translate(piece.x, piece.y, piece.z);
				PoseStack.Pose pose = stack.last();
				ShapeMesh mesh = piece.mesh;
				for (int q = 0; q < mesh.size(); q++) {
					int textureIndex = mesh.texture(q);
					float ox = (textureIndex % this.atlasColumns) * this.tileWidth;
					float oy = (textureIndex / this.atlasColumns) * this.tileWidth;
					int face = mesh.face(q);
					float nx = face == 4 ? -1 : face == 5 ? 1 : 0;
					float ny = face == 0 ? -1 : face == 1 ? 1 : 0;
					float nz = face == 2 ? -1 : face == 3 ? 1 : 0;
					int shade = TINTS[mesh.tint(q)] * (mesh.has(q, ShapeQuad.DARKEN) ? 180 : 255) / 255;
					float centreU = 0, centreV = 0;
					for (int v = 0; v < 4; v++) { centreU += mesh.cornerU(q, v) / 4; centreV += mesh.cornerV(q, v) / 4; }
					for (int v = 0; v < 4; v++) {
						float u = mesh.cornerU(q, v), vv = mesh.cornerV(q, v);
						// Nearest filtering plus a tiny inward offset keeps tile neighbours out of boundary samples.
						u += (centreU - u) * 0.001f; vv += (centreV - vv) * 0.001f;
						consumer.addVertex(pose, mesh.x(q, v) / 16f, mesh.y(q, v) / 16f, mesh.z(q, v) / 16f)
								.setColor(shade, shade, shade, 255)
								.setUv((ox + u * this.tileWidth / 16f) / this.atlasWidth, (oy + vv * this.tileWidth / 16f) / height)
								.setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
					}
				}
				stack.popPose();
			}
			if (this.finished && !this.minecraft.options.hideGui) {
				stack.pushPose();
				stack.translate(exhibit.width / 2, exhibit.height + 0.45, exhibit.depth / 2);
				stack.mulPose(context.camera().rotation());
				stack.scale(0.025f, -0.025f, 0.025f);
				var font = this.minecraft.font;
				font.drawInBatch(exhibit.label, -font.width(exhibit.label) / 2f, 0, 0xffffffff, false,
						stack.last().pose(), context.consumers(), Font.DisplayMode.NORMAL, 0xb0000000, LightTexture.FULL_BRIGHT);
				stack.popPose();
			}
			stack.popPose();
		}
		if (!this.finished && this.targetEye != null && camera.distanceToSqr(this.targetEye) < 0.0001
				&& Math.abs(net.minecraft.util.Mth.wrapDegrees(context.camera().getYRot() - this.targetYaw)) < 0.01
				&& Math.abs(context.camera().getXRot() - this.targetPitch) < 0.01) {
			if (this.renderedShot != this.shot) { this.renderedShot = this.shot; this.renderedFrames = 0; }
			this.renderedFrames++;
		}
	}

	/**
	 * A photographic floor, rendered in Minecraft before our subjects. Chunk
	 * compilation is asynchronous and culled sections may never compile; it is
	 * not a useful readiness condition for geometry emitted directly above.
	 * Drawing only this backdrop without depth testing replaces loaded/missing
	 * ground uniformly. It does not touch the depth buffer: the normal textured
	 * ShapeTable quads below retain their ordinary depth testing and materials.
	 */
	private void renderBackdrop(PoseStack stack, Vec3 camera) {
		float radius = Math.max(1024, this.minecraft.options.renderDistance().get() * 64);
		float y = (float) (this.floor - camera.y);
		PoseStack.Pose pose = stack.last();
		RenderSystem.disableDepthTest();
		RenderSystem.depthMask(false);
		try {
			RenderSystem.setShader(GameRenderer::getPositionColorShader);
			var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
			buffer.addVertex(pose, -radius, y, -radius).setColor(122, 122, 112, 255);
			buffer.addVertex(pose, -radius, y, radius).setColor(122, 122, 112, 255);
			buffer.addVertex(pose, radius, y, radius).setColor(122, 122, 112, 255);
			buffer.addVertex(pose, radius, y, -radius).setColor(122, 122, 112, 255);
			BufferUploader.drawWithShader(buffer.buildOrThrow());
		} finally {
			RenderSystem.depthMask(true);
			RenderSystem.enableDepthTest();
		}
	}

	/** Saved or newly loaded passive entities must never contaminate model photos. */
	private void clearVisualEntities() {
		var server = this.minecraft.getSingleplayerServer();
		if (!this.prepared || this.minecraft.level == null || server == null || server != this.galleryServer) return;
		List<net.minecraft.world.entity.Entity> entities = new ArrayList<>();
		this.minecraft.level.entitiesForRendering().forEach(entity -> {
			if (!(entity instanceof net.minecraft.world.entity.player.Player)) entities.add(entity);
		});
		entities.forEach(net.minecraft.world.entity.Entity::discard);
		this.hiddenEntities += entities.size();
	}

	private void capture() {
		if (!this.pendingCapture || this.failed || this.finished || this.minecraft.getSingleplayerServer() != this.galleryServer) return;
		this.pendingCapture = false;
		try {
			Exhibit e = this.exhibits.get(this.shot / this.views.size());
			String direction = this.views.get(this.shot % this.views.size());
			String filename = e.id + "_" + direction + ".png";
			try (NativeImage image = Screenshot.takeScreenshot(this.minecraft.getMainRenderTarget())) {
				image.writeToFile(this.output.resolve("screenshots").resolve(filename));
				JsonObject capture = new JsonObject();
				capture.addProperty("id", e.id); capture.addProperty("direction", direction);
				capture.addProperty("file", "screenshots/" + filename);
				capture.addProperty("width", image.getWidth()); capture.addProperty("height", image.getHeight());
				capture.addProperty("yaw", this.targetYaw); capture.addProperty("pitch", this.targetPitch);
				capture.addProperty("settled_ticks", this.settle);
				capture.addProperty("rendered_frames", this.renderedFrames);
				capture.addProperty("server_positioned_shot", this.positionedShot);
					JsonArray bounds = new JsonArray();
					for (double coordinate : new double[] {e.bounds.minX(), e.bounds.minY(), e.bounds.minZ(), e.bounds.maxX(), e.bounds.maxY(), e.bounds.maxZ()}) bounds.add(coordinate);
					capture.add("mesh_bounds", bounds);
				JsonArray eye = new JsonArray(); eye.add(this.targetEye.x); eye.add(this.targetEye.y); eye.add(this.targetEye.z);
				capture.add("eye", eye);
				this.captures.add(capture);
			}
			KantoBridge.LOGGER.info("gallery: captured {} {} ({}/{})", e.id, direction, this.shot + 1, this.exhibits.size() * this.views.size());
			this.shot++;
			if (this.shot >= this.exhibits.size() * this.views.size()) {
				this.finished = true;
				writeReport("complete");
				if (Boolean.getBoolean(PROPERTY + ".quit")) requestShutdown();
				else this.minecraft.options.hideGui = false;
			} else {
				writeReport("capturing");
				position();
			}
		} catch (Exception error) {
			fail(error);
		}
	}

	private void writeReport(String status) throws java.io.IOException {
		this.report.addProperty("non_player_entities_hidden", this.hiddenEntities);
		this.report.addProperty("status", status);
		// Merely opening the gallery must preserve completed screenshot provenance byte-for-byte.
		String reportName = this.autoCapture ? "capture.json" : "view-session.json";
		Files.writeString(this.output.resolve(reportName), new GsonBuilder().setPrettyPrinting().create().toJson(this.report));
	}

	private void fail(Exception error) {
		this.failed = true;
		KantoBridge.LOGGER.error("gallery: failed", error);
		this.report.addProperty("error", error.toString());
		try { writeReport("failed"); } catch (Exception ignored) { }
		if (Boolean.getBoolean(PROPERTY + ".quit")) requestShutdown();
	}

	/** Keep the same integrated server ticking after the last camera jump; never alter capture.json here. */
	private void requestShutdown() {
		if (this.shutdownRequested || this.galleryServer == null || this.minecraft.getSingleplayerServer() != this.galleryServer) return;
		this.shutdownRequested = true;
		var server = this.galleryServer;
		Path expected = this.minecraft.getLevelSource().getBaseDir().resolve(this.world).toAbsolutePath().normalize();
		server.execute(() -> {
			Path active = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
			try {
				this.shutdown = new GalleryShutdown(server, active, expected, Files.isRegularFile(active.resolve("model-gallery.json")), server.getTickCount());
				writeShutdown("draining", server.getTickCount(), new GalleryShutdown.Work(0, 0, 0));
				KantoBridge.LOGGER.info("gallery: capture evidence saved; draining generation before normal shutdown");
			} catch (IllegalArgumentException error) {
				KantoBridge.LOGGER.error("gallery: refusing shutdown outside its exact marked server/world", error);
			}
		});
	}

	private void drainShutdown(net.minecraft.server.MinecraftServer server) {
		GalleryShutdown gate = this.shutdown;
		if (gate == null || server != this.galleryServer) return;
		Path active = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
		boolean marked = Files.isRegularFile(active.resolve("model-gallery.json"));
		boolean current = this.minecraft.getSingleplayerServer() == server;
		java.util.Set<net.minecraft.server.level.ChunkHolder> holders = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		int pending = 0;
		if (current && gate.owns(server, active, marked)) {
			for (var level : server.getAllLevels()) {
				var chunks = (GalleryChunkMapAccessor) level.getChunkSource().chunkMap;
				holders.addAll(chunks.kantobridge$galleryUpdatingChunks().values());
				holders.addAll(chunks.kantobridge$galleryPendingUnloads().values());
				pending += chunks.kantobridge$galleryPendingGeneration().size();
			}
		}
		int references = 0, saves = 0;
		for (var holder : holders) {
			references += holder.getGenerationRefCount();
			if (!holder.getSaveSyncFuture().isDone()) saves++;
		}
		var work = new GalleryShutdown.Work(references, pending, saves);
		var result = gate.poll(current ? server : null, active, marked, server.getTickCount(), work);
		if (result == GalleryShutdown.Result.WAITING) {
			if (gate.elapsed(server.getTickCount()) % 20 == 0) writeShutdown("draining", server.getTickCount(), work);
			return;
		}
		this.shutdown = null;
		writeShutdown(result == GalleryShutdown.Result.STOP ? "stop_requested" : result.name().toLowerCase(java.util.Locale.ROOT), server.getTickCount(), work, gate);
		if (result == GalleryShutdown.Result.STOP) {
			KantoBridge.LOGGER.info("gallery: generation drained after {} ticks; requesting normal shutdown", gate.elapsed(server.getTickCount()));
			this.minecraft.execute(() -> {
				if (this.minecraft.getSingleplayerServer() == server && gate.owns(server,
						server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT), Files.isRegularFile(active.resolve("model-gallery.json")))) this.minecraft.stop();
			});
		} else KantoBridge.LOGGER.error("gallery: shutdown {} after {} ticks; completed capture evidence is preserved", result, gate.elapsed(server.getTickCount()));
	}

	private void writeShutdown(String status, int tick, GalleryShutdown.Work work) { writeShutdown(status, tick, work, this.shutdown); }
	private void writeShutdown(String status, int tick, GalleryShutdown.Work work, GalleryShutdown gate) {
		JsonObject state = new JsonObject();
		state.addProperty("status", status); state.addProperty("manifest", this.manifest.toString());
		state.addProperty("server_ticks", gate == null ? 0 : gate.elapsed(tick)); state.addProperty("quiet_ticks", gate == null ? 0 : gate.quietTicks());
		state.addProperty("generation_references", work.generationReferences()); state.addProperty("pending_generation", work.pendingGeneration());
		state.addProperty("pending_save_dependencies", work.pendingSaveDependencies());
		try { Files.writeString(this.output.resolve("shutdown.json"), new GsonBuilder().setPrettyPrinting().create().toJson(state)); }
		catch (java.io.IOException error) { KantoBridge.LOGGER.error("gallery: cannot write shutdown diagnostic", error); }
	}
}
