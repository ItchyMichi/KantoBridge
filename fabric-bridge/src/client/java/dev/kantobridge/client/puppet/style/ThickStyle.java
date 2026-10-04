package dev.kantobridge.client.puppet.style;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kantobridge.character.ThickCardMesh;
import dev.kantobridge.client.puppet.FacingFrames;

/**
 * The thick card (the default; report B5.2): the frame the camera sees with a front, a back and an edge strip around its
 * silhouette, 2 sprite pixels deep, still turned to the camera, so from the front it is today's card exactly. A mirrored
 * walking frame mirrors the geometry and keeps the texels with it.
 */
public final class ThickStyle implements CharacterStyle {
	private final ThickCardMesh.Mesh[][] meshes;
	private final int size;
	private final float scale;

	/**
	 * @param meshes per sheet row (standing, walking) and column, the card
	 * @param size   the frames' geometry size in pixels
	 */
	public ThickStyle(ThickCardMesh.Mesh[][] meshes, int size) {
		this.meshes = meshes;
		this.size = size;
		this.scale = FlatStyle.HEIGHT / size;
	}

	@Override
	public String id() {
		return "thick";
	}

	@Override
	public boolean worldOriented() {
		return false;
	}

	@Override
	public void emit(PoseStack poseStack, VertexConsumer out, int light, int column, boolean walking, boolean mirrored, float phase) {
		boolean mirror = walking && mirrored;
		ThickCardMesh.Mesh mesh = this.meshes[walking ? 1 : 0][Math.floorMod(column, 4)];
		FacingFrames.Uv uv = FacingFrames.uv(column, walking, false);
		float du = uv.u1() - uv.u0();
		float dv = uv.v1() - uv.v0();
		float half = this.size / 2.0F;
		PoseStack.Pose pose = poseStack.last();
		float[] v = mesh.vertices();
		float[] n = mesh.normals();
		for (int q = 0; q < mesh.quads(); q++) {
			float nx = mirror ? -n[q * 3] : n[q * 3];
			for (int c = 0; c < 4; c++) {
				int i = q * 20 + c * 5;
				float x = (v[i] - half) * this.scale;
				FlatStyle.vertex(out, pose, light, mirror ? -x : x, v[i + 1] * this.scale, v[i + 2] * this.scale, uv.u0() + v[i + 3] * du,
						uv.v0() + v[i + 4] * dv, nx, n[q * 3 + 1], n[q * 3 + 2]);
			}
		}
	}
}
