package dev.kantobridge.client.puppet;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.entity.PuppetEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * Draws a puppet as Gen 1 draws an NPC (docs/PLAN.md §4.3 "Puppets"): its 16×16 sprite frame on an upright billboard
 * two blocks wide and tall (one cell, 8 Gen 1 pixels a block) that turns about the vertical axis to face the camera,
 * with {@code entityCutoutNoCull} so shade 0 stays see-through. The frame comes from the NPC's facing as the camera sees
 * it ({@link FacingFrames}), standing or walking ({@link Puppet#walkingFrame}). An emote bubble floats above it. No
 * shadow and no name tag. M10: puppets far from the camera, or off the hidden player's map, aren't drawn at all
 * ({@link PuppetCulling}).
 */
public final class PuppetRenderer extends EntityRenderer<PuppetEntity> {
	/** Half the billboard's width, and its height, in blocks. */
	static final float HALF_WIDTH = 1.0F;
	static final float HEIGHT = 2.0F;
	private static final float BUBBLE_SCALE = 0.06F;

	public PuppetRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	/** Culled puppets skip the frame and bubble work entirely; the rest go through vanilla's frustum check. */
	@Override
	public boolean shouldRender(PuppetEntity entity, Frustum frustum, double cameraX, double cameraY, double cameraZ) {
		PuppetRuntime runtime = PuppetRuntime.get();
		if (runtime == null || !PuppetCulling.visible(entity.getX(), entity.getY(), entity.getZ(), cameraX, cameraY, cameraZ, runtime.area())) {
			return false;
		}
		return super.shouldRender(entity, frustum, cameraX, cameraY, cameraZ);
	}

	@Override
	public void render(PuppetEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
			int packedLight) {
		Puppet puppet = this.puppet(entity);
		if (puppet == null) {
			return;
		}
		float cameraYaw = this.entityRenderDispatcher.camera.getYRot();
		float partialFrames = partialTick * Puppet.FRAMES_PER_TICK;
		boolean walking = puppet.walkingFrame(partialFrames);
		FacingFrames.Uv uv = FacingFrames.uv(FacingFrames.column(puppet.facing(), cameraYaw), walking, walking && puppet.mirroredWalk());
		poseStack.pushPose();
		// Upright: turn about Y only, so the sprite's +X runs to the camera's right and its face looks at the camera.
		poseStack.mulPose(Axis.YP.rotation(Mth.PI - cameraYaw * Mth.DEG_TO_RAD));
		PoseStack.Pose pose = poseStack.last();
		VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(this.getTextureLocation(entity)));
		vertex(consumer, pose, packedLight, -HALF_WIDTH, 0.0F, uv.u0(), uv.v1());
		vertex(consumer, pose, packedLight, HALF_WIDTH, 0.0F, uv.u1(), uv.v1());
		vertex(consumer, pose, packedLight, HALF_WIDTH, HEIGHT, uv.u1(), uv.v0());
		vertex(consumer, pose, packedLight, -HALF_WIDTH, HEIGHT, uv.u0(), uv.v0());
		poseStack.popPose();
		if (puppet.bubble() != null) {
			this.renderBubble(puppet.bubble(), poseStack, buffers);
		}
	}

	@Override
	public ResourceLocation getTextureLocation(PuppetEntity entity) {
		Puppet puppet = this.puppet(entity);
		PuppetRuntime runtime = PuppetRuntime.get();
		if (puppet == null || runtime == null) {
			return PuppetTextures.PLACEHOLDER;
		}
		return runtime.textures().texture(puppet, ClientWorldIndex.current());
	}

	/** The bubble: a white box with the emote's sign, above the sprite, always facing the camera. */
	private void renderBubble(String bubble, PoseStack poseStack, MultiBufferSource buffers) {
		String sign = switch (bubble) {
			case "happy" -> "♥";
			default -> bubble;
		};
		poseStack.pushPose();
		poseStack.translate(0.0F, HEIGHT + 0.45F, 0.0F);
		poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
		poseStack.scale(BUBBLE_SCALE, -BUBBLE_SCALE, BUBBLE_SCALE);
		Font font = this.getFont();
		float x = -font.width(sign) / 2.0F;
		font.drawInBatch(sign, x, 0.0F, 0xFF000000, false, poseStack.last().pose(), buffers, Font.DisplayMode.NORMAL, 0xFFFFFFFF,
				LightTexture.FULL_BRIGHT);
		poseStack.popPose();
	}

	private @Nullable Puppet puppet(PuppetEntity entity) {
		PuppetRuntime runtime = PuppetRuntime.get();
		return runtime == null ? null : runtime.manager().byEntityId(entity.getId());
	}

	private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, int light, float x, float y, float u, float v) {
		consumer.addVertex(pose, x, y, 0.0F)
				.setColor(-1)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, 0.0F, 1.0F, 0.0F);
	}
}
