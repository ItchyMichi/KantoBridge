package dev.kantobridge.scene.profile;

import org.jetbrains.annotations.Nullable;

/**
 * The profile an export carried (docs/specs/PROFILE_PLAN.md §3): the mod's version, whether Gen1Recomp runs it, and
 * the profile itself, or null with a reason when its text couldn't be read as data.
 *
 * @param status {@code ok}, or {@code unreadable} (the reader's line and column are in {@code problem})
 * @param problem where reading stopped ({@code line:column}), or null
 */
public record ProfileSource(@Nullable Profile profile, String modVersion, boolean enabled, String status, @Nullable String problem) {
	/** A profile read from {@code text}; never throws. */
	public static ProfileSource read(String text, String modVersion, boolean enabled) {
		try {
			return new ProfileSource(Profile.parse(text), modVersion, enabled, "ok", null);
		} catch (LuaData.DataException e) {
			return new ProfileSource(null, modVersion, enabled, "unreadable", e.line() + ":" + e.column());
		}
	}
}
