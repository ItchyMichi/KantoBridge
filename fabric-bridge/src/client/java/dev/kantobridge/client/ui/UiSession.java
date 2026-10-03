package dev.kantobridge.client.ui;

import org.jetbrains.annotations.Nullable;

/**
 * One open hidden-game UI: its id (unique per connection), kind, model, and whether an answer to it is on its way (so a
 * second press can't answer twice). Client thread only.
 */
public final class UiSession {
	/** An answer that changed nothing visible frees the UI for another after this long. */
	public static final long ANSWER_TIMEOUT_MS = 1500;

	private final int id;
	private final UiKind kind;
	private final Object model;
	private final @Nullable String screen;
	private boolean answered;
	private long answeredAt;

	UiSession(int id, UiKind kind, Object model, @Nullable String screen) {
		this.id = id;
		this.kind = kind;
		this.model = model;
		this.screen = screen;
	}

	public int id() {
		return this.id;
	}

	public UiKind kind() {
		return this.kind;
	}

	/** The engine screen id or class ({@code StartMenu}, {@code PartyMenu}, …), a symbol; null when not given. */
	public @Nullable String screen() {
		return this.screen;
	}

	public @Nullable TextBoxModel text() {
		return this.model instanceof TextBoxModel model ? model : null;
	}

	public @Nullable ChoiceModel choice() {
		return this.model instanceof ChoiceModel model ? model : null;
	}

	public @Nullable MenuModel menu() {
		return this.model instanceof MenuModel model ? model : null;
	}

	public @Nullable QuantityModel quantity() {
		return this.model instanceof QuantityModel model ? model : null;
	}

	public @Nullable NamingModel naming() {
		return this.model instanceof NamingModel model ? model : null;
	}

	/** True while an answer is on its way and nothing has changed since. */
	public boolean answered(long now) {
		if (this.answered && now - this.answeredAt >= ANSWER_TIMEOUT_MS) {
			this.answered = false;
		}
		return this.answered;
	}

	void markAnswered(long now) {
		this.answered = true;
		this.answeredAt = now;
	}

	/** The hidden UI moved on (an update, or it is on top again after another UI closed): it takes input again. */
	void clearAnswered() {
		this.answered = false;
	}

	@Override
	public String toString() {
		// Symbols and numbers only: this can end up in logs.
		return this.kind.id() + "#" + this.id;
	}
}
