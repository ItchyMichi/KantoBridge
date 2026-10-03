package dev.kantobridge.gametest;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress;
import com.cobblemon.mod.common.api.pokedex.PokedexManager;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.pokemon.DexService;
import dev.kantobridge.pokemon.DexSync;
import dev.kantobridge.pokemon.Gen1Mon;
import dev.kantobridge.pokemon.Gen1Mons;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Pokédex between Cobblemon and the hidden game (docs/PLAN.md §4.4 {@code DexService}): what Cobblemon's Pokédex
 * learns reaches the client as {@code dex.sync} (the first 151 only, owned counted as seen), and the hidden game's
 * entries are written into Cobblemon's without posting events, so the two never loop. Made-up encounters only.
 */
public class DexGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_dex";

	static Set<Integer> numbers(JsonArray array) {
		Set<Integer> out = new HashSet<>();
		array.forEach(element -> out.add(element.getAsInt()));
		return out;
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
	public void whatCobblemonLearnsGoesToTheHiddenGame(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		try {
			helper.assertTrue(DexService.watching(player), "the Pokédex isn't followed after Cobblemon's data sync");
			PokedexManager pokedex = PlayerExtensionsKt.pokedex(player);
			pokedex.encounter(Gen1Mons.wild(16, 3));
			pokedex.obtain(Gen1Mons.gift(new Gen1Mon(25, 5, List.of(84), null)));
			// A species beyond Gen 1 stays out of the hidden game's Pokédex.
			Pokemon chikorita = PokemonSpecies.getByPokedexNumber(152, "cobblemon").create(5);
			pokedex.obtain(chikorita);
		} catch (Gen1Mons.Missing | RuntimeException e) {
			relay.close();
			MockPlayers.leave(helper, player);
			throw e instanceof RuntimeException runtime ? runtime : new GameTestAssertException("Cobblemon lacks a species");
		}
		helper.succeedWhen(() -> {
			List<Envelope> syncs = relay.of(MsgTypes.DEX_SYNC);
			helper.assertTrue(!syncs.isEmpty(), "no dex.sync yet");
			JsonObject data = syncs.get(syncs.size() - 1).data();
			Set<Integer> seen = numbers(data.getAsJsonArray("seen"));
			Set<Integer> owned = numbers(data.getAsJsonArray("owned"));
			helper.assertTrue(seen.contains(16) && seen.contains(25), "seen " + seen);
			helper.assertTrue(owned.contains(25) && !owned.contains(16), "owned " + owned);
			helper.assertTrue(!seen.contains(152) && !owned.contains(152), "a species beyond 151 was synced");
			helper.assertTrue(seen.containsAll(owned), "owned must count as seen");
			relay.close();
			MockPlayers.leave(helper, player);
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void theHiddenGamesEntriesAreWrittenWithoutEvents(GameTestHelper helper) {
		MockPlayers.with(helper, player -> {
			int[] events = {0};
			var subscription = CobblemonEvents.POKEDEX_DATA_CHANGED_POST.subscribe(Priority.NORMAL, event -> {
				if (event.getPlayerUUID().equals(player.getUUID())) {
					events[0]++;
				}
			});
			try {
				PokedexManager pokedex = PlayerExtensionsKt.pokedex(player);
				ResourceLocation bulbasaur = ResourceLocation.fromNamespaceAndPath("cobblemon", "bulbasaur");
				ResourceLocation charmander = ResourceLocation.fromNamespaceAndPath("cobblemon", "charmander");
				ResourceLocation squirtle = ResourceLocation.fromNamespaceAndPath("cobblemon", "squirtle");
				int changed = DexService.applyHidden(player, DexSync.of(List.of(1, 4, 7), List.of(4)));
				helper.assertTrue(changed == 3, "expected 3 species written, got " + changed);
				helper.assertTrue(pokedex.getKnowledgeForSpecies(bulbasaur) == PokedexEntryProgress.SEEN, "Bulbasaur should be seen");
				helper.assertTrue(pokedex.getKnowledgeForSpecies(charmander) == PokedexEntryProgress.OWNED, "Charmander should be owned");
				helper.assertTrue(pokedex.getKnowledgeForSpecies(squirtle) == PokedexEntryProgress.SEEN, "Squirtle should be seen");
				helper.assertTrue(events[0] == 0, "writing the hidden game's entries posted " + events[0] + " Pokédex events (an echo loop)");
				// The same entries again change nothing, and a seen-only entry never downgrades an owned one.
				helper.assertTrue(DexService.applyHidden(player, DexSync.of(List.of(1, 4, 7), List.of())) == 0, "applied twice");
				helper.assertTrue(pokedex.getKnowledgeForSpecies(charmander) == PokedexEntryProgress.OWNED, "owned was downgraded");
				DexSync merged = DexService.snapshot(player);
				helper.assertTrue(merged.seen().containsAll(List.of(1, 4, 7)) && merged.owned().contains(4), "the merged Pokédex is wrong");
			} finally {
				subscription.unsubscribe();
			}
		});
		helper.succeed();
	}
}
