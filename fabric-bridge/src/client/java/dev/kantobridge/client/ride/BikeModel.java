package dev.kantobridge.client.ride;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.entity.BikeEntity;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * The bike's hand-made placeholder model (docs/specs/BIKE_PLAN.md §2.6, D7): two wheels, a frame, a saddle and a handlebar
 * in cuboids, three variants: the Mach (a racing frame, a low bar), the Acro (a BMX: a low frame, a high bar, pegs on both
 * hubs) and the plain bike (Gen 1's and Gen 2's upright frame, a tall seat). Nothing in it comes from a game; the three
 * 64 × 32 textures are drawn by {@code tools/bike_textures.py}, and an ordinary resource pack may replace them.
 *
 * <p>Model space: 16 units a block, y down, the ground at y 24, the front towards −z. The wheels (radius 4) spin with the
 * distance rolled.
 */
public final class BikeModel extends EntityModel<BikeEntity> {
	public static final int TEXTURE_WIDTH = 64;
	public static final int TEXTURE_HEIGHT = 32;
	/** The wheels' radius in blocks (4 units). */
	public static final float WHEEL_RADIUS = 0.25F;
	/** The parts every variant has; the Acro adds {@link #PEGS}. */
	public static final String[] PARTS = {"wheel_front", "wheel_rear", "top_tube", "down_tube", "rear_stay", "seat_post", "saddle", "fork",
			"stem", "bar"};
	public static final String PEGS = "pegs";

	public static final ModelLayerLocation MACH = layer("bike_mach");
	public static final ModelLayerLocation ACRO = layer("bike_acro");
	public static final ModelLayerLocation PLAIN = layer("bike_plain");

	private final ModelPart root;
	private final ModelPart front;
	private final ModelPart rear;

	public BikeModel(ModelPart root) {
		super(RenderType::entityCutoutNoCull);
		this.root = root;
		this.front = root.getChild("wheel_front");
		this.rear = root.getChild("wheel_rear");
	}

	private static ModelLayerLocation layer(String name) {
		return new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, name), "main");
	}

	/** The layer for a kind's look. */
	public static ModelLayerLocation layerOf(BikeEntity.Kind kind) {
		return switch (kind) {
			case MACH -> MACH;
			case ACRO -> ACRO;
			case BIKE -> PLAIN;
		};
	}

	/** The mesh of a kind (the layer's definition, without the texture size). */
	public static MeshDefinition mesh(BikeEntity.Kind kind) {
		// the frame's heights by variant: the top tube, the saddle, the bar
		float top = kind == BikeEntity.Kind.ACRO ? 16.0F : kind == BikeEntity.Kind.BIKE ? 13.0F : 14.0F;
		float seat = kind == BikeEntity.Kind.ACRO ? 13.0F : kind == BikeEntity.Kind.BIKE ? 9.0F : 11.0F;
		float bar = kind == BikeEntity.Kind.ACRO ? 7.0F : kind == BikeEntity.Kind.BIKE ? 8.0F : 10.0F;
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		root.addOrReplaceChild("wheel_front", CubeListBuilder.create().texOffs(0, 0).addBox(-1.0F, -4.0F, -4.0F, 2.0F, 8.0F, 8.0F),
				PartPose.offset(0.0F, 20.0F, -7.0F));
		root.addOrReplaceChild("wheel_rear", CubeListBuilder.create().texOffs(20, 0).addBox(-1.0F, -4.0F, -4.0F, 2.0F, 8.0F, 8.0F),
				PartPose.offset(0.0F, 20.0F, 7.0F));
		root.addOrReplaceChild("top_tube", CubeListBuilder.create().texOffs(0, 16).addBox(-0.5F, -0.5F, -5.0F, 1.0F, 1.0F, 10.0F),
				PartPose.offset(0.0F, top, -1.0F));
		root.addOrReplaceChild("down_tube", CubeListBuilder.create().texOffs(22, 16).addBox(-0.5F, -0.5F, -4.5F, 1.0F, 1.0F, 9.0F),
				PartPose.offsetAndRotation(0.0F, (top + 20.0F) / 2.0F + 0.5F, -2.5F, -0.55F, 0.0F, 0.0F));
		root.addOrReplaceChild("rear_stay", CubeListBuilder.create().texOffs(44, 16).addBox(-0.5F, -0.5F, -3.0F, 1.0F, 1.0F, 6.0F),
				PartPose.offsetAndRotation(0.0F, 18.0F, 4.5F, 0.55F, 0.0F, 0.0F));
		root.addOrReplaceChild("seat_post", CubeListBuilder.create().texOffs(52, 0).addBox(-0.5F, 0.0F, -0.5F, 1.0F, 6.0F, 1.0F),
				PartPose.offset(0.0F, seat, 3.5F));
		root.addOrReplaceChild("saddle", CubeListBuilder.create().texOffs(40, 0).addBox(-1.0F, -1.0F, -2.0F, 2.0F, 1.0F, 4.0F),
				PartPose.offset(0.0F, seat, 3.5F));
		root.addOrReplaceChild("fork", CubeListBuilder.create().texOffs(60, 0).addBox(-0.5F, 0.0F, -0.5F, 1.0F, 6.0F, 1.0F),
				PartPose.offset(0.0F, 14.0F, -7.0F));
		root.addOrReplaceChild("stem", CubeListBuilder.create().texOffs(56, 0).addBox(-0.5F, 0.0F, -0.5F, 1.0F, 6.0F, 1.0F),
				PartPose.offset(0.0F, bar, -7.0F));
		root.addOrReplaceChild("bar", CubeListBuilder.create().texOffs(40, 8).addBox(-4.0F, -0.5F, -0.5F, 8.0F, 1.0F, 1.0F),
				PartPose.offset(0.0F, bar, -7.0F));
		if (kind == BikeEntity.Kind.ACRO) {
			// a BMX's pegs on both hubs
			root.addOrReplaceChild(PEGS, CubeListBuilder.create().texOffs(40, 12).addBox(-3.0F, -0.5F, 6.5F, 6.0F, 1.0F, 1.0F)
					.texOffs(40, 12).addBox(-3.0F, -0.5F, -7.5F, 6.0F, 1.0F, 1.0F), PartPose.offset(0.0F, 20.0F, 0.0F));
		}
		return mesh;
	}

	public static LayerDefinition layer(BikeEntity.Kind kind) {
		return LayerDefinition.create(mesh(kind), TEXTURE_WIDTH, TEXTURE_HEIGHT);
	}

	/** The wheels' turn for a distance rolled (blocks). */
	public static float spin(float travelled) {
		return -travelled / WHEEL_RADIUS;
	}

	@Override
	public void setupAnim(BikeEntity bike, float travelled, float unused, float ageInTicks, float netHeadYaw, float headPitch) {
		float angle = spin(travelled);
		this.front.xRot = angle;
		this.rear.xRot = angle;
	}

	@Override
	public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay, int color) {
		this.root.render(pose, buffer, light, overlay, color);
	}
}
