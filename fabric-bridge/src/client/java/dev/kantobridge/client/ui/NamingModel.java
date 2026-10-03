package dev.kantobridge.client.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * The naming screen (docs/PROTOCOL.md {@code ui.open{kind:naming}}, PLAN §2.4 flow 4, refs P2 §6), in Gen 1's way:
 *
 * <ul>
 * <li><b>Presets first.</b> With presets, a menu offers NEW NAME and each preset; a preset is answered at once, NEW NAME
 * opens the grid. The preset menu can't be cancelled.</li>
 * <li><b>The grid.</b> The engine's letter grid from {@code ui.open.grid} (its glyph cells; anything else in it is
 * dropped), or Gen 1's own upper/lower layout when there is none. ED ends the last row, and a case row follows when
 * there are several pages. The cursor wraps round; A types (up to {@code max}, then the cursor jumps to ED), B
 * deletes, SELECT flips the case, START or ED confirms.</li>
 * <li><b>Confirming</b> sends {@code ui.name{glyphs}}: one token per glyph ({@code <PK>} and {@code <MN>} count as one),
 * 1 to {@code max} of them (at most 10). An empty name falls back to the default {@code value}, then the first preset,
 * as Gen 1's {@code confirm} does; with neither, nothing is sent.</li>
 * </ul>
 */
public final class NamingModel {
	/** The most glyphs a name may have ({@code ui.name.glyphs} maxItems; nicknames). */
	public static final int MAX_LENGTH = 10;
	/** The grid's control cell that confirms; also dropped from an engine grid, which may carry its own. */
	public static final String END_TOKEN = "ED";

	/** Gen 1's naming grid: upper case, then lower case (no digits). */
	public static final List<List<List<String>>> GEN1_GRID = List.of(
			List.of(
					List.of("A", "B", "C", "D", "E", "F", "G", "H", "I"),
					List.of("J", "K", "L", "M", "N", "O", "P", "Q", "R"),
					List.of("S", "T", "U", "V", "W", "X", "Y", "Z", " "),
					List.of("×", "(", ")", ":", ";", "[", "]", Gen1Text.PK, Gen1Text.MN),
					List.of("-", "?", "!", "♂", "♀", "/", ".", ",")),
			List.of(
					List.of("a", "b", "c", "d", "e", "f", "g", "h", "i"),
					List.of("j", "k", "l", "m", "n", "o", "p", "q", "r"),
					List.of("s", "t", "u", "v", "w", "x", "y", "z", " "),
					List.of("×", "(", ")", ":", ";", "[", "]", Gen1Text.PK, Gen1Text.MN),
					List.of("-", "?", "!", "♂", "♀", "/", ".", ",")));

	public enum Phase {
		/** The NEW NAME / presets menu. */
		PRESETS,
		/** The letter grid. */
		GRID,
		/** The name was sent; waiting for the screen to close. */
		SENT
	}

	public enum Control {
		GLYPH,
		END,
		CASE
	}

	/** One cell of the grid as drawn: a glyph to type, ED, or the case switch. */
	public record Cell(Control control, String glyph) {
		static Cell glyph(String glyph) {
			return new Cell(Control.GLYPH, glyph);
		}
	}

	private final String title;
	private final int max;
	private final List<String> presets;
	private final String value;
	private final @Nullable Integer mon;
	private final boolean engineGrid;
	/** Per page, rows of cells, controls included. */
	private final List<List<List<Cell>>> pages;
	private final Set<String> charset = new HashSet<>();
	private final List<String> typed = new ArrayList<>();
	private Phase phase;
	private int preset;
	private int page;
	private int row;
	private int col;

