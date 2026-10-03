package dev.kantobridge.client.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.proto.MsgTypes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * The client between gen1-bridge and the Minecraft server for battles and Pokémon (docs/PLAN.md §2.4 flows 8, 9 and 11,
 * §3.3). Neither end hears the other directly: the client relays.
 *
 * <ul>
 * <li><b>{@code battle.start}</b> (from gen1-bridge) goes to the server with its data unchanged. A pending-battle record,
 * by the {@code battle} id, ties the server's {@code battle.result} to it. A start the server can't get (no world) is
 * answered {@code aborted} here; leaving the server answers {@code aborted} for every battle still pending, since the
 * server stops them and its answer can't arrive any more.</li>
 * <li><b>{@code battle.result}</b> (from the server) goes to gen1-bridge. gen1-bridge sends a battle's
 * {@code battle.start} again after every snapshot (a reconnect, a resync) until it has the result, so a result a
 * reconnect lost is re-sent from the record when that repeated start comes: answered records are kept for
 * {@value #RESEND_WINDOW_MS} ms after they were delivered (an undelivered one until it is). A repeated start for a
 * pending battle is ignored. Results are never re-sent unasked: gen1-bridge answers a result for a battle it isn't
 * waiting on with an error.</li>
 * <li><b>{@code party.give/heal/rename/take/item}</b> and <b>{@code daycare.deposit/withdraw}</b> (from gen1-bridge) go to
 * the server unchanged; while there is no server link they wait in a queue (at most {@value #QUEUE_MAX}, oldest dropped)
 * and go out on the next join.</li>
 * <li><b>{@code party.sync}</b> and <b>{@code dex.sync}</b> (from the server, Cobblemon is authoritative) go to
 * gen1-bridge; the latest of each is sent again after every snapshot, and forgotten when leaving the server.</li>
 * <li><b>{@code bag.sync}</b> (from gen1-bridge, the bag is authoritative; it sends one after every snapshot and on
 * every change) goes to the server. Without a server link it waits in the same queue as the party requests, so
 * gen1-bridge's order holds ({@code party.*}, {@code bag.sync}, then a {@code battle.start}); only the newest bag waits,
 * behind whatever came before it. A live snapshot's {@code dex} goes up as {@code dex.sync} when the protocol gives that
 * type a {@code c2s} leg ({@code dexUp}).</li>
 * <li><b>{@code bag.use}</b> (from the server: items Minecraft consumed) goes to gen1-bridge; without a link it waits
 * (at most {@value #QUEUE_MAX}) and goes out after the next snapshot.</li>
 * </ul>
 *
 * Correlation is by the ids in {@code data}; the envelopes' {@code seq} is new on each hop. Plain Java, client thread
 * only; {@link GameRelayRuntime} is the glue.
 */
public final class GameRelay {
	/** Where messages go. */
	public interface Port {
		/** To gen1-bridge; false when not connected (then the message is dropped). */
		boolean toBridge(String type, JsonObject data);

		/** To the server relay; false when there is no server link (then the message is dropped). */
		boolean toServer(String type, JsonObject data);

		/** A counted warning: a short code and how many so far. */
		void warn(String code, long count);
	}

	/** gen1-bridge's requests about the party that the server carries out (the daycare's too: it holds Pokémon, P15). */
	public static final List<String> PARTY_REQUESTS = List.of(MsgTypes.PARTY_GIVE, MsgTypes.PARTY_HEAL, MsgTypes.PARTY_RENAME,
			MsgTypes.PARTY_TAKE, MsgTypes.PARTY_ITEM, MsgTypes.DAYCARE_DEPOSIT, MsgTypes.DAYCARE_WITHDRAW);
	/** The server's state for gen1-bridge. */
	public static final List<String> SERVER_SYNCS = List.of(MsgTypes.PARTY_SYNC, MsgTypes.DEX_SYNC);

	static final int QUEUE_MAX = 32;
	static final int RECORDS_MAX = 16;
	static final long RESEND_WINDOW_MS = 60_000;
	private static final Set<String> OUTCOMES = Set.of("win", "lose", "fled", "caught", "aborted");

	/** Counters for the debug overlay and tests. */
	public static final class Stats {
		public long battlesRelayed;
		public long resultsForwarded;
		public long resultsResent;
		public long abortedHere;
		public long repeatedStarts;
		public long partyRelayed;
		public long partyQueued;
		public long syncsForwarded;
		public long bagSyncsRelayed;
		public long bagUsesForwarded;
		public long bagUsesQueued;
	}

	private enum State {
		/** Relayed to the server, no result yet. */
		PENDING,
		/** The result is known. */
		ANSWERED
	}

	private static final class Record {
		final String id;
		State state = State.PENDING;
		@Nullable JsonObject result;
		/** When the result last went to gen1-bridge, or -1 while it never could. */
		long deliveredAt = -1;

		Record(String id) {
			this.id = id;
		}
	}

	private record Queued(String type, JsonObject data) {
	}

	private final Port port;
	private final Stats stats = new Stats();
	/** Battle records in arrival order. */
	private final Map<String, Record> battles = new LinkedHashMap<>();
	private final Deque<Queued> queue = new ArrayDeque<>();
	private final Map<String, JsonObject> syncs = new LinkedHashMap<>();
	private final Map<String, Long> warnings = new TreeMap<>();
	/** bag.use messages gen1-bridge couldn't get yet. */
	private final Deque<JsonObject> pendingUses = new ArrayDeque<>();
	private final boolean dexUp;

	public GameRelay(Port port) {
		this(port, false);
	}

	/**
	 * @param dexUp relay a live snapshot's {@code dex} to the server as {@code dex.sync} (the protocol's catalog decides)
	 */
	public GameRelay(Port port, boolean dexUp) {
		this.port = port;
		this.dexUp = dexUp;
	}

	// ---- from gen1-bridge ----

	/** A message from gen1-bridge; types other than {@code battle.start}, the party requests and {@code bag.sync} are ignored. */
	public void fromBridge(Envelope message, long now) {
		if (message.type().equals(MsgTypes.BATTLE_START)) {
			this.battleStart(message.data(), now);
		} else if (PARTY_REQUESTS.contains(message.type())) {
			this.partyRequest(message.type(), message.data());
		} else if (message.type().equals(MsgTypes.BAG_SYNC)) {
			this.bagSync(message.data().deepCopy());
		}
	}

	/**
	 * The bag goes to the server after anything still waiting; without a link it waits in the queue, and a newer bag
	 * replaces an older one there (it is a whole state, not a request), at the end, behind what came before it.
	 */
	private void bagSync(JsonObject data) {
		if (this.queue.isEmpty() && this.port.toServer(MsgTypes.BAG_SYNC, data)) {
			this.stats.bagSyncsRelayed++;
			return;
		}
		this.queue.removeIf(queued -> queued.type().equals(MsgTypes.BAG_SYNC));
		this.enqueue(MsgTypes.BAG_SYNC, data);
	}

	private void battleStart(JsonObject data, long now) {
		String id = id(data, "battle");
		if (id == null) {
			// Without a usable id nobody could match an answer; the server would refuse it too.
			this.warn("battle.bad_id");
			return;
		}
		Record record = this.battles.get(id);
		if (record != null) {
			this.stats.repeatedStarts++;
			if (record.state == State.ANSWERED) {
				// gen1-bridge is still waiting for it: a reconnect lost the result.
				this.deliver(record, now);
				this.stats.resultsResent++;
			}
			return;
		}
		record = new Record(id);
		this.remember(record);
		// Party requests and the bag that waited go first, in gen1-bridge's order: the starter's party.give must be in
		// before the rival battle needs it, and the balls and medicine the battle uses.
		this.flush();
		if (this.queue.isEmpty() && this.port.toServer(MsgTypes.BATTLE_START, data)) {
			this.stats.battlesRelayed++;
		} else {
			this.warn("battle.no_server");
			this.abortHere(record, now);
		}
	}

	private void partyRequest(String type, JsonObject data) {
		if (this.queue.isEmpty() && this.port.toServer(type, data)) {
			this.stats.partyRelayed++;
			return;
		}
		this.enqueue(type, data);
		this.stats.partyQueued++;
	}

	private void enqueue(String type, JsonObject data) {
		if (this.queue.size() >= QUEUE_MAX) {
			this.queue.removeFirst();
			this.warn("party.queue_full");
		}
		this.queue.addLast(new Queued(type, data.deepCopy()));
	}

	/**
	 * gen1-bridge sent a snapshot (a new link, a reconnect or a resync): it rebuilds its state, so the server's latest syncs
	 * go again. Battle results don't: a battle still waiting is started again by gen1-bridge itself.
	 */
	public void bridgeSnapshot(long now) {
		this.bridgeSnapshot(new JsonObject(), now);
	}

	/**
	 * {@link #bridgeSnapshot(long)} with the snapshot's data: when it is live ({@code ingame}) and {@code dexUp}, its
	 * Pokédex goes to the server as {@code dex.sync}; at the title screen it is only the save's leftovers and goes
	 * nowhere. (The bag needs nothing here: gen1-bridge sends a {@code bag.sync} after every snapshot.) {@code bag.use}s
	 * that waited for the link go out after the syncs.
	 */
	public void bridgeSnapshot(JsonObject snapshot, long now) {
		this.syncs.forEach((type, data) -> {
			if (this.port.toBridge(type, data.deepCopy())) {
				this.stats.syncsForwarded++;
			}
		});
		JsonElement ingame = snapshot.get("ingame");
		if (ingame != null && ingame.isJsonPrimitive() && ingame.getAsJsonPrimitive().isBoolean() && ingame.getAsBoolean()) {
			JsonElement dex = snapshot.get("dex");
			if (this.dexUp && dex != null && dex.isJsonObject()) {
				this.port.toServer(MsgTypes.DEX_SYNC, dex.getAsJsonObject().deepCopy());
			}
		}
		while (!this.pendingUses.isEmpty() && this.port.toBridge(MsgTypes.BAG_USE, this.pendingUses.peekFirst().deepCopy())) {
			this.pendingUses.removeFirst();
			this.stats.bagUsesForwarded++;
		}
	}



	// ---- from the server ----

	/** A message from the server; types other than {@code battle.result} and the syncs are ignored. */
	public void fromServer(Envelope message, long now) {
		JsonObject data = message.data();
		if (message.type().equals(MsgTypes.BATTLE_RESULT)) {
			String id = id(data, "battle");
			if (id == null || !OUTCOMES.contains(str(data, "outcome"))) {
				this.warn("battle.bad_result");
				return;
			}
			Record record = this.battles.get(id);
			if (record == null) {
				// Not relayed by this client session (the client restarted): gen1-bridge decides whether it waits for it.
				record = new Record(id);
				this.remember(record);
			}
			this.answer(record, data.deepCopy(), now);
			this.stats.resultsForwarded++;
		} else if (SERVER_SYNCS.contains(message.type())) {
			this.syncs.put(message.type(), data.deepCopy());
			if (this.port.toBridge(message.type(), data)) {
				this.stats.syncsForwarded++;
			}
		} else if (message.type().equals(MsgTypes.BAG_USE)) {
			this.bagUse(data.deepCopy());
		}
	}

	/** Items consumed in Minecraft go to gen1-bridge, in order; without a link they wait for the next snapshot. */
	private void bagUse(JsonObject data) {
		if (this.pendingUses.isEmpty() && this.port.toBridge(MsgTypes.BAG_USE, data)) {
			this.stats.bagUsesForwarded++;
			return;
		}
		if (this.pendingUses.size() >= QUEUE_MAX) {
			this.pendingUses.removeFirst();
			this.warn("bag.use_queue_full");
		}
		this.pendingUses.addLast(data);
		this.stats.bagUsesQueued++;
	}

	/** Joined a server: party requests that waited go out. */
	public void serverJoined(long now) {
		this.flush();
	}

	/**
	 * Left the server: it stops the battles it ran for this player, and its answers can't arrive, so gen1-bridge hears
	 * {@code aborted} for each pending one; the server's syncs belong to that world and are forgotten.
	 */
	public void serverLeft(long now) {
		for (Record record : this.battles.values()) {
			if (record.state == State.PENDING) {
				this.warn("battle.server_left");
				this.abortHere(record, now);
			}
		}
		this.syncs.clear();
	}

	/** Each client tick: queued requests go out once the server link is back; delivered results are forgotten in time. */
	public void tick(long now) {
		this.flush();
		Iterator<Record> it = this.battles.values().iterator();
		while (it.hasNext()) {
			Record record = it.next();
			if (record.state == State.ANSWERED && record.deliveredAt >= 0 && now - record.deliveredAt > RESEND_WINDOW_MS) {
				it.remove();
			}
		}
	}

	// ---- views ----

	public Stats stats() {
		return this.stats;
	}

	/** Battles relayed and not answered yet. */
	public int pendingBattles() {
		int count = 0;
		for (Record record : this.battles.values()) {
			if (record.state == State.PENDING) {
				count++;
			}
		}
		return count;
	}

	/** True when a result for this battle is known (and kept to answer a repeated start). */
	public boolean answered(String battle) {
		Record record = this.battles.get(battle);
		return record != null && record.state == State.ANSWERED;
	}

	public int queued() {
		return this.queue.size();
	}

	/** {@code bag.use}s waiting for gen1-bridge. */
	public int queuedUses() {
		return this.pendingUses.size();
	}

	/** True while a {@code bag.sync} waits for the server. */
	public boolean bagPending() {
		return this.queue.stream().anyMatch(queued -> queued.type().equals(MsgTypes.BAG_SYNC));
	}

	public Map<String, Long> warnings() {
		return Map.copyOf(this.warnings);
	}

	// ---- helpers ----

	private void flush() {
		while (!this.queue.isEmpty()) {
			Queued next = this.queue.peekFirst();
			if (!this.port.toServer(next.type(), next.data())) {
				return;
			}
			this.queue.removeFirst();
			if (next.type().equals(MsgTypes.BAG_SYNC)) {
				this.stats.bagSyncsRelayed++;
			} else {
				this.stats.partyRelayed++;
			}
		}
	}

	private void answer(Record record, JsonObject result, long now) {
		record.state = State.ANSWERED;
		record.result = result;
		this.deliver(record, now);
	}

	private void deliver(Record record, long now) {
		if (record.result != null && this.port.toBridge(MsgTypes.BATTLE_RESULT, record.result.deepCopy())) {
			record.deliveredAt = now;
		}
	}

	private void abortHere(Record record, long now) {
		JsonObject result = new JsonObject();
		result.addProperty("battle", record.id);
		result.addProperty("outcome", "aborted");
		this.answer(record, result, now);
		this.stats.abortedHere++;
	}

	/** Keeps a record, making room by dropping the oldest answered ones first. */
	private void remember(Record record) {
		if (this.battles.size() >= RECORDS_MAX) {
			Iterator<Record> it = this.battles.values().iterator();
			boolean dropped = false;
			while (it.hasNext() && !dropped) {
				if (it.next().state == State.ANSWERED) {
					it.remove();
					dropped = true;
				}
			}
			if (!dropped) {
				it = this.battles.values().iterator();
				it.next();
				it.remove();
			}
		}
		this.battles.put(record.id, record);
	}

	private void warn(String code) {
		long count = this.warnings.merge(code, 1L, Long::sum);
		this.port.warn(code, count);
	}

	private static @Nullable String id(JsonObject data, String key) {
		String value = str(data, key);
		return value != null && Ids.ID.matcher(value).matches() ? value : null;
	}

	private static @Nullable String str(JsonObject data, String key) {
		JsonElement value = data.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}
}
