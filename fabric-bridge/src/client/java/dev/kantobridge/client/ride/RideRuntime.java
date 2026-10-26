package dev.kantobridge.client.ride;

import dev.kantobridge.client.move.MoveRuntime;
import dev.kantobridge.entity.BikeEntity;
import dev.kantobridge.entity.KbEntities;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Client glue for the bike's look (docs/specs/BIKE_PLAN.md B4): the three model layers and the renderer. */
public final class RideRuntime {
	private static boolean registered;

	private RideRuntime() {
	}

	/** Call once from the client entrypoint. */
	public static void init() {
		if (registered) {
			return;
		}
		registered = true;
		EntityModelLayerRegistry.registerModelLayer(BikeModel.MACH, () -> BikeModel.layer(BikeEntity.Kind.MACH));
		EntityModelLayerRegistry.registerModelLayer(BikeModel.ACRO, () -> BikeModel.layer(BikeEntity.Kind.ACRO));
		EntityModelLayerRegistry.registerModelLayer(BikeModel.PLAIN, () -> BikeModel.layer(BikeEntity.Kind.BIKE));
		EntityRendererRegistry.register(KbEntities.BIKE, BikeRenderer::new);
	}

	/** Ticks since the local rider's bike last bumped into something, or -1 for another player's bike (no jolt). */
	static float joltFor(BikeEntity bike) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || player.getVehicle() != bike) {
			return -1.0F;
		}
		long at = MoveRuntime.bumpAt();
		if (at == Long.MIN_VALUE) {
			return -1.0F;
		}
		return (System.currentTimeMillis() - at) / 50.0F;
	}
}
