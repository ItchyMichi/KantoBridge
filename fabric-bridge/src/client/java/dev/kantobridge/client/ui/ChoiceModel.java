package dev.kantobridge.client.ui;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A YES/NO-style choice (docs/PROTOCOL.md {@code ui.open{kind:choice}}): the labels, the cursor, and the question when
 * the box under it isn't shown as its own text box. Up and down move the cursor round (with two labels they toggle, as
 * in Gen 1); A picks the cursor's label ({@code ui.pick}), B answers with {@code ui.back} (gen1-bridge answers NO).
 */
public final class ChoiceModel {
	private final List<String> labels;
	private final @Nullable String question;
	private int index;

	public ChoiceModel(List<String> labels, int index, @Nullable String question) {
		this.labels = labels.isEmpty() ? List.of("") : List.copyOf(labels);
		this.question = question;
		this.index = clamp(index, this.labels.size());
	}

	public List<String> labels() {
		return this.labels;
	}

	public @Nullable String question() {
		return this.question;
	}

	public int index() {
		return this.index;
	}

	/** Moves the cursor by {@code delta}, wrapping round. */
	public void move(int delta) {
		this.index = Math.floorMod(this.index + delta, this.labels.size());
	}

	/** Puts the cursor on {@code index} (a click, or the hidden box's own cursor). */
	public void select(int index) {
		this.index = clamp(index, this.labels.size());
	}

	static int clamp(int index, int size) {
		return Math.max(0, Math.min(index, size - 1));
	}
}
