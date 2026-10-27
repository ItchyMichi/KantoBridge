package dev.kantobridge.client.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.proto.MsgTypes;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * Saving across the bridge (docs/PLAN.md §2.4 flow 12, PROTOCOL.md "Saving"): when the hidden game saves (its menu, or a
 * Pokémon Center heal), gen1-bridge sends {@code save.begin{save, reason}}. The client relays it to the server unchanged;
 * the server saves the player's data, Cobblemon's storage and the world and answers {@code save.done{save, ok}}, which
 * goes to gen1-bridge with {@code re} set to the {@code save.begin} it answers when that came on the current link (the
 * {@code save} id carries the correlation across the hops).
 *
 * <ul>
 * <li>Without a server to relay to (no world, a server without the mod) a save is answered {@code ok:false} here.</li>
 * <li>Leaving the server answers every save still waiting {@code ok:false}: its answer can't arrive any more.</li>
 * <li>gen1-bridge asking again for an answered save (a reconnect lost the answer) is answered from the record, without a
 * second save; a repeat while the server is still on it is ignored. Answers are never re-sent unasked. Answered records
 * are kept {@value #KEEP_MS} ms after delivery, at most {@value #RECORDS_MAX}.</li>
 * <li>M10: {@code save.loaded} (which save the hidden game runs from) goes to the server unchanged, which compares it with
 * the world's save pair. It isn't kept: gen1-bridge sends it after every pairing and whenever its game loads a save.</li>
 * </ul>
 * <p>WORLD_SESSIONS_PLAN WS4 (§2.4, D5): the server's {@code save.request{reason:"world"}} (Minecraft saves the world) and
 * the quit choreography's {@code save.request{reason:"quit"}} (WS5) go to gen1-bridge through {@link #request}, at most
 * once per link per {@value #SAVE_MIN_MS} ms for {@code world} ({@code quit} always). gen1-bridge answers
 * {@code act.result{kind:"save"}} ({@link #requestResult}); a written save then arrives as an ordinary {@code save.begin}
 * and is relayed and answered as above. {@link #request()} says where the latest request stands (for the HUD line and the
 * quit's wait).
 *
 * Plain Java, client thread only; {@link SessionRuntime} is the glue.
 */
public final class SaveRelay {
	/** WS4: one world save request per link at most this often. */
	public static final long SAVE_MIN_MS = 60_000;

	/** WS4: where the latest {@code save.request} stands. */
	public enum RequestState {
		/** None sent (or it was dropped with its link). */
		NONE,
		/** Sent, not answered yet. */
		SENT,
		/** gen1-bridge wrote the save; its {@code save.begin} is on the way (or waits for its {@code save.done}). */
		WRITTEN,
		/** The save's {@code save.done} came (ok or not). */
		DONE,
		/** gen1-bridge refused ({@link #refusal}: not_ingame, not_idle, vetoed, failed, busy). */
		REFUSED
	}

	/** Where messages go. */
	public interface Port {
		/** To gen1-bridge, with {@code re} or null; false when not connected (then the message is dropped). */
		boolean toBridge(String type, @Nullable Long re, JsonObject data);

		/** To the server relay; false when there is no server link (then the message is dropped). */
		boolean toServer(String type, JsonObject data);

		/** A counted warning: a short code and how many so far. */
		void warn(String code, long count);
	}

	static final int RECORDS_MAX = 16;
	static final long KEEP_MS = 60_000;

	/** Counters for the debug overlay and tests. */
	public static final class Stats {
		public long relayed;
		public long answered;
		public long failed;
		public long answeredHere;
		public long repeats;
		public long loaded;
		public long loadedDropped;
		/** WS4: save.request sent, skipped by the rate limit, written, refused. */
		public long requests;
		public long requestsLimited;
		public long requestsWritten;
		public long requestsRefused;
	}

	private static final class Record {
		final String id;
		boolean answered;
		boolean ok;
		/** The {@code save.begin} to answer: its seq and the link it came on (-1: none on any link of ours). */
		long askedSeq;
		long askedLink;
		long deliveredAt = -1;

		Record(String id, long askedSeq, long askedLink) {
			this.id = id;
			this.askedSeq = askedSeq;
			this.askedLink = askedLink;
		}
	}

	private final Port port;
	private final Stats stats = new Stats();
	private final Map<String, Record> records = new LinkedHashMap<>();
	private final Map<String, Long> warnings = new TreeMap<>();
	/** Counts connections to gen1-bridge: a {@code seq} means something only on its own link. */
	private long link;

	public SaveRelay(Port port) {
		this.port = port;
	}

	/** The link to gen1-bridge went up or down: seqs from before don't answer anything any more. */
	public void linkChanged() {
		this.link++;
		if (this.requestState == RequestState.SENT || this.requestState == RequestState.WRITTEN) {
			this.requestState = RequestState.NONE;
		}
	}

	// ---- WS4: save.request ----

	private RequestState requestState = RequestState.NONE;
	private @Nullable String refusal;
	private @Nullable String requestReason;
	private long requestSeq = -1;
	private long requestedAt = Long.MIN_VALUE;
	private long worldAskedAt = Long.MIN_VALUE;
	/** The save id the written request's save.begin carried (its save.done ends the request), or null. */
	private @Nullable String requestSave;

	/**
	 * Asks gen1-bridge for a save of its own ({@code world} or {@code quit}); {@code seq} is what {@code sendTracked} gave
	 * (-1: not sent). Returns false when a {@code world} request was skipped by the rate limit.
	 */
	public boolean request(String reason, long now, java.util.function.ToLongFunction<JsonObject> send) {
		if ("world".equals(reason) && this.worldAskedAt != Long.MIN_VALUE && now - this.worldAskedAt < SAVE_MIN_MS) {
			this.stats.requestsLimited++;
			return false;
		}
		JsonObject data = new JsonObject();
		data.addProperty("reason", reason);
		long seq = send.applyAsLong(data);
		if (seq < 0) {
			this.warn("save.request_unsent");
			return false;
		}
		if ("world".equals(reason)) {
			this.worldAskedAt = now;
		}
		this.requestSeq = seq;
		this.requestReason = reason;
		this.requestedAt = now;
		this.requestState = RequestState.SENT;
		this.refusal = null;
		this.requestSave = null;
		this.stats.requests++;
		return true;
	}

	/** {@code act.result{kind:"save"}} from gen1-bridge (its {@code re}); true when it answered the latest request. */
	public boolean requestResult(@Nullable Long re, boolean ok, @Nullable String reason) {
		if (re == null || re != this.requestSeq || this.requestState != RequestState.SENT) {
			return false;
		}
		if (ok) {
			this.requestState = RequestState.WRITTEN;
			this.stats.requestsWritten++;
		} else {
			this.requestState = RequestState.REFUSED;
			this.refusal = reason == null ? "failed" : reason;
			this.stats.requestsRefused++;
		}
		return true;
	}

	/** WS4: where the latest request stands. */
	public RequestState request() {
		return this.requestState;
	}

	/** WS4: why gen1-bridge refused the latest request, or null. */
	public @Nullable String refusal() {
		return this.requestState == RequestState.REFUSED ? this.refusal : null;
	}

	/** WS4: the latest request's reason ({@code world}, {@code quit}), or null. */
	public @Nullable String requestReason() {
		return this.requestReason;
	}

	/** WS4: when the latest request went out. */
	public long requestedAt() {
		return this.requestedAt;
	}

	/** {@code save.begin} from gen1-bridge. */
	public void fromBridge(Envelope message, long now) {
		String id = id(message.data());
		if (id == null) {
			// Nobody could match an answer to it; the server would refuse it too.
			this.warn("save.bad_id");
			return;
		}
		Record record = this.records.get(id);
		if (record != null) {
			this.stats.repeats++;
			record.askedSeq = message.seq();
			record.askedLink = this.link;
			if (record.answered) {
				this.deliver(record, now);
			}
			return;
		}
		record = new Record(id, message.seq(), this.link);
		this.remember(record);
		if (this.requestState == RequestState.WRITTEN && this.requestSave == null) {
			// WS4: the requested save's own save.begin (its save.done ends the request)
			this.requestSave = id;
		}
		if (this.port.toServer(MsgTypes.SAVE_BEGIN, message.data().deepCopy())) {
			this.stats.relayed++;
		} else {
			this.warn("save.no_server");
			this.answerHere(record, now);
		}
	}

	/**
	 * {@code save.loaded} from gen1-bridge: to the server (M10). Without one (the title screen, another world) there is no
	 * pair to compare with: it is dropped quietly, and gen1-bridge sends it again after the next pairing.
	 */
	public void loaded(Envelope message) {
		if (this.port.toServer(MsgTypes.SAVE_LOADED, message.data().deepCopy())) {
			this.stats.loaded++;
		} else {
			this.stats.loadedDropped++;
		}
	}

	/** {@code save.done} from the server. */
	public void fromServer(Envelope message, long now) {
		JsonObject data = message.data();
		String id = id(data);
		JsonElement ok = data.get("ok");
		if (id == null || ok == null || !ok.isJsonPrimitive() || !ok.getAsJsonPrimitive().isBoolean()) {
			this.warn("save.bad_done");
			return;
		}
		Record record = this.records.get(id);
		if (record == null) {
			// Not relayed by this client session (the client restarted): gen1-bridge decides whether it waits for it.
			record = new Record(id, -1, -1);
			this.remember(record);
		} else if (record.answered) {
			this.warn("save.second_done");
			return;
		}
		this.answer(record, ok.getAsBoolean(), now);
	}

	/** Left the server: its answers can't arrive, so the saves still waiting failed. */
	public void serverLeft(long now) {
		for (Record record : this.records.values()) {
			if (!record.answered) {
				this.warn("save.server_left");
				this.answerHere(record, now);
			}
		}
	}

	/** Each client tick: delivered answers are forgotten in time. */
	public void tick(long now) {
		Iterator<Record> it = this.records.values().iterator();
		while (it.hasNext()) {
			Record record = it.next();
			if (record.answered && record.deliveredAt >= 0 && now - record.deliveredAt > KEEP_MS) {
				it.remove();
			}
		}
	}

	// ---- views ----

	public Stats stats() {
		return this.stats;
	}

	/** Saves relayed and not answered yet. */
	public int pending() {
		int count = 0;
		for (Record record : this.records.values()) {
			if (!record.answered) {
				count++;
			}
		}
		return count;
	}

	/** True when this save's answer is known (and kept for a repeated ask). */
	public boolean answered(String save) {
		Record record = this.records.get(save);
		return record != null && record.answered;
	}

	public Map<String, Long> warnings() {
		return Map.copyOf(this.warnings);
	}

	// ---- helpers ----

	private void answerHere(Record record, long now) {
		this.stats.answeredHere++;
		this.answer(record, false, now);
	}

	private void answer(Record record, boolean ok, long now) {
		record.answered = true;
		record.ok = ok;
		this.stats.answered++;
		if (!ok) {
			this.stats.failed++;
		}
		if (this.requestState == RequestState.WRITTEN && record.id.equals(this.requestSave)) {
			// WS4: the requested save's Minecraft side is done
			this.requestState = RequestState.DONE;
		}
		this.deliver(record, now);
	}

	private void deliver(Record record, long now) {
		JsonObject data = new JsonObject();
		data.addProperty("save", record.id);
		data.addProperty("ok", record.ok);
		Long re = record.askedLink == this.link && record.askedSeq > 0 ? record.askedSeq : null;
		if (this.port.toBridge(MsgTypes.SAVE_DONE, re, data)) {
			record.deliveredAt = now;
		}
	}

	/** Keeps a record, making room by dropping the oldest answered ones first. */
	private void remember(Record record) {
		if (this.records.size() >= RECORDS_MAX) {
			Iterator<Record> it = this.records.values().iterator();
			boolean dropped = false;
			while (it.hasNext() && !dropped) {
				if (it.next().answered) {
					it.remove();
					dropped = true;
				}
			}
			if (!dropped) {
				it = this.records.values().iterator();
				it.next();
				it.remove();
			}
		}
		this.records.put(record.id, record);
	}

	private void warn(String code) {
		long count = this.warnings.merge(code, 1L, Long::sum);
		this.port.warn(code, count);
	}

	private static @Nullable String id(JsonObject data) {
		JsonElement value = data.get("save");
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			return null;
		}
		String id = value.getAsString();
		return Ids.ID.matcher(id).matches() ? id : null;
	}
}
