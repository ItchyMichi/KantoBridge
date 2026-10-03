package dev.kantobridge.client.net;

import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.HelloOk;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.bridge.VersionCheck;
import dev.kantobridge.client.ui.Notices;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.net.RelayHandshake;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.WorldIndex;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft glue for the version checks and mismatch screens (M10; DESIGN §9 "version handshake"):
 *
 * <ul>
 * <li><b>The relay's handshake</b>: the server's {@code relay.hello} is answered with this client's; a server speaking
 * another relay protocol can't work with this client, so it leaves with a reason naming both versions (the server
 * would send it away anyway).</li>
 * <li><b>gen1-bridge's engine</b> ({@code hello.ok.engine} against {@code tested}): a toast, once per pair of
 * versions, and the F7 line.</li>
 * <li><b>The game</b>: singleplayer only, a world built from the other version than Gen1Recomp runs gets a warning
 * screen once per world join: the story works, wild Pokémon follow the world's version.</li>
 * <li><b>The save pair</b> ({@code save.mismatch}): a warning screen with the kind's own text, once per distinct
 * mismatch per world join.</li>
 * </ul>
 * The decisions are {@link VersionCheck}'s (plain Java); warning screens wait their turn in {@link Notices}. Logs carry
 * kinds and version numbers only.
 */
public final class Versions {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	private static final Set<String> KINDS = Set.of("hidden_behind", "world_behind", "hidden_older", "hidden_newer", "new_game", "other");
	private static final SystemToast.SystemToastId ENGINE_TOAST = new SystemToast.SystemToastId(10_000L);
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault());
	private static @Nullable Versions instance;

	private final BridgeRuntime bridge;
	private final VersionCheck check = new VersionCheck();
	/** The server's relay protocol and mod version from its {@code relay.hello}, or -1 / null. */
	private int serverProtocol = -1;
	private @Nullable String serverMod;

	private Versions(BridgeRuntime bridge) {
		this.bridge = bridge;
	}

	/** Call once from the client entrypoint, after {@link BridgeRuntime#init} and {@link ClientRelay#init}. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		Versions versions = new Versions(bridge);
		instance = versions;
		bridge.dispatcher().on(MsgTypes.HELLO_OK, message -> versions.helloOk());
		ClientRelay relay = ClientRelay.get();
		relay.register(MsgTypes.RELAY_HELLO, versions::serverHello);
		relay.register(MsgTypes.SAVE_MISMATCH, versions::mismatch);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			versions.check.joined();
			versions.serverProtocol = -1;
			versions.serverMod = null;
		});
		ClientTickEvents.END_CLIENT_TICK.register(versions::tick);
		bridge.addDebugLine(versions::debugLine);
	}

	/** gen1-bridge's handshake: an engine it wasn't tested with gets a toast (once per pair of versions). */
	private void helloOk() {
		HelloOk ok = this.bridge.client().handshake();
		if (ok == null || !this.check.engineNotice(ok)) {
			return;
		}
		LOGGER.warn("bridge: Gen1Recomp {} isn't the engine gen1-bridge was tested with ({})", ok.engine(), ok.tested());
		Minecraft minecraft = Minecraft.getInstance();
		SystemToast.addOrUpdate(minecraft.getToasts(), ENGINE_TOAST, Component.translatable("kantobridge.version.engine.title", ok.engine()),
				Component.translatable("kantobridge.version.engine.text", ok.tested()));
	}

	/** The server's {@code relay.hello}: answered with ours; another protocol means this client can't stay. */
	private void serverHello(Envelope message) {
		JsonObject data = message.data();
		long theirs = JsonFields.integer(data, "protocol", -1);
		String theirMod = JsonFields.str(data, "mod");
		this.serverProtocol = theirs >= 1 && theirs <= 1000 ? (int) theirs : -1;
		this.serverMod = theirMod != null && RelayHandshake.VERSION.matcher(theirMod).matches() ? theirMod : null;
		ClientRelay.get().send(MsgTypes.RELAY_HELLO, RelayHandshake.helloData(), null);
		int ours = RelayHandshake.protocol();
		if (this.serverProtocol < 0) {
			// A hello without a usable number proves nothing: the server decides on our answer.
			LOGGER.warn("relay: the server's relay.hello has no usable protocol number");
			return;
		}
		if (RelayHandshake.decide(true, ours, this.serverProtocol) != RelayHandshake.Verdict.PROTOCOL) {
			return;
		}
		LOGGER.warn("relay: the server speaks relay protocol {}, this client {}; leaving", this.serverProtocol, ours);
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection != null) {
			connection.getConnection().disconnect(Component.translatable("kantobridge.relay.mismatch",
					this.serverMod == null ? "?" : this.serverMod, this.serverProtocol, RelayHandshake.modVersion(), ours));
		}
	}

	/** {@code save.mismatch}: a warning screen for each distinct one in this world join. */
	private void mismatch(Envelope message) {
		JsonObject data = message.data();
		String kind = JsonFields.str(data, "kind");
		if (kind == null || !KINDS.contains(kind)) {
			kind = "other";
		}
		long ops = JsonFields.integer(data, "ops", -1);
		long hiddenAt = JsonFields.integer(data, "hiddenAt", -1);
		long worldAt = JsonFields.integer(data, "worldAt", -1);
		if (!this.check.mismatchNotice(kind, ops, hiddenAt, worldAt)) {
			return;
		}
		LOGGER.warn("session: the hidden game's save doesn't match this world's ({})", kind);
		Notices.warn(Component.translatable("kantobridge.mismatch.title"), Component.translatable("kantobridge.mismatch." + kind,
				ops < 0 ? "?" : Long.toString(ops), time(hiddenAt), time(worldAt)));
	}

	/** Each client tick: the singleplayer game check, once both games are known. */
	private void tick(Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null) {
			return;
		}
		WorldIndex index = ClientWorldIndex.current();
		HelloOk ok = this.bridge.client().handshake();
		if (index == null || ok == null || !this.check.gameNotice(minecraft.isLocalServer(), index.game(), ok.game())) {
			return;
		}
		LOGGER.warn("bridge: this world was built from {}, Gen1Recomp runs {}", index.game(), ok.game());
		Component world = Component.translatable("kantobridge.game." + index.game());
		Component hidden = Component.translatable("kantobridge.game." + ok.game());
		Notices.warn(Component.translatable("kantobridge.version.game.title", world),
				Component.translatable("kantobridge.version.game.text", world, hidden));
	}

	private static String time(long unixSeconds) {
		return unixSeconds < 0 ? "?" : TIME.format(Instant.ofEpochSecond(unixSeconds));
	}

	private String debugLine() {
		HelloOk ok = this.bridge.client().handshake();
		return I18n.get("kantobridge.debug.versions", RelayHandshake.modVersion(), RelayHandshake.protocol(),
				this.serverMod == null ? "-" : this.serverMod, this.serverProtocol < 0 ? "-" : Integer.toString(this.serverProtocol),
				ok == null ? "-" : ok.engine(), ok == null || ok.tested() == null ? "-" : ok.tested(),
				ok != null && ok.untestedEngine() ? I18n.get("kantobridge.debug.versions.untested") : "");
	}
}
