package dev.kantobridge.client.model;

import dev.kantobridge.builder.shape.geom.ShapeMesh;
import dev.kantobridge.builder.shape.geom.ShapeQuad;
import java.util.List;
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
 * mesh threads; allocates nothing.
 */
public final class ShapeEmitter {
	private static final Direction[] DIRECTIONS = Direction.values();
	/** A darkened quad's vertex colour (ceilings, the {@code tint} modifier from V7). */
	static final int DARKEN = 0xFFB4B4B4;

	private ShapeEmitter() {
	}

	/**
	 * Emits every quad of {@code mesh}; {@code sprites} by the quad's texture index (a missing one draws the placeholder).
	 * Returns the quads emitted.
	 */
	public static int emit(ShapeMesh mesh, TextureAtlasSprite[] sprites, TextureAtlasSprite placeholder, @Nullable RenderMaterial solid,
			@Nullable RenderMaterial cutout, QuadEmitter out) {
		List<ShapeQuad> quads = mesh.quads();
		for (int q = 0; q < quads.size(); q++) {
			ShapeQuad quad = quads.get(q);
			int texture = quad.texture();
			TextureAtlasSprite sprite = texture < sprites.length && sprites[texture] != null ? sprites[texture] : placeholder;
			emit(quad, sprite, quad.has(ShapeQuad.CUTOUT) ? cutout : solid, out);
		}
		return quads.size();
	}

	static void emit(ShapeQuad quad, TextureAtlasSprite sprite, @Nullable RenderMaterial material, QuadEmitter out) {
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
		int colour = quad.has(ShapeQuad.DARKEN) ? DARKEN : -1;
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
}
