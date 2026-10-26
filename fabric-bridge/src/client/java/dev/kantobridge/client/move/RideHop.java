package dev.kantobridge.client.move;

import dev.kantobridge.entity.BikeEntity;

/**
 * The bike's own motion beside the legs (docs/specs/BIKE_PLAN.md §2.4, D6, D8): a hop's lift and free mode's speed. Plain
 * Java.
 *
 * <ul>
 * <li>A {@code player.pos} with {@code jump} lifts the seat on top of its leg: {@code low} (a bunny hop onto the next
 * cell) {@value #LOW}, {@code normal} (a side or turn jump) {@value #NORMAL}, {@code high} (a wheelie's ledge hop, 32
 * frames over two cells) {@value #HIGH} blocks a tick; Minecraft's gravity (0.08 a tick, with drag) lands it within the
 * leg's 16 or 32 frames.</li>
 * <li>A standing bunny hop ({@code trick: hop}, no step) hops in place every {@value #STANDING_HOP_TICKS} ticks (16
 * frames, the engine's hop) while it lasts.</li>
 * <li>Free mode (out in a zone, nothing followed) moves the bike at its kind's base speed: 8 frames a cell (0.75 blocks a
 * tick) for the plain bike and the Mach, 6 (1.0) for the Acro.</li>
 * </ul>
 */
public final class RideHop {
	public static final double LOW = 0.22;
	public static final double NORMAL = 0.27;
	public static final double HIGH = 0.42;
	public static final int STANDING_HOP_TICKS = 6;

	private RideHop() {
	}

	/** The upward velocity for a hop kind ({@code low}, {@code normal}, {@code high}); 0 for anything else. */
	public static double velocity(String jump) {
		return switch (jump) {
			case "low" -> LOW;
			case "normal" -> NORMAL;
			case "high" -> HIGH;
			default -> 0.0;
		};
	}

	/** Free mode's speed in blocks a tick. */
	public static double freeSpeed(BikeEntity.Kind kind) {
		return kind == BikeEntity.Kind.ACRO ? 1.0 : 0.75;
	}
}
