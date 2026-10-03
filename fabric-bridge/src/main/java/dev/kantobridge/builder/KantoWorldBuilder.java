package dev.kantobridge.builder;

import dev.kantobridge.builder.export.ExportBundle;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * The world builder's entry point (docs/PLAN.md §4.5): builds Kanto from an export bundle into a level. Used by the
 * headless trigger ({@link HeadlessBuild}), GameTests, and the client's "Create Kanto world" flow (integrated server).
 *
 * <p>Typical use, on the server thread:
 * <pre>{@code
 * ExportBundle bundle = ExportBundle.open(gameDir.resolve("kantobridge/export/red"));
 * BuildTask task = KantoWorldBuilder.start(server.overworld(), bundle, List.of("PALLET_TOWN"), BuildOptions.defaults(), listener);
 * }</pre>
 * The task plans off-thread, then places blocks a few milliseconds per tick; poll {@link BuildTask#phase()} and
 * {@link BuildTask#progress()}, or listen.
 */
public final class KantoWorldBuilder {
	private KantoWorldBuilder() {
	}

	/**
	 * Starts a build. Call on the server thread.
	 *
	 * @param maps the map ids to build (e.g. Pallet Town and its interiors); null builds every map
	 */
	public static BuildTask start(ServerLevel level, ExportBundle bundle, @Nullable List<String> maps, BuildOptions options,
			BuildListener listener) {
		if (!level.getServer().isSameThread()) {
			throw new IllegalStateException("start builds on the server thread");
		}
		BuildTask task = new BuildTask(level, bundle, maps, options, listener);
		BuildTasks.add(task);
		task.start();
		return task;
	}

	/** {@code <gameDir>/kantobridge/resourcepack}: the folder the client's pack-source mixin offers. */
	public static Path defaultPackDir() {
		return FabricLoader.getInstance().getGameDir().resolve("kantobridge").resolve("resourcepack");
	}

	/** {@code <gameDir>/kantobridge/export/<game>}: where exports are stored. */
	public static Path defaultExportDir(String game) {
		return FabricLoader.getInstance().getGameDir().resolve("kantobridge").resolve("export").resolve(game);
	}

	/** The level's world folder. */
	public static Path worldDir(ServerLevel level) {
		return level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
	}
}
