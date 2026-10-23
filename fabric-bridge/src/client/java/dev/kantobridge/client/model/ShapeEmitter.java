package dev.kantobridge.client.model;

import dev.kantobridge.builder.shape.geom.ShapeMesh;
import dev.kantobridge.builder.shape.geom.ShapeQuad;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Writes a shape's quads to Fabric's emitter (docs/specs/VOXEL_BLOCKS_PLAN.md §5.1): positions from 1/16 block, each
 * corner's UV by the quad's face convention ({@link ShapeQuad#cornerU}), inset toward the centre of the quad's UV
 * rectangle by the sprite's shrink ratio (Fabric's emitter doesn't shrink UVs as vanilla's face bakery does, so sub-tile
 * UVs would bleed), the cull face where the quad lies on the block's surface, the material by its flags. Runs on the
 * mesh threads; allocates nothing. Which quads of a shape are drawn (face masks against shaped neighbours) is
 * {@link RenderSnapshot#emitShape}'s.
 */
public final class ShapeEmitter {
	private static final Direction[] DIRECTIONS = Direction.values();
	/** A darkened quad's vertex colour (ceilings, the {@code tint} modifier from V7). */
	static final int DARKEN = 0xFFB4B4B4;
	/** A key's tint by level (V7: ceilings), multiplied in like {@link #DARKEN}. */
	static final int[] TINTS = {0xFFFFFFFF, 0xFFD8D8D8, 0xFFB0B0B0, 0xFF888888};

	private ShapeEmitter() {
	}

	/** Two colours multiplied channel by channel (ARGB). */
	static int multiply(int a, int b) {
		int out = 0;
		for (int shift = 0; shift < 32; shift += 8) {
			int channel = ((a >>> shift) & 0xFF) * ((b >>> shift) & 0xFF) / 255;
			out |= channel << shift;
		}
		return out;
	}

	static void emit(ShapeQuad quad, TextureAtlasSprite sprite, @Nullable RenderMaterial material, QuadEmitter out) {
		emit(quad, sprite, material, -1, out);
	}

	/**
	 * The same with a tint: a pack model's colour over the white sprite (V6), -1 for none. A darkened quad is darkened
	 * after.
	 */
	static void emit(ShapeQuad quad, TextureAtlasSprite sprite, @Nullable RenderMaterial material, int tint, QuadEmitter out) {
		float minU = Float.MAX_VALUE;
		float maxU = -Float.MAX_VALUE;
		float minV = Float.MAX_VALUE;
		float maxV = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			float u = quad.cornerU(i);
			float v = quad.cornerV(i);
			minU = Math.min(minU, u);
			maxU = Math.max(maxU, u);
			minV = Math.min(minV, v);
			maxV = Math.max(maxV, v);
		}
		float centreU = (minU + maxU) / 2;
		float centreV = (minV + maxV) / 2;
		float ratio = sprite.uvShrinkRatio();
		int colour = quad.has(ShapeQuad.DARKEN) ? multiply(tint, DARKEN) : tint;
		if (quad.tint() > 0) {
			colour = multiply(colour, TINTS[quad.tint()]);
		}
		for (int i = 0; i < 4; i++) {
			float u = quad.cornerU(i);
			float v = quad.cornerV(i);
			out.pos(i, quad.x(i) / 16.0F, quad.y(i) / 16.0F, quad.z(i) / 16.0F);
			out.uv(i, sprite.getU((u + (centreU - u) * ratio) / 16.0F), sprite.getV((v + (centreV - v) * ratio) / 16.0F));
			out.color(i, colour);
		}
		out.cullFace(quad.cull() >= 0 ? DIRECTIONS[quad.cull()] : null);
		if (quad.face() < ShapeQuad.SLOPED) {
			out.nominalFace(DIRECTIONS[quad.face()]);
		}
		if (material != null) {
			out.material(material);
		}
		out.emit();
	}

	/**
	 * {@link #emit(ShapeQuad, TextureAtlasSprite, RenderMaterial, int, QuadEmitter)} for quad {@code q} of a table's mesh,
	 * read in place from its packed store (the mesh threads' path: no quad object).
	 */
	static void emit(ShapeMesh mesh, int q, TextureAtlasSprite sprite, @Nullable RenderMaterial material, int tint, QuadEmitter out) {
		float minU = Float.MAX_VALUE;
		float maxU = -Float.MAX_VALUE;
		float minV = Float.MAX_VALUE;
		float maxV = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			float u = mesh.cornerU(q, i);
			float v = mesh.cornerV(q, i);
			minU = Math.min(minU, u);
			maxU = Math.max(maxU, u);
			minV = Math.min(minV, v);
			maxV = Math.max(maxV, v);
		}
		float centreU = (minU + maxU) / 2;
		float centreV = (minV + maxV) / 2;
		float ratio = sprite.uvShrinkRatio();
		int colour = mesh.has(q, ShapeQuad.DARKEN) ? multiply(tint, DARKEN) : tint;
		if (mesh.tint(q) > 0) {
			colour = multiply(colour, TINTS[mesh.tint(q)]);
		}
		for (int i = 0; i < 4; i++) {
			float u = mesh.cornerU(q, i);
			float v = mesh.cornerV(q, i);
			out.pos(i, mesh.x(q, i) / 16.0F, mesh.y(q, i) / 16.0F, mesh.z(q, i) / 16.0F);
			out.uv(i, sprite.getU((u + (centreU - u) * ratio) / 16.0F), sprite.getV((v + (centreV - v) * ratio) / 16.0F));
			out.color(i, colour);
		}
		int cull = mesh.cull(q);
		out.cullFace(cull >= 0 ? DIRECTIONS[cull] : null);
		int face = mesh.face(q);
		if (face < ShapeQuad.SLOPED) {
			out.nominalFace(DIRECTIONS[face]);
		}
		if (material != null) {
			out.material(material);
		}
		out.emit();
	}
}
