package dev.kantobridge.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * A hidden-game NPC drawn in Minecraft (docs/PLAN.md §4.2 "Entities", §4.3 {@code puppet/}): a plain entity that only
 * ever exists on the client that spawned it, under a negative id, so the server never hears of it. It has no AI, no
 * physics and nothing to save; the client's puppet manager moves it through a {@link Driver}. It is solid for the local
 * player ({@link #canBeCollidedWith}) and targetable with the crosshair ({@link #isPickable}); clicks on it are caught
 * on the client before any packet is sent.
 *
 * <p>The type is registered in common init so both sides' registries agree (Fabric registry sync), but nothing ever
 * spawns one on a server.
 */
public final class PuppetEntity extends Entity {
	/** Moves the puppet once per entity tick (client only). */
	@FunctionalInterface
	public interface Driver {
		void tick(PuppetEntity puppet);
	}

	private @Nullable Driver driver;
	/** The hidden game's NPC id ({@code <MAP>_obj_<index>}), for the client's lookups. */
	private @Nullable String npcId;

	public PuppetEntity(EntityType<? extends PuppetEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	public void setDriver(@Nullable Driver driver) {
		this.driver = driver;
	}

	public @Nullable String npcId() {
		return this.npcId;
	}

	public void setNpcId(@Nullable String npcId) {
		this.npcId = npcId;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	@Override
	protected void readAdditionalSaveData(CompoundTag compound) {
	}

	@Override
	protected void addAdditionalSaveData(CompoundTag compound) {
	}

	/** No base tick (fluids, fire, portals): the driver alone places the puppet. */
	@Override
	public void tick() {
		Driver current = this.driver;
		if (current != null) {
			current.tick(this);
		}
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved();
	}

	/** Solid for whoever moves into it; only the local player ever can, since the puppet exists on one client only. */
	@Override
	public boolean canBeCollidedWith() {
		return true;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean isAttackable() {
		return false;
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}
}
