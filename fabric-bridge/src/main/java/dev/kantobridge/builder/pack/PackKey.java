package dev.kantobridge.builder.pack;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * A key a texture pack's manifest names (docs/specs/VOXEL_BLOCKS_PLAN.md §4.9). The game is not part of a key: a
 * manifest names the games it targets, because Red and Blue share their tiles. Canonical text forms:
 *
 * <pre>
 * tile:&lt;atlas&gt;/&lt;tile&gt;                 a tile in every palette (a full-colour replacement)
 * tile:&lt;atlas&gt;/&lt;tile&gt;@&lt;PALETTE&gt;       the same tile in one palette
 * sprite:&lt;SPRITE_ID&gt;                  a character sheet or model
 * cell:&lt;atlas&gt;/&lt;t0&gt;-&lt;t1&gt;-&lt;t2&gt;-&lt;t3&gt;    a cell-sized unit (its tiles row-major)
 * struct:&lt;atlas&gt;/&lt;sig&gt;                a bounded structure by its 16-digit signature
 * place:&lt;MAP_ID&gt;/&lt;n&gt;                  one placement of a structure on a map
 * class:&lt;name&gt;                        a tile class, for shape rules ({@link #CLASSES})
 * map:&lt;MAP_ID&gt;                        per-map dressing
 * </pre>
 *
 * Pure; {@link #parse} throws {@link IllegalArgumentException} for anything else.
 */
public final class PackKey {
	/** The kinds of key. */
	public enum Kind {
		TILE("tile"),
		SPRITE("sprite"),
		CELL("cell"),
		STRUCT("struct"),
		PLACE("place"),
		CLASS("class"),
		MAP("map");

		private final String id;

		Kind(String id) {
			this.id = id;
		}

		public String id() {
			return this.id;
		}

		static @Nullable Kind byId(String id) {
			for (Kind kind : values()) {
				if (kind.id.equals(id)) {
					return kind;
				}
			}
			return null;
		}
	}

	/** The tile classes shape rules may name. */
	public static final List<String> CLASSES = List.of("ground", "water", "ledge", "wall", "roof", "tree", "grass", "flower", "counter", "prop",
			"door", "void");

	private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
	private static final Pattern SIG = Pattern.compile("^[0-9a-f]{16}$");

	private final Kind kind;
	/** The atlas (tile, cell, struct), sprite id (sprite), map id (place, map) or class name (class). */
	private final String scope;
	/** The tile (tile), four tiles (cell) or the placement number (place); empty otherwise. */
	private final int[] numbers;
	/** The palette of a palette tile key, else null. */
	private final @Nullable String palette;
	/** The signature of a struct key, else null. */
	private final @Nullable String sig;
	private final String text;

	private PackKey(Kind kind, String scope, int[] numbers, @Nullable String palette, @Nullable String sig) {
		this.kind = kind;
		this.scope = scope;
		this.numbers = numbers.clone();
		this.palette = palette;
		this.sig = sig;
		this.text = this.format();
	}

	public static PackKey tile(String atlas, int tile, @Nullable String palette) {
		return new PackKey(Kind.TILE, checkId(atlas), new int[] {checkTile(tile)}, palette == null ? null : checkId(palette), null);
	}

	public static PackKey sprite(String sprite) {
		return new PackKey(Kind.SPRITE, checkId(sprite), new int[0], null, null);
	}

	public static PackKey cell(String atlas, int[] tiles) {
		if (tiles.length != 4) {
			throw new IllegalArgumentException("a cell has four tiles");
		}
		for (int tile : tiles) {
			checkTile(tile);
		}
		return new PackKey(Kind.CELL, checkId(atlas), tiles, null, null);
	}

	public static PackKey struct(String atlas, String sig) {
		if (!SIG.matcher(sig).matches()) {
			throw new IllegalArgumentException("a signature is 16 hex digits");
		}
		return new PackKey(Kind.STRUCT, checkId(atlas), new int[0], null, sig);
	}

	public static PackKey place(String map, int n) {
		if (n < 0 || n > 65535) {
			throw new IllegalArgumentException("placement number out of range");
		}
		return new PackKey(Kind.PLACE, checkId(map), new int[] {n}, null, null);
	}

	public static PackKey clazz(String name) {
		if (!CLASSES.contains(name)) {
			throw new IllegalArgumentException("unknown class");
		}
		return new PackKey(Kind.CLASS, name, new int[0], null, null);
	}

	public static PackKey map(String map) {
		return new PackKey(Kind.MAP, checkId(map), new int[0], null, null);
	}

	private static String checkId(String id) {
		if (id == null || !ID.matcher(id).matches()) {
			throw new IllegalArgumentException("bad id");
		}
		return id;
	}

	private static int checkTile(int tile) {
		if (tile < 0 || tile > 1023) {
			throw new IllegalArgumentException("tile out of range");
		}
		return tile;
	}

	/** Parses a key's canonical text. */
	public static PackKey parse(String text) {
		int colon = text == null ? -1 : text.indexOf(':');
		if (colon < 1) {
			throw new IllegalArgumentException("not a pack key");
		}
		Kind kind = Kind.byId(text.substring(0, colon));
		if (kind == null) {
			throw new IllegalArgumentException("unknown key kind");
		}
		String rest = text.substring(colon + 1);
		PackKey key = switch (kind) {
			case TILE -> {
				int at = rest.indexOf('@');
				String palette = at < 0 ? null : rest.substring(at + 1);
				String body = at < 0 ? rest : rest.substring(0, at);
				int slash = body.lastIndexOf('/');
				if (slash < 1) {
					throw new IllegalArgumentException("tile:<atlas>/<tile>");
				}
				yield tile(body.substring(0, slash), number(body.substring(slash + 1)), palette);
			}
			case SPRITE -> sprite(rest);
			case CELL -> {
				int slash = rest.lastIndexOf('/');
				String[] parts = slash < 1 ? new String[0] : rest.substring(slash + 1).split("-", -1);
				if (parts.length != 4) {
					throw new IllegalArgumentException("cell:<atlas>/<t0>-<t1>-<t2>-<t3>");
				}
				int[] tiles = new int[4];
				for (int i = 0; i < 4; i++) {
					tiles[i] = number(parts[i]);
				}
				yield cell(rest.substring(0, slash), tiles);
			}
			case STRUCT -> {
				int slash = rest.lastIndexOf('/');
				if (slash < 1) {
					throw new IllegalArgumentException("struct:<atlas>/<sig>");
				}
				yield struct(rest.substring(0, slash), rest.substring(slash + 1));
			}
			case PLACE -> {
				int slash = rest.lastIndexOf('/');
				if (slash < 1) {
					throw new IllegalArgumentException("place:<MAP_ID>/<n>");
				}
				yield place(rest.substring(0, slash), number(rest.substring(slash + 1)));
			}
			case CLASS -> clazz(rest);
			case MAP -> map(rest);
		};
		if (!key.text.equals(text)) {
			throw new IllegalArgumentException("not canonical");
		}
		return key;
	}

	private static int number(String text) {
		if (!text.matches("[0-9]{1,5}")) {
			throw new IllegalArgumentException("not a number");
		}
		return Integer.parseInt(text, 10);
	}

	private String format() {
		StringBuilder out = new StringBuilder(this.kind.id).append(':').append(this.scope);
		switch (this.kind) {
			case TILE -> {
				out.append('/').append(this.numbers[0]);
				if (this.palette != null) {
					out.append('@').append(this.palette);
				}
			}
			case CELL -> out.append('/').append(this.numbers[0]).append('-').append(this.numbers[1]).append('-').append(this.numbers[2]).append('-')
					.append(this.numbers[3]);
			case STRUCT -> out.append('/').append(this.sig);
			case PLACE -> out.append('/').append(this.numbers[0]);
			default -> {
			}
		}
		return out.toString();
	}

	public Kind kind() {
		return this.kind;
	}

	/** The atlas, sprite id, map id or class name. */
	public String scope() {
		return this.scope;
	}

	/** A tile key's tile. */
	public int tile() {
		return this.numbers[0];
	}

	/** A cell key's four tiles (a copy). */
	public int[] tiles() {
		return this.numbers.clone();
	}

	/** A place key's placement number. */
	public int n() {
		return this.numbers[0];
	}

	public @Nullable String palette() {
		return this.palette;
	}

	public @Nullable String sig() {
		return this.sig;
	}

	public String text() {
		return this.text;
	}

	@Override
	public String toString() {
		return this.text;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof PackKey key && key.text.equals(this.text);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.text, Arrays.hashCode(this.numbers));
	}
}
