package dev.kantobridge.client.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.world.DataReader;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * WORLD_SESSIONS_PLAN §2.1 (WS2): the player's recorded save choices for the world they are in, as the server's
 * {@code session.bind.regions} names them: per region, the slot their hidden game paired and the choice it was made with
 * ({@code current}, {@code copy}, {@code new}). WS3's join decision reads them: a recorded slot is bound and continued
 * without asking. Plain Java; client thread only.
 *
 * @param byRegion region id → its recorded choice
 */
public record RecordedChoices(Map<String, Choice> byRegion) {
	public static final RecordedChoices NONE = new RecordedChoices(Map.of());
	static final Set<String> SAVES = Set.of("current", "copy", "new");

	/**
	 * One region's recorded choice.
	 *
	 * @param slot the recorded slot, or null
	 * @param save the recorded choice, or null (a pairing from before WS2)
	 */
	public record Choice(@Nullable Integer slot, @Nullable String save) {
	}

	public RecordedChoices {
		byRegion = Collections.unmodifiableMap(new TreeMap<>(byRegion));
	}

	/** The choices in a {@code session.bind} body's {@code regions} (absent: none); malformed entries are left out. */
	public static RecordedChoices of(@Nullable JsonObject bind) {
		JsonObject regions = bind == null ? null : JsonFields.obj(bind, "regions");
		if (regions == null) {
			return NONE;
		}
		Map<String, Choice> out = new TreeMap<>();
		for (Map.Entry<String, JsonElement> entry : regions.entrySet()) {
			if (!DataReader.REGION_ID.matcher(entry.getKey()).matches() || !entry.getValue().isJsonObject()) {
				continue;
			}
			JsonObject one = entry.getValue().getAsJsonObject();
			long slot = JsonFields.integer(one, "slot", -1);
			String save = JsonFields.str(one, "save");
			out.put(entry.getKey(), new Choice(slot >= 0 && slot <= 255 ? (int) slot : null, save != null && SAVES.contains(save) ? save : null));
		}
		return new RecordedChoices(out);
	}

	/** A region's recorded choice, or null. */
	public @Nullable Choice forRegion(@Nullable String region) {
		return region == null ? null : this.byRegion.get(region);
	}
}
