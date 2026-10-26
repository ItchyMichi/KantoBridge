package dev.kantobridge.entity;

import java.util.List;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The bike a player rides while the hidden player is on one (docs/specs/BIKE_PLAN.md §2.5, D2): a plain entity with one
 * seat, spawned and removed by the server ({@code dev.kantobridge.server.Bikes}) from the player's relayed story state and
 * seen by every client. Its rider's client is its mover ({@link #isControlledByLocalInstance}): the client walks the
 * hidden game's steps on it (the forced mover's velocity), and Minecraft's own vehicle packets carry its position to the
 * server and the other players. The kind, the Acro's pose and the Mach's gear are synced data (bytes; no art).
 *
 * <p>It is never saved, can't be summoned, hurt, pushed or picked, and goes away once it has had no rider for
 * {@value #RIDERLESS_TICKS} ticks. Its rider governs the story-state colliders ({@code StoryStates.governingPlayer}).
 */
public final class BikeEntity extends Entity {
	/** The three looks (D7): Emerald's Mach and Acro, Blue's and Crystal's one bike. */
	public enum Kind {
		BIKE, MACH, ACRO;

		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public static Kind byId(@Nullable String id) {
			if (id != null) {
				for (Kind kind : values()) {
					if (kind.id().equals(id)) {
						return kind;
					}
				}
			}
			return BIKE;
		}

		static Kind byOrdinal(int ordinal) {
			Kind[] all = values();
			return ordinal >= 0 && ordinal < all.length ? all[ordinal] : BIKE;
		}
	}

	/** The Acro bike's pose (D6). */
	public enum Trick {
		NONE, WHEELIE, HOP;

		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public static Trick byId(@Nullable String id) {
			if (id != null) {
				for (Trick trick : values()) {
					if (trick.id().equals(id)) {
						return trick;
					}
				}
			}
			return NONE;
		}

		static Trick byOrdinal(int ordinal) {
			Trick[] all = values();
			return ordinal >= 0 && ordinal < all.length ? all[ordinal] : NONE;
		}
	}

	/** Ticks without a rider before the bike goes away. */
	public static final int RIDERLESS_TICKS = 20;
	/** The rider's body may turn this far from the bike's heading (a horse's clamp). */
	public static final float BODY_CLAMP = 105.0F;
	public static final float WIDTH = 0.8F;
	public static final float HEIGHT = 0.9F;
	/** The rider sits this far above the bike's feet. */
	public static final float SEAT = 0.6F;
	/** Steps the bike climbs by itself (the world's half-block steps), as a walking player does. */
	public static final float STEP = 0.6F;

	private static final EntityDataAccessor<Byte> KIND = SynchedEntityData.defineId(BikeEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Byte> TRICK = SynchedEntityData.defineId(BikeEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Byte> GEAR = SynchedEntityData.defineId(BikeEntity.class, EntityDataSerializers.BYTE);

	private int riderless;
	private int lerpSteps;
	private double lerpX;
	private double lerpY;
	private double lerpZ;
	private double lerpYRot;
	/** Client side: blocks travelled, for the wheels' spin (the renderer reads it). */
	private float travelled;

	public BikeEntity(EntityType<? extends BikeEntity> type, Level level) {
		super(type, level);
		this.blocksBuilding = false;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(KIND, (byte) Kind.BIKE.ordinal());
		builder.define(TRICK, (byte) Trick.NONE.ordinal());
		builder.define(GEAR, (byte) 0);
	}

	// ---- synced data ----

	public Kind kind() {
		return Kind.byOrdinal(this.entityData.get(KIND));
	}

	public void setKind(Kind kind) {
		this.entityData.set(KIND, (byte) kind.ordinal());
	}

	public Trick trick() {
		return Trick.byOrdinal(this.entityData.get(TRICK));
	}

	public void setTrick(Trick trick) {
		this.entityData.set(TRICK, (byte) trick.ordinal());
	}

	public int gear() {
		return Mth.clamp(this.entityData.get(GEAR), 0, 2);
	}

	public void setGear(int gear) {
		this.entityData.set(GEAR, (byte) Mth.clamp(gear, 0, 2));
	}

	/** Blocks the wheels have rolled (client side). */
	public float travelled() {
		return this.travelled;
	}

	// ---- riding ----

	@Override
	public @Nullable LivingEntity getControllingPassenger() {
		Entity first = this.getFirstPassenger();
		return first instanceof Player player ? player : null;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return this.getPassengers().isEmpty() && passenger instanceof Player;
	}

	@Override
	public void onPassengerTurned(Entity passenger) {
		this.clampRotation(passenger);
	}

	@Override
	protected void positionRider(Entity passenger, Entity.MoveFunction move) {
		super.positionRider(passenger, move);
		this.clampRotation(passenger);
	}

	/** The rider's body stays within {@link #BODY_CLAMP} of the heading (the camera is free). */
	private void clampRotation(Entity passenger) {
		passenger.setYBodyRot(this.getYRot());
		float delta = Mth.wrapDegrees(passenger.getYRot() - this.getYRot());
		float clamped = Mth.clamp(delta, -BODY_CLAMP, BODY_CLAMP);
		passenger.yRotO += clamped - delta;
		passenger.setYRot(passenger.getYRot() + clamped - delta);
		passenger.setYHeadRot(passenger.getYRot());
	}

	// ---- ticking ----

	@Override
	public void tick() {
		super.tick();
		if (!this.level().isClientSide) {
			this.riderless = this.getControllingPassenger() == null ? this.riderless + 1 : 0;
			if (this.riderless > RIDERLESS_TICKS) {
				this.discard();
				return;
			}
		}
		if (this.isControlledByLocalInstance()) {
			// the rider's client (or a server with no rider): the velocity the forced mover or free mode set this tick
			this.lerpSteps = 0;
			this.syncPacketPositionCodec(this.getX(), this.getY(), this.getZ());
			this.applyGravity();
			Vec3 before = this.position();
			this.move(MoverType.SELF, this.getDeltaMovement());
			Vec3 moved = this.position().subtract(before);
			double flat = Math.hypot(moved.x, moved.z);
			this.travelled += (float) flat;
			if (flat > 0.02) {
				// the heading follows the way it rolls
				this.setYRot((float) (Mth.atan2(moved.z, moved.x) * Mth.RAD_TO_DEG) - 90.0F);
			}
			// the mover sets the horizontal speed each tick; nothing rolls on by itself
			Vec3 v = this.getDeltaMovement();
			this.setDeltaMovement(0.0, this.onGround() ? 0.0 : v.y * 0.98, 0.0);
		} else {
			double x0 = this.getX();
			double z0 = this.getZ();
			this.tickLerp();
			this.travelled += (float) Math.hypot(this.getX() - x0, this.getZ() - z0);
			this.setDeltaMovement(Vec3.ZERO);
		}
	}

	private void tickLerp() {
		if (this.lerpSteps <= 0) {
			return;
		}
		double x = this.getX() + (this.lerpX - this.getX()) / this.lerpSteps;
		double y = this.getY() + (this.lerpY - this.getY()) / this.lerpSteps;
		double z = this.getZ() + (this.lerpZ - this.getZ()) / this.lerpSteps;
		float yRot = (float) (this.getYRot() + Mth.wrapDegrees(this.lerpYRot - this.getYRot()) / this.lerpSteps);
		this.lerpSteps--;
		this.setPos(x, y, z);
		this.setYRot(yRot);
	}

	@Override
	public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
		this.lerpX = x;
		this.lerpY = y;
		this.lerpZ = z;
		this.lerpYRot = yRot;
		this.lerpSteps = Math.max(1, steps);
	}

	@Override
	public double lerpTargetX() {
		return this.lerpSteps > 0 ? this.lerpX : this.getX();
	}

	@Override
	public double lerpTargetY() {
		return this.lerpSteps > 0 ? this.lerpY : this.getY();
	}

	@Override
	public double lerpTargetZ() {
		return this.lerpSteps > 0 ? this.lerpZ : this.getZ();
	}

	@Override
	protected double getDefaultGravity() {
		return 0.08;
	}

	@Override
	public float maxUpStep() {
		return STEP;
	}

	/**
	 * BIKE_CLIMB_PLAN §3: stairs along the move standing higher than the step-up allows lift the move first (the Mach's 1.5
	 * blocks a tick up a slope's 1.5-per-block stairs); on both sides, so the server's own move lands where the client's did.
	 */
	@Override
	public void move(MoverType type, Vec3 movement) {
		super.move(type, this.climb(movement));
	}

	private Vec3 climb(Vec3 movement) {
		if (movement.x == 0.0 && movement.z == 0.0) {
			return movement;
		}
		AABB box = this.getBoundingBox();
		AABB path = box.expandTowards(movement.x, 0.0, movement.z);
		List<VoxelShape> stairs = BikeClimb.stairs(this.level(), path, BikeClimb.MAX_LIFT);
		if (stairs.isEmpty()) {
			return movement;
		}
		double dy = BikeClimb.lift(movement.y, box.minY, BikeClimb.top(path, stairs, BikeClimb.MAX_LIFT), STEP, BikeClimb.MAX_LIFT);
		return dy == movement.y ? movement : new Vec3(movement.x, dy, movement.z);
	}

	// ---- never hurt, pushed, picked or saved ----

	@Override
	public boolean hurt(DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean canBeCollidedWith() {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(CompoundTag tag) {
	}

	@Override
	protected void addAdditionalSaveData(CompoundTag tag) {
	}
}
