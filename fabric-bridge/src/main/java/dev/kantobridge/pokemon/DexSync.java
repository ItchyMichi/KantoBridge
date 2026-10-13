package dev.kantobridge.pokemon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.Collection;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The Pokédex as {@code dex.sync} carries it (protocol/messages/dex.sync.schema.json): the national dex numbers 1–151
 * seen and owned, sorted. As in Gen 1, an owned species counts as seen too. Plain Java.
 *
 * @param seen  every species seen or owned
 * @param owned every species owned
 */
public record DexSync(SortedSet<Integer> seen, SortedSet<Integer> owned) {
	private static final Set<String> FIELDS = Set.of("seen", "owned", "region");

	public DexSync {
		TreeSet<Integer> allSeen = new TreeSet<>();
		for (int dex : seen) {
			if (GenOneIds.isDex(dex)) {
				allSeen.add(dex);
			}
		}
		TreeSet<Integer> allOwned = new TreeSet<>();
		for (int dex : owned) {
			if (GenOneIds.isDex(dex)) {
				allOwned.add(dex);
			}
		}
		allSeen.addAll(allOwned);
		seen = java.util.Collections.unmodifiableSortedSet(allSeen);
		owned = java.util.Collections.unmodifiableSortedSet(allOwned);
	}

	public static DexSync of(Collection<Integer> seen, Collection<Integer> owned) {
		return new DexSync(new TreeSet<>(seen), new TreeSet<>(owned));
	}

	public JsonObject toJson() {
		JsonObject data = new JsonObject();
		JsonArray seen = new JsonArray();
		this.seen.forEach(seen::add);
		JsonArray owned = new JsonArray();
		this.owned.forEach(owned::add);
		data.add("seen", seen);
		data.add("owned", owned);
		return data;
	}

	/** A {@code dex.sync} body (or a snapshot's {@code dex}): {@code seen} and {@code owned}, dex 1–151, nothing else. */
	public static DexSync read(JsonElement element) throws DataException {
		DataReader reader = DataReader.of(element, "").allowOnly(FIELDS);
		return of(numbers(reader, "seen"), numbers(reader, "owned"));
	}

	private static Set<Integer> numbers(DataReader reader, String field) throws DataException {
		Set<Integer> out = new TreeSet<>();
		int i = 0;
		for (JsonElement value : reader.array(field)) {
			out.add(DataReader.asInt(value, reader.path(field) + "/" + i++, 1, GenOneIds.MAX_DEX));
		}
		return out;
	}
}
