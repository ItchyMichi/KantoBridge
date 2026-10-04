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
 * The resource pack the world builder generates from the player's own ROM: tile textures and sprites, addressed by key
 * (docs/PLAN.md §4.3 {@code packs/}; layout 2, docs/specs/VOXEL_BLOCKS_PLAN.md §4.7). It lives in
 * {@code <gameDir>/kantobridge/resourcepack/}, outside any shared folder, and is never sent to other players.
 *
 * <p>It sits at the <b>bottom</b> of the pack stack (VD9): it holds no blockstate or model to win any more, so player
 * packs above it override single textures by repeating their paths, and the order a player sets is kept across starts.
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

	/** Required, so it is always selected and can't be switched off; at the bottom, below every player pack. */
	public static PackSelectionConfig selection() {
		return new PackSelectionConfig(true, Pack.Position.BOTTOM, false);
	}

	/** The pack, or null when there is none yet or its {@code pack.mcmeta} is invalid (Minecraft logs why). */
	public static @Nullable Pack create(Path gameDir) {
		if (!present(gameDir)) {
			return null;
		}
		return Pack.readMetaAndCreate(location(), new PathPackResources.PathResourcesSupplier(directory(gameDir)),
				PackType.CLIENT_RESOURCES, selection());
	}

	/** What the pack says about itself ({@code kantobridge.json}), or null without one. */
	public static @Nullable PackInfo info(Path gameDir) {
		return present(gameDir) ? PackInfo.read(directory(gameDir)) : null;
	}

	/** Hands the pack to a pack source's consumer if there is one. */
	public static void offer(Path gameDir, Consumer<Pack> consumer) {
		Pack pack = create(gameDir);
		if (pack != null) {
			consumer.accept(pack);
		}
	}
}
