package dev.kantobridge.client.ui;

import org.jetbrains.annotations.Nullable;

/**
 * A how-many box (docs/PROTOCOL.md {@code ui.open{kind:quantity}}): like Gen 1's {@code QuantityBox}, up and down step
 * through 1..{@code max} and wrap round; A confirms with {@code ui.qty}, B cancels with {@code ui.back}. With a
 * {@code unit} price it also shows the total.
 */
public final class QuantityModel {
	/** The largest count the protocol carries. */
	public static final int LIMIT = 999;

	private final int max;
	private final @Nullable Integer unit;
	private int qty;

	public QuantityModel(int qty, int max, @Nullable Integer unit) {
		this.max = Math.max(1, Math.min(max, LIMIT));
		this.unit = unit;
		this.qty = clamp(qty, this.max);
	}

	/** Up (+1) or down (-1); a bigger step for paging. Wraps through 1..max. */
	public void step(int delta) {
		this.qty = Math.floorMod(this.qty - 1 + delta, this.max) + 1;
	}

	/** The hidden box's count changed. */
	public void apply(int qty) {
		this.qty = clamp(qty, this.max);
	}

	public int qty() {
		return this.qty;
	}

	public int max() {
		return this.max;
	}

	public @Nullable Integer unit() {
		return this.unit;
	}

	/** The price of {@link #qty()} units, or null without a unit price. */
	public @Nullable Long total() {
		return this.unit == null ? null : (long) this.unit * this.qty;
	}

	private static int clamp(int qty, int max) {
		return Math.max(1, Math.min(qty, max));
	}
}
