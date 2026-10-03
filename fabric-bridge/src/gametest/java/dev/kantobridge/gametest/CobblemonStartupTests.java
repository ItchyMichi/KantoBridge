package dev.kantobridge.gametest;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Species;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Spike S1: Cobblemon starts inside the headless GameTest server, and mock players get Cobblemon data. */
public class CobblemonStartupTests implements FabricGameTest {
	@GameTest(template = EMPTY_STRUCTURE)
	public void speciesDataLoaded(GameTestHelper helper) {
		// Appendix B maps Gen 1 species by national dex number; the whole first generation must resolve.
		for (int dex = 1; dex <= 151; dex++) {
			Species species = PokemonSpecies.getByPokedexNumber(dex, "cobblemon");
			helper.assertTrue(species != null, "Cobblemon species for dex " + dex + " is missing");
			helper.assertTrue(species.getNationalPokedexNumber() == dex, "species lookup returned the wrong dex number");
		}
		helper.assertTrue(PokemonSpecies.count() > 151, "Cobblemon species registry looks empty");
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void mockPlayerHasParty(GameTestHelper helper) {
		MockPlayers.with(helper, player -> {
			PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
			helper.assertTrue(party != null, "no Cobblemon party store for a mock player");
			helper.assertTrue(party.occupied() == 0, "a fresh player's party should be empty");
		});
		helper.succeed();
	}
}
