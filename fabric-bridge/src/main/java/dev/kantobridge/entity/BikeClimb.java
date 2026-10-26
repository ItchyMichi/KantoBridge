package dev.kantobridge.entity;

import dev.kantobridge.block.StepColliderBlock;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The bike's climb (docs/specs/BIKE_CLIMB_PLAN.md, BIKE_FOLLOWUPS A1): a ride at the Mach's speed covers 1.5 blocks a
 * tick, and the world's stairs rise 1.5 blocks per block, more than any step-up lifts in a tick (Minecraft's step-up takes
 * the first candidate step that gains ground, so a bigger step never helps). So when the stairs along a move stand higher
 * than the step-up allows, the move is lifted first ({@link #lift}): Minecraft resolves the vertical first, the forward
 * pass clears the steps, and gravity lands the bike on its stair. Only the world's step colliders count
 * ({@link #stairs}): a bike wall, a ledge or a plain wall is never ridden up this way.
 *
 * <p>The rider's client and the server's re-simulation of the vehicle packet run the same code from the same start and
 * end, so the two agree and the server's "moved wrongly" can't fire.
 */
public final class BikeClimb {
	/** Blocks a move may be lifted at most: a catch-up tick's 3 blocks of stairs (4.5) and a half step; a cliff (6) never. */
	public static final double MAX_LIFT = 5.0;
	private static final double EDGE = 1.0E-7;

	private BikeClimb() {
	}

	/**
	 * The vertical a move should carry: {@code dy} as given, or the rise from the feet to the stairs' top when that is more
	 * than {@code step} (step-up does the rest) and at most {@code maxLift} (anything higher is not stairs); a hop's own
	 * lift stays when it is the larger. Plain Java.
	 */
	public static double lift(double dy, double feetY, double top, double step, double maxLift) {
		double rise = top - feetY;
		if (rise > step && rise <= maxLift) {
			return Math.max(dy, rise);
		}
		return dy;
	}

	/**
	 * §3.5, the way down: {@code dy} as given, or the fall from the feet to the stairs' top under the move's end when that
	 * is more than {@code step} (a smaller one gravity takes in a tick or two) and at most {@code maxLift}, and the move is
	 * not a hop ({@code dy} at most 0). The caller moves forward first, then down by this. Plain Java.
	 */
	public static double drop(double dy, double feetY, double topAhead, double step, double maxLift) {
		double fall = feetY - topAhead;
		if (dy <= 0.0 && fall > step && fall <= maxLift) {
			return -fall;
		}
		return dy;
	}

	/**
	 * The collision shapes of the step colliders under a move's path: the columns the box {@code path} covers, from
	 * {@code maxLift} blocks under the feet to {@code maxLift} over them. Empty where no stairs are.
	 */
	public static List<VoxelShape> stairs(Level level, AABB path, double maxLift) {
		List<VoxelShape> out = new ArrayList<>();
		int y0 = Mth.floor(path.minY - maxLift);
		int y1 = Mth.floor(path.minY + maxLift);
		for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(path.minX), y0, Mth.floor(path.minZ), Mth.floor(path.maxX - EDGE), y1,
				Mth.floor(path.maxZ - EDGE))) {
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof StepColliderBlock) {
				VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
				if (!shape.isEmpty()) {
					out.add(shape.move(pos.getX(), pos.getY(), pos.getZ()));
				}
			}
		}
		return out;
	}

	/**
	 * The highest stairs top under {@code box} between {@code maxLift} over its feet and {@code maxLift} under them: the box
	 * swept down from that height against {@code stairs} stops on it. NaN when it meets none.
	 */
	public static double top(AABB box, List<VoxelShape> stairs, double maxLift) {
		AABB probe = box.move(0.0, maxLift, 0.0);
		double moved = Shapes.collide(Direction.Axis.Y, probe, stairs, -2.0 * maxLift);
		return moved <= -2.0 * maxLift ? Double.NaN : probe.minY + moved;
	}
}
