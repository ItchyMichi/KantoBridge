package dev.kantobridge.gametest;

import dev.kantobridge.net.RelayService;
import dev.kantobridge.server.FlashService;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.story.StoryStates;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Flash on a dark map (docs/PLAN.md §6 M8 item 5): a made-up dark interior laid over the test's own blocks (published
 * as the server's WorldIndex for this batch only, then withdrawn) gives a mock player Night Vision while their relayed
 * state has it lit, takes it away when it isn't, never gives it on a map that isn't dark, and leaves a potion's alone.
 */
public class FlashGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_flash";
	private static final String HASH = "0".repeat(64);

	/** A dark interior and a lit-able bright one, side by side over the test's origin, at its floor's height. */
	private static WorldIndex publish(GameTestHelper helper, BlockPos origin) {
		WorldIndex index = new WorldIndex("red", HASH, List.of(
				new MapPlacement("TEST_DARK", origin.getX(), origin.getY(), origin.getZ(), 4, 4, 1, MapPlacement.Kind.INTERIOR, "TEST_PALETTE",
						"kantobridge:kanto_cave", true, null),
				new MapPlacement("TEST_BRIGHT", origin.getX() + 8, origin.getY(), origin.getZ(), 4, 4, 1, MapPlacement.Kind.INTERIOR,
						"TEST_PALETTE", "kantobridge:kanto_interior", false, null)),
				HASH, StoryCellTable.empty(), List.of());
		WorldIndexSync.publish(helper.getLevel().getServer(), index, false);
		return index;
	}

	private static String litState(long seq, String litMaps) {
		return "{\"type\":\"story.state\",\"seq\":" + seq + ",\"data\":{\"full\":false,\"lit\":[" + litMaps + "]}}";
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void nightVisionOnlyWhileTheDarkMapIsLit(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
		try {
			WorldIndex index = publish(helper, origin);
			MockPlayers.with(helper, player -> {
				StoryState state = StoryStates.of(player);
				state.clear();
				player.setPos(origin.getX() + 3.0, origin.getY(), origin.getZ() + 3.0);
				helper.assertTrue(!FlashService.update(player, index) && !player.hasEffect(MobEffects.NIGHT_VISION), "dark but not lit");

				// The client relays the lit map (story.lit / player.state): Night Vision at once.
				RelayService.get().receive(player, litState(1, "\"TEST_DARK\""));
				helper.assertTrue(state.isLit("TEST_DARK"), "the relayed lit maps were not applied");
				MobEffectInstance effect = player.getEffect(MobEffects.NIGHT_VISION);
				helper.assertTrue(effect != null && FlashService.ours(effect), "no Night Vision on the lit dark map");

				// Walking onto the bright map: taken away, although the dark map is still lit.
				player.setPos(origin.getX() + 11.0, origin.getY(), origin.getZ() + 3.0);
				helper.assertTrue(!FlashService.update(player, index), "Night Vision off the dark map");

				// Back on the dark map, then Flash ends (leaving resets it): gone again.
				player.setPos(origin.getX() + 3.0, origin.getY(), origin.getZ() + 3.0);
				helper.assertTrue(FlashService.update(player, index), "back on the lit dark map");
				RelayService.get().receive(player, litState(2, ""));
				helper.assertTrue(!player.hasEffect(MobEffects.NIGHT_VISION), "the lit set emptied but Night Vision stayed");

				// A lit map that isn't dark never gives it, and a potion's Night Vision is never taken.
				state.setLit(Set.of("TEST_BRIGHT"));
				player.setPos(origin.getX() + 11.0, origin.getY(), origin.getZ() + 3.0);
				helper.assertTrue(!FlashService.update(player, index), "a bright map gave Night Vision");
				player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 600));
				FlashService.update(player, index);
				helper.assertTrue(player.hasEffect(MobEffects.NIGHT_VISION), "a potion's Night Vision was taken");
				player.removeEffect(MobEffects.NIGHT_VISION);
				state.clear();
			});
		} finally {
			// Batches run one after another; nothing may linger.
			WorldIndexSync.withdraw(server);
		}
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void withoutAWorldIndexNothingIsGiven(GameTestHelper helper) {
		MockPlayers.with(helper, player -> {
			StoryStates.of(player).setLit(Set.of("TEST_DARK"));
			helper.assertTrue(!FlashService.update(player, null) && !player.hasEffect(MobEffects.NIGHT_VISION), "no Kanto world, no Flash");
			StoryStates.of(player).clear();
		});
		helper.succeed();
	}
}
