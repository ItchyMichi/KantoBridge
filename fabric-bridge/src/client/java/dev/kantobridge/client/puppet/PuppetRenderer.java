package dev.kantobridge.client.puppet;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.kantobridge.client.puppet.style.CharacterStyle;
import dev.kantobridge.client.puppet.style.FlatStyle;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.entity.PuppetEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * Draws a puppet as Gen 1 draws an NPC (docs/PLAN.md §4.3 "Puppets"): its 16×16 sprite frame two blocks wide and tall
 * (one cell, 8 Gen 1 pixels a block), with {@code entityCutoutNoCull} so shade 0 stays see-through. The frame comes from
 * the NPC's facing as the camera sees it ({@link FacingFrames}), standing or walking ({@link Puppet#walkingFrame}). How
 * it stands is its {@link CharacterStyle} (the voxel plan's V8): a card or a thick card turned about the vertical axis
 * to face the camera, or a carved, rigged or modelled figure turned by the NPC's facing. An emote bubble floats above
 * it. No shadow and no name tag. M10: puppets far from the camera, or off the hidden player's map, aren't drawn at all
 * ({@link PuppetCulling}).
 */
public final class PuppetRenderer extends EntityRenderer<PuppetEntity> {
	/** The billboard's height in blocks (the emote bubble floats above it). */
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
		ResourceLocation sheet = this.getTextureLocation(entity);
		PuppetRuntime runtime = PuppetRuntime.get();
		CharacterStyle style = runtime == null ? FlatStyle.INSTANCE : runtime.styles().styleFor(puppet.sprite(), puppet.kind(), sheet);
		poseStack.pushPose();
		// A billboard turns about Y to face the camera (its front, +Z, toward it); a real figure by the NPC's own facing.
		poseStack.mulPose(Axis.YP.rotation(style.worldOriented() ? -puppet.facing().yaw() * Mth.DEG_TO_RAD : Mth.PI - cameraYaw * Mth.DEG_TO_RAD));
		VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(style.texture(sheet)));
		style.emit(poseStack, consumer, packedLight, FacingFrames.column(puppet.facing(), cameraYaw), walking, puppet.mirroredWalk(),
				puppet.walkPhase(partialFrames));
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
}
