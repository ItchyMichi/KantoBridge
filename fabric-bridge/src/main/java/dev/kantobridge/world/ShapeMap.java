package dev.kantobridge.world;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.jetbrains.annotations.Nullable;

/**
 * Position → shape id for a world (docs/specs/VOXEL_BLOCKS_PLAN.md §4.3, {@code <world>/kantobridge/shape_map.bin}):
 * ids only, never geometry or art. Big-endian:
 *
 * <pre>
 * magic "KBSM", u8 version 1, indexHash (32 bytes: the WorldIndex text's SHA-256), shapeHash (32: the shape index's)
 * u16 grids, sorted by (baseY, z0, x0); per grid:
 *   i32 x0, i32 z0 (the north-west column), u16 w, u16 d, i16 baseY (the lowest described block)
 *   u32 stacks (stack 0 is the empty one); per stack: u8 n, n × u24 shape id, bottom-up from baseY
 *   w × d × u32 stack index, row-major (z outer, x inner)
 * u16 overlays, sorted by key; per overlay: UTF key, i32 x, i16 y, i32 z (north-west column of the cell, feet level),
 *   4 × (u8 n, n × u24 shape id): columns north-west, north-east, south-west, south-east, bottom-up from y
 * </pre>
 *
 * {@link #hash()} is the SHA-256 of {@link #encode()}. The two header hashes bind the map to one WorldIndex and one
 * shape index: a client holding another index draws cubes. On the wire it travels as Base64 of its deflated bytes
 * ({@link #wire()}). Pure; {@link Lookup} answers {@code shapeAt(x, y, z)} without locks or allocation.
 */
public final class ShapeMap {
	public static final int VERSION = 1;
	private static final byte[] MAGIC = "KBSM".getBytes(StandardCharsets.US_ASCII);
	private static final int MAX_ID = (1 << 24) - 1;
	/** An unset header hash (a map planned before its WorldIndex existed). */
	public static final String NO_HASH = "0".repeat(64);
	/** The longest stack a column may hold. */
	public static final int MAX_STACK = 255;

	/** One box of columns: per column a stack of shape ids from {@code baseY} up. */
	public record Grid(int x0, int z0, int w, int d, int baseY, int[][] stacks, int[] columns) {
		public Grid {
			if (w < 1 || d < 1 || w > 65535 || d > 65535 || columns.length != w * d) {
				throw new IllegalArgumentException("bad grid size");
			}
			if (stacks.length == 0 || stacks[0].length != 0) {
				throw new IllegalArgumentException("stack 0 must be the empty stack");
			}
		}

		/** The highest Y the grid describes, exclusive. */
		public int maxY() {
			int max = 0;
			for (int[] stack : this.stacks) {
				max = Math.max(max, stack.length);
			}
			return this.baseY + max;
		}

		public boolean containsColumn(int x, int z) {
			return x >= this.x0 && x < this.x0 + this.w && z >= this.z0 && z < this.z0 + this.d;
		}

		/** The id at (x, y, z), 0 outside the grid or above its stack. */
		public int at(int x, int y, int z) {
			if (!this.containsColumn(x, z)) {
				return 0;
			}
			int[] stack = this.stacks[this.columns[(z - this.z0) * this.w + (x - this.x0)]];
			int dy = y - this.baseY;
			return dy >= 0 && dy < stack.length ? stack[dy] : 0;
		}
	}

	/** A closed story cell's shapes (from V5): four columns, bottom-up from the cell's feet level. */
	public record Overlay(String key, int x, int y, int z, int[][] columns) {
		public Overlay {
			if (columns.length != 4) {
				throw new IllegalArgumentException("a cell has four columns");
			}
		}
	}

	private final String indexHash;
	private final String shapeHash;
	private final List<Grid> grids;
	private final List<Overlay> overlays;
	private final byte[] bytes;
	private final String hash;

	public ShapeMap(String indexHash, String shapeHash, List<Grid> grids, List<Overlay> overlays) {
		this.indexHash = checkHash(indexHash);
		this.shapeHash = checkHash(shapeHash);
		List<Grid> sortedGrids = new ArrayList<>(grids);
		sortedGrids.sort(Comparator.comparingInt(Grid::baseY).thenComparingInt(Grid::z0).thenComparingInt(Grid::x0));
		List<Overlay> sortedOverlays = new ArrayList<>(overlays);
		sortedOverlays.sort(Comparator.comparing(Overlay::key));
		this.grids = List.copyOf(sortedGrids);
		this.overlays = List.copyOf(sortedOverlays);
		this.bytes = this.encodeBytes();
		this.hash = Hashes.sha256(this.bytes);
	}

