package dev.kantobridge.client.ui;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/** The kinds of hidden-game UI ({@code ui.open.kind}). */
public enum UiKind {
	TEXT,
	CHOICE,
	MENU,
	LIST,
	QUANTITY,
	NAMING,
	MIRROR;

	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public static @Nullable UiKind byId(@Nullable String id) {
		if (id != null) {
			for (UiKind kind : values()) {
				if (kind.id().equals(id)) {
					return kind;
				}
			}
		}
		return null;
	}
}
