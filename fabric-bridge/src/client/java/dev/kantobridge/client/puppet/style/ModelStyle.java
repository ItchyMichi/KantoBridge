package dev.kantobridge.client.puppet.style;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.shape.geom.QuadMerger;
import dev.kantobridge.builder.shape.geom.ShapeQuad;
import dev.kantobridge.client.shape.vox.VoxMesher;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * A pack's model of a sprite (docs/PACKS.md; V8): its {@code .vox} meshed once, each quad in its palette colour over a
 * white texture, the footprint's centre on the character's feet, its front (vox's south) along the facing. It neither
 * walks nor swings.
 */
public final class ModelStyle implements CharacterStyle {
	static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "textures/block/white.png");
	private static final float[][] NORMALS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

	/** Per quad, 4 corners × (x, y, z) in blocks. */
	private final float[] corners;
	private final int[] colours;
	private final float[] normals;
	private final int quads;

	public ModelStyle(VoxMesher.Mesh mesh) {
		int count = 0;
		for (List<ShapeQuad> block : mesh.blocks().values()) {
			count += block.size();
		}
		this.quads = count;
		this.corners = new float[count * 12];
		this.colours = new int[count];
		this.normals = new float[count * 3];
		float cx = mesh.bw() * 16 / 2.0F;
		float cz = mesh.bd() * 16 / 2.0F;
		int q = 0;
		for (Map.Entry<Integer, List<ShapeQuad>> e : mesh.blocks().entrySet()) {
			int bx = QuadMerger.blockX(e.getKey());
			int by = QuadMerger.blockY(e.getKey());
			int bz = QuadMerger.blockZ(e.getKey());
			for (ShapeQuad quad : e.getValue()) {
				for (int c = 0; c < 4; c++) {
					this.corners[q * 12 + c * 3] = (bx * 16 + quad.x(c) - cx) / 16.0F;
					this.corners[q * 12 + c * 3 + 1] = (by * 16 + quad.y(c)) / 16.0F;
					this.corners[q * 12 + c * 3 + 2] = (bz * 16 + quad.z(c) - cz) / 16.0F;
				}
				this.colours[q] = mesh.palette()[quad.texture() & 0xFF] | 0xFF000000;
				float[] normal = quad.face() < NORMALS.length ? NORMALS[quad.face()] : NORMALS[1];
				System.arraycopy(normal, 0, this.normals, q * 3, 3);
				q++;
			}
		}
	}

	@Override
	public String id() {
		return "model";
	}

	@Override
	public boolean worldOriented() {
		return true;
	}

	@Override
	public ResourceLocation texture(ResourceLocation sheet) {
		return WHITE;
	}

	@Override
	public void emit(PoseStack poseStack, VertexConsumer out, int light, int column, boolean walking, boolean mirrored, float phase) {
		PoseStack.Pose pose = poseStack.last();
		for (int q = 0; q < this.quads; q++) {
			for (int c = 0; c < 4; c++) {
				int i = q * 12 + c * 3;
				out.addVertex(pose, this.corners[i], this.corners[i + 1], this.corners[i + 2]).setColor(this.colours[q]).setUv(0.5F, 0.5F)
						.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, this.normals[q * 3], this.normals[q * 3 + 1],
								this.normals[q * 3 + 2]);
			}
		}
	}
}
