package dev.kantobridge.builder.export;

import com.google.gson.JsonArray;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * The smaller Gen 3 export resources (MG7 G3D; protocol/export/g3*.schema.json): the layout, encounters, names, image pages
 * and sprite sheets. ROM-derived: never logged.
 */
public final class G3Data {
	private static final Pattern SYM = Pattern.compile("^[A-Z0-9_]{1,64}$");

	private G3Data() {
	}

	/**
	 * The surface maps' cell origins ({@code g3layout/<region>}), relative to the root, and (MG8) the room a new game
	 * starts in, or null when the export doesn't say.
	 */
	public record Layout(String root, Map<String, Rect> maps, @Nullable Start start) {
		public Layout {
			maps = Collections.unmodifiableMap(new TreeMap<>(maps));
		}

		public Layout(String root, Map<String, Rect> maps) {
			this(root, maps, null);
		}

		static Layout parse(DataReader r) throws DataException {
			if (!"cells".equals(r.string("unit"))) {
				throw new DataException(r.path("unit"), "the unit is cells");
			}
			Map<String, Rect> maps = new TreeMap<>();
			DataReader m = r.object("maps");
			for (String id : m.json().keySet()) {
				if (!DataReader.MAP_ID.matcher(id).matches()) {
					throw new DataException(m.path(), "bad map id");
				}
				DataReader rect = m.object(id);
				maps.put(id, new Rect(rect.integer("x", -16384, 16384), rect.integer("y", -16384, 16384), rect.integer("w", 1, 1023),
						rect.integer("h", 1, 1023)));
			}
			DataReader start = r.optionalObject("start");
			return new Layout(r.string("root", DataReader.MAP_ID), maps, start == null ? null
					: new Start(start.string("map", DataReader.MAP_ID), start.integer("x", 0, 1023), start.integer("y", 0, 1023)));
		}
	}

	/** The room a new game starts in and the cell there (MG8: the moving truck on RSE). */
	public record Start(String map, int x, int y) {
	}

	/** Origin and size in cells. */
	public record Rect(int x, int y, int w, int h) {
	}

	/** One encounter slot: a national dex number and a level range. */
	public record Slot(int dex, int min, int max) {
	}

	/** One table: its rate and slots. */
	public record Table(int rate, List<Slot> slots) {
	}

	/** A map's tables (any may be null): land (12 slots), water (5), rock (5), fish (10). */
	public record MapTables(@Nullable Table land, @Nullable Table water, @Nullable Table rock, @Nullable Table fish) {
	}

	/**
	 * The wild encounter tables per map ({@code g3encounters/all}) and the land slots' chances in percent as the engine's
	 * roll uses them ({@code landWeights}, 12 numbers; null when the export doesn't say).
	 */
	public record Encounters(Map<String, MapTables> maps, int @Nullable [] landWeights) {
		public Encounters {
			maps = Collections.unmodifiableMap(new TreeMap<>(maps));
			landWeights = landWeights == null ? null : landWeights.clone();
		}

		/** The chance of land slot {@code slot} in percent, or null when the export doesn't say. */
		public @Nullable Integer landWeight(int slot) {
			return this.landWeights == null || slot < 0 || slot >= this.landWeights.length ? null : this.landWeights[slot];
		}

		static Encounters parse(DataReader r) throws DataException {
			Map<String, MapTables> out = new TreeMap<>();
			DataReader m = r.object("maps");
			for (String id : m.json().keySet()) {
				if (!DataReader.MAP_ID.matcher(id).matches()) {
					throw new DataException(m.path(), "bad map id");
				}
				DataReader t = m.object(id);
				out.put(id, new MapTables(table(t, "land", 12), table(t, "water", 5), table(t, "rock", 5), table(t, "fish", 10)));
			}
			int[] weights = null;
			if (r.has("landWeights")) {
				weights = DataReader.intArray(r.array("landWeights"), r.path("landWeights"), 0, 255);
				if (weights.length != 12) {
					throw new DataException(r.path("landWeights"), "12 land slots");
				}
			}
			return new Encounters(out, weights);
		}

