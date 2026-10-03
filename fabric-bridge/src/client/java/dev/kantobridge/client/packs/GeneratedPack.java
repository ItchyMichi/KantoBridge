package dev.kantobridge.client.packs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import org.jetbrains.annotations.Nullable;

/**
 * The resource pack the world builder generates from the player's own ROM: tile textures, sprites and blockstate
 * variants (docs/PLAN.md §4.3 {@code packs/}). It lives in {@code <gameDir>/kantobridge/resourcepack/}, outside
 * any shared folder, and is never sent to other players.
 */
public final class GeneratedPack {
	public static final String ID = "kantobridge_generated";

	/** Added to {@code BuiltInPackSource} by the pack-source mixin, so tests can see that the mixin applied. */
	public interface SourceMarker {
	}

	private GeneratedPack() {
	}

	public static Path directory(Path gameDir) {
		return gameDir.resolve("kantobridge").resolve("resourcepack");
	}

	/** True once the builder has written a pack (a {@code pack.mcmeta} file) for this game directory. */
	public static boolean present(Path gameDir) {
		return Files.isRegularFile(directory(gameDir).resolve("pack.mcmeta"));
	}

	public static PackLocationInfo location() {
		return new PackLocationInfo(ID, Component.literal("Kanto Bridge (generated)"), PackSource.BUILT_IN, Optional.empty());
	}

	/** Required, so it is always selected and can't be switched off; on top, so it overrides the jar's placeholders. */
	public static PackSelectionConfig selection() {
		return new PackSelectionConfig(true, Pack.Position.TOP, false);
	}

	/** The pack, or null when there is none yet or its {@code pack.mcmeta} is invalid (Minecraft logs why). */
	public static @Nullable Pack create(Path gameDir) {
		if (!present(gameDir)) {
			return null;
		}
		return Pack.readMetaAndCreate(location(), new PathPackResources.PathResourcesSupplier(directory(gameDir)),
				PackType.CLIENT_RESOURCES, selection());
	}

	/** Hands the pack to a pack source's consumer if there is one. */
	public static void offer(Path gameDir, Consumer<Pack> consumer) {
		Pack pack = create(gameDir);
		if (pack != null) {
			consumer.accept(pack);
		}
	}
}