	private static String checkHash(String hash) {
		if (hash == null || !hash.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("a header hash is 64 lowercase hex digits");
		}
		return hash;
	}

	/** The same map bound to a WorldIndex and a shape index. */
	public ShapeMap withHeader(String indexHash, String shapeHash) {
		return new ShapeMap(indexHash, shapeHash, this.grids, this.overlays);
	}

	public String indexHash() {
		return this.indexHash;
	}

	public String shapeHash() {
		return this.shapeHash;
	}

	public List<Grid> grids() {
		return this.grids;
	}

	public List<Overlay> overlays() {
		return this.overlays;
	}

	/** The file's bytes (a copy). */
	public byte[] encode() {
		return this.bytes.clone();
	}

	public int size() {
		return this.bytes.length;
	}

	/** SHA-256 of {@link #encode()}. */
	public String hash() {
		return this.hash;
	}

	/** The id at (x, y, z) by scanning the grids (the builder's and the tests' path; clients use {@link #lookup()}). */
	public int shapeAt(int x, int y, int z) {
		for (Grid grid : this.grids) {
			if (y >= grid.baseY() && y < grid.maxY() && grid.containsColumn(x, z)) {
				return grid.at(x, y, z);
			}
		}
		return 0;
	}

	/** Positions with a nonzero id. */
	public long shapedBlocks() {
		long n = 0;
		for (Grid grid : this.grids) {
			int[] perStack = new int[grid.stacks().length];
			for (int s = 0; s < grid.stacks().length; s++) {
				for (int id : grid.stacks()[s]) {
					perStack[s] += id != 0 ? 1 : 0;
				}
			}
			for (int column : grid.columns()) {
				n += perStack[column];
			}
		}
		return n;
	}

	// ---- codec ----

