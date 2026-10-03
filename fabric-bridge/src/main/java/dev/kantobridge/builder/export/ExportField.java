package dev.kantobridge.builder.export;

import com.google.gson.JsonElement;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

/**
 * The engine field tables the builder reads (protocol/export/field.schema.json, the {@code field} resource): which
 * maps roll their grass table on every floor cell ({@code indoorEncounters}: map index from {@code firstIndoorMap} on,
 * any tileset but {@code excludedTileset}, as Gen 1's wild_encounters.asm does for caves, towers, the Mansion and the
 * Power Plant), the Safari Zone's maps ({@code safari.stepMaps}), whose battles stay native (P9), and the player's
 * surfing sprite ({@code playerSprites.surf}), drawn under a surfing player (M8). Other tables are left alone.
 *
 * @param indoor     null when the export has no {@code indoorEncounters} table
 * @param safariMaps the Safari Zone's maps (empty without a {@code safari} table)
 * @param surfSprite the sprite the hidden game draws for a surfing player, or null when the export doesn't say
 */
public record ExportField(@Nullable Indoor indoor, Set<String> safariMaps, @Nullable String surfSprite) {
	/** Gen 1's indoor encounter rule. */
	public record Indoor(int firstIndoorMap, String excludedTileset) {
	}

	public ExportField {
		safariMaps = Set.copyOf(safariMaps);
	}

	public ExportField(@Nullable Indoor indoor, Set<String> safariMaps) {
		this(indoor, safariMaps, null);
	}

	public static ExportField empty() {
		return new ExportField(null, Set.of(), null);
	}

	/** True when map {@code index} with tileset {@code tileset} rolls encounters on every floor cell. */
	public boolean indoorEncounters(int index, String tileset) {
		return this.indoor != null && index >= this.indoor.firstIndoorMap() && !tileset.equals(this.indoor.excludedTileset());
	}

	static ExportField parse(DataReader r) throws DataException {
		DataReader tables = r.object("tables");
		Indoor indoor = null;
		DataReader rule = tables.optionalObject("indoorEncounters");
		if (rule != null) {
			indoor = new Indoor(rule.integer("firstIndoorMap", 0, 255), rule.string("excludedTileset", DataReader.SYMBOL));
		}
		Set<String> safari = new TreeSet<>();
		DataReader safariTable = tables.optionalObject("safari");
		if (safariTable != null && safariTable.has("stepMaps")) {
			int i = 0;
			for (JsonElement map : safariTable.array("stepMaps")) {
				safari.add(DataReader.asString(map, safariTable.path("stepMaps") + "/" + i++, DataReader.MAP_ID));
			}
		}
		// Cosmetic only: a table shaped otherwise leaves the surfer without a sprite rather than failing the build.
		String surf = null;
		JsonElement sprites = tables.json().get("playerSprites");
		if (sprites != null && sprites.isJsonObject()) {
			JsonElement value = sprites.getAsJsonObject().get("surf");
			if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
					&& DataReader.SYMBOL.matcher(value.getAsString()).matches()) {
				surf = value.getAsString();
			}
		}
		return new ExportField(indoor, safari, surf);
	}
}
