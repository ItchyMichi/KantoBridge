package dev.kantobridge.client.puppet.style;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kantobridge.character.HullCarver;

/**
 * A carved figure (an option; report B5.3): the visual hull of the standing (or walking) views, painted from the sheet,
 * turned by the character's own facing. Alternate steps of an up or down walker mirror it, as the card's frame is.
 */
public final class CarvedStyle implements CharacterStyle {
	private final HullCarver.Figure stand;
	private final HullCarver.Figure walk;
	private final float scale;

	public CarvedStyle(HullCarver.Figure stand, HullCarver.Figure walk, int size) {
		this.stand = stand;
		this.walk = walk;
		this.scale = FlatStyle.HEIGHT / size;
	}

	@Override
	public String id() {
		return "carved";
	}

	@Override
	public boolean worldOriented() {
		return true;
	}

	@Override
	public void emit(PoseStack poseStack, VertexConsumer out, int light, int column, boolean walking, boolean mirrored, float phase) {
		HullCarver.Figure figure = walking ? this.walk : this.stand;
		emitTurned(poseStack, out, light, figure.vertices(), figure.normals(), figure.quads(), this.scale, walking && mirrored);
	}

	/** Quads whose front looks along -Z, turned half round so it looks along +Z (the facing), optionally mirrored in x. */
	static void emitTurned(PoseStack poseStack, VertexConsumer out, int light, float[] v, float[] n, int quads, float scale, boolean mirror) {
		PoseStack.Pose pose = poseStack.last();
		float sx = mirror ? 1.0F : -1.0F;
		for (int q = 0; q < quads; q++) {
			float nx = n[q * 3] * sx;
			float ny = n[q * 3 + 1];
			float nz = -n[q * 3 + 2];
			for (int c = 0; c < 4; c++) {
				int i = q * 20 + c * 5;
				FlatStyle.vertex(out, pose, light, v[i] * scale * sx, v[i + 1] * scale, -v[i + 2] * scale, v[i + 3], v[i + 4], nx, ny, nz);
			}
		}
	}
}
