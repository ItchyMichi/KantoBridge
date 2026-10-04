package dev.kantobridge.client.packs;

import dev.kantobridge.KantoBridge;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

/**
 * The generated pack as the last resource reload loaded it (docs/specs/VOXEL_BLOCKS_PLAN.md §5.2): its
 * {@code kantobridge.json}, read when resources reload, so the art set the client draws with is the one in the block
 * atlas, even after a newer pack was written to disk. Every {@link #generation()} is one reload. Client thread only.
 */
public final class LoadedPack {
	private static @Nullable PackInfo info;
	private static long generation;
	private static boolean registered;

	private LoadedPack() {
	}

	/** Call once from client init, before anything that reads it. */
	public static void init() {
		if (registered) {
			return;
		}
		registered = true;
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public ResourceLocation getFabricId() {
				return ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "loaded_pack");
			}

			@Override
			public void onResourceManagerReload(ResourceManager manager) {
				info = GeneratedPack.info(FabricLoader.getInstance().getGameDir());
				generation++;
			}
		});
	}

	/** The loaded pack's info, or null when no generated pack was loaded. */
	public static @Nullable PackInfo info() {
		return info;
	}

	/** The art set the block atlas holds, or null when the loaded pack can't be drawn from (none, or layout 1). */
	public static @Nullable String artSet() {
		PackInfo loaded = info;
		return loaded != null && loaded.usable() ? loaded.artSet() : null;
	}

	/** Counts resource reloads: a new value means a new block atlas. */
	public static long generation() {
		return generation;
	}
}
