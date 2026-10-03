package dev.kantobridge.client.ui;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A boxed menu or a scrolling list (docs/PROTOCOL.md {@code ui.open{kind:menu|list}}): items with an optional right
 * column, the cursor, and the window of {@code rows} items starting at {@code top}. The cursor moves here, without
 * telling gen1-bridge; only the answer travels ({@code ui.pick} with the index, or {@code ui.back}). The cursor wraps
 * round when {@code ui.open.wrap} says so; without it, menus wrap like Gen 1's {@code Menu} and lists stop at their ends
 * like {@code ListMenu}. Left/right page through lists (stopping at the ends). {@code ui.update} replaces the items,
 * cursor or window when the hidden one changes.
 */
public final class MenuModel {
	/** One row: its label and right column (a count, a price), in the markup of {@link Gen1Text}. */
	public record Item(String label, @Nullable String right) {
	}

	/** At most this many rows are drawn (a full-screen Gen 1 list shows 7). */
	public static final int MAX_ROWS = 8;

	private final boolean list;
	private final boolean wrap;
	private final int rows;
	private final @Nullable String title;
	private final @Nullable String footer;
	private final @Nullable Integer money;
	private final @Nullable String screen;
	private List<Item> items;
	private int index;
	private int top;

	/** A menu or list whose cursor wraps as its kind does by default (menus wrap, lists stop). */
	public MenuModel(boolean list, List<Item> items, int index, int top, int rows, @Nullable String title, @Nullable String footer,
			@Nullable Integer money, @Nullable String screen) {
		this(list, null, items, index, top, rows, title, footer, money, screen);
	}

	/**
	 * @param list   a list (pages with left/right) rather than a menu
	 * @param wrap   {@code ui.open.wrap}: the cursor wraps from the last item to the first; null for the kind's default
	 * @param rows   the hidden window's height in items
	 * @param screen the engine screen id, e.g. {@code StartMenu} (a symbol)
	 */
	public MenuModel(boolean list, @Nullable Boolean wrap, List<Item> items, int index, int top, int rows, @Nullable String title,
			@Nullable String footer, @Nullable Integer money, @Nullable String screen) {
		this.list = list;
		this.wrap = wrap != null ? wrap : !list;
		this.items = List.copyOf(items);
		this.rows = Math.max(1, Math.min(rows, MAX_ROWS));
		this.title = title;
		this.footer = footer;
		this.money = money;
		this.screen = screen;
		this.index = ChoiceModel.clamp(index, Math.max(1, this.items.size()));
		this.top = top;
		this.scroll();
	}

	// ---- input ----

	/** Up (-1) or down (+1). */
	public void move(int delta) {
		if (this.items.isEmpty()) {
			return;
		}
		int next = this.index + delta;
		if (this.wrap) {
			next = Math.floorMod(next, this.items.size());
		} else {
			next = Math.max(0, Math.min(next, this.items.size() - 1));
		}
		this.index = next;
		this.scroll();
	}

	/** Left (-1) or right (+1): a page of a list, stopping at its ends (even in a list that wraps); menus don't page. */
	public void page(int direction) {
		if (this.list && !this.items.isEmpty()) {
			this.index = Math.max(0, Math.min(this.index + direction * this.rows, this.items.size() - 1));
			this.scroll();
		}
	}

	/** Puts the cursor on {@code index} (a click). */
	public void select(int index) {
		this.index = ChoiceModel.clamp(index, Math.max(1, this.items.size()));
		this.scroll();
	}

	/** The hidden menu changed (each field optional). */
	public void apply(@Nullable Integer index, @Nullable Integer top, @Nullable List<Item> items) {
		if (items != null) {
			this.items = List.copyOf(items);
		}
		if (top != null) {
			this.top = top;
		}
		if (index != null) {
			this.index = index;
		}
		this.index = ChoiceModel.clamp(this.index, Math.max(1, this.items.size()));
		this.scroll();
	}

	// ---- view ----

	public List<Item> items() {
		return this.items;
	}

	public boolean empty() {
		return this.items.isEmpty();
	}

	public int index() {
		return this.index;
	}

	public int top() {
		return this.top;
	}

	/** Rows drawn. */
	public int rows() {
		return this.rows;
	}

	public boolean list() {
		return this.list;
	}

	/** The cursor wraps round. */
	public boolean wrap() {
		return this.wrap;
	}

	/** The items in the window, from {@link #top()}. */
	public List<Item> window() {
		return this.items.subList(this.top, Math.min(this.items.size(), this.top + this.rows));
	}

	public boolean moreAbove() {
		return this.top > 0;
	}

	public boolean moreBelow() {
		return this.top + this.rows < this.items.size();
	}

	public @Nullable String title() {
		return this.title;
	}

	public @Nullable String footer() {
		return this.footer;
	}

	public @Nullable Integer money() {
		return this.money;
	}

	public @Nullable String screen() {
		return this.screen;
	}

	/** Keeps the cursor inside the window, and the window inside the list. */
	private void scroll() {
		int maxTop = Math.max(0, this.items.size() - this.rows);
		if (this.index < this.top) {
			this.top = this.index;
		} else if (this.index >= this.top + this.rows) {
			this.top = this.index - this.rows + 1;
		}
		this.top = Math.max(0, Math.min(this.top, maxTop));
	}
}
