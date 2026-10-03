package dev.kantobridge.gametest;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.config.CobblemonConfig;
import com.cobblemon.mod.common.pokemon.Species;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.pokemon.Gen1Mons;
import dev.kantobridge.pokemon.GenOneIds;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.server.CobblemonSettings;
import dev.kantobridge.story.Activity;
import java.util.Objects;
import kotlin.ranges.IntRange;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameRules;

/**
 * Cobblemon set up for Kanto on a real server (docs/PLAN.md Appendix B, Appendix C, P12): Gen 1's numbers resolve to
 * Cobblemon's species and moves, the settings and game rules are applied, the starter prompt is locked, and the
 * players' {@code activity} is kept.
 */
public class CobblemonSetupGameTests implements FabricGameTest {
	@GameTest(template = EMPTY_STRUCTURE)
	public void all151SpeciesAnd165MovesResolve(GameTestHelper helper) {
		for (int dex = 1; dex <= GenOneIds.MAX_DEX; dex++) {
			// By number first (Appendix B), and the number lookup alone must find it.
			helper.assertTrue(PokemonSpecies.getByPokedexNumber(dex, GenOneIds.NAMESPACE) != null, "no species for dex " + dex);
			Species species = Gen1Mons.species(dex);
			helper.assertTrue(species != null && species.getNationalPokedexNumber() == dex, "species " + dex);
			helper.assertTrue(species.getResourceIdentifier().getPath().equals(GenOneIds.speciesId(dex)),
					"species " + dex + " is " + species.getResourceIdentifier().getPath() + ", the table says " + GenOneIds.speciesId(dex));
		}
		for (int number = 1; number <= GenOneIds.MAX_MOVE; number++) {
			helper.assertTrue(Moves.getByNumericalId(number) != null, "no move for number " + number);
			MoveTemplate move = Gen1Mons.move(number);
			helper.assertTrue(move != null && move.getNum() == number, "move " + number);
			helper.assertTrue(move.getName().equals(GenOneIds.moveId(number)),
					"move " + number + " is " + move.getName() + ", the table says " + GenOneIds.moveId(number));
		}
		helper.assertTrue(Gen1Mons.species(0) == null && Gen1Mons.species(152) == null, "numbers beyond Gen 1 resolve");
		helper.assertTrue(Gen1Mons.move(166) == null, "move numbers beyond Gen 1 resolve");
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void appendixCSettingsAreApplied(GameTestHelper helper) {
		CobblemonConfig config = Cobblemon.INSTANCE.getConfig();
		helper.assertTrue(config.getHealPercent() == 0.0 && config.getFaintAwakenHealthPercent() == 0.0F, "passive healing must be off");
		helper.assertTrue(config.getExperienceMultiplier() == 1.0F, "experience multiplier");
		helper.assertTrue(config.getAwardExperienceOnBattleLoss(), "experience on a loss");
		helper.assertFalse(config.getAllowExperienceFromPvP(), "no experience from PvP");
		helper.assertTrue(config.getPreventCompletePartyDeposit(), "the last Pokémon can't be deposited");
		helper.assertFalse(config.getPlayerDamagePokemon(), "players can't hurt Pokémon");
		helper.assertTrue(config.getShinyRate() >= CobblemonSettings.SHINY_RATE, "shinies must be (practically) impossible");
		helper.assertTrue(config.getMinimumSpawningZoneDistanceFromPlayer() == 8.0F
				&& config.getMaximumSpawningZoneDistanceFromPlayer() == 32.0F, "spawning zone distances");
		helper.assertFalse(config.getSavePokemonToWorld(), "wild Pokémon aren't saved");
		helper.assertTrue(config.getDefaultFleeDistance() == 12.0F, "flee distance");
		helper.assertFalse(config.getPassiveStatuses().isEmpty(), "no passive statuses configured");
		for (IntRange range : config.getPassiveStatuses().values()) {
			helper.assertTrue(range.getFirst() >= CobblemonSettings.STATUS_SECONDS, "statuses must last until healed");
		}
		GameRules rules = helper.getLevel().getServer().getGameRules();
		CobblemonSettings.GAME_RULES.forEach((key, value) -> helper.assertTrue(rules.getBoolean(key) == value, "game rule " + key.getId()));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void theStarterPromptIsLocked(GameTestHelper helper) {
		MockPlayers.with(helper, player -> helper.assertTrue(Cobblemon.INSTANCE.getPlayerDataManager().getGenericData(player).getStarterLocked(),
				"Cobblemon's starter prompt must be locked: the starter comes from the hidden game"));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void eachPlayersActivityIsKept(GameTestHelper helper) {
		RelayService relay = RelayService.get();
		MockPlayers.with(helper, player -> MockPlayers.with(helper, other -> RelayTap.capture(player, sent -> {
			helper.assertTrue(Activity.of(player) == Activity.NONE, "a new player is doing nothing");
			relay.receive(player, "{\"type\":\"activity\",\"seq\":1,\"data\":{\"activity\":\"battle\"}}");
			helper.assertTrue(Activity.of(player) == Activity.BATTLE, "activity battle");
			relay.receive(player, "{\"type\":\"activity\",\"seq\":2,\"data\":{\"activity\":\"dialogue\"}}");
			helper.assertTrue(Activity.of(player) == Activity.DIALOGUE, "activity dialogue");
			helper.assertTrue(Activity.of(other) == Activity.NONE, "a client speaks only for its own player");
			relay.receive(player, "{\"type\":\"activity\",\"seq\":3,\"data\":{\"activity\":\"sleeping\"}}");
			relay.receive(player, "{\"type\":\"activity\",\"seq\":4,\"data\":{\"activity\":\"none\",\"who\":\"other\"}}");
			helper.assertTrue(Activity.of(player) == Activity.DIALOGUE, "a malformed activity changed something");
			helper.assertTrue(sent.size() == 2 && sent.stream().allMatch(message -> message.type().equals(MsgTypes.ERROR)),
					"malformed activities must be answered with errors");
			helper.assertTrue(Objects.equals(sent.get(0).re(), 3L) && Objects.equals(sent.get(1).re(), 4L), "errors answer their messages");
		})));
		helper.succeed();
	}
}
