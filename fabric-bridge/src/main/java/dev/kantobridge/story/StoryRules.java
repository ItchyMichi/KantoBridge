package dev.kantobridge.story;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

/**
 * One player's story cells as the hidden game reports them (docs/PROTOCOL.md "Story rules (M8)"): which cells of the
 * WorldIndex's story-cell table are closed for this player, and which tile cells show their closed art. Plain Java, fed
 * the bridge's messages in arrival order; the client keeps one for its own player ({@code ClientStoryView}), applies
 * {@link #cells()}'s active set to the player's {@link StoryState} and relays it.
 *
 * <ul>
 * <li><b>{@code object}</b> cells are closed while their object is visible. On the map of the live NPC list (the
 * hidden player's current map) a cell is closed while a live NPC stands on it; elsewhere, while the object's effective
 * visibility (the snapshot's {@code toggles}, then each {@code story.toggles}) or else its WorldIndex default
 * ({@code hidden}) says visible.</li>
 * <li><b>{@code tile}</b> cells show their closed state while the live block at their site is the cell's closing block
 * ({@code closed.block}, the site's {@code before} for the usual site). Live blocks are known for one map, the hidden
 * player's current one ({@code story.blocks}, the snapshot's {@code blocks}); every other map shows its map data
 * ({@code start}), so cut trees grow back once the player leaves. A closed cell is drawn, and active (collides) as its
 * {@code closed.pass} says: always when solid, unless surfing when water, never when only its art differs. An open
 * cell whose built state is water ({@code water}) is active unless the player surfs, like a water cell.</li>
 * <li><b>{@code boulder_area}</b> cells are closed where a live boulder stands (the NPC list's {@code boulder}s).</li>
 * <li><b>{@code water}</b> cells are never active: they close unless the player surfs ({@link StoryState#blocks}).</li>
 * </ul>
 * Surf and bike come from {@code player.state}; lit maps from the snapshot's {@code lit}, {@code story.lit} and
 * {@code player.state}'s {@code dark}/{@code lit} for the current map. Malformed parts of a message are skipped; logs
 * are the caller's (counts only).
 */
public final class StoryRules {
	/** A live NPC: the cell it stands on (or walks to) and its kind ({@code person}, {@code item}, {@code boulder}, …). */
	public record Npc(int x, int y, String kind) {
	}

	/**
	 * What the rules decide.
	 *
	 * @param active cells closed for the player ({@code story.state}'s {@code active})
	 * @param drawn  tile cells showing their closed art (client only)
	 */
	public record Cells(Set<String> active, Set<String> drawn) {
		public Cells {
			active = Collections.unmodifiableSet(new TreeSet<>(active));
			drawn = Collections.unmodifiableSet(new TreeSet<>(drawn));
		}
	}

	private static final int MAX_COORD = 4096;

	private @Nullable WorldIndex index;
	private final List<StoryCellTable.Entry> objects = new ArrayList<>();
	private final List<StoryCellTable.Entry> tiles = new ArrayList<>();
	private final List<StoryCellTable.Entry> boulders = new ArrayList<>();
	/** Effective object visibility that differs from the map defaults: map → object → visible. */
	private final Map<String, Map<String, Boolean>> toggles = new HashMap<>();
	/** The map whose live blocks are known, and those blocks by {@code by * 256 + bx}. */
	private @Nullable String blocksMap;
	private final Map<Integer, Integer> liveBlocks = new HashMap<>();
	private @Nullable String currentMap;
	/** The live NPC list's map, and the cells its NPCs (and its boulders) stand on, as {@code x * 65536 + y}. */
	private @Nullable String npcMap;
	private final Set<Long> npcCells = new HashSet<>();
	private final Set<Long> boulderCells = new HashSet<>();
	private boolean surf;
	private boolean bike;
	private final Set<String> lit = new TreeSet<>();
	private boolean dirty = true;
	private Cells cells = new Cells(Set.of(), Set.of());

	// ---- the world ----

	/** The world's WorldIndex (null outside Kanto worlds: nothing is closed then). */
	public void setIndex(@Nullable WorldIndex index) {
		if (index == this.index) {
			return;
		}
		this.index = index;
		this.objects.clear();
		this.tiles.clear();
		this.boulders.clear();
		if (index != null) {
			for (StoryCellTable.Entry entry : index.storyCells().entries()) {
				switch (entry.type()) {
					case OBJECT -> this.objects.add(entry);
					case TILE -> this.tiles.add(entry);
					case BOULDER_AREA -> this.boulders.add(entry);
					case WATER -> {
					}
				}
			}
		}
		this.dirty = true;
	}

	public @Nullable WorldIndex index() {
		return this.index;
	}

	// ---- the hidden game's reports ----

