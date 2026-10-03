package dev.kantobridge.gametest;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.BattleRegistry;
import dev.kantobridge.bag.BagItems;
import dev.kantobridge.bag.BagService;
import dev.kantobridge.battle.EncounterService;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The hidden game's battle items in the Minecraft inventory (docs/PLAN.md P17, §4.4 {@code BagService}): every mirrored
 * Gen 1 item is a real Cobblemon item, {@code bag.sync} adds and removes them until the inventory matches (and changes
 * nothing when it already does), and what Minecraft consumes goes back once as {@code bag.use}; during a battle only once
 * it is over. Consumption is simulated by shrinking a stack, as Cobblemon does when a ball is thrown or an item used.
 */
public class BagGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_bag";

	static Item item(String path) {
		return BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(BagItems.NAMESPACE, path));
	}

	static int count(ServerPlayer player, String path) {
		return player.getInventory().countItem(item(path));
	}

	static void sync(ServerPlayer player, long seq, String items) {
		RelayService.get().receive(player, BattleGameTests.envelope(seq, MsgTypes.BAG_SYNC, "{\"items\":" + items + "}"));
	}

	/** Takes one of an item from the inventory, as a throw or a use does. */
	static void consume(GameTestHelper helper, ServerPlayer player, String path) {
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item(path))) {
				stack.shrink(1);
				return;
			}
		}
		helper.fail("no " + path + " to consume");
	}

	static List<Envelope> uses(RelayTap.Recorder relay) {
		return relay.of(MsgTypes.BAG_USE);
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void everyMirroredItemIsACobblemonItem(GameTestHelper helper) {
		Map<String, Item> items = BagService.items();
		for (Map.Entry<String, String> entry : BagItems.table().entrySet()) {
			helper.assertTrue(items.containsKey(entry.getKey()), "Cobblemon 1.8.1 has no item cobblemon:" + entry.getValue());
			helper.assertTrue(BuiltInRegistries.ITEM.getKey(items.get(entry.getKey())).getPath().equals(entry.getValue()), entry.getKey());
		}
		helper.assertTrue(BagService.warnings("item_missing") == 0, "a mirrored item is missing");
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
	public void aBagSyncMirrorsTheBagWithoutEcho(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		try {
			// Key items and Gen 1-only items are ignored; battle items appear as Cobblemon items.
			sync(player, 1, "{\"POKE_BALL\":5,\"POTION\":2,\"X_DEFEND\":1,\"BICYCLE\":1,\"MOON_STONE\":1}");
			helper.assertTrue(count(player, "poke_ball") == 5 && count(player, "potion") == 2 && count(player, "x_defence") == 1,
					"the inventory doesn't match the bag");
			helper.assertTrue(count(player, "moon_stone") == 0, "a stone was mirrored; it stays in the Gen 1 bag");
			// The same bag again: nothing to change.
			long changed = BagService.stats()[1];
			sync(player, 2, "{\"POKE_BALL\":5,\"POTION\":2,\"X_DEFEND\":1,\"BICYCLE\":1}");
			helper.assertTrue(BagService.stats()[1] == changed, "a matching sync changed the inventory");
			helper.assertTrue(count(player, "poke_ball") == 5, "a matching sync changed the count");
			// Fewer balls, no potions: taken away (not a use).
			sync(player, 3, "{\"POKE_BALL\":3,\"X_DEFEND\":1}");
			helper.assertTrue(count(player, "poke_ball") == 3 && count(player, "potion") == 0, "the sync didn't take items away");
			helper.assertTrue(BagService.reference(player, "POKE_BALL") == 3, "the reference didn't follow the sync");
			// Malformed: an error, nothing changes.
			sync(player, 4, "{\"poke ball\":1}");
			helper.assertTrue(relay.of(MsgTypes.ERROR).size() == 1 && count(player, "poke_ball") == 3, "a malformed sync was used");
		} catch (RuntimeException e) {
			relay.close();
			MockPlayers.leave(helper, player);
			throw e;
		}
		helper.startSequence()
				.thenExecuteAfter(12, () -> helper.assertTrue(uses(relay).isEmpty(), "the mirror itself was reported as a use"))
				.thenExecute(() -> {
					relay.close();
					MockPlayers.leave(helper, player);
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
	public void aConsumedItemIsReportedOnce(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		try {
			sync(player, 1, "{\"POKE_BALL\":5}");
			consume(helper, player, "poke_ball");
		} catch (RuntimeException e) {
			relay.close();
			MockPlayers.leave(helper, player);
			throw e;
		}
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(!uses(relay).isEmpty(), "no bag.use yet"))
				.thenExecuteAfter(12, () -> {
					List<Envelope> uses = uses(relay);
					helper.assertTrue(uses.size() == 1, "expected one bag.use, got " + uses.size());
					helper.assertTrue(uses.get(0).data().get("item").getAsString().equals("POKE_BALL")
							&& uses.get(0).data().get("qty").getAsInt() == 1, "the use names the wrong item or count");
					helper.assertTrue(BagService.reference(player, "POKE_BALL") == 4, "the reference didn't follow the use");
					// The hidden game's next sync agrees: nothing changes, nothing echoes.
					sync(player, 2, "{\"POKE_BALL\":4}");
					helper.assertTrue(count(player, "poke_ball") == 4, "the agreeing sync changed the inventory");
				})
				.thenExecuteAfter(12, () -> {
					helper.assertTrue(uses(relay).size() == 1, "the agreeing sync was reported as a use");
					relay.close();
					MockPlayers.leave(helper, player);
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 200)
	public void aBattleReportsItsUseOnceItIsOver(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		try {
			sync(player, 1, "{\"POTION\":2}");
			EncounterGameTests.start(helper, player, "bag-1", "static", 143, 20, true, false);
			// Cobblemon takes the potion when it is chosen in the battle; it could still give it back.
			consume(helper, player, "potion");
		} catch (RuntimeException e) {
			EncounterGameTests.cleanUp(helper, player, relay);
			throw e;
		}
		GameTestSequence end = helper.startSequence()
				.thenExecuteAfter(12, () -> {
					helper.assertTrue(uses(relay).isEmpty(), "a use was reported during the battle");
					PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(player);
					helper.assertTrue(battle != null && battle == EncounterService.battleOf(player), "the battle isn't running");
					battle.stop();
				})
				.thenWaitUntil(() -> helper.assertTrue(!uses(relay).isEmpty(), "the battle's use wasn't reported after it"))
				.thenExecute(() -> {
					List<Envelope> uses = uses(relay);
					helper.assertTrue(uses.size() == 1 && uses.get(0).data().get("item").getAsString().equals("POTION"), "wrong use");
				});
		MockPlayers.leaveAfterBattle(helper, end, relay::close, player);
	}
}
