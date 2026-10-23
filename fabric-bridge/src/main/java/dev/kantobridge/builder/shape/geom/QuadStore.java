package dev.kantobridge.builder.shape.geom;

import java.util.Arrays;

/**
 * Quads packed three {@code long}s each, in pages: a shape table's millions of quads as a few arrays instead of three
 * objects a quad (2026-10-23: Hoenn's 6.2 million quads held about 1.3 GB as objects). Word 0 holds corners 0..9, word
 * 1 corners 10 and 11, the face, the cull face, the flags, whether the uvs are per corner, and the texture; word 2 the
 * uv bytes. A corner coordinate (−8..24) is stored plus 8 in 6 bits. Appending is for one builder thread; once built the
 * store is only read, so mesh threads read it freely.
 */
public final class QuadStore {
	/** Words a quad. */
	static final int WORDS = 3;
	private static final int PAGE_BITS = 16;
	private static final int PAGE_QUADS = 1 << PAGE_BITS;
	private static final int PAGE_MASK = PAGE_QUADS - 1;

	private long[][] pages = new long[0][];
	private int size;

	/** The quads stored. */
	public int size() {
		return this.size;
	}

	/** Appends a quad; returns its index. */
	public int add(ShapeQuad quad) {
		short[] xyz = quad.xyz();
		long w0 = 0;
		for (int c = 0; c < 10; c++) {
			w0 |= (long) (xyz[c] + 8) << (6 * c);
		}
		long w1 = (xyz[10] + 8) | (xyz[11] + 8) << 6 | quad.face() << 12 | (quad.cull() + 1) << 15 | (quad.flags() & 0xFF) << 18
				| (quad.uv().length == 8 ? 1 : 0) << 26 | (quad.texture() & 0xFFFFFFFFL) << 27;
		long w2 = 0;
		byte[] uv = quad.uv();
		for (int i = 0; i < uv.length; i++) {
			w2 |= (uv[i] & 0xFFL) << (8 * i);
		}
		return this.add(w0, w1, w2);
	}

	/** Appends a quad's three words as {@link #word} gives them (a cache file's); returns its index. */
	int add(long w0, long w1, long w2) {
		int page = this.size >>> PAGE_BITS;
		if (page == this.pages.length) {
			this.pages = Arrays.copyOf(this.pages, page + 1);
			this.pages[page] = new long[PAGE_QUADS * WORDS];
		}
		int at = (this.size & PAGE_MASK) * WORDS;
		long[] words = this.pages[page];
		words[at] = w0;
		words[at + 1] = w1;
		words[at + 2] = w2;
		return this.size++;
	}

	/** Word {@code w} (0..2) of quad {@code q}. */
	long word(int q, int w) {
		return this.pages[q >>> PAGE_BITS][(q & PAGE_MASK) * WORDS + w];
	}

	/** Drops the unused end of the last page (after building). */
	public void trim() {
		int page = this.size >>> PAGE_BITS;
		if (page < this.pages.length) {
			int used = (this.size & PAGE_MASK) * WORDS;
			this.pages[page] = Arrays.copyOf(this.pages[page], used);
		}
	}

	/** Bytes held by the quads' pages (for logs and tests). */
	public long bytes() {
		long n = 0;
		for (long[] page : this.pages) {
			n += 16L + page.length * 8L;
		}
		return n;
	}

	// ---- one quad's fields ----

	/** Coordinate {@code c} (0..11: corner c / 3, axis c % 3) of quad {@code q}. */
	int coord(int q, int c) {
		long word = c < 10 ? this.word(q, 0) >>> (6 * c) : this.word(q, 1) >>> (6 * (c - 10));
		return (int) (word & 63) - 8;
	}

	int face(int q) {
		return (int) (this.word(q, 1) >>> 12 & 7);
	}

	int cull(int q) {
		return (int) (this.word(q, 1) >>> 15 & 7) - 1;
	}

	int flags(int q) {
		return (int) (this.word(q, 1) >>> 18 & 0xFF);
	}

	boolean cornerUvs(int q) {
		return (this.word(q, 1) >>> 26 & 1) != 0;
	}

	int texture(int q) {
		return (int) (this.word(q, 1) >>> 27);
	}

	/** Uv byte {@code i} of quad {@code q} (0..3, or 0..7 with per-corner uvs). */
	int uv(int q, int i) {
		return (byte) (this.word(q, 2) >>> (8 * i));
	}

	/** Quad {@code q} as an object (for tests, the builder's checks and the self-check; the mesh threads read fields). */
	ShapeQuad quad(int q) {
		short[] xyz = new short[12];
		for (int c = 0; c < 12; c++) {
			xyz[c] = (short) this.coord(q, c);
		}
		byte[] uv = new byte[this.cornerUvs(q) ? 8 : 4];
		for (int i = 0; i < uv.length; i++) {
			uv[i] = (byte) this.uv(q, i);
		}
		return new ShapeQuad((byte) this.face(q), xyz, this.texture(q), uv, (byte) this.cull(q), (byte) this.flags(q));
	}

	/**
	 * True when three words read from a file make a well-formed quad ({@link ShapeQuad}'s own checks) whose texture is
	 * below {@code textures}.
	 */
	static boolean valid(long w0, long w1, long w2, int textures) {
		for (int c = 0; c < 12; c++) {
			long v = c < 10 ? w0 >>> (6 * c) & 63 : w1 >>> (6 * (c - 10)) & 63;
			if (v > 32) {
				return false;
			}
		}
		if ((w0 >>> 60) != 0 || (w1 >>> 59) != 0) {
			return false;
		}
		int face = (int) (w1 >>> 12 & 7);
		int cull = (int) (w1 >>> 15 & 7) - 1;
		boolean cornerUvs = (w1 >>> 26 & 1) != 0;
		long texture = w1 >>> 27;
		if (face > ShapeQuad.SLOPED || cull > ShapeQuad.EAST || (face == ShapeQuad.SLOPED && !cornerUvs) || texture >= textures) {
			return false;
		}
		return cornerUvs || (w2 >>> 32) == 0;
	}
}