	/** A {@code snapshot}: everything is replaced. */
	public void onSnapshot(JsonObject data) {
		JsonObject player = object(data, "player");
		String map = player == null ? null : mapId(player, "map");
		if (map != null) {
			this.currentMap = map;
		}
		this.toggles.clear();
		JsonObject toggles = object(data, "toggles");
		if (toggles != null) {
			for (Map.Entry<String, JsonElement> mapEntry : toggles.entrySet()) {
				if (!DataReader.MAP_ID.matcher(mapEntry.getKey()).matches() || !mapEntry.getValue().isJsonObject()) {
					continue;
				}
				for (Map.Entry<String, JsonElement> obj : mapEntry.getValue().getAsJsonObject().entrySet()) {
					Boolean visible = bool(obj.getValue());
					if (visible != null && DataReader.SYMBOL.matcher(obj.getKey()).matches()) {
						this.toggles.computeIfAbsent(mapEntry.getKey(), k -> new HashMap<>()).put(obj.getKey(), visible);
					}
				}
			}
		}
		// The snapshot's blocks are the current map's live blocks that differ from the map data.
		this.blocksMap = this.currentMap;
		this.liveBlocks.clear();
		JsonElement blocks = data.get("blocks");
		if (blocks != null && blocks.isJsonArray()) {
			for (JsonElement item : blocks.getAsJsonArray()) {
				if (item.isJsonObject() && this.currentMap != null && this.currentMap.equals(mapId(item.getAsJsonObject(), "map"))) {
					this.putBlock(item.getAsJsonObject());
				}
			}
		}
		this.lit.clear();
		JsonElement lit = data.get("lit");
		if (lit != null && lit.isJsonArray()) {
			for (JsonElement item : lit.getAsJsonArray()) {
				if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() && DataReader.MAP_ID.matcher(item.getAsString()).matches()) {
					this.lit.add(item.getAsString());
				}
			}
		}
		JsonObject state = object(data, "state");
		if (state != null) {
			this.onPlayerState(state);
		}
		JsonObject npcs = object(data, "npcs");
		if (npcs != null) {
			this.setNpcs(mapId(npcs, "map"), npcList(npcs.get("list")));
		}
		this.dirty = true;
	}

	/** {@code player.state} (or a snapshot's {@code state}): surf, bike, and the current dark map's Flash. */
	public void onPlayerState(JsonObject state) {
		Boolean surf = bool(state.get("surf"));
		Boolean bike = bool(state.get("bike"));
		if (surf != null && surf != this.surf) {
			this.surf = surf;
			// Tile cells whose state is water depend on it.
			this.dirty = true;
		}
		if (bike != null) {
			this.bike = bike;
		}
		Boolean dark = bool(state.get("dark"));
		Boolean lit = bool(state.get("lit"));
		if (Boolean.TRUE.equals(dark) && lit != null && this.currentMap != null) {
			if (lit) {
				this.lit.add(this.currentMap);
			} else {
				this.lit.remove(this.currentMap);
			}
		}
	}

	/** The hidden player is on {@code map} ({@code player.pos}, a warp's arrival): other maps show their map data again. */
	public void onPlayerMap(@Nullable String map) {
		if (map == null || !DataReader.MAP_ID.matcher(map).matches()) {
			return;
		}
		this.currentMap = map;
		if (!map.equals(this.blocksMap)) {
			this.blocksMap = map;
			if (!this.liveBlocks.isEmpty()) {
				this.liveBlocks.clear();
				this.dirty = true;
			}
		}
	}

	/** {@code story.toggles{map, obj, visible}}. */
	public void onToggles(JsonObject data) {
		String map = mapId(data, "map");
		String obj = str(data, "obj");
		Boolean visible = bool(data.get("visible"));
		if (map == null || obj == null || !DataReader.SYMBOL.matcher(obj).matches() || visible == null) {
			return;
		}
		Boolean before = this.toggles.computeIfAbsent(map, k -> new HashMap<>()).put(obj, visible);
		if (!visible.equals(before)) {
			this.dirty = true;
		}
	}

	/** {@code story.blocks{map, changes}}: live blocks of the hidden player's current map. */
	public void onBlocks(JsonObject data) {
		String map = mapId(data, "map");
		JsonElement changes = data.get("changes");
		if (map == null || changes == null || !changes.isJsonArray()) {
			return;
		}
		if (!map.equals(this.blocksMap)) {
			this.blocksMap = map;
			this.liveBlocks.clear();
		}
		for (JsonElement item : changes.getAsJsonArray()) {
			if (item.isJsonObject()) {
				this.putBlock(item.getAsJsonObject());
			}
		}
		this.dirty = true;
	}

	/** {@code story.lit{map, lit}}. */
	public void onLit(JsonObject data) {
		String map = mapId(data, "map");
		Boolean lit = bool(data.get("lit"));
		if (map == null || lit == null) {
			return;
		}
		if (lit) {
			this.lit.add(map);
		} else {
			this.lit.remove(map);
		}
	}

	/** The live NPC list of {@code map} (null: none known). */
	public void setNpcs(@Nullable String map, Collection<Npc> npcs) {
		Set<Long> cells = new HashSet<>();
		Set<Long> boulders = new HashSet<>();
		for (Npc npc : npcs) {
			cells.add(pack(npc.x(), npc.y()));
			if ("boulder".equals(npc.kind())) {
				boulders.add(pack(npc.x(), npc.y()));
			}
		}
		if (Objects.equals(map, this.npcMap) && cells.equals(this.npcCells) && boulders.equals(this.boulderCells)) {
			return;
		}
		this.npcMap = map;
		this.npcCells.clear();
		this.npcCells.addAll(cells);
		this.boulderCells.clear();
		this.boulderCells.addAll(boulders);
		this.dirty = true;
	}

	// ---- views ----

	public boolean surf() {
		return this.surf;
	}

	public boolean bike() {
		return this.bike;
	}

	/** Dark maps lit by Flash. */
	public Set<String> lit() {
		return Collections.unmodifiableSet(this.lit);
	}

	public @Nullable String currentMap() {
		return this.currentMap;
	}

	/** The active and drawn cells, recomputed when something they depend on changed. */
	public Cells cells() {
		if (this.dirty) {
			this.cells = this.compute();
			this.dirty = false;
		}
		return this.cells;
	}

	private Cells compute() {
		Set<String> active = new LinkedHashSet<>();
		Set<String> drawn = new LinkedHashSet<>();
		for (StoryCellTable.Entry entry : this.objects) {
			if (this.objectClosed(entry)) {
				active.add(entry.key());
			}
		}
		for (StoryCellTable.Entry entry : this.tiles) {
			StoryCellTable.Swap swap = entry.swap();
			if (swap == null) {
				continue;
			}
			StoryCellTable.Closed closed = entry.closed();
			int closing = closed != null ? closed.block() : swap.before();
			int start = entry.start() != null ? entry.start() : swap.before();
			Integer live = entry.cell().map().equals(this.blocksMap) ? this.liveBlocks.get(swap.by() * 256 + swap.bx()) : null;
			boolean shut = (live != null ? live : start) == closing;
			boolean collides;
			if (shut) {
				drawn.add(entry.key());
				StoryCellTable.Pass pass = closed != null ? closed.pass() : StoryCellTable.Pass.SOLID;
				collides = pass == StoryCellTable.Pass.SOLID || (pass == StoryCellTable.Pass.WATER && !this.surf);
			} else {
				collides = entry.water() && !this.surf;
			}
			if (collides) {
				active.add(entry.key());
			}
		}
		for (StoryCellTable.Entry entry : this.boulders) {
			if (entry.cell().map().equals(this.npcMap) && this.boulderCells.contains(pack(entry.cell().x(), entry.cell().y()))) {
				active.add(entry.key());
			}
		}
		return new Cells(active, drawn);
	}

	private boolean objectClosed(StoryCellTable.Entry entry) {
		if (entry.cell().map().equals(this.npcMap)) {
			// The live list decides on its map: whoever stands there now.
			return this.npcCells.contains(pack(entry.cell().x(), entry.cell().y()));
		}
		Map<String, Boolean> map = this.toggles.get(entry.cell().map());
		Boolean visible = map == null || entry.obj() == null ? null : map.get(entry.obj());
		return visible != null ? visible : !entry.hidden();
	}

	// ---- helpers ----

	private void putBlock(JsonObject change) {
		long bx = integer(change, "bx");
		long by = integer(change, "by");
		long block = integer(change, "block");
		if (bx >= 0 && bx <= 255 && by >= 0 && by <= 255 && block >= 0 && block <= 255) {
			this.liveBlocks.put((int) (by * 256 + bx), (int) block);
		}
	}

	private static List<Npc> npcList(@Nullable JsonElement list) {
		List<Npc> out = new ArrayList<>();
		if (list == null || !list.isJsonArray()) {
			return out;
		}
		JsonArray array = list.getAsJsonArray();
		for (JsonElement item : array) {
			if (!item.isJsonObject()) {
				continue;
			}
			JsonObject npc = item.getAsJsonObject();
			long x = integer(npc, "x");
			long y = integer(npc, "y");
			String kind = str(npc, "kind");
			if (Math.abs(x) <= MAX_COORD && Math.abs(y) <= MAX_COORD && kind != null) {
				out.add(new Npc((int) x, (int) y, kind));
			}
		}
		return out;
	}

	private static long pack(int x, int y) {
		return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
	}

	private static @Nullable JsonObject object(JsonObject data, String key) {
		JsonElement value = data.get(key);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
	}

	private static @Nullable String str(JsonObject data, String key) {
		JsonElement value = data.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}

	private static @Nullable String mapId(JsonObject data, String key) {
		String value = str(data, key);
		return value != null && DataReader.MAP_ID.matcher(value).matches() ? value : null;
	}

	private static @Nullable Boolean bool(@Nullable JsonElement value) {
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : null;
	}

	/** An integer field, or {@link Long#MIN_VALUE} when it is missing or not an integer. */
	private static long integer(JsonObject data, String key) {
		JsonElement value = data.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			return Long.MIN_VALUE;
		}
		double number = value.getAsDouble();
		return number == Math.rint(number) && Math.abs(number) < 1.0E9 ? (long) number : Long.MIN_VALUE;
	}
}
