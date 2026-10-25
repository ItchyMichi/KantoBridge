package dev.kantobridge.client.puppet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.world.Dir;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * The hidden game's NPCs as puppets (docs/PLAN.md §2.4 flow 5, §4.3 {@code puppet/}; PROTOCOL.md "NPCs"), kept in step
 * with {@code npc.set} (the current map's full list: puppets are rebuilt from it), {@code npc.add}, {@code npc.remove},
 * {@code npc.move}, {@code npc.face} and {@code emote}, and the snapshot's {@code npcs}. Plain Java on the client
 * thread; {@code PuppetRuntime} turns puppets into client-only entities through the {@link Listener}.
 *
 * <p>Each puppet gets a negative entity id, counting down from {@link #FIRST_ID}, which it keeps for its life (also
 * when it is re-added to a new world): server entity ids are positive, so a puppet never collides with one. Messages are
 * checked like the schemas do; a malformed one changes nothing and is counted by code.
 *
 * <p>Wild Routes (WR5): a second list, the zone puppets (a zone's trainers and balls, from the WorldIndex), set with
 * {@link #setZone}. Their ids start with {@link #ZONE_PREFIX}, which no NPC id can, and the hidden game's lists and a
 * dropped link leave them alone.
 */
public final class PuppetManager {
	/** The first puppet's entity id; later ones count down. */
	public static final int FIRST_ID = -1_000_000;
	/** Zone puppets' ids start with this. */
	public static final String ZONE_PREFIX = "zone:";

	/** A zone puppet: a Wild Routes trainer or ball at its post, in its route map's grid. */
	public record ZoneSpec(String id, String map, String sprite, String kind, int x, int y, Dir facing) {
	}

	private static final Pattern NPC_ID = Pattern.compile("^[A-Za-z0-9_]{1,96}$");
	private static final Pattern SYMBOL = Pattern.compile("^[A-Z0-9_]{1,64}$");
	private static final Set<String> KINDS = Set.of("person", "item", "boulder", "static", "trainer");
	private static final Set<String> BUBBLES = Set.of("!", "?", "happy");
	private static final int MAX_COORD = 4096;
	private static final int MAX_FRAMES = 3600;

	/** Puppets coming and going. */
	public interface Listener {
		void added(Puppet puppet);

		void removed(Puppet puppet);
	}

	/** Counters for the debug overlay. */
	public static final class Stats {
		public long sets;
		public long adds;
		public long removes;
		public long moves;
		public long faces;
		public long emotes;
		public long stale;
		public long refused;
	}

	private final Listener listener;
	private final Map<String, Puppet> puppets = new LinkedHashMap<>();
	private final Map<Integer, Puppet> byEntity = new HashMap<>();
	private final Map<String, Long> warnings = new TreeMap<>();
	private final Stats stats = new Stats();
	private int nextId = FIRST_ID;
	private @Nullable String map;
	private @Nullable String playerBubble;
	private int playerBubbleFrames;

	public PuppetManager(Listener listener) {
		this.listener = listener;
	}

	// ---- messages ----

	/** {@code npc.set}, or a snapshot's {@code npcs}: the map's full list. */
	public void onSet(@Nullable JsonObject data) {
		String setMap = data == null ? null : JsonFields.str(data, "map");
		JsonElement list = data == null ? null : data.get("list");
		if (setMap == null || !SYMBOL.matcher(setMap).matches() || list == null || !list.isJsonArray() || list.getAsJsonArray().size() > 64) {
			this.refuse("set");
			return;
		}
		List<JsonObject> entries = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (JsonElement element : list.getAsJsonArray()) {
			if (!element.isJsonObject() || !valid(element.getAsJsonObject()) || !ids.add(JsonFields.str(element.getAsJsonObject(), "id"))) {
				this.refuse("set");
				return;
			}
			entries.add(element.getAsJsonObject());
		}
		this.stats.sets++;
		this.map = setMap;
		for (Puppet puppet : List.copyOf(this.puppets.values())) {
			if (!ids.contains(puppet.id()) && !isZone(puppet)) {
				this.remove(puppet);
			}
		}
		for (JsonObject entry : entries) {
			this.put(setMap, entry);
		}
	}

	/** {@code npc.add}: one NPC appeared (or changed). */
	public void onAdd(JsonObject data) {
		String addMap = JsonFields.str(data, "map");
		JsonObject npc = data.deepCopy();
		npc.remove("map");
		if (addMap == null || !SYMBOL.matcher(addMap).matches() || !valid(npc)) {
			this.refuse("add");
			return;
		}
		this.stats.adds++;
		this.put(addMap, npc);
	}

	public void onRemove(JsonObject data) {
		Puppet puppet = this.byIdField(data);
		if (puppet != null) {
			this.stats.removes++;
			this.remove(puppet);
		}
	}

	public void onMove(JsonObject data) {
		Puppet puppet = this.byIdField(data);
		Integer x = coord(data, "x");
		Integer y = coord(data, "y");
		Dir facing = Dir.byId(JsonFields.str(data, "facing"));
		long frames = JsonFields.integer(data, "frames", -1);
		if (puppet == null) {
			return;
		}
		if (x == null || y == null || facing == null || frames < 0 || frames > MAX_FRAMES) {
			this.refuse("move");
			return;
		}
		this.stats.moves++;
		puppet.move(x, y, facing, (int) frames);
		if (data.has("elev")) {
			puppet.setElev(elev(data));
		}
	}

	public void onFace(JsonObject data) {
		Puppet puppet = this.byIdField(data);
		Dir facing = Dir.byId(JsonFields.str(data, "facing"));
		if (puppet == null) {
			return;
		}
		if (facing == null) {
			this.refuse("face");
			return;
		}
		this.stats.faces++;
		puppet.face(facing);
	}

	/** {@code emote}: a bubble over the player or an NPC ({@code bubble} null takes it away). */
	public void onEmote(JsonObject data) {
		String target = JsonFields.str(data, "target");
		JsonElement bubbleField = data.get("bubble");
		String bubble = bubbleField == null || bubbleField.isJsonNull() ? null : JsonFields.str(data, "bubble");
		long frames = JsonFields.integer(data, "frames", -1);
		boolean bubbleOk = bubbleField != null && (bubbleField.isJsonNull() || (bubble != null && BUBBLES.contains(bubble)));
		if (target == null || !NPC_ID.matcher(target).matches() || !bubbleOk || frames < 0 || frames > MAX_FRAMES) {
			this.refuse("emote");
			return;
		}
		this.stats.emotes++;
		if (target.equals("player")) {
			this.playerBubble = bubble;
			this.playerBubbleFrames = bubble == null ? 0 : (frames > 0 ? (int) frames : Puppet.DEFAULT_EMOTE_FRAMES);
			return;
		}
		Puppet puppet = this.puppets.get(target);
		if (puppet == null) {
			this.stats.stale++;
			return;
		}
		puppet.emote(bubble, (int) frames);
	}

	/** Every puppet of the hidden game goes (the link dropped; a snapshot will bring the current ones back). */
	public void clear() {
		for (Puppet puppet : List.copyOf(this.puppets.values())) {
			if (!isZone(puppet)) {
				this.remove(puppet);
			}
		}
		this.map = null;
		this.playerBubble = null;
		this.playerBubbleFrames = 0;
	}

	/** Advances the player's emote; puppets advance with their entities. */
	public void advancePlayer(int frames) {
		if (this.playerBubbleFrames > 0) {
			this.playerBubbleFrames -= frames;
			if (this.playerBubbleFrames <= 0) {
				this.playerBubble = null;
				this.playerBubbleFrames = 0;
			}
		}
	}

	// ---- Wild Routes zone puppets ----

	/**
	 * The zone puppets now: those not listed go, new ones appear at their posts, and ones still listed stay as they are
	 * (a trainer walking up keeps walking).
	 */
	public void setZone(List<ZoneSpec> specs) {
		Set<String> ids = new HashSet<>();
		for (ZoneSpec spec : specs) {
			ids.add(spec.id());
		}
		for (Puppet puppet : List.copyOf(this.puppets.values())) {
			if (isZone(puppet) && !ids.contains(puppet.id())) {
				this.remove(puppet);
			}
		}
		for (ZoneSpec spec : specs) {
			if (!spec.id().startsWith(ZONE_PREFIX) || this.puppets.containsKey(spec.id())) {
				continue;
			}
			Puppet puppet = new Puppet(spec.id(), this.nextId--, spec.map(), spec.sprite(), spec.kind(), spec.x(), spec.y(), spec.facing(), false);
			this.puppets.put(spec.id(), puppet);
			this.byEntity.put(puppet.entityId(), puppet);
			this.listener.added(puppet);
		}
	}

	/** A zone puppet walks (or, with 0 frames, jumps) to a cell of its route map's grid. */
	public void zoneMove(String id, int x, int y, Dir facing, int frames) {
		Puppet puppet = this.puppets.get(id);
		if (puppet != null && isZone(puppet)) {
			puppet.move(x, y, facing, Math.max(0, Math.min(frames, MAX_FRAMES)));
		}
	}

	public void zoneFace(String id, Dir facing) {
		Puppet puppet = this.puppets.get(id);
		if (puppet != null && isZone(puppet)) {
			puppet.face(facing);
		}
	}

	/** A bubble over a zone puppet ({@code null} takes it away). */
	public void zoneEmote(String id, @Nullable String bubble, int frames) {
		Puppet puppet = this.puppets.get(id);
		if (puppet != null && isZone(puppet) && (bubble == null || BUBBLES.contains(bubble))) {
			puppet.emote(bubble, Math.max(0, Math.min(frames, MAX_FRAMES)));
		}
	}

	public static boolean isZone(Puppet puppet) {
		return puppet.id().startsWith(ZONE_PREFIX);
	}

	// ---- views ----

	public @Nullable Puppet get(String id) {
		return this.puppets.get(id);
	}

	public @Nullable Puppet byEntityId(int entityId) {
		return this.byEntity.get(entityId);
	}

	public List<Puppet> all() {
		return List.copyOf(this.puppets.values());
	}

	public int size() {
		return this.puppets.size();
	}

	/** The map the last full list named. */
	public @Nullable String map() {
		return this.map;
	}

	public @Nullable String playerBubble() {
		return this.playerBubble;
	}

	public Stats stats() {
		return this.stats;
	}

	/** Counted problems by code ({@code refused.move}, …). */
	public Map<String, Long> warnings() {
		return Map.copyOf(this.warnings);
	}

	// ---- helpers ----

	private void put(String npcMap, JsonObject npc) {
		String id = JsonFields.str(npc, "id");
		String sprite = JsonFields.str(npc, "sprite");
		String kind = JsonFields.str(npc, "kind");
		int x = coord(npc, "x");
		int y = coord(npc, "y");
		Dir facing = Dir.byId(JsonFields.str(npc, "facing"));
		boolean moving = JsonFields.bool(npc, "moving", false);
		int elev = elev(npc);
		Puppet puppet = this.puppets.get(id);
		if (puppet != null) {
			puppet.reset(npcMap, sprite, kind, x, y, facing, moving);
			puppet.setElev(elev);
			return;
		}
		puppet = new Puppet(id, this.nextId--, npcMap, sprite, kind, x, y, facing, moving);
		puppet.setElev(elev);
		this.puppets.put(id, puppet);
		this.byEntity.put(puppet.entityId(), puppet);
		this.listener.added(puppet);
	}

	private void remove(Puppet puppet) {
		this.puppets.remove(puppet.id());
		this.byEntity.remove(puppet.entityId());
		this.listener.removed(puppet);
	}

	private @Nullable Puppet byIdField(JsonObject data) {
		String id = JsonFields.str(data, "id");
		if (id == null || !NPC_ID.matcher(id).matches()) {
			this.refuse("id");
			return null;
		}
		Puppet puppet = this.puppets.get(id);
		if (puppet == null) {
			this.stats.stale++;
		}
		return puppet;
	}

	/** One NPC entry as the schema's {@code npc}: id, sprite, kind, x, y, facing, moving, nothing else. */
	private static boolean valid(JsonObject npc) {
		for (String key : npc.keySet()) {
			if (!Set.of("id", "sprite", "kind", "x", "y", "facing", "moving", "elev").contains(key)) {
				return false;
			}
		}
		String id = JsonFields.str(npc, "id");
		String sprite = JsonFields.str(npc, "sprite");
		String kind = JsonFields.str(npc, "kind");
		JsonElement moving = npc.get("moving");
		return id != null && NPC_ID.matcher(id).matches() && sprite != null && SYMBOL.matcher(sprite).matches() && kind != null
				&& KINDS.contains(kind) && coord(npc, "x") != null && coord(npc, "y") != null
				&& Dir.byId(JsonFields.str(npc, "facing")) != null && moving != null && moving.isJsonPrimitive()
				&& moving.getAsJsonPrimitive().isBoolean();
	}

	/** An object's elevation (D7: the floor of a two-floor cell it stands on), -1 when absent or out of range. */
	private static int elev(JsonObject data) {
		long value = JsonFields.integer(data, "elev", -1);
		return value >= 0 && value <= 15 ? (int) value : -1;
	}

	private static @Nullable Integer coord(JsonObject data, String key) {
		long value = JsonFields.integer(data, key, Long.MAX_VALUE);
		return value >= -MAX_COORD && value <= MAX_COORD ? (int) value : null;
	}

	private void refuse(String what) {
		this.stats.refused++;
		this.warnings.merge("refused." + what, 1L, Long::sum);
	}
}
