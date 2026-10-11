package dev.kantobridge.story;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.world.WorldIndex;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * One player's Wild Routes progress as their hidden game reports it (docs/specs/WILD_ROUTES_PLAN.md §4.2, §4.6;
 * PROTOCOL.md {@code routeProgress}): the item balls taken and how often each trainer was beaten, by zone. The
 * snapshot's {@code routes} replaces it all; {@code route.progress} replaces the zones it lists. Malformed parts are
 * skipped. Plain Java; one owning thread.
 */
public final class RouteProgress {
	private static final Pattern ZONE_ID = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
	private static final int MAX_WINS = 1_000_000;

	private final Map<String, Set<String>> taken = new HashMap<>();
	private final Map<String, Map<String, Integer>> wins = new HashMap<>();
	private long version;

	/** A snapshot's {@code routes} (null: none reported): everything is replaced. */
	public void replaceAll(@Nullable JsonObject routes) {
		this.taken.clear();
		this.wins.clear();
		this.update(routes);
	}

	/** {@code route.progress}'s {@code routes}: each zone listed is replaced whole. */
	public void update(@Nullable JsonObject routes) {
		this.version++;
		if (routes == null) {
			return;
		}
		for (Map.Entry<String, JsonElement> zone : routes.entrySet()) {
			if (!ZONE_ID.matcher(zone.getKey()).matches() || !zone.getValue().isJsonObject()) {
				continue;
			}
			JsonObject entry = zone.getValue().getAsJsonObject();
			Set<String> balls = new HashSet<>();
			JsonElement list = entry.get("taken");
			if (list != null && list.isJsonArray()) {
				for (JsonElement id : list.getAsJsonArray()) {
					if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isString() && WorldIndex.ZONE_OBJECT_ID.matcher(id.getAsString()).matches()) {
						balls.add(id.getAsString());
					}
				}
			}
			Map<String, Integer> beaten = new HashMap<>();
			JsonElement counts = entry.get("wins");
			if (counts != null && counts.isJsonObject()) {
				for (Map.Entry<String, JsonElement> win : counts.getAsJsonObject().entrySet()) {
					JsonElement n = win.getValue();
					if (WorldIndex.ZONE_OBJECT_ID.matcher(win.getKey()).matches() && n.isJsonPrimitive() && n.getAsJsonPrimitive().isNumber()
							&& n.getAsDouble() == Math.floor(n.getAsDouble()) && n.getAsDouble() >= 1 && n.getAsDouble() <= MAX_WINS) {
						beaten.put(win.getKey(), n.getAsInt());
					}
				}
			}
			this.taken.put(zone.getKey(), balls);
			this.wins.put(zone.getKey(), beaten);
		}
	}

	public boolean taken(String zone, String ball) {
		Set<String> balls = this.taken.get(zone);
		return balls != null && balls.contains(ball);
	}

	public Set<String> taken(String zone) {
		Set<String> balls = this.taken.get(zone);
		return balls == null ? Set.of() : Collections.unmodifiableSet(balls);
	}

	/** How often the player has beaten {@code trainer} of {@code zone} (0: never). */
	public int wins(String zone, String trainer) {
		Map<String, Integer> beaten = this.wins.get(zone);
		return beaten == null ? 0 : beaten.getOrDefault(trainer, 0);
	}

	/** Counts every change, so a reader can tell it needs to look again. */
	public long version() {
		return this.version;
	}
}
