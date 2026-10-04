package dev.kantobridge.character;

import java.util.Locale;

/**
 * Which style a sprite is drawn in (docs/specs/VOXEL_BLOCKS_PLAN.md §5.11; report B5.5): the asked-for style, else the
 * next one down the chain {@code rigged → carved → thick → flat}, decided once per sprite from its masks. Pure; the
 * headless build runs it on the export's sprites to count the fallbacks.
 */
public final class StyleFallback {
	/** The styles, simplest last. */
	public enum Style {
		FLAT,
		THICK,
		CARVED,
		RIGGED,
		MODEL;

		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		/** A style by its setting's name, or null. */
		public static Style byId(String id) {
			for (Style style : values()) {
				if (style.id().equals(id)) {
					return style;
				}
			}
			return null;
		}
	}

	/**
	 * A sprite's decision: the style it is drawn in and, when that isn't the one asked for, what failed first.
	 *
	 * @param thick  its thick cards (null unless the style is thick)
	 * @param carved its carved poses (null unless carved)
	 * @param rig    its rig (null unless rigged)
	 */
	public record Decision(Style style, Style asked, String reason, ThickCardMesh.Mesh[][] thick, HullCarver.Figure[] carved,
			RigSplitter.Rig rig) {
	}

	private StyleFallback() {
	}

	/**
	 * Decides a sprite's style.
	 *
	 * @param asked    the style the settings ask for (never {@link Style#MODEL}: a pack's model is decided before)
	 * @param revolves a 1-frame item or boulder (carved as its outline turned about its axis)
	 */
	public static Decision decide(SpriteFrames frames, Style asked, boolean revolves) {
		String reason = null;
		if (asked == Style.RIGGED) {
			RigSplitter.Rig rig = RigSplitter.split(frames);
			if (rig.ok()) {
				return new Decision(Style.RIGGED, asked, null, null, null, rig);
			}
			reason = "rig_" + rig.failure();
		}
		if (asked == Style.RIGGED || asked == Style.CARVED) {
			HullCarver.Figure stand = HullCarver.carve(frames, SpriteFrames.STAND, revolves && frames.frames() == 1);
			HullCarver.Figure walk = stand.ok() && frames.walks() ? HullCarver.carve(frames, SpriteFrames.WALK, false) : stand;
			if (stand.ok() && walk.ok()) {
				return new Decision(Style.CARVED, asked, reason, null, new HullCarver.Figure[] {stand, walk}, null);
			}
			reason = reason != null ? reason : "carve_" + (stand.ok() ? walk : stand).failure().name().toLowerCase(Locale.ROOT);
		}
		if (asked != Style.FLAT) {
			ThickCardMesh.Mesh[][] cards = new ThickCardMesh.Mesh[2][4];
			boolean ok = true;
			for (int row = 0; row < 2 && ok; row++) {
				for (int column = 0; column < 4 && ok; column++) {
					cards[row][column] = ThickCardMesh.build(frames.mask(row, column), frames.size(), frames.size(), ThickCardMesh.DEPTH);
					ok = cards[row][column] != null;
				}
			}
			if (ok) {
				return new Decision(Style.THICK, asked, reason, cards, null, null);
			}
			reason = reason != null ? reason : "thick";
		}
		return new Decision(Style.FLAT, asked, reason, null, null, null);
	}
}
