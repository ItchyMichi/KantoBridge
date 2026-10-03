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
 * </ul>
 * Plain Java, client thread only; {@link SessionRuntime} is the glue.
 */
public final class SaveRelay {
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
		if (this.port.toServer(MsgTypes.SAVE_BEGIN, message.data().deepCopy())) {
			this.stats.relayed++;
		} else {
			this.warn("save.no_server");
			this.answerHere(record, now);
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
