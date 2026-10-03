package dev.kantobridge.pokemon;

import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.api.storage.pc.link.PCLink;
import com.cobblemon.mod.common.api.storage.pc.link.PCLinkManager;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.net.messages.client.storage.pc.ClosePCPacket;
import com.cobblemon.mod.common.net.messages.client.storage.pc.OpenPCPacket;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.server.BuildHold;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Bill's PC is Cobblemon's PC (docs/PLAN.md §4.4 {@code PcService}, DESIGN §6 "One PC"): when the player picks Bill's
 * PC in the hidden game, gen1-bridge holds the game and sends {@code pc.open}, which the client relays here. The server
 * opens Cobblemon's PC screen for the player through its own {@link BridgePcLink} (any place: there is no PC block, and
 * the link alone lets the client edit the PC) and {@code OpenPCPacket}, and answers {@code pc.closed} exactly once when
 * the session ends:
 *
 * <ul>
 * <li>the PC screen closes: Cobblemon's screen unlinks itself on every way out (Esc, its exit button, the inventory key),
 * which removes the link; checked every server tick, so no Cobblemon event or mixin is needed;</li>
 * <li>something else replaces the link (another PC opened for the player, e.g. {@code /pc});</li>
 * <li>the PC can't open at all (a battle, a player held for a world build, no PC store, a malformed message): at once,
 * with a counted warning, so the hidden game never waits for a screen that isn't there.</li>
 * </ul>
 *
 * A second {@code pc.open} while one is open is ignored: the open one's {@code pc.closed} answers both. Leaving the
 * server ends the session without an answer (the client tells gen1-bridge itself). Logs carry codes and counts only.
 * Server thread only.
 */
public final class PcService {
	/**
	 * The link for Bill's PC: Cobblemon checks a link's permission on every PC edit, and this one allows the linked
	 * player anywhere (Cobblemon's PC blocks check their distance instead) unless they are in a battle.
	 */
	public static final class BridgePcLink extends PCLink {
		BridgePcLink(PCStore pc, UUID player) {
			super(pc, player);
		}

		@Override
		public boolean isPermitted(ServerPlayer player) {
			return player.getUUID().equals(this.getPlayerID()) && player.isAlive() && BattleRegistry.getBattleByParticipatingPlayer(player) == null;
		}
	}

	/** Open sessions by player. */
	private static final Map<UUID, BridgePcLink> OPEN = new HashMap<>();
	private static final Map<String, AtomicLong> WARNINGS = new ConcurrentHashMap<>();
	private static final AtomicLong OPENED = new AtomicLong();
	private static final AtomicLong CLOSED = new AtomicLong();
	private static final AtomicLong REPEATS = new AtomicLong();

	private PcService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		RelayService.get().register(MsgTypes.PC_OPEN, PcService::onOpen);
		ServerTickEvents.END_SERVER_TICK.register(PcService::tick);
		// Cobblemon drops a leaving player's PC link itself; the session goes with it, unanswered.
		ServerPlayerEvents.LEAVE.register(PcService::onLeave);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> OPEN.clear());
	}

	static void onOpen(ServerPlayer player, Envelope message) {
		if (!message.data().keySet().isEmpty()) {
			RelayService.get().sendError(player, "bad_message", message.seq());
			refuse(player, "bad_message");
			return;
		}
		UUID id = player.getUUID();
		if (OPEN.containsKey(id)) {
			// gen1-bridge asking again (after a reconnect): the open session's pc.closed answers it.
			REPEATS.incrementAndGet();
			KantoBridge.LOGGER.debug("pc: ignored a repeated pc.open");
			return;
		}
		if (BuildHold.isHeld(player)) {
			refuse(player, "held");
			return;
		}
		if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
			refuse(player, "battle");
			return;
		}
		PCStore pc;
		try {
			pc = PlayerExtensionsKt.pc(player);
		} catch (RuntimeException e) {
			// Only the class: an exception's text could quote data.
			KantoBridge.LOGGER.warn("pc: Cobblemon has no PC for the player ({})", e.getClass().getName());
			refuse(player, "no_pc");
			return;
		}
		BridgePcLink link = new BridgePcLink(pc, id);
		PCLinkManager.INSTANCE.addLink(link);
		OPEN.put(id, link);
		new OpenPCPacket(pc).sendToPlayer(player);
		long count = OPENED.incrementAndGet();
		KantoBridge.LOGGER.info("pc: opened Bill's PC; {} so far", count);
	}

	/** The PC can't open: the hidden game hears {@code pc.closed} at once. */
	private static void refuse(ServerPlayer player, String code) {
		warn(code);
		answer(player);
	}

	/** Ends sessions whose link is gone (the screen closed and unlinked) or was replaced by another PC. */
	private static void tick(MinecraftServer server) {
		if (OPEN.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, BridgePcLink>> it = OPEN.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, BridgePcLink> entry = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				it.remove();
			} else if (PCLinkManager.INSTANCE.getLink(entry.getKey()) != entry.getValue()) {
				it.remove();
				CLOSED.incrementAndGet();
				answer(player);
				KantoBridge.LOGGER.debug("pc: Bill's PC closed");
			}
		}
	}

	private static void onLeave(ServerPlayer player) {
		BridgePcLink link = OPEN.remove(player.getUUID());
		if (link != null && PCLinkManager.INSTANCE.getLink(player.getUUID()) == link) {
			PCLinkManager.INSTANCE.removeLink(player.getUUID());
		}
	}

	/**
	 * Closes the player's Bill's PC from the server: Cobblemon's screen closes on their client and the link goes, so the
	 * next tick answers {@code pc.closed}. False when no session is open.
	 */
	public static boolean close(ServerPlayer player) {
		BridgePcLink link = OPEN.get(player.getUUID());
		if (link == null) {
			return false;
		}
		new ClosePCPacket(link.getPc().getUuid()).sendToPlayer(player);
		if (PCLinkManager.INSTANCE.getLink(player.getUUID()) == link) {
			PCLinkManager.INSTANCE.removeLink(player.getUUID());
		}
		return true;
	}

	private static void answer(ServerPlayer player) {
		RelayService.get().send(player, MsgTypes.PC_CLOSED, new JsonObject(), null);
	}

	// ---- views (tests, diagnostics) ----

	/** True while the player's Bill's PC is open. */
	public static boolean isOpen(ServerPlayer player) {
		return OPEN.containsKey(player.getUUID());
	}

	/** The link of the player's open Bill's PC, or null. */
	public static @Nullable PCLink linkOf(ServerPlayer player) {
		return OPEN.get(player.getUUID());
	}

	private static void warn(String code) {
		long count = WARNINGS.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
		KantoBridge.LOGGER.warn("pc: {} ({} so far)", code, count);
	}

	public static long warnings(String code) {
		AtomicLong count = WARNINGS.get(code);
		return count == null ? 0 : count.get();
	}

	public static Map<String, Long> warnings() {
		Map<String, Long> out = new TreeMap<>();
		WARNINGS.forEach((code, count) -> out.put(code, count.get()));
		return out;
	}

	public static long opened() {
		return OPENED.get();
	}

	/** Sessions that ended with the screen (or link) closing, answered with {@code pc.closed}. */
	public static long closed() {
		return CLOSED.get();
	}

	/** {@code pc.open}s ignored because one was open already. */
	public static long repeats() {
		return REPEATS.get();
	}
}
