package dev.kantobridge.client.net;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/**
 * WORLD_SESSIONS_PLAN WS5: the pause menu's "Save and Quit to Title" as {@code PauseScreen.onDisconnect} runs it
 * (1.21.1): the level disconnects, an integrated server saves behind "Saving world", and the title (or the server list)
 * comes back. The quit choreography calls it once the hidden games have answered.
 */
public final class Leave {
	private Leave() {
	}

	public static void vanilla(Minecraft minecraft) {
		boolean local = minecraft.isLocalServer();
		ServerData server = minecraft.getCurrentServer();
		if (minecraft.level != null) {
			minecraft.level.disconnect();
		}
		if (local) {
			minecraft.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
		} else {
			minecraft.disconnect();
		}
		TitleScreen title = new TitleScreen();
		if (local) {
			minecraft.setScreen(title);
		} else if (server != null && server.isRealm()) {
			minecraft.setScreen(title);
		} else {
			minecraft.setScreen(new JoinMultiplayerScreen(title));
		}
	}
}