	/**
	 * @param max  the name's length limit (7 for player and rival, 10 for nicknames); clamped to 1..10
	 * @param grid the engine's grid (pages of rows of glyph tokens), or null for Gen 1's own
	 */
	public NamingModel(String title, int max, List<String> presets, String value, @Nullable Integer mon,
			@Nullable List<List<List<String>>> grid) {
		this.title = title;
		this.max = Math.max(1, Math.min(max, MAX_LENGTH));
		this.presets = List.copyOf(presets);
		this.value = value;
		this.mon = mon;
		List<List<List<Cell>>> built = grid == null ? null : build(grid);
		this.engineGrid = built != null;
		this.pages = built != null ? built : build(GEN1_GRID);
		for (List<List<Cell>> rows : this.pages) {
			for (List<Cell> cells : rows) {
				for (Cell cell : cells) {
					if (cell.control() == Control.GLYPH) {
						this.charset.add(cell.glyph());
					}
				}
			}
		}
		this.phase = this.presets.isEmpty() ? Phase.GRID : Phase.PRESETS;
	}

	// ---- presets ----

	/** Moves the preset cursor (entry 0 is NEW NAME), wrapping round. */
	public void movePreset(int delta) {
		if (this.phase == Phase.PRESETS) {
			this.preset = Math.floorMod(this.preset + delta, this.presets.size() + 1);
		}
	}

	public void selectPreset(int entry) {
		if (this.phase == Phase.PRESETS) {
			this.preset = Math.max(0, Math.min(entry, this.presets.size()));
		}
	}

	/** A on the preset menu: NEW NAME opens the grid (null); a preset returns the glyphs to send. */
	public @Nullable List<String> pressPreset() {
		if (this.phase != Phase.PRESETS) {
			return null;
		}
		if (this.preset == 0) {
			this.phase = Phase.GRID;
			return null;
		}
		return this.send(Gen1Text.glyphs(this.presets.get(this.preset - 1)));
	}

	// ---- grid ----

	/** Moves the grid cursor, wrapping round rows and columns. */
	public void moveCursor(int dx, int dy) {
		if (this.phase != Phase.GRID) {
			return;
		}
		List<List<Cell>> rows = this.pages.get(this.page);
		if (dy != 0) {
			this.row = Math.floorMod(this.row + dy, rows.size());
			this.col = Math.min(this.col, rows.get(this.row).size() - 1);
		}
		if (dx != 0) {
			this.col = Math.floorMod(this.col + dx, rows.get(this.row).size());
		}
	}

	/** Puts the grid cursor on a cell (a click); ignored outside the grid. */
	public void selectCell(int row, int col) {
		List<List<Cell>> rows = this.pages.get(this.page);
		if (this.phase == Phase.GRID && row >= 0 && row < rows.size() && col >= 0 && col < rows.get(row).size()) {
			this.row = row;
			this.col = col;
		}
	}

	/** A on the grid: types the glyph, confirms on ED (returning the glyphs to send), or flips the case. */
	public @Nullable List<String> pressCell() {
		if (this.phase != Phase.GRID) {
			return null;
		}
		Cell cell = this.cell();
		switch (cell.control()) {
			case END -> {
				return this.confirm();
			}
			case CASE -> this.flipCase();
			default -> {
				if (this.typed.size() < this.max) {
					this.typed.add(cell.glyph());
				}
				if (this.typed.size() >= this.max) {
					this.toEnd();
				}
			}
		}
		return null;
	}

	/** B: deletes the last glyph. */
	public void delete() {
		if (this.phase == Phase.GRID && !this.typed.isEmpty()) {
			this.typed.remove(this.typed.size() - 1);
		}
	}

	/** SELECT, or the case cell: the next page of the grid. */
	public void flipCase() {
		if (this.phase == Phase.GRID && this.pages.size() > 1) {
			this.page = (this.page + 1) % this.pages.size();
			List<List<Cell>> rows = this.pages.get(this.page);
			this.row = Math.min(this.row, rows.size() - 1);
			this.col = Math.min(this.col, rows.get(this.row).size() - 1);
		}
	}

	/** START or ED: the glyphs to send, or null when there is nothing valid to send. */
	public @Nullable List<String> confirm() {
		if (this.phase != Phase.GRID) {
			return null;
		}
		List<String> glyphs = new ArrayList<>(this.typed);
		if (glyphs.isEmpty()) {
			String fallback = !this.value.isEmpty() ? this.value : this.presets.isEmpty() ? "" : this.presets.get(0);
			glyphs = Gen1Text.glyphs(fallback);
			if (glyphs.size() > this.max) {
				glyphs = new ArrayList<>(glyphs.subList(0, this.max));
			}
		} else if (!this.charset.containsAll(glyphs)) {
			return null;
		}
		return this.send(glyphs);
	}

