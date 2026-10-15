package dev.kantobridge.server;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Whether the hidden game runs from the save this world last saved with (M10 crash recovery; docs/PROTOCOL.md
 * {@code save.loaded}, {@code save.mismatch}). Plain Java: the rules alone, with JUnit tests; {@link SavePairService} is
 * the glue.
 *
 * <ul>
 * <li>No stored pair (the world never saved with a hidden game): nothing to compare.</li>
 * <li>A fresh New Game in a world that has a pair: {@code new_game}.</li>
 * <li>The same pair: equal operation counts are in step; fewer on the hidden side mean the hidden game went back to the
 * save after Pokémon came across ({@code hidden_behind}: they are still in Minecraft, and the story may give them
 * again); more mean Minecraft went back (a crash lost what came since: {@code world_behind}). {@code ops} is the
 * difference.</li>
 * <li>Another pair: {@code hidden_older} or {@code hidden_newer} by the two saves' times when both are known (equal
 * times say nothing), else {@code other}.</li>
 * </ul>
 */
public final class SavePairRules {
	/** {@code save.mismatch.kind}. */
	public enum Kind {
		HIDDEN_BEHIND, WORLD_BEHIND, HIDDEN_OLDER, HIDDEN_NEWER, NEW_GAME, OTHER;

		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * What {@code save.loaded} says.
	 *
	 * @param fresh   a New Game never saved
	 * @param pair    the save it runs from (null when fresh)
	 * @param at      that save's time, or -1
	 * @param ops     Pokémon operations sent since that save
	 * @param daycare the Pokémon in the hidden game's daycare, or null
	 * @param boarded every Pokémon it holds when it holds more than one (Gen 3's holds two), else empty
	 */
	public record Loaded(boolean fresh, @Nullable String pair, long at, int ops, @Nullable UUID daycare, Set<UUID> boarded) {
		public Loaded {
			boarded = Set.copyOf(boarded);
		}

		public Loaded(boolean fresh, @Nullable String pair, long at, int ops, @Nullable UUID daycare) {
			this(fresh, pair, at, ops, daycare, Set.of());
		}

		/** Every Pokémon the hidden game's daycare holds. */
		public Set<UUID> held() {
			if (this.daycare == null) {
				return this.boarded;
			}
			Set<UUID> all = new HashSet<>(this.boarded);
			all.add(this.daycare);
			return all;
		}
	}

	/**
	 * A mismatch to warn about.
	 *
	 * @param ops      how many operations differ ({@code hidden_behind}, {@code world_behind}), else -1
	 * @param hiddenAt the hidden save's time, or -1
	 * @param worldAt  the world's paired save's time, or -1
	 */
	public record Mismatch(Kind kind, int ops, long hiddenAt, long worldAt) {
		/** The {@code save.mismatch} body: unknown values left out. */
		public JsonObject toJson() {
			JsonObject data = new JsonObject();
			data.addProperty("kind", this.kind.id());
			if (this.ops >= 0) {
				data.addProperty("ops", this.ops);
			}
			if (this.hiddenAt >= 0) {
				data.addProperty("hiddenAt", this.hiddenAt);
			}
			if (this.worldAt >= 0) {
				data.addProperty("worldAt", this.worldAt);
			}
			return data;
		}
	}

	private SavePairRules() {
	}

	/** The mismatch between the world's stored pair and what the hidden game loaded, or null when they agree. */
	public static @Nullable Mismatch compare(SavePair stored, Loaded loaded) {
		String pair = stored.pair();
		if (pair == null) {
			return null;
		}
		long worldAt = Math.max(-1, stored.at());
		if (loaded.fresh()) {
			return new Mismatch(Kind.NEW_GAME, -1, -1, worldAt);
		}
		long hiddenAt = Math.max(-1, loaded.at());
		if (pair.equals(loaded.pair())) {
			if (loaded.ops() == stored.ops()) {
				return null;
			}
			return loaded.ops() < stored.ops()
					? new Mismatch(Kind.HIDDEN_BEHIND, stored.ops() - loaded.ops(), hiddenAt, worldAt)
					: new Mismatch(Kind.WORLD_BEHIND, loaded.ops() - stored.ops(), hiddenAt, worldAt);
		}
		if (hiddenAt >= 0 && worldAt >= 0 && hiddenAt != worldAt) {
			return new Mismatch(hiddenAt < worldAt ? Kind.HIDDEN_OLDER : Kind.HIDDEN_NEWER, -1, hiddenAt, worldAt);
		}
		return new Mismatch(Kind.OTHER, -1, hiddenAt, worldAt);
	}
}
