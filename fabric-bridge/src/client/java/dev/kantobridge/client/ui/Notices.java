package dev.kantobridge.client.ui;

import dev.kantobridge.client.battle.ClientBattles;
import dev.kantobridge.client.bridge.BridgeRuntime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Warning screens waiting for their turn (M10's mismatch screens): one shows when no other screen is up (never over a
 * dialogue, a battle or the pause overlay, which come first), in a world. Each is Minecraft's own alert, a title, a text
 * and OK; it pauses singleplayer while it shows (a multiplayer world goes on). Leaving the world drops what waited.
 * Client thread only.
 */
public final class Notices {
	private static final Deque<Supplier<Screen>> WAITING = new ArrayDeque<>();
	private static final int MAX = 8;

	private Notices() {
	}

	/** Call once from the client entrypoint. */
	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(Notices::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> WAITING.clear());
	}

	/** A warning with a title and a text, shown when its turn comes. */
	public static void warn(Component title, Component text) {
		show(() -> new AlertScreen(() -> Minecraft.getInstance().setScreen(null), title, text, CommonComponents.GUI_OK, true));
	}

	/** A screen shown when its turn comes. */
	public static void show(Supplier<Screen> screen) {
		if (WAITING.size() >= MAX) {
			WAITING.removeFirst();
		}
		WAITING.addLast(screen);
	}

	/** Warnings waiting (F7, tests). */
	public static int waiting() {
		return WAITING.size();
	}

	private static void tick(Minecraft minecraft) {
		if (WAITING.isEmpty() || minecraft.screen != null || minecraft.level == null || minecraft.player == null) {
			return;
		}
		BridgeRuntime bridge = BridgeRuntime.get();
		UiRuntime ui = UiRuntime.get();
		if ((bridge != null && bridge.pauseShown()) || (ui != null && ui.open()) || ClientBattles.active()) {
			// The hidden game's dialogue, a battle and the pause overlay come first.
			return;
		}
		minecraft.setScreen(WAITING.removeFirst().get());
	}
}
