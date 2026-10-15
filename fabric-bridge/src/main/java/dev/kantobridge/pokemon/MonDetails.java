package dev.kantobridge.pokemon;

import com.google.gson.JsonObject;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * A Pokémon's details where its game has them (MG9, docs/specs/GEN2_BRIDGE_PLAN.md §5.10, J12; protocol defs
 * {@code heldItem}, {@code gender}, {@code form}, {@code friendship}): the held item as the item catalogue's canonical id
 * (protocol/items.json), shininess, gender, form (Unown's letter) and friendship. Gen 2 is the first game with them; a Gen
 * 1 or Gen 3 message carries none ({@link #NONE}). Each is optional. Plain Java.
 *
 * @param item       the held item's canonical id, or null
 * @param shiny      shininess, or null when not said
 * @param gender     {@code male}, {@code female} or {@code genderless}, or null
 * @param form       a form id ({@code a}..{@code z} for Unown), or null
 * @param friendship 0–255, or null
 */
public record MonDetails(@Nullable String item, @Nullable Boolean shiny, @Nullable String gender, @Nullable String form,
		@Nullable Integer friendship) {
	public static final MonDetails NONE = new MonDetails(null, null, null, null, null);
	/** The fields a team member or a wild Pokémon may carry (no friendship). */
	public static final Set<String> TEAM_FIELDS = Set.of("item", "shiny", "gender", "form");
	/** The fields a gift or a party member may carry. */
	public static final Set<String> PARTY_FIELDS = Set.of("item", "shiny", "gender", "form", "friendship");
	public static final Pattern ITEM = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
	public static final Pattern FORM = Pattern.compile("^[a-z0-9_]{1,24}$");
	public static final Set<String> GENDERS = Set.of("male", "female", "genderless");
	public static final int MAX_FRIENDSHIP = 255;

	public MonDetails {
		if (item != null && !ITEM.matcher(item).matches() || gender != null && !GENDERS.contains(gender)
				|| form != null && !FORM.matcher(form).matches() || friendship != null && (friendship < 0 || friendship > MAX_FRIENDSHIP)) {
			throw new IllegalArgumentException("not details the protocol carries");
		}
	}

	/** True when nothing is said. */
	public boolean isEmpty() {
		return this.item == null && this.shiny == null && this.gender == null && this.form == null && this.friendship == null;
	}

	/** Reads whichever of the details a message object has; friendship only where {@code withFriendship}. */
	public static MonDetails read(DataReader reader, boolean withFriendship) throws DataException {
		String item = reader.has("item") ? reader.string("item", ITEM) : null;
		Boolean shiny = reader.has("shiny") ? reader.bool("shiny") : null;
		String gender = null;
		if (reader.has("gender")) {
			gender = reader.string("gender");
			if (!GENDERS.contains(gender)) {
				throw new DataException(reader.path("gender"), "unknown gender");
			}
		}
		String form = reader.has("form") ? reader.string("form", FORM) : null;
		Integer friendship = withFriendship && reader.has("friendship") ? reader.integer("friendship", 0, MAX_FRIENDSHIP) : null;
		MonDetails out = new MonDetails(item, shiny, gender, form, friendship);
		return out.isEmpty() ? NONE : out;
	}

	/** Writes the details said into a message object. */
	public void write(JsonObject out) {
		if (this.item != null) {
			out.addProperty("item", this.item);
		}
		if (this.shiny != null) {
			out.addProperty("shiny", this.shiny);
		}
		if (this.gender != null) {
			out.addProperty("gender", this.gender);
		}
		if (this.form != null) {
			out.addProperty("form", this.form);
		}
		if (this.friendship != null) {
			out.addProperty("friendship", this.friendship);
		}
	}
}
