package dev.kantobridge.client.model;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.shape.QuadCounter;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.shape.ClientShapes;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.world.ShapeMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The measuring tool for the voxel plan (docs/specs/VOXEL_BLOCKS_PLAN.md V1a, §10): with {@code -Dkantobridge.meshstats=true}
 * (the dev client: {@code ./gradlew runClient -Pkb.meshstats=true}), a key (F8 by default) measures the current scene:
 *
 * <ol>
 * <li>a full re-mesh ({@code allChanged()}), timed until the last section finished and the queue stayed empty;</li>
 * <li>30 s of frame times: the mean and the "1% low" frame rate;</li>
 * <li>the heap after two garbage collections.</li>
 * </ol>
 * The run, with the section timings of the dev mixin, the last resource reload's duration and the time from joining to the
 * first full view, is appended to {@code mesh_report.json} (numbers only) and shown on F7. Client thread only.
 */
public final class MeshStatsRuntime {
	/** The queue must stay empty this long, with no section finishing, before a re-mesh counts as done. */
	static final long QUIET_NANOS = 500_000_000L;
	static final long FRAME_WINDOW_NANOS = 30_000_000_000L;
	static final long REMESH_LIMIT_NANOS = 120_000_000_000L;

	private enum Phase {
		IDLE,
		REMESH,
		FRAMES,
		DONE
	}

	private static @Nullable MeshStatsRuntime instance;

	private final KeyMapping key;
	private Phase phase = Phase.IDLE;
	private long phaseStart;
	private long lastFrame;
	private long[] frames = new long[4096];
	private int frameCount;
	private double remeshMs = -1;
	private long reloadStart;
	private double reloadMs = -1;
	private long joinedAt;
	private double joinToViewMs = -1;
	private @Nullable JsonObject last;
	private int runs;

	private MeshStatsRuntime(KeyMapping key) {
		this.key = key;
	}

