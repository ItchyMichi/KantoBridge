package dev.kantobridge.client.model;

import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * The plain cubes of a tile slot (docs/specs/VOXEL_BLOCKS_PLAN.md §5.1, "the same cubes, exactly"): the full cube of
 * {@code cube_all} and the half cube of the jar's former {@code models/block/tile_half_placeholder.json}, with the vertex order
 * of Fabric's {@code QuadEmitter#square}, which is vanilla's, the UVs of vanilla's default (UV-locked) face mapping, and
 * vanilla's UV shrink toward each face's centre by {@code sprite.uvShrinkRatio()}. So the runtime model draws exactly what
 * the generated JSON models drew. The tables are pure data ({@code CubeEmitterTest} compares them with the jar's model);
 * the emit and bake methods write them for a sprite.
 */
public final class CubeEmitter {
	public static final int FULL = 0;
	public static final int HALF = 1;
	private static final Direction[] FACES = Direction.values();

	/** [shape][face]: 4 corners × (x, y, z), block units. Faces in {@link Direction#get3DDataValue()} order. */
	private static final float[][][] CORNERS = new float[2][6][];
	/** [shape][face]: 4 corners × (u, v) in 1/16 of the texture, before the shrink. */
	private static final float[][][] UVS = new float[2][6][];
	/** [shape][face]: the face lies on the block's surface, so a neighbour can cull it. */
	private static final boolean[][] CULLS = new boolean[2][6];

	static {
		for (int shape = FULL; shape <= HALF; shape++) {
			for (Direction face : FACES) {
				boolean side = face.getAxis().isHorizontal();
				float top = shape == HALF && side ? 0.5F : 1.0F;
				float depth = shape == HALF && face == Direction.UP ? 0.5F : 0.0F;
				float[] corners = square(face, 0, 0, 1, top, depth);
				CORNERS[shape][face.get3DDataValue()] = corners;
				UVS[shape][face.get3DDataValue()] = lockedUvs(face, corners);
				CULLS[shape][face.get3DDataValue()] = depth == 0.0F;
			}
		}
	}

	private CubeEmitter() {
	}

	/** The shape of a carrier: {@link #HALF} for half blocks. */
	public static int shape(boolean half) {
		return half ? HALF : FULL;
	}

	/** The corners of one face (a copy): 4 × (x, y, z) in block units. */
	public static float[] corners(int shape, Direction face) {
		return CORNERS[shape][face.get3DDataValue()].clone();
	}

	/** The UVs of one face (a copy): 4 × (u, v) in 1/16 of the texture, before the shrink. */
	public static float[] uvs(int shape, Direction face) {
		return UVS[shape][face.get3DDataValue()].clone();
	}

	/** True when the face lies on the block's surface (it has a cull face). */
	public static boolean culls(int shape, Direction face) {
		return CULLS[shape][face.get3DDataValue()];
	}

	/**
	 * Fabric's {@code QuadEmitter#square} corners, in its (vanilla's) vertex order: a square parallel to {@code face},
	 * {@code depth} in from it, from ({@code left}, {@code bottom}) to ({@code right}, {@code top}) as seen from outside.
	 */
	static float[] square(Direction face, float left, float bottom, float right, float top, float depth) {
		float[] p = new float[12];
		switch (face) {
			case UP, DOWN -> {
				float d = face == Direction.UP ? 1 - depth : depth;
				float t = face == Direction.UP ? 1 - top : top;
				float b = face == Direction.UP ? 1 - bottom : bottom;
				set(p, 0, left, d, t);
				set(p, 1, left, d, b);
				set(p, 2, right, d, b);
				set(p, 3, right, d, t);
			}
			case EAST, WEST -> {
				float d = face == Direction.EAST ? 1 - depth : depth;
				float l = face == Direction.EAST ? 1 - left : left;
				float r = face == Direction.EAST ? 1 - right : right;
				set(p, 0, d, top, l);
				set(p, 1, d, bottom, l);
				set(p, 2, d, bottom, r);
				set(p, 3, d, top, r);
			}
			default -> {
				float d = face == Direction.SOUTH ? 1 - depth : depth;
				float l = face == Direction.SOUTH ? 1 - left : left;
				float r = face == Direction.SOUTH ? 1 - right : right;
				set(p, 0, 1 - l, top, d);
				set(p, 1, 1 - l, bottom, d);
				set(p, 2, 1 - r, bottom, d);
				set(p, 3, 1 - r, top, d);
			}
		}
		return p;
	}

	/** Vanilla's default face UVs, as Fabric's UV lock writes them: from each corner's position, in 1/16 units. */
	static float[] lockedUvs(Direction face, float[] corners) {
		float[] uv = new float[8];
		for (int i = 0; i < 4; i++) {
			float x = corners[i * 3];
			float y = corners[i * 3 + 1];
			float z = corners[i * 3 + 2];
			float u;
			float v;
			switch (face) {
				case EAST -> {
					u = 1 - z;
					v = 1 - y;
				}
				case WEST -> {
					u = z;
					v = 1 - y;
				}
				case NORTH -> {
					u = 1 - x;
					v = 1 - y;
				}
				case SOUTH -> {
					u = x;
					v = 1 - y;
				}
				case DOWN -> {
					u = x;
					v = 1 - z;
				}
				default -> {
					u = x;
					v = z;
				}
			}
			uv[i * 2] = u * 16;
			uv[i * 2 + 1] = v * 16;
		}
		return uv;
	}

	/** Vanilla's shrink: every UV moves {@code ratio} of the way toward the centre of the face's UV rectangle. */
	public static float[] shrunk(float[] uv16, float ratio) {
		float minU = Float.MAX_VALUE;
		float maxU = -Float.MAX_VALUE;
		float minV = Float.MAX_VALUE;
		float maxV = -Float.MAX_VALUE;
		for (int i = 0; i < uv16.length; i += 2) {
			minU = Math.min(minU, uv16[i]);
			maxU = Math.max(maxU, uv16[i]);
			minV = Math.min(minV, uv16[i + 1]);
			maxV = Math.max(maxV, uv16[i + 1]);
		}
		float centreU = (minU + maxU) / 2;
		float centreV = (minV + maxV) / 2;
		float[] out = new float[uv16.length];
		for (int i = 0; i < uv16.length; i += 2) {
			out[i] = uv16[i] + (centreU - uv16[i]) * ratio;
			out[i + 1] = uv16[i + 1] + (centreV - uv16[i + 1]) * ratio;
		}
		return out;
	}

	/** Emits the six faces of a slot's cube; each face with a cull face where it lies on the block's surface. */
	public static void emit(QuadEmitter out, int shape, TextureAtlasSprite sprite, @Nullable RenderMaterial material) {
		float ratio = sprite.uvShrinkRatio();
		for (Direction face : FACES) {
			int f = face.get3DDataValue();
			float[] p = CORNERS[shape][f];
			float[] uv = shrunk(UVS[shape][f], ratio);
			for (int i = 0; i < 4; i++) {
				out.pos(i, p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
				out.uv(i, sprite.getU(uv[i * 2] / 16.0F), sprite.getV(uv[i * 2 + 1] / 16.0F));
				out.color(i, -1);
			}
			out.cullFace(CULLS[shape][f] ? face : null);
			out.nominalFace(face);
			if (material != null) {
				out.material(material);
			}
			out.emit();
		}
	}

	/** A slot's cube as a mesh of the active renderer (atlas UVs baked in). */
	public static Mesh mesh(MeshBuilder builder, int shape, TextureAtlasSprite sprite, @Nullable RenderMaterial material) {
		emit(builder.getEmitter(), shape, sprite, material);
		return builder.build();
	}

	/** One face as a vanilla baked quad, vertex for vertex what {@code FaceBakery} makes of the JSON model. */
	public static BakedQuad bake(int shape, Direction face, TextureAtlasSprite sprite) {
		int f = face.get3DDataValue();
		float[] p = CORNERS[shape][f];
		float[] uv = shrunk(UVS[shape][f], sprite.uvShrinkRatio());
		int[] data = new int[32];
		for (int i = 0; i < 4; i++) {
			int o = i * 8;
			data[o] = Float.floatToRawIntBits(p[i * 3]);
			data[o + 1] = Float.floatToRawIntBits(p[i * 3 + 1]);
			data[o + 2] = Float.floatToRawIntBits(p[i * 3 + 2]);
			data[o + 3] = -1;
			data[o + 4] = Float.floatToRawIntBits(sprite.getU(uv[i * 2] / 16.0F));
			data[o + 5] = Float.floatToRawIntBits(sprite.getV(uv[i * 2 + 1] / 16.0F));
		}
		return new BakedQuad(data, -1, face, sprite, true);
	}

	private static void set(float[] p, int vertex, float x, float y, float z) {
		p[vertex * 3] = x;
		p[vertex * 3 + 1] = y;
		p[vertex * 3 + 2] = z;
	}
}
