package dev.kantobridge.client.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.pack.ResourcePackWriter;
import dev.kantobridge.client.puppet.FacingFrames;
import dev.kantobridge.client.puppet.Puppet;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.story.StoryStates;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.WorldIndex;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The surfing player's mount (docs/PLAN.md §6 M8 item 5): while the local player surfs, the hidden game's surfing sprite
 * (Gen 1's Seel, {@code field.playerSprites.surf}) from the player's own generated pack, in the palette of the map
 * they're on ({@code textures/player/<palette>/surf.png}, an NPC-style sheet). Kept simple:
 *
 * <ul>
 * <li>seen from outside (third person): an upright, camera-facing 2×2-block billboard like a puppet, sunk into the water
 * and drawn just in front of the player, so the player sits on it;</li>
 * <li>in first person: the sprite's back lies flat on the water under the player, turned with them, so looking down shows
 * what they ride.</li>
 * </ul>
 * The walking frame cycles while the player moves, as Gen 1 animates a moving sprite. Without the sheet (an older pack,
 * an export without the sprite) nothing is drawn. Other players' surfing isn't known on this client until M9.
 */
public final class SurfSprite {
	/** Half the billboard's width and its height, in blocks (one cell, as puppets). */
	static final float HALF_WIDTH = 1.0F;
	static final float HEIGHT = 2.0F;
	/** The billboard sinks this far below the player's feet (the water's top hides that part) ... */
	static final float SINK = 0.75F;
	/** ... and stands this far towards the camera, in front of the player's legs. */
	static final float FORWARD = 0.35F;
	/** The flat sprite floats this far above the water. */
	static final float FLAT_LIFT = 0.02F;
	/** Blocks per tick above which the player counts as moving (the walking frame cycles). */
	static final double MOVING = 0.02;

	private static final Map<String, Optional<ResourceLocation>> TEXTURES = new HashMap<>();

	private SurfSprite() {
	}

	/** Call once from the client entrypoint. */
	public static void init() {
		WorldRenderEvents.AFTER_ENTITIES.register(SurfSprite::render);
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public ResourceLocation getFabricId() {
				return ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "surf_sprite");
			}

			@Override
			public void onResourceManagerReload(ResourceManager resourceManager) {
				TEXTURES.clear();
			}
		});
	}

	private static void render(WorldRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		MultiBufferSource buffers = context.consumers();
		WorldIndex index = ClientWorldIndex.current();
		StoryState state = player == null ? null : StoryStates.peek(player);
		if (player == null || buffers == null || index == null || state == null || !state.surf() || player.isSpectator()) {
			return;
		}
		float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);
		Vec3 feet = player.getPosition(partialTick);
		ResourceLocation texture = texture(index, feet);
		if (texture == null) {
			return;
		}
		Camera camera = context.camera();
		Vec3 eye = camera.getPosition();
		PoseStack poseStack = context.matrixStack();
		int light = LevelRenderer.getLightColor(context.world(), player.blockPosition());
		Vec3 motion = player.getDeltaMovement();
		boolean moving = motion.x * motion.x + motion.z * motion.z > MOVING * MOVING;
		float frames = (player.tickCount + partialTick) * Puppet.FRAMES_PER_TICK;
		float cycle = frames % Puppet.CYCLE;
		boolean walking = moving && cycle >= 4 && cycle < 12;
		Dir facing = Dir.fromYaw(player.getYRot());
		VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
		poseStack.pushPose();
		poseStack.translate(feet.x - eye.x, feet.y - eye.y, feet.z - eye.z);
		if (camera.isDetached()) {
			FacingFrames.Uv uv = FacingFrames.uv(FacingFrames.column(facing, camera.getYRot()), walking, false);
			// Upright, turned about Y to face the camera (as PuppetRenderer does); +Z then points at the camera.
			poseStack.mulPose(Axis.YP.rotation(Mth.PI - camera.getYRot() * Mth.DEG_TO_RAD));
			poseStack.translate(0.0F, -SINK, FORWARD);
			PoseStack.Pose pose = poseStack.last();
			vertex(consumer, pose, light, -HALF_WIDTH, 0.0F, 0.0F, uv.u0(), uv.v1(), 0.0F, 0.0F, 1.0F);
			vertex(consumer, pose, light, HALF_WIDTH, 0.0F, 0.0F, uv.u1(), uv.v1(), 0.0F, 0.0F, 1.0F);
			vertex(consumer, pose, light, HALF_WIDTH, HEIGHT, 0.0F, uv.u1(), uv.v0(), 0.0F, 0.0F, 1.0F);
			vertex(consumer, pose, light, -HALF_WIDTH, HEIGHT, 0.0F, uv.u0(), uv.v0(), 0.0F, 0.0F, 1.0F);
		} else {
			// Flat on the water, its top towards where the player faces: the sprite's back ("up" frame) seen from above.
			FacingFrames.Uv uv = FacingFrames.uv(FacingFrames.COLUMN_UP, walking, false);
			poseStack.mulPose(Axis.YP.rotation(Mth.PI - facing.yaw() * Mth.DEG_TO_RAD));
			poseStack.translate(0.0F, FLAT_LIFT, 0.0F);
			PoseStack.Pose pose = poseStack.last();
			vertex(consumer, pose, light, -HALF_WIDTH, 0.0F, HALF_WIDTH, uv.u0(), uv.v1(), 0.0F, 1.0F, 0.0F);
			vertex(consumer, pose, light, HALF_WIDTH, 0.0F, HALF_WIDTH, uv.u1(), uv.v1(), 0.0F, 1.0F, 0.0F);
			vertex(consumer, pose, light, HALF_WIDTH, 0.0F, -HALF_WIDTH, uv.u1(), uv.v0(), 0.0F, 1.0F, 0.0F);
			vertex(consumer, pose, light, -HALF_WIDTH, 0.0F, -HALF_WIDTH, uv.u0(), uv.v0(), 0.0F, 1.0F, 0.0F);
		}
		poseStack.popPose();
	}

	/** The surfing sheet for the map under {@code feet}, or null (not on a map, or the pack has no sheet for its palette). */
	private static @Nullable ResourceLocation texture(WorldIndex index, Vec3 feet) {
		Optional<CellPos> cell = index.toCell(Mth.floor(feet.x), Mth.floor(feet.y), Mth.floor(feet.z));
		MapPlacement map = cell.map(c -> index.map(c.map())).orElse(null);
		if (map == null) {
			return null;
		}
		return TEXTURES.computeIfAbsent(map.palette(), palette -> {
			ResourceLocation location = ResourceLocation.tryParse(ResourcePackWriter.surfTexture(palette));
			boolean present = location != null && Minecraft.getInstance().getResourceManager().getResource(location).isPresent();
			return present ? Optional.of(location) : Optional.empty();
		}).orElse(null);
	}

	private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, int light, float x, float y, float z, float u, float v, float nx,
			float ny, float nz) {
		consumer.addVertex(pose, x, y, z)
				.setColor(-1)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, nx, ny, nz);
	}
}
