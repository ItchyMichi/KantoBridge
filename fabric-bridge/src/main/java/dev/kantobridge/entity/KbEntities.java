package dev.kantobridge.entity;

import dev.kantobridge.KantoBridge;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Entity registration: the client-only NPC puppet (docs/PLAN.md §4.2) and the ridden bike (docs/specs/BIKE_PLAN.md D2). */
public final class KbEntities {
	/**
	 * A puppet fills its 2×2-block cell almost completely, so the local player can't step into the NPC's cell, and stands
	 * nearly as tall as the 16-px sprite (2 blocks).
	 */
	public static final float PUPPET_WIDTH = 1.75F;
	public static final float PUPPET_HEIGHT = 1.9F;

	private static boolean registered;

	/** Set by {@link #register()}. */
	public static EntityType<PuppetEntity> PUPPET;
	/** Set by {@link #register()}: the bike a player rides (BIKE_PLAN D2). */
	public static EntityType<BikeEntity> BIKE;

	private KbEntities() {
	}

	/** Registers every entity type once; safe to call again (unit tests call it after bootstrap). */
	public static synchronized void register() {
		if (registered) {
			return;
		}
		registered = true;
		// noSave: never written to disk (and no data-fixer lookup); noSummon: /summon can't make a server copy everyone
		// would see. The server never tracks one, so its tracking range is 0.
		PUPPET = Registry.register(BuiltInRegistries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "puppet"),
				EntityType.Builder.<PuppetEntity>of(PuppetEntity::new, MobCategory.MISC)
						.sized(PUPPET_WIDTH, PUPPET_HEIGHT)
						.noSave()
						.noSummon()
						.clientTrackingRange(0)
						.build("puppet"));
		// BIKE_PLAN D2: a server entity every client sees; never saved, never summoned; the rider's hips on the saddle
		// (a sitting player's feet 0.15 above the bike's); its position every tick to the other players
		BIKE = Registry.register(BuiltInRegistries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "bike"),
				EntityType.Builder.<BikeEntity>of(BikeEntity::new, MobCategory.MISC)
						.sized(BikeEntity.WIDTH, BikeEntity.HEIGHT)
						.passengerAttachments(BikeEntity.SEAT + 0.15F)
						.noSave()
						.noSummon()
						.clientTrackingRange(10)
						.updateInterval(1)
						.build("bike"));
	}
}
