package dev.kantobridge;

import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Catalog;
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
		RelayService.init();
		LOGGER.info("kantobridge loaded (protocol {}, {} message types)", Catalog.bundled().protocol(), Catalog.bundled().messages().size());
	}
}
