package dev.kantobridge.gametest;

import com.cobblemon.mod.common.entity.npc.NPCEntity;
import dev.kantobridge.battle.BattleProxies;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** The relay skeleton on a real server thread, and the battle-proxy visibility mixin. */
public class RelayGameTests implements FabricGameTest {
	@GameTest(template = EMPTY_STRUCTURE)
	public void relayDispatchesOnlyValidClientMessages(GameTestHelper helper) {
		List<Envelope> seen = new ArrayList<>();
		RelayService relay = RelayService.get();
		// Stands in for the real texture.status handler (TextureStatusService), which is put back afterwards.
		RelayService.Handler real = relay.handler(MsgTypes.TEXTURE_STATUS);
		relay.register(MsgTypes.TEXTURE_STATUS, (player, message) -> seen.add(message));
		try {
			MockPlayers.with(helper, player -> {
				String data = "{\"ok\":true,\"slotMapHash\":\"a1b2c3d4e5f60718\",\"textures\":3}";
				relay.receive(player, "{\"type\":\"texture.status\",\"seq\":1,\"data\":" + data + "}");
				// Rejected: a type that only travels server-to-client, trailing data, NaN, and a reply-only field.
				relay.receive(player, "{\"type\":\"warp.done\",\"seq\":2,\"data\":{\"ok\":true}}");
				relay.receive(player, "{\"type\":\"texture.status\",\"seq\":3,\"data\":" + data + "} {}");
				relay.receive(player, "{\"type\":\"texture.status\",\"seq\":NaN,\"data\":" + data + "}");
				relay.receive(player, "{\"type\":\"texture.status\",\"seq\":5,\"re\":1,\"data\":" + data + "}");
			});
		} finally {
			if (real != null) {
				relay.register(MsgTypes.TEXTURE_STATUS, real);
			} else {
				relay.unregister(MsgTypes.TEXTURE_STATUS);
			}
		}
		helper.assertTrue(seen.size() == 1, "expected exactly one accepted message, got " + seen.size());
		helper.assertTrue(seen.get(0).seq() == 1 && seen.get(0).data().get("textures").getAsInt() == 3, "message changed in transit");
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void battleProxyIsNeverBroadcast(GameTestHelper helper) {
		MockPlayers.with(helper, player -> {
			NPCEntity proxy = new NPCEntity(helper.getLevel());
			NPCEntity other = new NPCEntity(helper.getLevel());
			proxy.addTag(BattleProxies.TAG);
			helper.assertTrue(!proxy.broadcastToPlayer(player), "a battle proxy would be sent to a client");
			helper.assertTrue(other.broadcastToPlayer(player), "an ordinary NPC is hidden too: the mixin is too broad");
			proxy.discard();
			other.discard();
		});
		helper.succeed();
	}
}
