package dev.kantobridge.client.packs;

import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/**
 * Makes Minecraft pick up a freshly written generated pack (docs/PLAN.md §4.3 {@code packs/}). The pack-source mixin
 * offers {@code <gameDir>/kantobridge/resourcepack/} whenever the client repository asks its sources, and a required
 * pack is selected automatically, so a re-scan plus a resource reload is all it takes, also for a folder that didn't
 * exist at start-up. Client thread only.
 */
public final class PackReload {
	private PackReload() {
	}

	/** Re-scans the pack sources and reloads resources; the future completes when the new textures are in use. */
	public static CompletableFuture<Void> reload(Minecraft minecraft) {
		// reloadResourcePacks() re-scans too; doing it first makes the new pack visible even if a reload is already
		// running and this call only queues behind it.
		minecraft.getResourcePackRepository().reload();
		return minecraft.reloadResourcePacks();
	}

	/** True when the generated pack is selected (it only can be once its folder holds a valid pack). */
	public static boolean selected(Minecraft minecraft) {
		return minecraft.getResourcePackRepository().getSelectedIds().contains(GeneratedPack.ID);
	}
}
