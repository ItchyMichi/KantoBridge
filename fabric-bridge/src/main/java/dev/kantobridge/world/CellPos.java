package dev.kantobridge.world;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One 16-px cell of a map, the unit of every protocol coordinate. Its key {@code "MAP:x:y"} names story cells
 * (protocol {@code cellKey}).
 */
public record CellPos(String map, int x, int y) {
	private static final Pattern KEY = Pattern.compile("^([A-Z0-9_]{1,64}):(-?[0-9]{1,6}):(-?[0-9]{1,6})$");

	public CellPos {
		Objects.requireNonNull(map, "map");
		if (!DataReader.MAP_ID.matcher(map).matches()) {
			throw new IllegalArgumentException("invalid map id");
		}
	}

	/** {@code "MAP:x:y"}. */
	public String key() {
		return key(this.map, this.x, this.y);
	}

	public static String key(String map, int x, int y) {
		return map + ":" + x + ":" + y;
	}

	/** Parses {@code "MAP:x:y"}; throws {@link IllegalArgumentException} for anything else. */
	public static CellPos parse(String key) {
		Matcher m = KEY.matcher(key);
		if (!m.matches()) {
			throw new IllegalArgumentException("invalid cell key");
		}
		return new CellPos(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
	}

	public static boolean isKey(String key) {
		return KEY.matcher(key).matches();
	}

	@Override
	public String toString() {
		return this.key();
	}
}
