package dev.kantobridge.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/** Ticks running builds at the end of every server tick, and stops them (releasing their tickets) when the server stops. */
public final class BuildTasks {
	private static final List<BuildTask> TASKS = new CopyOnWriteArrayList<>();

	private BuildTasks() {
	}

	/** Call once from common init. */
	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(BuildTasks::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (BuildTask task : running(server)) {
				task.cancel();
			}
			TASKS.removeIf(task -> task.server() == server);
		});
	}

	static void add(BuildTask task) {
		TASKS.add(task);
	}

	/** The builds still running on {@code server}. */
	public static List<BuildTask> running(MinecraftServer server) {
		List<BuildTask> out = new ArrayList<>();
		for (BuildTask task : TASKS) {
			if (task.server() == server && !task.isDone()) {
				out.add(task);
			}
		}
		return out;
	}

	private static void tick(MinecraftServer server) {
		for (BuildTask task : TASKS) {
			if (task.server() == server) {
				task.tick();
			}
		}
		TASKS.removeIf(BuildTask::isDone);
	}
}