		private static @Nullable Table table(DataReader t, String name, int maxSlots) throws DataException {
			if (!t.has(name)) {
				return null;
			}
			DataReader tr = t.object(name);
			List<Slot> slots = new ArrayList<>();
			for (DataReader s : DataReader.objects(tr.array("slots"), tr.path("slots"))) {
				int min = s.integer("min", 1, 100);
				int max = s.integer("max", 1, 100);
				slots.add(new Slot(s.integer("dex", 1, 2000), Math.min(min, max), Math.max(min, max)));
			}
			if (slots.isEmpty() || slots.size() > maxSlots) {
				throw new DataException(tr.path("slots"), "slot count");
			}
			return new Table(tr.integer("rate", 0, 255), List.copyOf(slots));
		}
	}

	/** The engine's constant names ({@code g3names/all}): species to national dex, moves and items to numbers. */
	public record Names(Map<String, Integer> species, Map<String, Integer> moves, Map<String, Integer> items) {
		static Names parse(DataReader r) throws DataException {
			return new Names(names(r.object("species")), names(r.object("moves")), names(r.object("items")));
		}

		private static Map<String, Integer> names(DataReader o) throws DataException {
			Map<String, Integer> out = new TreeMap<>();
			for (String key : o.json().keySet()) {
				if (!SYM.matcher(key).matches()) {
					throw new DataException(o.path(), "bad name");
				}
				out.put(key, o.integer(key, 1, 2000));
			}
			return Collections.unmodifiableMap(out);
		}
	}

	/** An RGBA PNG ({@code g3image}: a quarter page, 256x256). */
	public record Image(String id, int w, int h, byte[] png) {
		static Image parse(DataReader r) throws DataException {
			return new Image(r.string("id"), r.integer("w", 1, 4096), r.integer("h", 1, 4096), decodePng(r));
		}
	}

	/**
	 * An object-event sheet ({@code g3sprite}): a vertical strip of {@code frames} frames of {@code w x h}; {@code gid} -1 for
	 * {@link #SURF_BLOB} (the follow-up's item 8: the field effect under a surfing player, frames facing down, up and left).
	 */
	public record Sprite(String id, int gid, int w, int h, int frames, byte[] png) {
		/** The surf blob's sheet. */
		public static final String SURF_BLOB = "SURF_BLOB";

		static Sprite parse(DataReader r) throws DataException {
			return new Sprite(r.string("id", SYM), r.has("gid") ? r.integer("gid", 0, 1023) : -1, r.integer("w", 1, 256),
					r.integer("h", 1, 256), r.integer("frames", 1, 512), decodePng(r));
		}
	}

	/**
	 * The animated quarters ({@code g3anims}, the follow-up's item 8): per animated quarter id its frames (quarter ids in the
	 * {@code g3image} pages) and its loop, {@code [frame index, GBA frames]} in order.
	 */
	public record Anims(Map<Integer, Anim> byQuarter) {
		/** One animated quarter's frames and loop. */
		public record Anim(int[] frames, int[][] loop) {
		}

		static Anims parse(DataReader r) throws DataException {
			if (!"all".equals(r.string("id"))) {
				throw new DataException(r.path("id"), "not all");
			}
			Map<Integer, Anim> out = new TreeMap<>();
			for (DataReader a : DataReader.objects(r.array("anims"), r.path("anims"))) {
				int q = a.integer("q", 0, 1048575);
				int[] frames = DataReader.intArray(a.array("f"), a.path("f"), 0, 1048575);
				if (frames.length < 2) {
					throw new DataException(a.path("f"), "fewer than two frames");
				}
				JsonArray steps = a.array("s");
				int[][] loop = new int[steps.size()][];
				for (int i = 0; i < steps.size(); i++) {
					int[] step = DataReader.intArray(steps.get(i).isJsonArray() ? steps.get(i).getAsJsonArray() : new JsonArray(),
							a.path("s") + "/" + i, 0, 4096);
					if (step.length != 2 || step[0] >= frames.length || step[1] < 1) {
						throw new DataException(a.path("s") + "/" + i, "not [frame, GBA frames]");
					}
					loop[i] = step;
				}
				if (loop.length < 2) {
					throw new DataException(a.path("s"), "fewer than two steps");
				}
				out.put(q, new Anim(frames, loop));
			}
			return new Anims(Collections.unmodifiableMap(out));
		}
	}

	private static byte[] decodePng(DataReader r) throws DataException {
		try {
			return Base64.getDecoder().decode(r.string("png"));
		} catch (IllegalArgumentException e) {
			throw new DataException(r.path("png"), "not base64");
		}
	}
}
