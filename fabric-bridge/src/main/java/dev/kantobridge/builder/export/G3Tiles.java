package dev.kantobridge.builder.export;

import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * A Gen 3 tileset pair (protocol/export/g3tiles.schema.json; MG7 G3D): per metatile its four 8x8 quarters in the flattened
 * art (ids {@code page * 1024 + index} into the {@code g3image} pages {@code g3q.<page>}), its behaviour number and whether
 * part of it draws over sprites; the behaviours' MB_* names. ROM-derived: never logged.
 */
public final class G3Tiles {
	/** Quarters per page (32 x 32), the slot key's tile limit. */
	public static final int PAGE = 1024;
	private static final Pattern BEHAVIOR = Pattern.compile("^[A-Z0-9_]{1,64}$");

	private final String id;
	private final int[] mids;
	private final int[][] quarters;
	private final int[] beh;
	private final boolean[] over;
	private final Map<Integer, String> behaviors;
	private final @Nullable Set<Integer> land;
	private final Map<Integer, Integer> slotOf = new HashMap<>();

	private G3Tiles(String id, int[] mids, int[][] quarters, int[] beh, boolean[] over, Map<Integer, String> behaviors,
			@Nullable Set<Integer> land) {
		this.id = id;
		this.mids = mids;
		this.quarters = quarters;
		this.beh = beh;
		this.over = over;
		this.behaviors = Collections.unmodifiableMap(behaviors);
		this.land = land == null ? null : Collections.unmodifiableSet(land);
		for (int i = 0; i < mids.length; i++) {
			this.slotOf.put(mids[i], i);
		}
	}

	public String id() {
		return this.id;
	}

	/** The pair's metatile ids, in export order. */
	public int[] mids() {
		return this.mids.clone();
	}

	public boolean has(int mid) {
		return this.slotOf.containsKey(mid);
	}

	/** A metatile's four quarter ids (top left, top right, bottom left, bottom right), or null for one the pair lacks. */
	public int @Nullable [] quarters(int mid) {
		Integer slot = this.slotOf.get(mid);
		return slot == null ? null : this.quarters[slot].clone();
	}

	/** A metatile's behaviour number (0 for one the pair lacks). */
	public int behavior(int mid) {
		Integer slot = this.slotOf.get(mid);
		return slot == null ? 0 : this.beh[slot];
	}

	/** A metatile's behaviour name, or null. */
	public @Nullable String behaviorName(int mid) {
		return this.behaviors.get(this.behavior(mid));
	}

	/** True when the export says which behaviours roll land encounters ({@link #landEncounter}). */
	public boolean knowsLand() {
		return this.land != null;
	}

	/**
	 * True when a cell showing the metatile rolls land encounters, by the engine's own rule for its behaviour (the
	 * export's {@code land} list); false when it doesn't, or when the export doesn't say ({@link #knowsLand}).
	 */
	public boolean landEncounter(int mid) {
		return this.land != null && this.slotOf.containsKey(mid) && this.land.contains(this.behavior(mid));
	}

	/** True when part of the metatile is drawn over sprites (roofs, tree tops). */
	public boolean over(int mid) {
		Integer slot = this.slotOf.get(mid);
		return slot != null && this.over[slot];
	}

	static G3Tiles parse(DataReader r) throws DataException {
		String id = r.string("id", G3Map.PAIR);
		int[] mids = DataReader.intArray(r.array("mids"), r.path("mids"), 0, 1023);
		var quarterRows = r.array("quarters");
		int[] beh = DataReader.intArray(r.array("beh"), r.path("beh"), 0, 255);
		var overArray = r.array("over");
		if (quarterRows.size() != mids.length || beh.length != mids.length || overArray.size() != mids.length) {
			throw new DataException(r.path(), "per-metatile lists differ in length");
		}
		int[][] quarters = new int[mids.length][];
		boolean[] over = new boolean[mids.length];
		for (int i = 0; i < mids.length; i++) {
			var row = quarterRows.get(i);
			if (!row.isJsonArray()) {
				throw new DataException(r.path("quarters"), "not an array");
			}
			int[] q = DataReader.intArray(row.getAsJsonArray(), r.path("quarters"), 0, 1048575);
			if (q.length != 4) {
				throw new DataException(r.path("quarters"), "a metatile has four quarters");
			}
			quarters[i] = q;
			var o = overArray.get(i);
			if (!o.isJsonPrimitive() || !o.getAsJsonPrimitive().isBoolean()) {
				throw new DataException(r.path("over"), "not a boolean");
			}
			over[i] = o.getAsBoolean();
		}
		Map<Integer, String> behaviors = new TreeMap<>();
		DataReader b = r.object("behaviors");
		for (String key : b.json().keySet()) {
			int number;
			try {
				number = Integer.parseInt(key);
			} catch (NumberFormatException e) {
				throw new DataException(b.path(), "behaviour key");
			}
			behaviors.put(number, b.string(key, BEHAVIOR));
		}
		Set<Integer> land = null;
		if (r.has("land")) {
			land = new TreeSet<>();
			for (int number : DataReader.intArray(r.array("land"), r.path("land"), 0, 255)) {
				land.add(number);
			}
		}
		return new G3Tiles(id, mids, quarters, beh, over, behaviors, land);
	}
}
