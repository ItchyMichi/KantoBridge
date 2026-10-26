package dev.kantobridge.client.ride;

import dev.kantobridge.entity.BikeEntity;

/**
 * The bike's pose beside its model (docs/specs/BIKE_PLAN.md §2.6, D6), from the synced trick and the local jolt. Plain
 * Java: a wheelie pitches it {@value #WHEELIE_DEGREES}° nose-up; a standing bunny hop lifts it in small arcs, one every
 * {@value #HOP_TICKS} ticks (the engine's 16-frame hop), up to {@value #HOP_LIFT} blocks; a bump pushes it
 * {@value #JOLT} blocks back for {@value #JOLT_TICKS} ticks.
 */
public final class RideLook {
	public static final float WHEELIE_DEGREES = 35.0F;
	public static final double HOP_TICKS = 16.0 / 3.0;
	public static final double HOP_LIFT = 0.3;
	public static final double JOLT = 0.1;
	public static final int JOLT_TICKS = 4;
	/** The rear wheel's contact, in blocks behind the middle (7 units). */
	public static final double REAR_CONTACT = 7.0 / 16.0;

	/** The pose: blocks up, blocks back, degrees nose-up. */
	public record Pose(double lift, double back, float pitch) {
	}

	private RideLook() {
	}

	/**
	 * @param trick the synced pose
	 * @param age   the entity's age in ticks (with the partial tick)
	 * @param jolt  ticks since the local rider's last bump (negative or beyond {@link #JOLT_TICKS}: none)
	 */
	public static Pose of(BikeEntity.Trick trick, float age, float jolt) {
		double lift = 0.0;
		float pitch = 0.0F;
		if (trick == BikeEntity.Trick.WHEELIE) {
			pitch = WHEELIE_DEGREES;
		} else if (trick == BikeEntity.Trick.HOP) {
			pitch = WHEELIE_DEGREES;
			double phase = (age % HOP_TICKS) / HOP_TICKS;
			lift = HOP_LIFT * Math.sin(Math.PI * phase);
		}
		double back = jolt >= 0.0F && jolt < JOLT_TICKS ? JOLT * (1.0 - jolt / JOLT_TICKS) : 0.0;
		return new Pose(lift, back, pitch);
	}
}
