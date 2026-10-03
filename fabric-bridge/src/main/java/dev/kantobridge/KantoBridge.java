package dev.kantobridge;

import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.builder.BuildTasks;
import dev.kantobridge.builder.HeadlessBuild;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.server.BuildHold;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.story.StoryStates;
import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KantoBridge implements ModInitializer {
	public static final String MOD_ID = "kantobridge";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		KbBlocks.register();
		StoryStates.init();
		RelayService.init();
		WorldIndexSync.init();
		BuildTasks.init();
		HeadlessBuild.init();
		// Holds players out of a world while the build the client's Create Kanto world flow asked for runs.
		BuildHold.init();
		LOGGER.info("kantobridge loaded (protocol {}, {} message types)", Catalog.bundled().protocol(), Catalog.bundled().messages().size());
	}
}
