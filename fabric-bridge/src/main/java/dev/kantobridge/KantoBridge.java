package dev.kantobridge;

import dev.kantobridge.bag.BagService;
import dev.kantobridge.battle.BattleMusic;
import dev.kantobridge.battle.BattleService;
import dev.kantobridge.battle.EncounterService;
import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.builder.BuildTasks;
import dev.kantobridge.builder.HeadlessBuild;
import dev.kantobridge.entity.DeferredRemovals;
import dev.kantobridge.entity.KbEntities;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.pokemon.DaycareService;
import dev.kantobridge.pokemon.DexService;
import dev.kantobridge.pokemon.PartyService;
import dev.kantobridge.pokemon.PcService;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.server.ActivityService;
import dev.kantobridge.server.BuildHold;
import dev.kantobridge.server.FlashService;
import dev.kantobridge.server.HabitatService;
import dev.kantobridge.server.Rules;
import dev.kantobridge.server.SaveService;
import dev.kantobridge.server.SessionService;
import dev.kantobridge.server.StoryStateService;
import dev.kantobridge.server.TextureStatusService;
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
		// Entity removals that can't run inside the server's entity tracking (a Pokémon whose owner leaves).
		DeferredRemovals.init();
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
		// Night Vision on dark maps the player's hidden game lit with Flash (M8).
		FlashService.init();
		// Battles for the hidden game (hidden proxy NPCs + Cobblemon's pvn), the party both ways, the players' activity.
		BattleService.init();
		// Static, surfing and fishing encounters (wild battle.start kinds), and Run for every wild battle in Kanto.
		EncounterService.init();
		// Cobblemon's battle themes are silenced for Kanto battles: the hidden game plays Gen 1's (P22).
		BattleMusic.init();
		PartyService.init();
		// Gen 1's daycare: a boarded Pokémon waits on the server and comes back with its steps' experience (P15).
		DaycareService.init();
		// The Pokédex and the bag's battle items between Cobblemon and the hidden game.
		DexService.init();
		BagService.init();
		// Habitat blocks: verified (and their pools re-attached) as their chunks load.
		HabitatService.init();
		ActivityService.init();
		// Bill's PC as Cobblemon's PC, both sides saving together, and the world's id for save pairing (M7).
		PcService.init();
		SaveService.init();
		SessionService.init();
		// Each player's own generated pack against this world's slot map (texture.status, M9): hashes and counts only.
		TextureStatusService.init();
		LOGGER.info("kantobridge loaded (protocol {}, {} message types)", Catalog.bundled().protocol(), Catalog.bundled().messages().size());
	}
}