	// ---- view ----

	public Phase phase() {
		return this.phase;
	}

	public String title() {
		return this.title;
	}

	public int max() {
		return this.max;
	}

	public List<String> presets() {
		return this.presets;
	}

	/** The preset menu's cursor: 0 is NEW NAME, {@code i} is preset {@code i - 1}. */
	public int presetCursor() {
		return this.preset;
	}

	public @Nullable Integer mon() {
		return this.mon;
	}

	/** True when the grid came from {@code ui.open} rather than Gen 1's built-in layout. */
	public boolean engineGrid() {
		return this.engineGrid;
	}

	public int pageCount() {
		return this.pages.size();
	}

	public int page() {
		return this.page;
	}

	/** The current page's rows of cells. */
	public List<List<Cell>> rows() {
		return this.pages.get(this.page);
	}

	public int row() {
		return this.row;
	}

	public int col() {
		return this.col;
	}

	public Cell cell() {
		return this.pages.get(this.page).get(this.row).get(this.col);
	}

	public List<String> typed() {
		return List.copyOf(this.typed);
	}

	/** Every glyph the grid can type. */
	public Set<String> charset() {
		return Set.copyOf(this.charset);
	}

	// ---- helpers ----

	/** Checks a name before it goes out: 1..max tokens, each one glyph of at most 8 chars. */
	public static boolean valid(List<String> glyphs, int max) {
		if (glyphs.isEmpty() || glyphs.size() > Math.min(max, MAX_LENGTH)) {
			return false;
		}
		for (String glyph : glyphs) {
			if (!Gen1Text.isGlyph(glyph) || glyph.length() > Gen1Text.MAX_TOKEN_CHARS) {
				return false;
			}
		}
		return true;
	}

	private @Nullable List<String> send(List<String> glyphs) {
		if (!valid(glyphs, this.max)) {
			return null;
		}
		this.phase = Phase.SENT;
		return List.copyOf(glyphs);
	}

	private void toEnd() {
		List<List<Cell>> rows = this.pages.get(this.page);
		for (int r = 0; r < rows.size(); r++) {
			for (int c = 0; c < rows.get(r).size(); c++) {
				if (rows.get(r).get(c).control() == Control.END) {
					this.row = r;
					this.col = c;
					return;
				}
			}
		}
	}

	/**
	 * The drawn grid: glyph cells only (an engine's own ED, case labels or anything else that isn't one glyph is
	 * dropped), ED after the last row's glyphs, and a case row when there are several pages. Null when any page has no
	 * glyph at all, so the caller falls back to Gen 1's grid.
	 */
	static @Nullable List<List<List<Cell>>> build(List<List<List<String>>> grid) {
		if (grid.isEmpty()) {
			return null;
		}
		List<List<List<Cell>>> pages = new ArrayList<>();
		int width = 1;
		for (List<List<String>> page : grid) {
			List<List<Cell>> rows = new ArrayList<>();
			for (List<String> row : page) {
				List<Cell> cells = new ArrayList<>();
				for (String token : row) {
					if (token != null && Gen1Text.isGlyph(token) && token.length() <= Gen1Text.MAX_TOKEN_CHARS) {
						cells.add(Cell.glyph(token));
					}
				}
				if (!cells.isEmpty()) {
					rows.add(cells);
					width = Math.max(width, cells.size());
				}
			}
			if (rows.isEmpty()) {
				return null;
			}
			pages.add(rows);
		}
		for (List<List<Cell>> rows : pages) {
			List<Cell> last = rows.get(rows.size() - 1);
			Cell end = new Cell(Control.END, END_TOKEN);
			if (last.size() < width) {
				last.add(end);
			} else {
				rows.add(new ArrayList<>(List.of(end)));
			}
			if (pages.size() > 1) {
				rows.add(new ArrayList<>(List.of(new Cell(Control.CASE, ""))));
			}
		}
		return pages;
	}
}
