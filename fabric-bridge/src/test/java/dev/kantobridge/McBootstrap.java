package dev.kantobridge;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Minecraft's registries for unit tests. fabric-loader-junit starts Knot but never the game, so bootstrap by hand. */
public final class McBootstrap {
	private McBootstrap() {
	}

	public static synchronized void ensure() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}
}