	private byte[] encodeBytes() {
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(raw)) {
			out.write(MAGIC);
			out.writeByte(VERSION);
			out.write(HexFormat.of().parseHex(this.indexHash));
			out.write(HexFormat.of().parseHex(this.shapeHash));
			if (this.grids.size() > 65535 || this.overlays.size() > 65535) {
				throw new IllegalStateException("too many grids or overlays");
			}
			out.writeShort(this.grids.size());
			for (Grid grid : this.grids) {
				out.writeInt(grid.x0());
				out.writeInt(grid.z0());
				out.writeShort(grid.w());
				out.writeShort(grid.d());
				out.writeShort(grid.baseY());
				out.writeInt(grid.stacks().length);
				for (int[] stack : grid.stacks()) {
					writeStack(out, stack);
				}
				for (int column : grid.columns()) {
					out.writeInt(column);
				}
			}
			out.writeShort(this.overlays.size());
			for (Overlay overlay : this.overlays) {
				out.writeUTF(overlay.key());
				out.writeInt(overlay.x());
				out.writeShort(overlay.y());
				out.writeInt(overlay.z());
				for (int[] column : overlay.columns()) {
					writeStack(out, column);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return raw.toByteArray();
	}

	private static void writeStack(DataOutputStream out, int[] stack) throws IOException {
		if (stack.length > MAX_STACK) {
			throw new IllegalStateException("stack too high");
		}
		out.writeByte(stack.length);
		for (int id : stack) {
			if (id < 0 || id > MAX_ID) {
				throw new IllegalStateException("shape id out of range");
			}
			out.writeByte(id >>> 16);
			out.writeByte(id >>> 8);
			out.writeByte(id);
		}
	}

	/** Reads a map's bytes; anything malformed is a {@link DataException}. */
	public static ShapeMap decode(byte[] bytes) throws DataException {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			byte[] magic = new byte[4];
			in.readFully(magic);
			if (!Arrays.equals(magic, MAGIC)) {
				throw new DataException("/magic", "not a shape map");
			}
			if (in.readUnsignedByte() != VERSION) {
				throw new DataException("/version", "unsupported version");
			}
			byte[] hash = new byte[32];
			in.readFully(hash);
			String indexHash = HexFormat.of().formatHex(hash);
			in.readFully(hash);
			String shapeHash = HexFormat.of().formatHex(hash);
			int gridCount = in.readUnsignedShort();
			List<Grid> grids = new ArrayList<>(gridCount);
			for (int g = 0; g < gridCount; g++) {
				int x0 = in.readInt();
				int z0 = in.readInt();
				int w = in.readUnsignedShort();
				int d = in.readUnsignedShort();
				int baseY = in.readShort();
				long stackCount = in.readInt() & 0xFFFFFFFFL;
				if (stackCount < 1 || stackCount > bytes.length) {
					throw new DataException("/grids/" + g, "bad stack count");
				}
				int[][] stacks = new int[(int) stackCount][];
				for (int s = 0; s < stackCount; s++) {
					stacks[s] = readStack(in);
				}
				if (w < 1 || d < 1 || (long) w * d * 4 > bytes.length) {
					throw new DataException("/grids/" + g, "bad grid size");
				}
				int[] columns = new int[w * d];
				for (int i = 0; i < columns.length; i++) {
					int column = in.readInt();
					if (column < 0 || column >= stacks.length) {
						throw new DataException("/grids/" + g, "column names a missing stack");
					}
					columns[i] = column;
				}
				try {
					grids.add(new Grid(x0, z0, w, d, baseY, stacks, columns));
				} catch (IllegalArgumentException e) {
					throw new DataException("/grids/" + g, e.getMessage());
				}
			}
			int overlayCount = in.readUnsignedShort();
			List<Overlay> overlays = new ArrayList<>(overlayCount);
			for (int o = 0; o < overlayCount; o++) {
				String key = in.readUTF();
				int x = in.readInt();
				int y = in.readShort();
				int z = in.readInt();
				int[][] columns = new int[4][];
				for (int c = 0; c < 4; c++) {
					columns[c] = readStack(in);
				}
				overlays.add(new Overlay(key, x, y, z, columns));
			}
			if (in.read() != -1) {
				throw new DataException("/", "trailing bytes");
			}
			ShapeMap map = new ShapeMap(indexHash, shapeHash, grids, overlays);
			if (!Arrays.equals(map.bytes, bytes)) {
				throw new DataException("/", "not canonical");
			}
			return map;
		} catch (EOFException e) {
			throw new DataException("/", "truncated");
		} catch (IOException e) {
			throw new DataException("/", "unreadable");
		}
	}

	private static int[] readStack(DataInputStream in) throws IOException, DataException {
		int n = in.readUnsignedByte();
		int[] stack = new int[n];
		for (int i = 0; i < n; i++) {
			stack[i] = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
		}
		return stack;
	}

	// ---- wire form ----

	/** The wire form: Base64 of the deflated file bytes (one {@code world.shapes} document). */
	public String wire() {
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
		deflater.setInput(this.bytes);
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[65536];
		while (!deflater.finished()) {
			out.write(buffer, 0, deflater.deflate(buffer));
		}
		deflater.end();
		return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
	}

	/** Reads the wire form; refuses more than {@code maxBytes} once inflated. */
	public static ShapeMap fromWire(String text, int maxBytes) throws DataException {
		byte[] deflated;
		try {
			deflated = java.util.Base64.getDecoder().decode(text);
		} catch (IllegalArgumentException e) {
			throw new DataException("/", "not base64");
		}
		Inflater inflater = new Inflater(true);
		inflater.setInput(deflated);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[65536];
		try {
			while (!inflater.finished()) {
				int n = inflater.inflate(buffer);
				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					throw new DataException("/", "truncated");
				}
				out.write(buffer, 0, n);
				if (out.size() > maxBytes) {
					throw new DataException("/", "too large");
				}
			}
		} catch (DataFormatException e) {
			throw new DataException("/", "not deflated");
		} finally {
			inflater.end();
		}
		return decode(out.toByteArray());
	}

	// ---- building ----

	/** Collects a box of columns' ids; positions outside the box are refused. */
	public static final class GridBuilder {
		private final int x0;
		private final int z0;
		private final int w;
		private final int d;
		private final int baseY;
		private final int[][] columns;

		public GridBuilder(int x0, int z0, int w, int d, int baseY) {
			this.x0 = x0;
			this.z0 = z0;
			this.w = w;
			this.d = d;
			this.baseY = baseY;
			this.columns = new int[w * d][];
		}

		public boolean contains(int x, int z) {
			return x >= this.x0 && x < this.x0 + this.w && z >= this.z0 && z < this.z0 + this.d;
		}

		/** Sets the id at (x, y, z); y at or above {@code baseY}. */
		public void set(int x, int y, int z, int id) {
			if (!this.contains(x, z) || y < this.baseY || y - this.baseY >= MAX_STACK) {
				throw new IllegalArgumentException("outside the grid");
			}
			int i = (z - this.z0) * this.w + (x - this.x0);
			int dy = y - this.baseY;
			int[] stack = this.columns[i];
			if (stack == null || stack.length <= dy) {
				stack = stack == null ? new int[dy + 1] : Arrays.copyOf(stack, dy + 1);
				this.columns[i] = stack;
			}
			stack[dy] = id;
		}

