package dev.kantobridge.client.puppet.style;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.kantobridge.character.RigSplitter;
import net.minecraft.util.Mth;

/**
 * A rigged figure (an option; report B5.4): head, torso, arms and legs as boxes whose faces show the sheet's own frames
 * (front: the standing "down" frame; back: the "up" frame, mirrored; sides: the "left" frame; top and bottom: the front's
 * edge rows, stretched), turned by the character's facing; while walking, the legs and arms swing about their joints like
 * a mob's, opposite each other. The boxes are quads straight into the sheet, so no texture is made.
 */
public final class RiggedStyle implements CharacterStyle {
	/** The legs' swing amplitude in radians; the arms swing half as far. */
	static final float SWING = 0.6F;
	/** Face normals in the order {@link #faces} builds them: front, back, left, right, top, bottom. */
	private static final float[][] NORMALS = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, -1, 0}};

	private final RigSplitter.Rig rig;
	private final int size;
	private final float scale;
	/** Per part, its 6 faces × 4 corners × (x, y, z, u, v), in sprite pixels about the figure's centre, the front at -z. */
	private final float[][] faces;

	public RiggedStyle(RigSplitter.Rig rig, int size) {
		this.rig = rig;
		this.size = size;
		this.scale = FlatStyle.HEIGHT / size;
		this.faces = new float[rig.parts().size()][];
		for (int p = 0; p < rig.parts().size(); p++) {
			this.faces[p] = faces(rig.parts().get(p), size);
		}
	}

	@Override
	public String id() {
		return "rigged";
	}

	@Override
	public boolean worldOriented() {
		return true;
	}

	@Override
	public void emit(PoseStack poseStack, VertexConsumer out, int light, int column, boolean walking, boolean mirrored, float phase) {
		float swing = phase < 0 ? 0.0F : Mth.cos(phase * Mth.TWO_PI);
		for (int p = 0; p < this.rig.parts().size(); p++) {
			RigSplitter.Part part = this.rig.parts().get(p);
			float angle = 0.0F;
			if (part.swings() && phase >= 0) {
				boolean leg = part.name().endsWith("_leg");
				boolean right = part.name().startsWith("right");
				// A leg swings against the arm on its side, and against the other leg.
				angle = swing * (leg ? SWING : SWING / 2) * (leg == right ? 1 : -1);
			}
			poseStack.pushPose();
			if (angle != 0.0F) {
				// The joint, turned half round as the figure is: y up from the feet, z mirrored.
				float pivotY = (this.size - part.pivotY()) * this.scale;
				float pivotZ = -(part.z0() + part.d() / 2.0F - this.size / 2.0F) * this.scale;
				poseStack.translate(0.0F, pivotY, pivotZ);
				poseStack.mulPose(Axis.XP.rotation(angle));
				poseStack.translate(0.0F, -pivotY, -pivotZ);
			}
			float[] f = this.faces[p];
			PoseStack.Pose pose = poseStack.last();
			for (int q = 0; q < 6; q++) {
				float[] normal = NORMALS[q];
				for (int c = 0; c < 4; c++) {
					int i = (q * 4 + c) * 5;
					// Turned half round: the figure's front (-z here) looks along +Z, the facing.
					FlatStyle.vertex(out, pose, light, -f[i] * this.scale, f[i + 1] * this.scale, -f[i + 2] * this.scale, f[i + 3], f[i + 4],
							-normal[0], normal[1], -normal[2]);
				}
			}
			poseStack.popPose();
		}
	}

	/**
	 * A part's box as 6 faces of 4 corners: x centred, y up from the feet, z centred with the front at -z; UVs over the
	 * whole sheet (4 × 2 frames of {@code size} pixels; the standing row).
	 */
	static float[] faces(RigSplitter.Part p, int size) {
		float half = size / 2.0F;
		float x0 = p.x0() - half;
		float x1 = p.x0() + p.w() - half;
		float y0 = size - (p.y0() + p.h());
		float y1 = size - p.y0();
		float z0 = p.z0() - half;
		float z1 = p.z0() + p.d() - half;
		int top = p.y0();
		int bottom = p.y0() + p.h();
		float[] out = new float[6 * 4 * 5];
		int i = 0;
		// Front (-z): the down frame, as drawn.
		i = quad(out, i, new float[][] {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}},
				new float[][] {uv(0, p.x0(), bottom, size), uv(0, p.x0() + p.w(), bottom, size), uv(0, p.x0() + p.w(), top, size), uv(0, p.x0(), top, size)});
		// Back (+z): the up frame, mirrored.
		int bx0 = size - (p.x0() + p.w());
		int bx1 = size - p.x0();
		i = quad(out, i, new float[][] {{x1, y0, z1}, {x0, y0, z1}, {x0, y1, z1}, {x1, y1, z1}},
				new float[][] {uv(1, bx0, bottom, size), uv(1, bx1, bottom, size), uv(1, bx1, top, size), uv(1, bx0, top, size)});
		// Left (-x) and right (+x): the left frame over the part's depth.
		i = quad(out, i, new float[][] {{x0, y0, z1}, {x0, y0, z0}, {x0, y1, z0}, {x0, y1, z1}},
				new float[][] {uv(2, p.z0() + p.d(), bottom, size), uv(2, p.z0(), bottom, size), uv(2, p.z0(), top, size), uv(2, p.z0() + p.d(), top, size)});
		i = quad(out, i, new float[][] {{x1, y0, z0}, {x1, y0, z1}, {x1, y1, z1}, {x1, y1, z0}},
				new float[][] {uv(2, p.z0(), bottom, size), uv(2, p.z0() + p.d(), bottom, size), uv(2, p.z0() + p.d(), top, size), uv(2, p.z0(), top, size)});
		// Top and bottom: the front's edge rows, stretched over the depth.
		float topRow = top + 0.5F;
		float bottomRow = bottom - 0.5F;
		i = quad(out, i, new float[][] {{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}},
				new float[][] {uv(0, p.x0(), topRow, size), uv(0, p.x0() + p.w(), topRow, size), uv(0, p.x0() + p.w(), topRow, size), uv(0, p.x0(), topRow, size)});
		quad(out, i, new float[][] {{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}},
				new float[][] {uv(0, p.x0(), bottomRow, size), uv(0, p.x0() + p.w(), bottomRow, size), uv(0, p.x0() + p.w(), bottomRow, size),
						uv(0, p.x0(), bottomRow, size)});
		return out;
	}

	/** A sheet coordinate of the standing row: column {@code column}, frame pixel (fx, fy). */
	private static float[] uv(int column, float fx, float fy, int size) {
		return new float[] {(column * size + fx) / (4.0F * size), fy / (2.0F * size)};
	}

	private static int quad(float[] out, int i, float[][] corners, float[][] uvs) {
		for (int c = 0; c < 4; c++) {
			out[i++] = corners[c][0];
			out[i++] = corners[c][1];
			out[i++] = corners[c][2];
			out[i++] = uvs[c][0];
			out[i++] = uvs[c][1];
		}
		return i;
	}
}
