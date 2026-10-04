package dev.kantobridge.client.puppet.style;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kantobridge.client.puppet.FacingFrames;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** Today's card (docs/PLAN.md §4.3 "Puppets"): one quad, the frame the camera sees, turned to the camera. */
public final class FlatStyle implements CharacterStyle {
	public static final FlatStyle INSTANCE = new FlatStyle();
	/** Half the card's width, and its height, in blocks. */
	static final float HALF_WIDTH = 1.0F;
	static final float HEIGHT = 2.0F;

	private FlatStyle() {
	}

	@Override
	public String id() {
		return "flat";
	}

	@Override
	public boolean worldOriented() {
		return false;
	}

	@Override
	public void emit(PoseStack poseStack, VertexConsumer out, int light, int column, boolean walking, boolean mirrored, float phase) {
		FacingFrames.Uv uv = FacingFrames.uv(column, walking, walking && mirrored);
		PoseStack.Pose pose = poseStack.last();
		vertex(out, pose, light, -HALF_WIDTH, 0.0F, 0.0F, uv.u0(), uv.v1(), 0.0F, 1.0F, 0.0F);
		vertex(out, pose, light, HALF_WIDTH, 0.0F, 0.0F, uv.u1(), uv.v1(), 0.0F, 1.0F, 0.0F);
		vertex(out, pose, light, HALF_WIDTH, HEIGHT, 0.0F, uv.u1(), uv.v0(), 0.0F, 1.0F, 0.0F);
		vertex(out, pose, light, -HALF_WIDTH, HEIGHT, 0.0F, uv.u0(), uv.v0(), 0.0F, 1.0F, 0.0F);
	}

	static void vertex(VertexConsumer out, PoseStack.Pose pose, int light, float x, float y, float z, float u, float v, float nx, float ny,
			float nz) {
		out.addVertex(pose, x, y, z).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
	}
}
