package dev.kantobridge.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.kantobridge.client.fx.BlockadeFade;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Other players standing inside one of the local player's blockades are drawn faded ({@link BlockadeFade}, DESIGN §8).
 * Vanilla already draws a translucent body for an invisible teammate: the body counts as not visible, so
 * {@code render} takes its translucent path ({@code itemEntityTranslucentCull}); its nearly-gone alpha becomes
 * {@link BlockadeFade#COLOR}'s, and the layers (held items, cape, a Pokémon on the shoulder) are left out, as they would
 * otherwise stay opaque on a see-through body. Nothing changes for anyone {@link BlockadeFade#faded} doesn't name.
 */
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
	@Inject(method = "isBodyVisible", at = @At("HEAD"), cancellable = true)
	private void kantobridge$fadeInBlockade(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
		if (BlockadeFade.faded(entity)) {
			cir.setReturnValue(false);
		}
	}

	@ModifyConstant(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
			+ "Lnet/minecraft/client/renderer/MultiBufferSource;I)V", constant = @Constant(intValue = 654311423))
	private int kantobridge$fadeAlpha(int color, @Local(argsOnly = true) LivingEntity entity) {
		return BlockadeFade.faded(entity) ? BlockadeFade.COLOR : color;
	}

	@WrapWithCondition(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
			+ "Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;"
					+ "Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"))
	private boolean kantobridge$noLayersWhenFaded(RenderLayer<?, ?> layer, PoseStack poseStack, MultiBufferSource buffers, int light, Entity entity,
			float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
		return !BlockadeFade.faded(entity);
	}
}
