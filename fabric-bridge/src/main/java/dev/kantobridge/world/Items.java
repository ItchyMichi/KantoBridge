package dev.kantobridge.world;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * The canonical item catalogue, {@code protocol/items.json} (docs/specs/MULTI_GAME_PLAN.md §4.9, MG6), bundled in the jar
 * beside the protocol catalog: every item the one trainer economy can hold. For each canonical id it holds the item's
 * class ({@code ball}, {@code medicine}, {@code battle}, {@code field}, {@code stone}, {@code held}, {@code valuable},
 * {@code tm}, {@code key}), the Cobblemon item the Minecraft mirror creates for it (the battle items only), the move a
 * machine teaches (TMs cross between games <b>by move</b>: Gen 1's TM01 and Gen 3's TM01 are different moves), and each
 * family's symbol for it (the hidden game's own item id, e.g. {@code POTION}, {@code TM_MEGA_PUNCH}). Items a game lacks
 * have no symbol for it; class {@code key} (key items, HMs, badges, story items) never crosses. Nothing else names an item
 * symbol or a Cobblemon item path itself.
 *
 * <p>The static methods read the bundled catalogue; {@link #parse} reads any catalogue document (tests). Ids and symbols
 * only, never game content.
 */
public final class Items {
	/** Where the build puts the catalogue inside the jar (build.gradle, beside the protocol catalog). */
	public static final String RESOURCE = "/kantobridge/protocol/items.json";
	public static final int VERSION = 1;
	/** A canonical item id (protocol/items.schema.json). */
	public static final Pattern ITEM_ID = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
	/** A game's item symbol (protocol defs {@code itemId}). */
	public static final Pattern SYMBOL = Pattern.compile("^[A-Z0-9_]{1,64}$");
	/** A Cobblemon item path (namespace {@code cobblemon}). */
	public static final Pattern COBBLEMON_PATH = Pattern.compile("^[a-z0-9_./-]{1,64}$");
	/** The classes, in the order a projection fills a bag: what a trainer reaches for first. */
	public static final List<String> CLASSES = List.of("ball", "medicine", "battle", "field", "stone", "held", "valuable", "tm", "key");
	/** The class that never crosses: key items, HMs, badges, story items. */
	public static final String KEY = "key";
	/** The class that crosses by move. */
	public static final String TM = "tm";
	/** The classes whose Cobblemon items the Minecraft mirror creates (the battle-usable items). */
	public static final Set<String> MIRRORED_CLASSES = Set.of("ball", "medicine", "battle");
	public static final int MAX_MOVE = 2000;
	private static final Set<String> ROOT_FIELDS = Set.of("version", "items");
	private static final Set<String> ITEM_FIELDS = Set.of("class", "cobblemon", "move", "symbols");

	private static volatile @Nullable Items bundled;

	/**
	 * One catalogue entry.
	 *
	 * @param clazz     the item's class ({@link #CLASSES})
	 * @param cobblemon the Cobblemon item path the mirror creates for it, or null (ledger only)
	 * @param move      tm/hm: the move taught, as its national (Showdown) move number; else null
	 * @param symbols   family → the game's item symbol (sorted)
	 */
	public record Item(String id, String clazz, @Nullable String cobblemon, @Nullable Integer move, Map<String, String> symbols) {
		public Item {
			symbols = Collections.unmodifiableMap(new TreeMap<>(symbols));
		}

		/** The item's symbol in a family's games, or null when they lack it. */
		public @Nullable String symbol(@Nullable String family) {
			return family == null ? null : this.symbols.get(family);
		}

		/** True unless the item is a key item (class {@link #KEY}). */
		public boolean crosses() {
			return !KEY.equals(this.clazz);
		}

		/** True when the Minecraft mirror creates Cobblemon items for it. */
		public boolean mirrored() {
			return this.cobblemon != null && MIRRORED_CLASSES.contains(this.clazz);
		}

		/** The class's place in {@link #CLASSES}. */
		public int classRank() {
			return CLASSES.indexOf(this.clazz);
		}
	}

	private final Map<String, Item> items;
	/** family → symbol → canonical id. */
	private final Map<String, Map<String, String>> canonical;

	private Items(Map<String, Item> items) {
		this.items = Collections.unmodifiableMap(new TreeMap<>(items));
		Map<String, Map<String, String>> reverse = new HashMap<>();
		for (Item item : this.items.values()) {
			for (Map.Entry<String, String> symbol : item.symbols().entrySet()) {
				reverse.computeIfAbsent(symbol.getKey(), key -> new HashMap<>()).put(symbol.getValue(), item.id());
			}
		}
		this.canonical = Collections.unmodifiableMap(reverse);
	}

	/** Parses a catalogue document, checking every field's shape (the schema's rules) and that no family names a symbol twice. */
	public static Items parse(String json) throws DataException {
		JsonObject root;
		try {
			root = ProtoJson.parseObject(json);
		} catch (ProtocolException e) {
			throw new DataException("", "invalid JSON (" + e.code() + ")");
		}
		DataReader r = DataReader.of(root, "").allowOnly(ROOT_FIELDS);
		if (r.integer("version") != VERSION) {
			throw new DataException(r.path("version"), "unsupported version");
		}
		DataReader list = r.object("items");
		Map<String, Item> out = new TreeMap<>();
		Map<String, Set<String>> seen = new HashMap<>();
		for (String id : list.json().keySet()) {
			if (!ITEM_ID.matcher(id).matches()) {
				throw new DataException(list.path(), "bad item id");
			}
			DataReader e = list.object(id).allowOnly(ITEM_FIELDS);
			String clazz = e.string("class");
			if (!CLASSES.contains(clazz)) {
				throw new DataException(e.path("class"), "unknown class");
			}
			String cobblemon = e.optionalString("cobblemon", COBBLEMON_PATH);
			Integer move = e.has("move") ? e.integer("move", 1, MAX_MOVE) : null;
			DataReader s = e.object("symbols");
			Map<String, String> symbols = new TreeMap<>();
			for (String family : s.json().keySet()) {
				if (!Games.FAMILIES.contains(family)) {
					throw new DataException(s.path(), "unknown family");
				}
				String symbol = s.string(family, SYMBOL);
				if (!seen.computeIfAbsent(family, key -> new java.util.HashSet<>()).add(symbol)) {
					throw new DataException(s.path(family), "duplicate symbol");
				}
				symbols.put(family, symbol);
			}
			out.put(id, new Item(id, clazz, cobblemon, move, symbols));
		}
		if (out.isEmpty()) {
			throw new DataException(r.path("items"), "no items");
		}
		return new Items(out);
	}

	/** The catalogue bundled in the mod jar. Missing or broken means a broken build, so this throws unchecked. */
	public static Items bundled() {
		Items items = bundled;
		if (items == null) {
			synchronized (Items.class) {
				items = bundled;
				if (items == null) {
					items = loadBundled();
					bundled = items;
				}
			}
		}
		return items;
	}

	private static Items loadBundled() {
		try (InputStream in = Items.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("item catalogue missing from the jar: " + RESOURCE);
			}
			return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (DataException e) {
			throw new IllegalStateException("item catalogue is invalid: " + e.getMessage(), e);
		}
	}

	// ---- this catalogue ----

	/** Every entry by id, sorted. */
	public Map<String, Item> entries() {
		return this.items;
	}

	/** An entry, or null for an id this catalogue doesn't know. */
	public @Nullable Item entry(@Nullable String id) {
		return id == null ? null : this.items.get(id);
	}

	/** The canonical id of a family's item symbol, or null when no item carries it for that family. */
	public @Nullable String canonical(@Nullable String family, @Nullable String symbol) {
		if (family == null || symbol == null) {
			return null;
		}
		Map<String, String> symbols = this.canonical.get(family);
		return symbols == null ? null : symbols.get(symbol);
	}

	/** A family's symbol for a canonical id, or null when its games lack the item. */
	public @Nullable String symbol(@Nullable String family, @Nullable String id) {
		Item item = this.entry(id);
		return item == null ? null : item.symbol(family);
	}

	/** The ids the Minecraft mirror creates Cobblemon items for, sorted. */
	public List<String> mirroredIds() {
		List<String> out = new ArrayList<>();
		for (Item item : this.items.values()) {
			if (item.mirrored()) {
				out.add(item.id());
			}
		}
		return out;
	}

	/** The ids the ledger may hold (every class but {@link #KEY}), sorted. */
	public List<String> crossingIds() {
		List<String> out = new ArrayList<>();
		for (Item item : this.items.values()) {
			if (item.crosses()) {
				out.add(item.id());
			}
		}
		return out;
	}

	/**
	 * The TM that teaches {@code move} in a family's games (a family's TM for the same move may be another canonical id:
	 * TMs cross by move), or null; with {@code family} null, any family's. Sorted ids, the first wins.
	 */
	public @Nullable String tmByMove(@Nullable String family, int move) {
		for (Item item : this.items.values()) {
			if (TM.equals(item.clazz()) && item.move() != null && item.move() == move && (family == null || item.symbol(family) != null)) {
				return item.id();
			}
		}
		return null;
	}

	// ---- the bundled catalogue ----

	/** True when the catalogue names the id. */
	public static boolean known(@Nullable String id) {
		return get(id) != null;
	}

	/** Every canonical id, sorted. */
	public static List<String> ids() {
		return new ArrayList<>(bundled().items.keySet());
	}

	/** The catalogue's entry for an id, or null. */
	public static @Nullable Item get(@Nullable String id) {
		return bundled().entry(id);
	}

	/** A family's symbol for a canonical id, or null when its games lack the item. */
	public static @Nullable String symbolOf(@Nullable String family, @Nullable String id) {
		return bundled().symbol(family, id);
	}

	/** The canonical id of a family's item symbol, or null. */
	public static @Nullable String canonicalOf(@Nullable String family, @Nullable String symbol) {
		return bundled().canonical(family, symbol);
	}

	/** The Cobblemon item path (namespace {@code cobblemon}) the mirror creates for an id, or null when it is never mirrored. */
	public static @Nullable String cobblemonPath(@Nullable String id) {
		Item item = get(id);
		return item == null ? null : item.cobblemon();
	}

	/** An id's class, or null. */
	public static @Nullable String clazz(@Nullable String id) {
		Item item = get(id);
		return item == null ? null : item.clazz();
	}

	/** The move a machine teaches, or null. */
	public static @Nullable Integer move(@Nullable String id) {
		Item item = get(id);
		return item == null ? null : item.move();
	}

	/** The ids the Minecraft mirror creates Cobblemon items for (the battle-usable items with a Cobblemon item), sorted. */
	public static List<String> mirrored() {
		return bundled().mirroredIds();
	}

	/** The ids the ledger may hold (every class but {@link #KEY}), sorted. */
	public static List<String> crossing() {
		return bundled().crossingIds();
	}

	/** {@link #tmByMove(String, int)} of the bundled catalogue. */
	public static @Nullable String byMove(@Nullable String family, int move) {
		return bundled().tmByMove(family, move);
	}
}
