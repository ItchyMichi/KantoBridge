package dev.kantobridge.client.shape.vox;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * MagicaVoxel's {@code .vox} files (docs/specs/VOXEL_BLOCKS_PLAN.md §5.10), read and written from the format's own
 * description: {@code "VOX "}, an int version, then chunks ({@code id}, content size, children size, content, children).
 * {@code MAIN} holds an optional {@code PACK}, one {@code SIZE} + {@code XYZI} pair per model and an optional
 * {@code RGBA}; every other chunk (the scene graph, materials, layers) is skipped by its declared sizes. Only the first
 * model is used. Palette index 0 is empty; {@code RGBA}'s entry {@code i} is index {@code i + 1}. Without an {@code RGBA}
 * chunk the model is drawn in greys (our own fallback, not MagicaVoxel's default palette). Little-endian. Pure.
 */
public final class VoxFile {
	/** The format's magic. */
	static final byte[] MAGIC = "VOX ".getBytes(StandardCharsets.US_ASCII);
	/** The versions read: 150 (the description's) up to 200 (newer MagicaVoxel). */
	static final int MIN_VERSION = 150;
	static final int MAX_VERSION = 200;
	/** At most this many voxels per axis (the coordinates are single bytes). */
	public static final int MAX_SIZE = 256;

	/** Why a file was refused: {@code pack.vox_format} or {@code pack.vox_size}. */
	public static final class VoxException extends Exception {
		private final String code;

		VoxException(String code, String message) {
			super(message);
			this.code = code;
		}

		public String code() {
			return this.code;
		}
	}

	/**
	 * One model.
	 *
	 * @param sx      size along vox x
	 * @param sy      size along vox y
	 * @param sz      size along vox z (up)
	 * @param voxels  four bytes per voxel: x, y, z, colour index (1..255)
	 * @param palette 256 colours (ARGB) by colour index; entry 0 unused
	 * @param models  how many models the file held (only the first is used)
	 */
	public record Model(int sx, int sy, int sz, byte[] voxels, int[] palette, int models) {
		public int count() {
			return this.voxels.length / 4;
		}

		public int x(int i) {
			return this.voxels[i * 4] & 0xFF;
		}

		public int y(int i) {
			return this.voxels[i * 4 + 1] & 0xFF;
		}

		public int z(int i) {
			return this.voxels[i * 4 + 2] & 0xFF;
		}

		public int colour(int i) {
			return this.voxels[i * 4 + 3] & 0xFF;
		}
	}

	private VoxFile() {
	}

	/** Reads the first model of a file. */
	public static Model read(byte[] bytes) throws VoxException {
		ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		try {
			byte[] magic = new byte[4];
			in.get(magic);
			int version = in.getInt();
			if (!java.util.Arrays.equals(magic, MAGIC) || version < MIN_VERSION || version > MAX_VERSION) {
				throw new VoxException("pack.vox_format", "not a .vox file this reader knows");
			}
			if (!"MAIN".equals(id(in))) {
				throw new VoxException("pack.vox_format", "no MAIN chunk");
			}
			int mainContent = in.getInt();
			int mainChildren = in.getInt();
			if (mainContent < 0 || mainChildren < 0 || mainContent > in.remaining()) {
				throw new VoxException("pack.vox_format", "bad MAIN sizes");
			}
			in.position(in.position() + mainContent);
			int end = in.position() + Math.min(mainChildren, in.remaining());
			int[] size = null;
			byte[] voxels = null;
			int[] palette = null;
			int models = 0;
			while (in.position() + 12 <= end) {
				String id = id(in);
				int content = in.getInt();
				int children = in.getInt();
				if (content < 0 || children < 0 || content > end - in.position()) {
					throw new VoxException("pack.vox_format", "a chunk overruns the file");
				}
				int next = in.position() + content;
				switch (id) {
					case "SIZE" -> {
						if (size == null) {
							size = new int[] {in.getInt(), in.getInt(), in.getInt()};
						}
					}
					case "XYZI" -> {
						models++;
						if (voxels == null) {
							int n = in.getInt();
							if (n < 0 || n * 4L > content - 4L) {
								throw new VoxException("pack.vox_format", "bad voxel count");
							}
							voxels = new byte[n * 4];
							in.get(voxels);
						}
					}
					case "RGBA" -> {
						if (content < 256 * 4) {
							throw new VoxException("pack.vox_format", "short palette");
						}
						palette = new int[256];
						for (int i = 0; i < 256; i++) {
							int r = in.get() & 0xFF;
							int g = in.get() & 0xFF;
							int b = in.get() & 0xFF;
							in.get();
							if (i < 255) {
								palette[i + 1] = 0xFF000000 | r << 16 | g << 8 | b;
							}
						}
					}
					default -> {
						// PACK, the scene graph (nTRN, nGRP, nSHP), MATL, LAYR, IMAP, NOTE...: skipped.
					}
				}
				in.position(Math.min(end, next + children));
			}
			if (size == null || voxels == null) {
				throw new VoxException("pack.vox_format", "no model");
			}
			if (size[0] < 1 || size[1] < 1 || size[2] < 1 || size[0] > MAX_SIZE || size[1] > MAX_SIZE || size[2] > MAX_SIZE) {
				throw new VoxException("pack.vox_size", "a model is 1 to 256 voxels along each axis");
			}
			for (int i = 0; i < voxels.length; i += 4) {
				if ((voxels[i] & 0xFF) >= size[0] || (voxels[i + 1] & 0xFF) >= size[1] || (voxels[i + 2] & 0xFF) >= size[2]) {
					throw new VoxException("pack.vox_format", "a voxel lies outside its model");
				}
			}
			return new Model(size[0], size[1], size[2], voxels, palette != null ? palette : greys(), models);
		} catch (java.nio.BufferUnderflowException e) {
			throw new VoxException("pack.vox_format", "truncated");
		}
	}

	/** Our fallback palette for a file without one: index i a grey. */
	static int[] greys() {
		int[] out = new int[256];
		for (int i = 1; i < 256; i++) {
			out[i] = 0xFF000000 | i << 16 | i << 8 | i;
		}
		return out;
	}

	private static String id(ByteBuffer in) {
		byte[] id = new byte[4];
		in.get(id);
		return new String(id, StandardCharsets.US_ASCII);
	}

	/** Writes a one-model file (version 150) with its palette. */
	public static byte[] write(Model model) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		chunk(body, "SIZE", ints(model.sx(), model.sy(), model.sz()));
		ByteBuffer xyzi = ByteBuffer.allocate(4 + model.voxels().length).order(ByteOrder.LITTLE_ENDIAN);
		xyzi.putInt(model.count());
		xyzi.put(model.voxels());
		chunk(body, "XYZI", xyzi.array());
		ByteBuffer rgba = ByteBuffer.allocate(256 * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < 256; i++) {
			int argb = i < 255 ? model.palette()[i + 1] : 0;
			rgba.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) 0xFF);
		}
		chunk(body, "RGBA", rgba.array());
		byte[] children = body.toByteArray();
		ByteBuffer out = ByteBuffer.allocate(8 + 12 + children.length).order(ByteOrder.LITTLE_ENDIAN);
		out.put(MAGIC).putInt(MIN_VERSION);
		out.put("MAIN".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(children.length);
		out.put(children);
		return out.array();
	}

	private static byte[] ints(int... values) {
		ByteBuffer out = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int v : values) {
			out.putInt(v);
		}
		return out.array();
	}

	private static void chunk(ByteArrayOutputStream out, String id, byte[] content) {
		ByteBuffer head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
		head.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(content.length).putInt(0);
		out.writeBytes(head.array());
		out.writeBytes(content);
	}
}
