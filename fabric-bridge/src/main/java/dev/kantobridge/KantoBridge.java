package dev.kantobridge;

import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.builder.BuildTasks;
import dev.kantobridge.builder.HeadlessBuild;
import dev.kantobridge.entity.KbEntities;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.server.BuildHold;
import dev.kantobridge.server.Rules;
import dev.kantobridge.server.StoryStateService;
import dev.kantobridge.server.WarpService;
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
		// The NPC puppet type: registered on both sides so registries agree, spawned only by clients (docs/PLAN.md §4.2).
		KbEntities.register();
		StoryStates.init();
		RelayService.init();
		WorldIndexSync.init();
		BuildTasks.init();
		HeadlessBuild.init();
		// Holds players out of a world while the build the client's Create Kanto world flow asked for runs.
		BuildHold.init();
		// Kanto's own rules (game rules, Peaceful, Adventure, Gen 1 speeds, no damage), after BuildHold so held
		// players keep waiting as spectators.
		Rules.init();
		// The relay's story.state and warp.request (docs/PLAN.md §4.4).
		StoryStateService.init();
		WarpService.init();
		LOGGER.info("kantobridge loaded (protocol {}, {} message types)", Catalog.bundled().protocol(), Catalog.bundled().messages().size());
	}
}
