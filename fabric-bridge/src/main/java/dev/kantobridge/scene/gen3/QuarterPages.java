package dev.kantobridge.scene.gen3;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gen 3 art addresses (MG7, docs/specs/GEN3_BRIDGE_PLAN.md §5 "As built", §6): the export pages its flattened 8×8
 * quarters 1,024 to an atlas image {@code g3q.<page>} (32 × 32 quarters, row-major); a quarter's id is
 * {@code page × 1024 + index}. A map's art spans several pages, so the cell models, the scene and the shape planner name the
 * whole art one virtual atlas, {@link #VIRTUAL}, with quarter ids as its tiles; slot keys, texture keys and texture files
 * name the page and the index within it ({@link #atlasOf}, {@link #tileOf}), so their tile stays below 1,024. Gen 3's art
 * is baked RGBA: every key takes the one palette {@link #PALETTE}.
 */
public final class QuarterPages {
	/** The virtual atlas of the whole Gen 3 art. */
	public static final String VIRTUAL = "g3q";
	/** A page's atlas id is this prefix and its number. */
	public static final String PREFIX = "g3q.";
	/** Quarters per page, the slot key's tile limit. */
	public static final int PAGE = 1024;
	/** Quarters per page row. */
	public static final int PER_ROW = 32;
	/** The palette of every Gen 3 key: the art is baked, never coloured (a symbol, as slot and texture keys need). */
	public static final String PALETTE = "BAKED";
	private static final Pattern PAGE_ID = Pattern.compile("^g3q\\.(\\d{1,4})$");

	private QuarterPages() {
	}

	/** True for the virtual atlas {@link #VIRTUAL}. */
	public static boolean virtual(String atlas) {
		return VIRTUAL.equals(atlas);
	}

	/** The page atlas holding a quarter. */
	public static String page(int quarter) {
		if (quarter < 0) {
			throw new IllegalArgumentException("not a quarter id");
		}
		return PREFIX + quarter / PAGE;
	}

	/** A quarter's index within its page. */
	public static int index(int quarter) {
		if (quarter < 0) {
			throw new IllegalArgumentException("not a quarter id");
		}
		return quarter % PAGE;
	}

	/** The atlas a tile of {@code atlas} lives in: its page for the virtual atlas, {@code atlas} itself otherwise. */
	public static String atlasOf(String atlas, int tile) {
		return virtual(atlas) ? page(tile) : atlas;
	}

	/** A tile's index in {@link #atlasOf}: its index within its page for the virtual atlas, the tile itself otherwise. */
	public static int tileOf(String atlas, int tile) {
		return virtual(atlas) ? index(tile) : tile;
	}

	/** A page atlas's number, or -1 for an atlas id that isn't a page. */
	public static int pageNumber(String atlas) {
		Matcher m = PAGE_ID.matcher(atlas);
		return m.matches() ? Integer.parseInt(m.group(1)) : -1;
	}

	/** The quarter id of an index within a page atlas, or -1 when {@code atlas} isn't a page. */
	public static int quarter(String atlas, int index) {
		int page = pageNumber(atlas);
		return page < 0 || index < 0 || index >= PAGE ? -1 : page * PAGE + index;
	}
}