	/** Call once from the client entrypoint; does nothing without {@code -Dkantobridge.meshstats=true}. */
	public static void init(BridgeRuntime bridge) {
		if (!MeshStats.ENABLED || instance != null) {
			return;
		}
		KeyMapping key = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.kantobridge.mesh_report", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.kantobridge"));
		MeshStatsRuntime runtime = new MeshStatsRuntime(key);
		instance = runtime;
		ClientTickEvents.END_CLIENT_TICK.register(runtime::tick);
		WorldRenderEvents.END.register(context -> runtime.frame());
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			runtime.joinedAt = System.nanoTime();
			runtime.joinToViewMs = -1;
		});
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new IdentifiableResourceReloadListener() {
			@Override
			public ResourceLocation getFabricId() {
				return ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "mesh_stats");
			}

			@Override
			public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager manager, ProfilerFiller preparations,
					ProfilerFiller reload, Executor background, Executor game) {
				// Every listener's reload() is called when the reload starts, before any preparation runs.
				runtime.reloadStart = System.nanoTime();
				return barrier.wait(null).thenRun(() -> {
				});
			}
		});
		InvalidateRenderStateCallback.EVENT.register(() -> {
			if (runtime.reloadStart != 0) {
				runtime.reloadMs = (System.nanoTime() - runtime.reloadStart) / 1e6;
				runtime.reloadStart = 0;
			}
		});
		bridge.addDebugLine(runtime::debugLine);
		KantoBridge.LOGGER.info("meshstats: on; F8 measures the current view into {}", reportPath());
	}

	/**
	 * Measures the current view now, as the key does (the dev pilot's "measure": a frame-rate test without a keyboard);
	 * false when meshstats is off, no world is loaded or a measurement runs.
	 */
	public static boolean measure(Minecraft minecraft) {
		MeshStatsRuntime runtime = instance;
		if (runtime == null || minecraft.level == null || runtime.measuring()) {
			return false;
		}
		runtime.start(minecraft);
		return true;
	}

	/** True while a measurement (its re-mesh or its frames) runs. */
	public static boolean busy() {
		MeshStatsRuntime runtime = instance;
		return runtime != null && runtime.measuring();
	}

	private boolean measuring() {
		return this.phase == Phase.REMESH || this.phase == Phase.FRAMES;
	}

	static Path reportPath() {
		String configured = System.getProperty(MeshStats.REPORT_PROPERTY);
		if (configured != null && !configured.isBlank()) {
			return Path.of(configured);
		}
		return FabricLoader.getInstance().getGameDir().resolve("kantobridge").resolve("reports").resolve("mesh_report.json");
	}

	private void tick(Minecraft minecraft) {
		while (this.key.consumeClick()) {
			if (this.phase != Phase.REMESH && this.phase != Phase.FRAMES && minecraft.level != null) {
				this.start(minecraft);
			}
		}
		if (this.joinedAt != 0 && minecraft.level != null && minecraft.levelRenderer.hasRenderedAllSections()
				&& MeshStats.sectionsCompiled() > 0 && System.nanoTime() - MeshStats.lastSectionEnd() > QUIET_NANOS
				&& MeshStats.lastSectionEnd() > this.joinedAt) {
			this.joinToViewMs = (MeshStats.lastSectionEnd() - this.joinedAt) / 1e6;
			this.joinedAt = 0;
		}
	}

	private void start(Minecraft minecraft) {
		this.phase = Phase.REMESH;
		this.phaseStart = System.nanoTime();
		this.remeshMs = -1;
		this.frameCount = 0;
		MeshStats.startRecording();
		minecraft.levelRenderer.allChanged();
		KantoBridge.LOGGER.info("meshstats: measuring (re-mesh, then {} s of frames)", FRAME_WINDOW_NANOS / 1_000_000_000L);
	}

	/** Once per rendered frame. */
	private void frame() {
		long now = System.nanoTime();
		long delta = this.lastFrame == 0 ? 0 : now - this.lastFrame;
		this.lastFrame = now;
		Minecraft minecraft = Minecraft.getInstance();
		switch (this.phase) {
			case REMESH -> {
				long lastEnd = MeshStats.lastSectionEnd();
				boolean quiet = minecraft.levelRenderer.hasRenderedAllSections() && lastEnd > this.phaseStart && now - lastEnd > QUIET_NANOS;
				if (quiet || now - this.phaseStart > REMESH_LIMIT_NANOS) {
					this.remeshMs = quiet ? (lastEnd - this.phaseStart) / 1e6 : -1;
					MeshStats.stopRecording();
					this.phase = Phase.FRAMES;
					this.phaseStart = now;
				}
			}
			case FRAMES -> {
				if (delta > 0) {
					if (this.frameCount == this.frames.length) {
						this.frames = Arrays.copyOf(this.frames, this.frames.length * 2);
					}
					this.frames[this.frameCount++] = delta;
				}
				if (now - this.phaseStart >= FRAME_WINDOW_NANOS) {
					this.finish(minecraft);
				}
			}
			default -> {
			}
		}
	}

	private void finish(Minecraft minecraft) {
		this.phase = Phase.DONE;
		Runtime vm = Runtime.getRuntime();
		System.gc();
		System.gc();
		double heapMb = (vm.totalMemory() - vm.freeMemory()) / (1024.0 * 1024.0);
		long[][] sections = MeshStats.sections();
		double[] quads = Arrays.stream(sections[2]).asDoubleStream().toArray();
		MeshStats.Summary frameMs = MeshStats.summarizeNanos(Arrays.copyOf(this.frames, this.frameCount));
		double[] fps = MeshStats.fps(frameMs);
		JsonObject run = new JsonObject();
		run.addProperty("at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
		run.addProperty("model", MeshStats.model());
		run.addProperty("renderer", RendererAccess.INSTANCE.hasRenderer() ? RendererAccess.INSTANCE.getRenderer().getClass().getSimpleName() : "none");
		run.addProperty("render_distance", minecraft.options.getEffectiveRenderDistance());
		Entity camera = minecraft.getCameraEntity();
		if (camera != null) {
			JsonArray position = new JsonArray();
			position.add(MeshStats.round(camera.getX()));
			position.add(MeshStats.round(camera.getY()));
			position.add(MeshStats.round(camera.getZ()));
			run.add("position", position);
			JsonArray rotation = new JsonArray();
			rotation.add(MeshStats.round(camera.getYRot()));
			rotation.add(MeshStats.round(camera.getXRot()));
			run.add("rotation", rotation);
		}
		run.addProperty("remesh_ms", MeshStats.round(this.remeshMs));
		run.add("section_ms", MeshStats.summarizeNanos(sections[0]).toJson());
		run.add("ours_ms", MeshStats.summarizeNanos(sections[1]).toJson());
		run.add("quads_per_section", MeshStats.summarize(quads).toJson());
		run.add("frame_ms", frameMs.toJson());
		JsonObject rate = new JsonObject();
		rate.addProperty("mean", fps[0]);
		rate.addProperty("low1", fps[1]);
		run.add("fps", rate);
		run.addProperty("heap_mb", MeshStats.round(heapMb));
		run.add("prediction", prediction(minecraft, sections));
		run.addProperty("reload_ms", MeshStats.round(this.reloadMs));
		run.addProperty("join_to_view_ms", MeshStats.round(this.joinToViewMs));
		this.last = run;
		this.runs++;
		Path path = reportPath();
		try {
			JsonObject report = new JsonObject();
			if (Files.isRegularFile(path)) {
				try {
					report = ProtoJson.parseObject(Files.readString(path, StandardCharsets.UTF_8));
				} catch (ProtocolException e) {
					report = new JsonObject();
				}
			}
			report = MeshStats.append(report, run);
			Files.createDirectories(path.toAbsolutePath().getParent());
			Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n", StandardCharsets.UTF_8);
			KantoBridge.LOGGER.info("meshstats: run {} written to {}: re-mesh {} ms, {} fps (1% low {}), heap {} MB", this.runs, path,
					MeshStats.round(this.remeshMs), fps[0], fps[1], MeshStats.round(heapMb));
		} catch (IOException e) {
			KantoBridge.LOGGER.error("meshstats: could not write {} ({})", path, e.getClass().getSimpleName());
		}
	}

	/** Sections the dev-only mesh check compares per run, at most. */
	static final int PREDICTED = 256;

	/**
	 * The dev-only mesh check (docs/specs/VOXEL_BLOCKS_PLAN.md §6.3): for up to {@link #PREDICTED} sections this run
	 * meshed, the quads our model emitted there against the pure-Java prediction ({@link QuadCounter#emittedAt}) from
	 * this client's level and shapes. Counts only: sections compared, how many agree, the largest difference.
	 */
	static JsonObject prediction(Minecraft minecraft, long[][] sections) {
		Map<Long, Long> latest = new LinkedHashMap<>();
		for (int s = 0; s < sections[3].length; s++) {
			latest.put(sections[3][s], sections[2][s]);
		}
		ClientLevel level = minecraft.level;
		ShapeTable table = ClientShapes.table();
		ShapeMap.Lookup lookup = ClientShapes.lookup();
		RenderSnapshot snapshot = RenderSnapshot.current();
		Map<Block, Integer> codes = new HashMap<>();
		for (KbBlocks.Carrier carrier : KbBlocks.carriers()) {
			codes.put(carrier.block(), switch (carrier.kind()) {
				case TILE_FULL -> BlockCode.full(0);
				case TILE_HALF -> BlockCode.half(0);
				case SHAPE_FULL -> BlockCode.shape(0);
				case DECOR -> BlockCode.decor(false);
				case DECOR_LEAVES -> BlockCode.decor(true);
				case LEDGE -> BlockCode.ledge(0);
				case STEP -> BlockCode.step(dev.kantobridge.world.Dir.RIGHT);
				case STORY_TILE -> BlockCode.storyTile(0);
				case SEAT -> BlockCode.seat();
			});
		}
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		QuadCounter.World world = new QuadCounter.World() {
			@Override
			public int code(int x, int y, int z) {
				return level == null ? BlockCode.AIR : codes.getOrDefault(level.getBlockState(cursor.set(x, y, z)).getBlock(), BlockCode.AIR);
			}

			@Override
			public int shapeId(int x, int y, int z) {
				return lookup == null || table == null ? 0 : lookup.shapeAt(x, y, z);
			}

			@Override
			public int closedId(int x, int y, int z) {
				return snapshot.closedIdAt(x, y, z);
			}
		};
		int compared = 0;
		int equal = 0;
		long maxDiff = 0;
		if (level != null) {
			for (Map.Entry<Long, Long> e : latest.entrySet()) {
				if (compared >= PREDICTED) {
					break;
				}
				SectionPos section = SectionPos.of(e.getKey());
				long predicted = 0;
				for (int y = section.minBlockY(); y <= section.maxBlockY(); y++) {
					for (int z = section.minBlockZ(); z <= section.maxBlockZ(); z++) {
						for (int x = section.minBlockX(); x <= section.maxBlockX(); x++) {
							predicted += QuadCounter.emittedAt(world, table, x, y, z);
						}
					}
				}
				compared++;
				equal += predicted == e.getValue() ? 1 : 0;
				maxDiff = Math.max(maxDiff, Math.abs(predicted - e.getValue()));
			}
		}
		JsonObject out = new JsonObject();
		out.addProperty("sections", compared);
		out.addProperty("equal", equal);
		out.addProperty("max_diff", maxDiff);
		return out;
	}

	private String debugLine() {
		JsonObject run = this.last;
		String phase = this.phase.name().toLowerCase(Locale.ROOT);
		if (run == null) {
			return I18n.get("kantobridge.debug.mesh.idle", phase, MeshStats.model(), MeshStats.sectionsCompiled());
		}
		return I18n.get("kantobridge.debug.mesh", phase, run.get("model").getAsString(), run.get("remesh_ms").getAsDouble(),
				run.getAsJsonObject("section_ms").get("count").getAsInt(), run.getAsJsonObject("section_ms").get("p95").getAsDouble(),
				run.getAsJsonObject("ours_ms").get("p95").getAsDouble(), run.getAsJsonObject("fps").get("mean").getAsDouble(),
				run.getAsJsonObject("fps").get("low1").getAsDouble(), run.get("heap_mb").getAsDouble(), run.get("reload_ms").getAsDouble(),
				run.get("join_to_view_ms").getAsDouble(), this.runs);
	}
}