		/** The grid: stacks numbered in row-major order of first use, stack 0 empty; trailing zeros dropped. */
		public Grid build() {
			Map<String, Integer> ids = new HashMap<>();
			List<int[]> stacks = new ArrayList<>();
			stacks.add(new int[0]);
			ids.put("", 0);
			int[] out = new int[this.columns.length];
			for (int i = 0; i < this.columns.length; i++) {
				int[] stack = trim(this.columns[i]);
				String key = Arrays.toString(stack);
				if (stack.length == 0) {
					out[i] = 0;
					continue;
				}
				Integer id = ids.get(key);
				if (id == null) {
					id = stacks.size();
					stacks.add(stack);
					ids.put(key, id);
				}
				out[i] = id;
			}
			return new Grid(this.x0, this.z0, this.w, this.d, this.baseY, stacks.toArray(new int[0][]), out);
		}

		private static int[] trim(int @Nullable [] stack) {
			if (stack == null) {
				return new int[0];
			}
			int n = stack.length;
			while (n > 0 && stack[n - 1] == 0) {
				n--;
			}
			return n == stack.length ? stack : Arrays.copyOf(stack, n);
		}
	}

	// ---- the client's lookup ----

	/** A fast, allocation-free {@code shapeAt} over a decoded map: per chunk column, the grids touching it. */
	public Lookup lookup() {
		return new Lookup(this);
	}

	public static final class Lookup {
		private final Grid[] grids;
		private final int[] minY;
		private final int[] maxY;
		private final int cx0;
		private final int cz0;
		private final int cw;
		private final int cd;
		private final int[][] byChunk;

		Lookup(ShapeMap map) {
			this.grids = map.grids.toArray(new Grid[0]);
			this.minY = new int[this.grids.length];
			this.maxY = new int[this.grids.length];
			int minCx = Integer.MAX_VALUE;
			int minCz = Integer.MAX_VALUE;
			int maxCx = Integer.MIN_VALUE;
			int maxCz = Integer.MIN_VALUE;
			for (int g = 0; g < this.grids.length; g++) {
				Grid grid = this.grids[g];
				this.minY[g] = grid.baseY();
				this.maxY[g] = grid.maxY();
				minCx = Math.min(minCx, grid.x0() >> 4);
				minCz = Math.min(minCz, grid.z0() >> 4);
				maxCx = Math.max(maxCx, (grid.x0() + grid.w() - 1) >> 4);
				maxCz = Math.max(maxCz, (grid.z0() + grid.d() - 1) >> 4);
			}
			if (this.grids.length == 0) {
				this.cx0 = 0;
				this.cz0 = 0;
				this.cw = 0;
				this.cd = 0;
				this.byChunk = new int[0][];
				return;
			}
			this.cx0 = minCx;
			this.cz0 = minCz;
			this.cw = maxCx - minCx + 1;
			this.cd = maxCz - minCz + 1;
			List<List<Integer>> lists = new ArrayList<>(this.cw * this.cd);
			for (int i = 0; i < this.cw * this.cd; i++) {
				lists.add(null);
			}
			for (int g = 0; g < this.grids.length; g++) {
				Grid grid = this.grids[g];
				for (int cz = grid.z0() >> 4; cz <= (grid.z0() + grid.d() - 1) >> 4; cz++) {
					for (int cx = grid.x0() >> 4; cx <= (grid.x0() + grid.w() - 1) >> 4; cx++) {
						int i = (cz - this.cz0) * this.cw + (cx - this.cx0);
						if (lists.get(i) == null) {
							lists.set(i, new ArrayList<>());
						}
						lists.get(i).add(g);
					}
				}
			}
			this.byChunk = new int[lists.size()][];
			for (int i = 0; i < lists.size(); i++) {
				List<Integer> list = lists.get(i);
				this.byChunk[i] = list == null ? null : list.stream().mapToInt(Integer::intValue).toArray();
			}
		}

		/** The shape id at (x, y, z); 0 where the map has none. */
		public int shapeAt(int x, int y, int z) {
			int cx = (x >> 4) - this.cx0;
			int cz = (z >> 4) - this.cz0;
			if (cx < 0 || cz < 0 || cx >= this.cw || cz >= this.cd) {
				return 0;
			}
			int[] list = this.byChunk[cz * this.cw + cx];
			if (list == null) {
				return 0;
			}
			for (int g : list) {
				if (y >= this.minY[g] && y < this.maxY[g]) {
					Grid grid = this.grids[g];
					if (grid.containsColumn(x, z)) {
						return grid.at(x, y, z);
					}
				}
			}
			return 0;
		}
	}
}
