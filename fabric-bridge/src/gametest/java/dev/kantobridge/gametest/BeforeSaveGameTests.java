package dev.kantobridge.gametest;

import dev.kantobridge.KantoBridge;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;

/**
 * WORLD_SESSIONS_PLAN WS0 (2): which of Minecraft's saves reach Fabric's {@code ServerLifecycleEvents.BEFORE_SAVE}
 * (fabric-lifecycle-events-v1 2.6.0 injects it into {@code MinecraftServer.saveAllChunks}). A counting handler, logged
 * line by line ({@code before_save flush=<b> force=<b> count=<n>}), so the server's own stop at the end of the run shows
 * in the log too. The vanilla autosave runs {@code saveEverything(true, false, false)}; {@code /save-all} runs it with
 * force. A batch of its own: a save writes the whole test world.
 */
public class BeforeSaveGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_before_save";
	static final AtomicInteger COUNT = new AtomicInteger();
	static final AtomicInteger FORCED = new AtomicInteger();
	private static final AtomicBoolean REGISTERED = new AtomicBoolean();

	static void register() {
		if (REGISTERED.compareAndSet(false, true)) {
			ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> {
				int n = COUNT.incrementAndGet();
				if (force) {
					FORCED.incrementAndGet();
				}
				KantoBridge.LOGGER.info("gametest: before_save flush={} force={} stopping={} count={}", flush, force, !server.isRunning(), n);
			});
		}
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 400)
	public void theAutosaveAndSaveAllReachBeforeSave(GameTestHelper helper) {
		register();
		MinecraftServer server = helper.getLevel().getServer();
		int before = COUNT.get();
		int forcedBefore = FORCED.get();
		// the vanilla autosave's own call (MinecraftServer.tickServer -> autoSave)
		server.saveEverything(true, false, false);
		int afterAutosave = COUNT.get();
		// /save-all, through the command itself
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "save-all");
		int afterSaveAll = COUNT.get();
		KantoBridge.LOGGER.info("gametest: before_save autosave={} save_all={} forced={}", afterAutosave - before, afterSaveAll - afterAutosave,
				FORCED.get() - forcedBefore);
		helper.assertTrue(afterAutosave - before == 1, "the autosave reached BEFORE_SAVE " + (afterAutosave - before) + " times");
		helper.assertTrue(afterSaveAll - afterAutosave == 1, "/save-all reached BEFORE_SAVE " + (afterSaveAll - afterAutosave) + " times");
		helper.assertTrue(FORCED.get() - forcedBefore == 1, "only /save-all is forced");
		helper.succeed();
	}
}
