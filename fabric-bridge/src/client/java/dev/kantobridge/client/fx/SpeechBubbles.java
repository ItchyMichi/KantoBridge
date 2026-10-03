package dev.kantobridge.client.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kantobridge.story.Activity;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Speech bubbles over other players (DESIGN §8 "Smoothing it over"; docs/PLAN.md §4.3 {@code fx/}): each player's
 * hidden game runs its own story, so a player reading a sign or watching a cutscene would seem to talk to thin air, and
 * one in a story battle fights a trainer nobody else sees. Their {@link Activity} (reported by their own client, synced
 * by the server to everyone else) shows as a small white bubble above their name tag: dialogue, cutscene or battle.
 *
 * <p>Drawn in {@code WorldRenderEvents.AFTER_ENTITIES} with the name-tag recipe (camera-facing, scaled text with a
 * background), occluded by walls like a hidden name tag so interiors stacked below don't shine through the ground. The
 * rules ({@link #bubble}) are plain Java.
 */
public final class SpeechBubbles {
	/** Bubbles show as far as name tags do. */
	public static final double RANGE = 64.0;
	/** Above the head, clear of the name tag. */
	static final float ABOVE_HEAD = 0.95F;
	/** Text scale: a little larger than a name tag's 0.025. */
	static final float SCALE = 0.035F;

	/** Bubbles drawn in the last frame (F7). */
	private static int shown;

	/** What a bubble says, as a lang key (a sign, not words: it reads the same in every language). */
	public enum Bubble {
		DIALOGUE("kantobridge.bubble.dialogue"),
		CUTSCENE("kantobridge.bubble.cutscene"),
		BATTLE("kantobridge.bubble.battle");

		private final String key;

		Bubble(String key) {
			this.key = key;
		}

		public String key() {
			return this.key;
		}
	}

	private SpeechBubbles() {
	}

	/** Call once from the client entrypoint. */
	public static void init() {
		WorldRenderEvents.AFTER_ENTITIES.register(SpeechBubbles::render);
	}

	/**
	 * The bubble over a player, or null: never over the local player (the activity isn't even synced to its own client),
	 * spectators or players this client can't see, nor beyond {@link #RANGE}; none while they do nothing for their hidden
	 * game.
	 */
	public static @Nullable Bubble bubble(Activity activity, boolean self, boolean spectator, boolean invisible, double distanceSq) {
		if (self || spectator || invisible || !(distanceSq <= RANGE * RANGE)) {
			return null;
		}
		return switch (activity) {
			case DIALOGUE -> Bubble.DIALOGUE;
			case CUTSCENE -> Bubble.CUTSCENE;
			case BATTLE -> Bubble.BATTLE;
			case NONE -> null;
		};
	}

	private static void render(WorldRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer local = minecraft.player;
		ClientLevel level = minecraft.level;
		MultiBufferSource buffers = context.consumers();
		// Name tags hide with F1; so do bubbles.
		if (local == null || level == null || buffers == null || minecraft.options.hideGui) {
			shown = 0;
			return;
		}
		float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);
		Camera camera = context.camera();
		Vec3 eye = camera.getPosition();
		PoseStack poseStack = context.matrixStack();
		Font font = minecraft.font;
		int drawn = 0;
		for (AbstractClientPlayer other : level.players()) {
			Bubble bubble = bubble(Activity.of(other), other == local, other.isSpectator(), other.isInvisibleTo(local), other.distanceToSqr(local));
			if (bubble == null) {
				continue;
			}
			Vec3 feet = other.getPosition(partialTick);
			String sign = I18n.get(bubble.key());
			poseStack.pushPose();
			poseStack.translate(feet.x - eye.x, feet.y + other.getBbHeight() + ABOVE_HEAD - eye.y, feet.z - eye.z);
			poseStack.mulPose(camera.rotation());
			poseStack.scale(SCALE, -SCALE, SCALE);
			float x = -font.width(sign) / 2.0F;
			font.drawInBatch(sign, x, 0.0F, 0xFF000000, false, poseStack.last().pose(), buffers, Font.DisplayMode.NORMAL, 0xFFFFFFFF,
					LightTexture.FULL_BRIGHT);
			poseStack.popPose();
			drawn++;
		}
		shown = drawn;
	}

	/** The F7 line: other players in this level, bubbles drawn, players faded inside a blockade. */
	public static String debugLine() {
		ClientLevel level = Minecraft.getInstance().level;
		int others = level == null ? 0 : Math.max(0, level.players().size() - 1);
		return I18n.get("kantobridge.debug.players", others, shown, BlockadeFade.count());
	}
}
