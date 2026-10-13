package dev.kantobridge.client.net;

import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * One hidden game's half of the trainer economy's projection (MG6, docs/specs/MULTI_GAME_PLAN.md §4.9; PROTOCOL.md "One
 * economy"): the server's {@code bag.set} goes down to gen1-bridge, and gen1-bridge's {@code act.result} of kind
 * {@code bag.set} goes back up carrying the server's {@code seq} as {@code re}, so the server knows which projection was
 * applied and what did not fit ({@code left}, "in your trunk"). Only results for a {@code bag.set} forwarded here are
 * taken: the hidden game's other {@code act.result}s are the field moves' and the crossings'. The link's last
 * {@code bag.sync} money is kept for the F7 overlay. Plain Java; {@link BagSetRuntime} is the Minecraft glue.
 */
public final class BagSetRelay {
	/** The most projections waiting for gen1-bridge's answer. */
	static final int MAX_PENDING = 32;
	public static final String KIND = "bag.set";

	/** The link, as the relay sees it. */
	public interface Port {
		/** Sends to gen1-bridge; its {@code seq}, or -1 when not sent (not connected, or the pairing holds the world's data). */
		long toBridge(String type, JsonObject data);

		/** Sends to the server with {@code re}; false when it couldn't go. */
		boolean toServer(String type, JsonObject data, @Nullable Long re);
	}

	/** Counters for the debug overlay and tests. */
	public static final class Stats {
		public long projections;
		public long resultsForwarded;
		public long dropped;
	}

	private final Port port;
	private final Stats stats = new Stats();
	/** gen1-bridge's seq of a forwarded {@code bag.set} → the server's seq, for the answer's {@code re}. */
	private final Map<Long, Long> forwarded = new LinkedHashMap<>();
	private long money = -1;
	private long left = -1;
	private @Nullable String lastReason;

	public BagSetRelay(Port port) {
		this.port = port;
	}

	/** The server's {@code bag.set}: down to gen1-bridge, remembered by the seq it got there. */
	public void fromServer(Envelope message) {
		long seq = this.port.toBridge(MsgTypes.BAG_SET, message.data().deepCopy());
		if (seq <= 0) {
			this.stats.dropped++;
			return;
		}
		this.stats.projections++;
		this.forwarded.put(seq, message.seq());
		Iterator<Long> oldest = this.forwarded.keySet().iterator();
		while (this.forwarded.size() > MAX_PENDING && oldest.hasNext()) {
			oldest.next();
			oldest.remove();
		}
	}

	/**
	 * One of gen1-bridge's {@code act.result}s: forwarded to the server when it answers a {@code bag.set} sent from here
	 * and says so ({@code kind}); returns whether it was taken (so the field moves ignore it).
	 */
	public boolean onResult(Envelope message) {
		Long re = message.re();
		if (re == null || !KIND.equals(JsonFields.str(message.data(), "kind"))) {
			return false;
		}
		Long serverSeq = this.forwarded.remove(re);
		if (serverSeq == null) {
			return false;
		}
		JsonObject data = message.data().deepCopy();
		this.left = Math.max(0, JsonFields.integer(data, "left", 0));
		this.lastReason = JsonFields.bool(data, "ok", false) ? null : JsonFields.str(data, "reason");
		if (this.port.toServer(MsgTypes.ACT_RESULT, data, serverSeq)) {
			this.stats.resultsForwarded++;
		} else {
			this.stats.dropped++;
		}
		return true;
	}

	/** gen1-bridge's {@code bag.sync}: the wallet it carries (MG6), kept for the overlay. */
	public void onBagSync(JsonObject data) {
		if (data.has("money") && data.get("money").isJsonPrimitive()) {
			this.money = Math.max(0, JsonFields.integer(data, "money", 0));
		}
	}

	/** The link dropped: nothing it answers can be matched any more. */
	public void disconnected() {
		this.forwarded.clear();
		this.money = -1;
	}

	/** The wallet from the last {@code bag.sync}, or -1 before one. */
	public long money() {
		return this.money;
	}

	/** Items the last projection couldn't fit into the bag (they stay in the ledger), or -1 before one. */
	public long left() {
		return this.left;
	}

	/** The last projection's refusal, or null when it was applied. */
	public @Nullable String lastReason() {
		return this.lastReason;
	}

	public int pending() {
		return this.forwarded.size();
	}

	public Stats stats() {
		return this.stats;
	}
}
