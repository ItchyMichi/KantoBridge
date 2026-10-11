package dev.kantobridge.builder.shape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * A pixel-free name of a shape (docs/specs/VOXEL_BLOCKS_PLAN.md §4.2): which rule, which atlas (an index into the shape
 * index's sorted atlas list), which tile ids and offsets. Every client turns the same key into geometry with its own
 * art, so a key never holds a pixel (I8). Canonical text forms:
 *
 * <pre>
 * cube:&lt;a&gt;|&lt;u&gt;,&lt;d&gt;,&lt;n&gt;,&lt;s&gt;,&lt;w&gt;,&lt;e&gt;               a full cube, one tile per face
 * slab:&lt;a&gt;|&lt;tile&gt;|&lt;h&gt;                                a box h/16 high
 * roof:&lt;a&gt;|&lt;u&gt;,&lt;s&gt;,&lt;n&gt;|&lt;h0&gt;,&lt;h1&gt;|&lt;hipW&gt;,&lt;hipE&gt;    a wedge
 * tuft:&lt;a&gt;|&lt;tile&gt;                                     standing cut-outs of the tile's strokes
 * cell:&lt;a&gt;|&lt;t0&gt;,&lt;t1&gt;,&lt;t2&gt;,&lt;t3&gt;|&lt;g&gt;|&lt;bx&gt;,&lt;by&gt;,&lt;bz&gt;     one block of a cell-sized unit
 * tmpl:&lt;a&gt;|&lt;sig&gt;|&lt;bx&gt;,&lt;by&gt;,&lt;bz&gt;                     one block of a bounded structure
 * box:&lt;a&gt;|&lt;u&gt;,&lt;n&gt;,&lt;s&gt;,&lt;w&gt;,&lt;e&gt;|&lt;h&gt;              a box h/16 high without a bottom (P3's class boxes)
 * hang:&lt;a&gt;|&lt;t&gt;|&lt;y0&gt;|&lt;d&gt;,&lt;b&gt;,&lt;p&gt;,&lt;w&gt;,&lt;x&gt;,&lt;z&gt;        a box hanging from the block's top down to y0, and a spike
 *                                              (docs/specs/CAVES_AND_WOODS_PLAN.md §4.1)
 * vine:&lt;a&gt;|&lt;t&gt;|&lt;x&gt;,&lt;z&gt;,&lt;w&gt;,&lt;r&gt;|&lt;top&gt;,&lt;len&gt;,&lt;k&gt;|&lt;n&gt;,&lt;s1&gt;,&lt;s2&gt;,&lt;e1&gt;,&lt;e2&gt;   one block of a leaf strand (CW2)
 * strand:&lt;a&gt;|&lt;x&gt;,&lt;z&gt;,&lt;r&gt;|&lt;top&gt;,&lt;len&gt;,&lt;k&gt;         one block of a stem-and-stub strand (CW2)
 * modifiers, in this order when present:  |tint=&lt;0..3&gt;  |v=&lt;variant&gt;
 * </pre>
 *
 * Keys order by kind name, then atlas, then each number numerically (never by comparing the text of numbers), then the
 * signature, then the modifiers.
 */
public final class ShapeKey implements Comparable<ShapeKey> {
	/** The kinds, with the number of values in each of their number groups. */
	public enum Kind {
		CUBE("cube", 6),
		SLAB("slab", 1, 1),
		ROOF("roof", 3, 2, 2),
		TUFT("tuft", 1),
		CELL("cell", 4, 1, 3),
		TMPL("tmpl", 3),
		BOX("box", 5, 1),
		HANG("hang", 1, 1, 6),
		VINE("vine", 1, 4, 3, 5),
		STRAND("strand", 3, 3);

		private final String id;
		private final int[] groups;

		Kind(String id, int... groups) {
			this.id = id;
			this.groups = groups;
		}

		public String id() {
			return this.id;
		}

		/** Values per number group (a template's signature comes before its one group). */
		int[] groups() {
			return this.groups;
		}

		public static @Nullable Kind byId(String id) {
			for (Kind kind : values()) {
				if (kind.id.equals(id)) {
					return kind;
				}
			}
			return null;
		}
	}

	private static final Pattern SIG = Pattern.compile("^[0-9a-f]{16}$");
	private static final Pattern VARIANT = Pattern.compile("^[a-z0-9_]{1,32}$");
	private static final Comparator<ShapeKey> ORDER = Comparator.comparing((ShapeKey k) -> k.kind.id)
			.thenComparingInt(k -> k.atlas)
			.thenComparing(k -> k.numbers, Arrays::compare)
			.thenComparing(k -> k.sig == null ? "" : k.sig)
			.thenComparingInt(k -> k.tint)
			.thenComparing(k -> k.variant == null ? "" : k.variant);

	private final Kind kind;
	private final int atlas;
	private final int[] numbers;
	private final @Nullable String sig;
	/** 0 for none; 1..3 a darkening step. */
	private final int tint;
	private final @Nullable String variant;
	private final String text;

	private ShapeKey(Kind kind, int atlas, int[] numbers, @Nullable String sig, int tint, @Nullable String variant) {
		this.kind = Objects.requireNonNull(kind, "kind");
		if (atlas < 0 || atlas > 65535) {
			throw new IllegalArgumentException("atlas index out of range");
		}
		int expected = 0;
		for (int n : kind.groups()) {
			expected += n;
		}
		if (numbers.length != expected) {
			throw new IllegalArgumentException("a " + kind.id + " key has " + expected + " numbers");
		}
		for (int n : numbers) {
			if (n < -1 || n > 65535) {
				throw new IllegalArgumentException("number out of range");
			}
		}
		switch (kind) {
			case SLAB -> check(numbers[1] >= 1 && numbers[1] <= 16, "slab height out of range");
			case ROOF -> check(numbers[3] >= 0 && numbers[3] <= 16 && numbers[4] >= 0 && numbers[4] <= 16 && (numbers[5] == 0 || numbers[5] == 1)
					&& (numbers[6] == 0 || numbers[6] == 1), "roof heights or hips out of range");
			case CELL -> check(numbers[5] >= 0 && numbers[6] >= 0 && numbers[7] >= 0, "negative block offset");
			case TMPL -> check(numbers[0] >= 0 && numbers[1] >= 0 && numbers[2] >= 0, "negative block offset");
			case BOX -> check(numbers[5] >= 1 && numbers[5] <= 16, "box height out of range");
			case HANG -> check(hangValid(numbers), "hanging box or spike out of range");
			case VINE -> check(strandValid(numbers[1], numbers[2], numbers[4], numbers[5], numbers[6], numbers[7]) && numbers[0] >= 0 && numbers[3] >= 1
					&& numbers[3] <= 8 && stubsValid(numbers), "vine out of range");
			case STRAND -> check(strandValid(numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], numbers[5]), "strand out of range");
			default -> {
			}
		}
		if ((kind == Kind.TMPL) != (sig != null) || (sig != null && !SIG.matcher(sig).matches())) {
			throw new IllegalArgumentException("a template key needs a 16-digit signature, others none");
		}
		if (tint < 0 || tint > 3) {
			throw new IllegalArgumentException("tint out of range");
		}
		if (variant != null && !VARIANT.matcher(variant).matches()) {
			throw new IllegalArgumentException("bad variant");
		}
		this.atlas = atlas;
		this.numbers = numbers.clone();
		this.sig = sig;
		this.tint = tint;
		this.variant = variant;
		this.text = this.format();
	}

	private static void check(boolean ok, String problem) {
		if (!ok) {
			throw new IllegalArgumentException(problem);
		}
	}

	/**
	 * A strand's place: (x, z) in its column, a turn of r × π/16, its top in its top block, its length, and k, the blocks
	 * this one lies below the top block; the strand must reach into block k.
	 */
	private static boolean strandValid(int x, int z, int r, int top, int len, int k) {
		return x >= 0 && x <= 16 && z >= 0 && z <= 16 && r >= 0 && r <= 15 && top >= 1 && top <= 16 && len >= 1 && len <= 255 && k >= 0
				&& k <= 15 && top - len + 16 * k < 16;
	}

	/** A vine's stubs: one or two, each a height above the tail and a side; the second all 0 when there is one. */
	private static boolean stubsValid(int[] n) {
		int len = n[6];
		if (n[8] < 1 || n[8] > 2 || n[9] < 0 || n[9] > len || (n[11] != 0 && n[11] != 1)) {
			return false;
		}
		return n[8] == 1 ? n[10] == 0 && n[12] == 0 : n[10] >= 0 && n[10] <= len && (n[12] == 0 || n[12] == 1);
	}

	/** A hanging box's numbers: tile, y0, then the spike (direction, base, apex, half-width, x, z; all 0 for none). */
	private static boolean hangValid(int[] n) {
		if (n[0] < 0 || n[1] < 0 || n[1] > 16) {
			return false;
		}
		int d = n[2];
		if (d == 0) {
			return n[3] == 0 && n[4] == 0 && n[5] == 0 && n[6] == 0 && n[7] == 0;
		}
		boolean ends = d == 1 ? n[4] < n[3] : d == 2 && n[3] < n[4];
		return ends && n[3] >= 0 && n[3] <= 16 && n[4] >= 0 && n[4] <= 16 && n[5] >= 1 && n[5] <= 8 && n[6] >= 0 && n[6] <= 16 && n[7] >= 0
				&& n[7] <= 16;
	}

	// ---- factories ----

	/** A full cube with one tile per face (up, down, north, south, west, east). */
	public static ShapeKey cube(int atlas, int up, int down, int north, int south, int west, int east) {
		return new ShapeKey(Kind.CUBE, atlas, new int[] {up, down, north, south, west, east}, null, 0, null);
	}

	/** A box {@code h}/16 high with the tile on top and its lowest rows on its sides. */
	public static ShapeKey slab(int atlas, int tile, int h) {
		return new ShapeKey(Kind.SLAB, atlas, new int[] {tile, h}, null, 0, null);
	}

	/** A wedge inside one block: its top from {@code h0}/16 at the south edge to {@code h1}/16 at the north edge. */
	public static ShapeKey roof(int atlas, int up, int south, int north, int h0, int h1, boolean hipWest, boolean hipEast) {
		return new ShapeKey(Kind.ROOF, atlas, new int[] {up, south, north, h0, h1, hipWest ? 1 : 0, hipEast ? 1 : 0}, null, 0, null);
	}

	/** Standing cut-outs of a tile's strokes over the ground block below. */
	public static ShapeKey tuft(int atlas, int tile) {
		return new ShapeKey(Kind.TUFT, atlas, new int[] {tile}, null, 0, null);
	}

	/** One block of a cell-sized unit: its four tiles, its ground tile, and the block's offset in the unit. */
	public static ShapeKey cell(int atlas, int[] tiles, int ground, int bx, int by, int bz) {
		return new ShapeKey(Kind.CELL, atlas, new int[] {tiles[0], tiles[1], tiles[2], tiles[3], ground, bx, by, bz}, null, 0, null);
	}

	/** One block of a bounded structure, by its template's signature. */
	public static ShapeKey tmpl(int atlas, String sig, int bx, int by, int bz) {
		return new ShapeKey(Kind.TMPL, atlas, new int[] {bx, by, bz}, sig, 0, null);
	}

	/**
	 * A box {@code h}/16 high without a bottom (the profile's class boxes, P3): its top wearing {@code up}, its sides
	 * {@code north}, {@code south}, {@code west} and {@code east}, each showing its tile's lowest rows; -1: no face.
	 */
	public static ShapeKey box(int atlas, int up, int north, int south, int west, int east, int h) {
		return new ShapeKey(Kind.BOX, atlas, new int[] {up, north, south, west, east, h}, null, 0, null);
	}

	/** A spike's direction in a {@code hang} key: none, hanging (a stalactite), standing (a stalagmite). */
	public static final int SPIKE_NONE = 0;
	public static final int SPIKE_DOWN = 1;
	public static final int SPIKE_UP = 2;

	/**
	 * A box hanging from the block's top down to {@code y0}/16 (16: none), wearing {@code tile}, and a spike: a square
	 * pyramid of half-width {@code w}, its base centred at ({@code x}, {@code z}) at height {@code base}, its apex at
	 * {@code apex} ({@code dir} {@link #SPIKE_NONE} with every spike number 0 for none). Cave rock (CW1).
	 */
	public static ShapeKey hang(int atlas, int tile, int y0, int dir, int base, int apex, int w, int x, int z) {
		return new ShapeKey(Kind.HANG, atlas, new int[] {tile, y0, dir, base, apex, w, x, z}, null, 0, null);
	}

	/**
	 * One block of a leaf strand (CW2's vines): two crossed quads of half-width {@code w} about ({@code x}, {@code z}), turned
	 * {@code r} × π/16, hanging from {@code top} (in its top block) down {@code len}, wearing {@code tile} stretched along
	 * it; {@code stubs} stubs at heights {@code s1}, {@code s2} above the tail on sides {@code e1}, {@code e2} (0 west, 1
	 * east). This key draws block {@code k} below the top block.
	 */
	public static ShapeKey vine(int atlas, int tile, int x, int z, int w, int r, int top, int len, int k, int stubs, int s1, int s2, int e1,
			int e2) {
		return new ShapeKey(Kind.VINE, atlas, new int[] {tile, x, z, w, r, top, len, k, stubs, s1, s2, e1, e2}, null, 0, null);
	}

	/**
	 * One block of a stem-and-stub strand (CW2's second vine kind): the pattern in three greens on two crossed planes
	 * turned {@code r} × π/16 about ({@code x}, {@code z}), hanging from {@code top} (in its top block) down {@code len};
	 * this key draws block {@code k} below the top block.
	 */
	public static ShapeKey strand(int atlas, int x, int z, int r, int top, int len, int k) {
		return new ShapeKey(Kind.STRAND, atlas, new int[] {x, z, r, top, len, k}, null, 0, null);
	}

	/** A carrier that draws nothing: a solid cell's blocks over a shape lower than its column (PD7). */
	public static ShapeKey empty(int atlas) {
		return box(atlas, -1, -1, -1, -1, -1, 16);
	}

	/** This key with a darkening step. */
	public ShapeKey withTint(int tint) {
		return new ShapeKey(this.kind, this.atlas, this.numbers, this.sig, tint, this.variant);
	}

	/** This key with a variant. */
	public ShapeKey withVariant(@Nullable String variant) {
		return new ShapeKey(this.kind, this.atlas, this.numbers, this.sig, this.tint, variant);
	}

	// ---- views ----

	public Kind kind() {
		return this.kind;
	}

	public int atlas() {
		return this.atlas;
	}

	/** The key's numbers, all groups in order (a copy). */
	public int[] numbers() {
		return this.numbers.clone();
	}

	public int number(int i) {
		return this.numbers[i];
	}

	public @Nullable String sig() {
		return this.sig;
	}

	public int tint() {
		return this.tint;
	}

	public @Nullable String variant() {
		return this.variant;
	}

	/** The canonical text. */
	public String text() {
		return this.text;
	}

	@Override
	public String toString() {
		return this.text;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof ShapeKey key && key.text.equals(this.text);
	}

	@Override
	public int hashCode() {
		return this.text.hashCode();
	}

	@Override
	public int compareTo(ShapeKey other) {
		return ORDER.compare(this, other);
	}

	private String format() {
		StringBuilder out = new StringBuilder(this.kind.id).append(':').append(this.atlas);
		if (this.sig != null) {
			out.append('|').append(this.sig);
		}
		int i = 0;
		for (int group : this.kind.groups()) {
			out.append('|');
			for (int g = 0; g < group; g++) {
				if (g > 0) {
					out.append(',');
				}
				out.append(this.numbers[i++]);
			}
		}
		if (this.tint != 0) {
			out.append("|tint=").append(this.tint);
		}
		if (this.variant != null) {
			out.append("|v=").append(this.variant);
		}
		return out.toString();
	}

	/** Parses a key's canonical text; throws {@link IllegalArgumentException} for anything else (not canonical included). */
	public static ShapeKey parse(String text) {
		int colon = text.indexOf(':');
		if (colon < 1) {
			throw new IllegalArgumentException("not a shape key");
		}
		Kind kind = Kind.byId(text.substring(0, colon));
		if (kind == null) {
			throw new IllegalArgumentException("unknown shape kind");
		}
		String[] parts = text.substring(colon + 1).split("\\|", -1);
		int p = 0;
		int atlas = integer(parts[p++]);
		String sig = null;
		if (kind == Kind.TMPL) {
			if (parts.length <= p) {
				throw new IllegalArgumentException("missing signature");
			}
			sig = parts[p++];
		}
		List<Integer> numbers = new ArrayList<>();
		for (int group : kind.groups()) {
			if (parts.length <= p) {
				throw new IllegalArgumentException("missing numbers");
			}
			String[] values = parts[p++].split(",", -1);
			if (values.length != group) {
				throw new IllegalArgumentException("wrong group size");
			}
			for (String value : values) {
				numbers.add(integer(value));
			}
		}
		int tint = 0;
		String variant = null;
		while (p < parts.length) {
			String modifier = parts[p++];
			if (modifier.startsWith("tint=") && tint == 0 && variant == null) {
				tint = integer(modifier.substring(5));
			} else if (modifier.startsWith("v=") && variant == null) {
				variant = modifier.substring(2);
			} else {
				throw new IllegalArgumentException("unknown modifier");
			}
		}
		ShapeKey key = new ShapeKey(kind, atlas, numbers.stream().mapToInt(Integer::intValue).toArray(), sig, tint, variant);
		if (!key.text.equals(text)) {
			throw new IllegalArgumentException("not canonical");
		}
		return key;
	}

	private static int integer(String text) {
		if (!text.matches("-?[0-9]{1,6}")) {
			throw new IllegalArgumentException("not a number");
		}
		return Integer.parseInt(text, 10);
	}
}
