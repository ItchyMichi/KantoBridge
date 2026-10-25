package dev.kantobridge.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * A cell plus a facing: the protocol's {@code place} ({@code {map, x, y, facing}}), used by {@code snapshot.player},
 * {@code warp.to}, {@code warp.request} and {@code warp.done}. Coordinates are 16-px cells and may be -1 or one past the
 * last cell at map edges. {@code elev} (WATER_AND_BRIDGES_PLAN D7, Gen 3 only) is the hidden game's elevation of the player
 * there, which names the floor of a two-floor cell; -1 when not given.
 */
public record Place(String map, int x, int y, Dir facing, int elev) {
	/** The protocol's {@code coord} range. */
	public static final int MAX_COORD = 4096;

	public Place {
		Objects.requireNonNull(map, "map");
		Objects.requireNonNull(facing, "facing");
		if (!DataReader.MAP_ID.matcher(map).matches()) {
			throw new IllegalArgumentException("invalid map id");
		}
		if (Math.abs(x) > MAX_COORD || Math.abs(y) > MAX_COORD) {
			throw new IllegalArgumentException("coordinate out of range");
		}
		if (elev < -1 || elev > 15) {
			throw new IllegalArgumentException("elevation out of range");
		}
	}

	/** A place without an elevation. */
	public Place(String map, int x, int y, Dir facing) {
		this(map, x, y, facing, -1);
	}

	public CellPos cell() {
		return new CellPos(this.map, this.x, this.y);
	}

	public Place withFacing(Dir dir) {
		return new Place(this.map, this.x, this.y, dir, this.elev);
	}

	/** The same place with elevation {@code elev} (-1: none). */
	public Place withElev(int elev) {
		return new Place(this.map, this.x, this.y, this.facing, elev);
	}

	public JsonObject toJson() {
		JsonObject object = new JsonObject();
		object.addProperty("map", this.map);
		object.addProperty("x", this.x);
		object.addProperty("y", this.y);
		object.addProperty("facing", this.facing.id());
		if (this.elev >= 0) {
			object.addProperty("elev", this.elev);
		}
		return object;
	}

	/**
	 * Reads a {@code place} object; null when anything is missing, mistyped or out of range (Java checks envelopes but
	 * not per-type schemas, so every reader must tolerate bad data). Extra fields are rejected like the schema does.
	 */
	public static @Nullable Place fromJson(@Nullable JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return null;
		}
		JsonObject object = element.getAsJsonObject();
		for (String key : object.keySet()) {
			if (!key.equals("map") && !key.equals("x") && !key.equals("y") && !key.equals("facing") && !key.equals("elev")) {
				return null;
			}
		}
		String map = string(object.get("map"));
		Integer x = coord(object.get("x"));
		Integer y = coord(object.get("y"));
		Dir facing = Dir.byId(string(object.get("facing")));
		if (map == null || !DataReader.MAP_ID.matcher(map).matches() || x == null || y == null || facing == null) {
			return null;
		}
		int elev = -1;
		if (object.has("elev")) {
			Integer e = coord(object.get("elev"));
			if (e == null || e < 0 || e > 15) {
				return null;
			}
			elev = e;
		}
		return new Place(map, x, y, facing, elev);
	}

	private static @Nullable String string(@Nullable JsonElement value) {
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}

	private static @Nullable Integer coord(@Nullable JsonElement value) {
		if (value == null || !value.isJsonPrimitive()) {
			return null;
		}
		JsonPrimitive primitive = value.getAsJsonPrimitive();
		if (!primitive.isNumber()) {
			return null;
		}
		// ProtoJson parses integral literals as Long (fractions as BigDecimal), like JsonFields expects on the client.
		Number number = primitive.getAsNumber();
		if (!(number instanceof Long || number instanceof Integer) || Math.abs(number.longValue()) > MAX_COORD) {
			return null;
		}
		return number.intValue();
	}

	@Override
	public String toString() {
		return this.map + ":" + this.x + ":" + this.y + ":" + this.facing.id();
	}
}
