package dev.kantobridge.dev;

import java.nio.file.Path;

/** Read-only lifecycle gate for the explicitly marked offline gallery. */
public final class GalleryShutdown {
	public static final int GRACE_TICKS = 100;
	public static final int QUIET_TICKS = 20;
	public static final int TIMEOUT_TICKS = 1200;
	public enum Result { WAITING, STOP, CANCELLED, TIMED_OUT }
	public record Work(int generationReferences, int pendingGeneration, int pendingSaveDependencies) {
		public Work {
			if (generationReferences < 0 || pendingGeneration < 0 || pendingSaveDependencies < 0)
				throw new IllegalArgumentException("negative gallery drain work");
		}
		public boolean quiet() { return generationReferences == 0 && pendingGeneration == 0 && pendingSaveDependencies == 0; }
	}
	private final Object server;
	private final Path world;
	private final int started;
	private int lastTick;
	private int quietTicks;
	private Result terminal;

	public GalleryShutdown(Object server, Path actualWorld, Path expectedWorld, boolean marked, int tick) {
		if (server == null || !marked || !actualWorld.toAbsolutePath().normalize().equals(expectedWorld.toAbsolutePath().normalize()))
			throw new IllegalArgumentException("shutdown requires the exact marked gallery server/world");
		this.server = server;
		this.world = actualWorld.toAbsolutePath().normalize();
		this.started = tick;
		this.lastTick = tick;
	}

	public boolean owns(Object activeServer, Path actualWorld, boolean marked) {
		return this.server == activeServer && marked && this.world.equals(actualWorld.toAbsolutePath().normalize());
	}

	public Result poll(Object activeServer, Path actualWorld, boolean marked, int tick, Work work) {
		if (this.terminal != null) return this.terminal == Result.STOP ? Result.WAITING : this.terminal;
		if (!owns(activeServer, actualWorld, marked)) return this.terminal = Result.CANCELLED;
		if (tick <= this.lastTick) return Result.WAITING;
		this.lastTick = tick;
		this.quietTicks = work.quiet() ? this.quietTicks + 1 : 0;
		if (elapsed(tick) >= GRACE_TICKS && this.quietTicks >= QUIET_TICKS) return this.terminal = Result.STOP;
		if (elapsed(tick) >= TIMEOUT_TICKS) return this.terminal = Result.TIMED_OUT;
		return Result.WAITING;
	}

	public int elapsed(int tick) { return Math.max(0, tick - this.started); }
	public int quietTicks() { return this.quietTicks; }
}
