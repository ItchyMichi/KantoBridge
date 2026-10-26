package dev.kantobridge.client.ride;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.entity.BikeEntity;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Draws the bike under its rider (docs/specs/BIKE_PLAN.md §2.6): the kind's model and texture, the wheels turning with the
 * distance rolled, the Acro's wheelie pitched {@value RideLook#WHEELIE_DEGREES}° nose-up about the rear wheel, a standing
 * bunny hop as small hops in place, and the local rider's bump as a short jolt back. Drawn in first person too (as a
 * boat is), so the rider sees the bar and the front wheel. Nothing here carries art from a game.
 */
public final class BikeRenderer extends EntityRenderer<BikeEntity> {
	private static final Map<BikeEntity.Kind, ResourceLocation> TEXTURES = new EnumMap<>(BikeEntity.Kind.class);

	static {
		TEXTURES.put(BikeEntity.Kind.MACH, texture("bike_mach"));
		TEXTURES.put(BikeEntity.Kind.ACRO, texture("bike_acro"));
		TEXTURES.put(BikeEntity.Kind.BIKE, texture("bike_plain"));
	}

	private final Map<BikeEntity.Kind, BikeModel> models = new EnumMap<>(BikeEntity.Kind.class);

	public BikeRenderer(EntityRendererProvider.Context context) {
		super(context);
		for (BikeEntity.Kind kind : BikeEntity.Kind.values()) {
			this.models.put(kind, new BikeModel(context.bakeLayer(BikeModel.layerOf(kind))));
		}
		this.shadowRadius = 0.4F;
	}

	private static ResourceLocation texture(String name) {
		return ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "textures/entity/" + name + ".png");
	}

	@Override
	public void render(BikeEntity bike, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
		BikeModel model = this.models.get(bike.kind());
		pose.pushPose();
		pose.mulPose(Axis.YP.rotationDegrees(180.0F - Mth.rotLerp(partialTick, bike.yRotO, bike.getYRot())));
		float age = bike.tickCount + partialTick;
		RideLook.Pose look = RideLook.of(bike.trick(), age, RideRuntime.joltFor(bike));
		pose.translate(0.0, look.lift(), look.back());
		if (look.pitch() != 0.0F) {
			// nose-up about the rear wheel's contact (7 units behind the middle, on the ground)
			pose.translate(0.0, 0.0, RideLook.REAR_CONTACT);
			pose.mulPose(Axis.XP.rotationDegrees(-look.pitch()));
			pose.translate(0.0, 0.0, -RideLook.REAR_CONTACT);
		}
		pose.scale(-1.0F, -1.0F, 1.0F);
		pose.translate(0.0, -1.501, 0.0);
		model.setupAnim(bike, bike.travelled(), 0.0F, age, 0.0F, 0.0F);
		model.renderToBuffer(pose, buffers.getBuffer(model.renderType(this.getTextureLocation(bike))), light, OverlayTexture.NO_OVERLAY);
		pose.popPose();
		super.render(bike, yaw, partialTick, pose, buffers, light);
	}

	@Override
	public ResourceLocation getTextureLocation(BikeEntity bike) {
		return TEXTURES.get(bike.kind());
	}
}
