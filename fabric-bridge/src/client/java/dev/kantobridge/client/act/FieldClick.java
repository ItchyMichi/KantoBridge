package dev.kantobridge.client.act;

import com.google.gson.JsonObject;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * Field moves from a press on a cell (docs/PLAN.md §6 M8 item 4; PROTOCOL.md {@code act.field}): A, or a plain
 * right-click, on
 *
 * <ul>
 * <li>a Cut tree the player still sees standing: {@code act.field{cut, x, y}};</li>
 * <li>water, while not surfing: {@code act.field{surf, x, y}};</li>
 * <li>the ground of a land cell, while surfing (a right-click only, so A still reads a sign on the shore):
 * {@code act.field{surf, x, y}}, which gen1-bridge answers as a dismount;</li>
 * </ul>
 * anything else is the ordinary A ({@code act.interact}). Gen 1 itself does nothing for A on a tree or on water (Cut and
 * Surf come from the party menu), so nothing is lost. A field move's target must be next to the hidden player (the
 * engine turns to it and uses the move); one further away is refused here as {@code not_adjacent} (shown like
 * gen1-bridge's own refusals) without asking gen1-bridge. Plain Java.
 */
public final class FieldClick {
	public static final String CUT = "cut";
	public static final String SURF = "surf";
	public static final String NOT_ADJACENT = "not_adjacent";

	/** What a press does. */
	public enum Kind {
		/** The ordinary A on the cell. */
		INTERACT,
		/** A field move on the cell ({@link Decision#action}). */
		FIELD,
		/** A field move that can't go out ({@link Decision#reason}). */
		REFUSED
	}

	/**
	 * @param target the pressed cell, named in the hidden player's map
	 * @param action the field action ({@link Kind#FIELD}, {@link Kind#REFUSED}), else null
	 * @param reason why it was refused ({@link Kind#REFUSED}), else null
	 */
	public record Decision(Kind kind, CellPos target, @Nullable String action, @Nullable String reason) {
		/** The {@code act.field} body for a {@link Kind#FIELD} decision. */
		public JsonObject fieldData() {
			JsonObject data = new JsonObject();
			data.addProperty("action", this.action);
			data.addProperty("x", this.target.x());
			data.addProperty("y", this.target.y());
			return data;
		}
	}

	private FieldClick() {
	}

	/**
	 * Decides what a press on {@code target} does.
	 *
	 * @param hiddenMap the hidden player's map ({@code target} is named in it)
	 * @param hx        the hidden player's cell
	 * @param surfing   the hidden player surfs
	 * @param drawn     the tile cells this player sees closed
	 * @param land      the press hit the ground surface of a cell (a right-click on its floor block); false for the A key
	 */
	public static Decision decide(WorldIndex index, String hiddenMap, int hx, int hy, CellPos target, boolean surfing, Predicate<String> drawn,
			boolean land) {
		CellPos own = InteractTarget.own(index, target);
		StoryCellTable.Entry entry = own == null ? null : index.storyCells().get(own.key());
		String action = action(entry, surfing, drawn, land);
		if (action == null) {
			return new Decision(Kind.INTERACT, target, null, null);
		}
		boolean adjacent = target.map().equals(hiddenMap) && Math.abs(target.x() - hx) + Math.abs(target.y() - hy) == 1;
		return adjacent ? new Decision(Kind.FIELD, target, action, null) : new Decision(Kind.REFUSED, target, action, NOT_ADJACENT);
	}

	/** The field action a press on a cell with this story-cell entry means, or null for the ordinary A. */
	static @Nullable String action(StoryCellTable.@Nullable Entry entry, boolean surfing, Predicate<String> drawn, boolean land) {
		if (entry != null && entry.type() == StoryCellType.TILE && entry.swap() != null && CUT.equals(entry.swap().kind())
				&& drawn.test(entry.key())) {
			return CUT;
		}
		boolean water = water(entry, drawn);
		if (water && !surfing) {
			return SURF;
		}
		if (surfing && land && !water && !closedSolid(entry, drawn)) {
			return SURF;
		}
		return null;
	}

	/** True when the cell is water for this player now: a water cell, or a tile cell whose present state is water. */
	static boolean water(StoryCellTable.@Nullable Entry entry, Predicate<String> drawn) {
		if (entry == null) {
			return false;
		}
		if (entry.type() == StoryCellType.WATER) {
			return true;
		}
		if (entry.type() != StoryCellType.TILE) {
			return false;
		}
		if (drawn.test(entry.key())) {
			return entry.closed() != null && entry.closed().pass() == StoryCellTable.Pass.WATER;
		}
		return entry.water();
	}

	private static boolean closedSolid(StoryCellTable.@Nullable Entry entry, Predicate<String> drawn) {
		return entry != null && entry.type() == StoryCellType.TILE && drawn.test(entry.key()) && entry.closed() != null
				&& entry.closed().pass() == StoryCellTable.Pass.SOLID;
	}
}
