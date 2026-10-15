package dev.kantobridge.builder.export;

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
 * The smaller Gen 2 export resources (MG9 G2D; protocol/export/g2*.schema.json): the surface layouts, the wild tables the
 * builder reads and the object sheets. The pages and the animated tiles have Gen 3's shapes ({@link G3Data.Image},
 * {@link G3Data.Anims}), and so do the names ({@link G3Data.Names}). ROM-derived: never logged.
 */
public final class G2Data {
	/** The daytimes of a grass table, in the engine's order. */
	public static final List<String> DAYTIMES = List.of("MORN", "DAY", "NITE");
	private static final Pattern SPRITE_ID = Pattern.compile("^[A-Z0-9_]{1,64}(\\.p[0-7])?$");
	private static final Pattern SYM = Pattern.compile("^[A-Z0-9_]{1,64}$");

	private G2Data() {
	}

	/**
	 * One surface area's block origins ({@code g2layout/<area>}), relative to its root; on Johto's, where a new game starts
	 * (in cells), or null.
	 */
	public record Layout(String area, String root, Map<String, G3Data.Rect> maps, G3Data.@Nullable Start start) {
		public Layout {
			maps = Collections.unmodifiableMap(new TreeMap<>(maps));
		}

		static Layout parse(DataReader r, String area) throws DataException {
			if (!"blocks".equals(r.string("unit"))) {
				throw new DataException(r.path("unit"), "the unit is blocks");
			}
			Map<String, G3Data.Rect> maps = new TreeMap<>();
			DataReader m = r.object("maps");
			for (String id : m.json().keySet()) {
				if (!DataReader.MAP_ID.matcher(id).matches()) {
					throw new DataException(m.path(), "bad map id");
				}
				DataReader rect = m.object(id);
				maps.put(id, new G3Data.Rect(rect.integer("x", -4096, 4096), rect.integer("y", -4096, 4096), rect.integer("w", 1, 255),
						rect.integer("h", 1, 255)));
			}
			DataReader start = r.optionalObject("start");
			return new Layout(area, r.string("root", DataReader.MAP_ID), maps, start == null ? null
					: new G3Data.Start(start.string("map", DataReader.MAP_ID), start.integer("x", 0, 511), start.integer("y", 0, 511)));
		}
	}

	/** One slot: a national dex number and a level. */
	public record Slot(int dex, int level) {
	}

	/** A grass table: its rate and slots per daytime ({@link #DAYTIMES}). */
	public record Grass(Map<String, Integer> rates, Map<String, List<Slot>> slots) {
		public Grass {
			rates = Collections.unmodifiableMap(new TreeMap<>(rates));
			slots = Collections.unmodifiableMap(new TreeMap<>(slots));
		}
	}

	/** A water table: its rate and slots. */
	public record Water(int rate, List<Slot> slots) {
		public Water {
			slots = List.copyOf(slots);
		}
	}

	/**
	 * The wild tables the builder reads ({@code g2encounters/all}): grass and water per map, and the grass slots'
	 * cumulative chances out of 100 (null when the export doesn't say). Fishing, headbutt, Rock Smash and the contest stay
	 * the hidden game's rolls.
	 */
	public record Encounters(Map<String, Grass> grass, Map<String, Water> water, int @Nullable [] grassChances) {
		public Encounters {
			grass = Collections.unmodifiableMap(new TreeMap<>(grass));
			water = Collections.unmodifiableMap(new TreeMap<>(water));
			grassChances = grassChances == null ? null : grassChances.clone();
		}

		/** The chance of grass slot {@code slot} in percent (the cumulative chances' step), or null when unknown. */
		public @Nullable Integer grassChance(int slot) {
			if (this.grassChances == null || slot < 0 || slot >= this.grassChances.length) {
				return null;
			}
			return this.grassChances[slot] - (slot == 0 ? 0 : this.grassChances[slot - 1]);
		}

		static Encounters parse(DataReader r) throws DataException {
			Map<String, Grass> grass = new TreeMap<>();
			DataReader g = r.object("grass");
			for (String id : g.json().keySet()) {
				if (!DataReader.MAP_ID.matcher(id).matches()) {
					throw new DataException(g.path(), "bad map id");
				}
				DataReader t = g.object(id);
				DataReader rates = t.object("rates");
				DataReader slots = t.object("slots");
				Map<String, Integer> rateBy = new TreeMap<>();
				Map<String, List<Slot>> slotsBy = new TreeMap<>();
				for (String d : DAYTIMES) {
					rateBy.put(d, rates.integer(d, 0, 255));
					slotsBy.put(d, slots(slots, d));
				}
				grass.put(id, new Grass(rateBy, slotsBy));
			}
			Map<String, Water> water = new TreeMap<>();
			DataReader w = r.object("water");
			for (String id : w.json().keySet()) {
				if (!DataReader.MAP_ID.matcher(id).matches()) {
					throw new DataException(w.path(), "bad map id");
				}
				DataReader t = w.object(id);
				water.put(id, new Water(t.integer("rate", 0, 255), slots(t, "slots")));
			}
			int[] chances = r.has("grassChances") ? DataReader.intArray(r.array("grassChances"), r.path("grassChances"), 0, 100) : null;
			return new Encounters(grass, water, chances);
		}

		private static List<Slot> slots(DataReader t, String name) throws DataException {
			List<Slot> out = new ArrayList<>();
			for (DataReader s : DataReader.objects(t.array(name), t.path(name))) {
				out.add(new Slot(s.integer("dex", 1, 251), s.integer("level", 1, 100)));
			}
			return List.copyOf(out);
		}
	}

	/**
	 * An object sheet ({@code g2sprite}): {@code sprite} drawn with OBJ palette {@code palette}, a 16-px-wide strip of
	 * {@code frames} 16x16 frames ({@code h = 16 * frames}).
	 */
	public record Sprite(String id, String sprite, int palette, int w, int h, int frames, boolean walker, @Nullable String type, byte[] png) {
		static Sprite parse(DataReader r) throws DataException {
			byte[] png;
			try {
				png = Base64.getDecoder().decode(r.string("png"));
			} catch (IllegalArgumentException e) {
				throw new DataException(r.path("png"), "not base64");
			}
			int h = r.integer("h", 16, 1024);
			int frames = r.integer("frames", 1, 64);
			if (h != frames * 16) {
				throw new DataException(r.path("h"), "16-px frames");
			}
			return new Sprite(r.string("id", SPRITE_ID), r.string("sprite", SYM), r.integer("palette", 0, 7), r.integer("w", 16, 16), h, frames,
					r.has("walker") && r.bool("walker"), r.optionalString("type", SYM), png);
		}
	}
}
