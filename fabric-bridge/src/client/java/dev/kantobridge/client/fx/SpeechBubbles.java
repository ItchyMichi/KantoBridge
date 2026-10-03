package dev.kantobridge.client.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kantobridge.story.Activity;
import java.util.List;
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
import net.minecraft.locale.Language;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Speech bubbles over other players (DESIGN §8 "Smoothing it over"; docs/PLAN.md §4.3 {@code fx/}): each player's
 * hidden game runs its own story, so a player reading a sign or watching a cutscene would seem to talk to thin air, and
 * one in a story battle fights a trainer nobody else sees. Their {@link Activity} (reported by their own client, synced
 * by the server to everyone else) shows as a small white bubble above their name tag: dialogue, cutscene, battle, or
 * (M10) paused, while their Gen1Recomp isn't connected and their game waits.
 *
 * <p>Drawn in {@code WorldRenderEvents.AFTER_ENTITIES} with the name-tag recipe (camera-facing, scaled text with a
 * background), occluded by walls like a hidden name tag so interiors stacked below don't shine through the ground. The
 * rules ({@link #bubble}) are plain Java. The signs and their widths are kept until the language changes, so a frame
 * allocates nothing of ours.
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
	/** Each bubble's sign and its width, in the current language. */
	private static final String[] SIGNS = new String[Bubble.values().length];
	private static final float[] HALF_WIDTHS = new float[Bubble.values().length];
	private static @Nullable Language signsIn;

	/** What a bubble says, as a lang key (a sign, not words: it reads the same in every language). */
	public enum Bubble {
		DIALOGUE("kantobridge.bubble.dialogue"),
		CUTSCENE("kantobridge.bubble.cutscene"),
		BATTLE("kantobridge.bubble.battle"),
		PAUSED("kantobridge.bubble.paused");

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
			case PAUSED -> Bubble.PAUSED;
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
		List<AbstractClientPlayer> players = level.players();
		if (players.size() < 2) {
			shown = 0;
			return;
		}
		Font font = minecraft.font;
		signs(font);
		float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);
		Camera camera = context.camera();
		Vec3 eye = camera.getPosition();
		PoseStack poseStack = context.matrixStack();
		int drawn = 0;
		for (int i = 0; i < players.size(); i++) {
			AbstractClientPlayer other = players.get(i);
			Bubble bubble = bubble(Activity.of(other), other == local, other.isSpectator(), other.isInvisibleTo(local), other.distanceToSqr(local));
			if (bubble == null) {
				continue;
			}
			double x = Mth.lerp(partialTick, other.xo, other.getX());
			double y = Mth.lerp(partialTick, other.yo, other.getY());
			double z = Mth.lerp(partialTick, other.zo, other.getZ());
			poseStack.pushPose();
			poseStack.translate(x - eye.x, y + other.getBbHeight() + ABOVE_HEAD - eye.y, z - eye.z);
			poseStack.mulPose(camera.rotation());
			poseStack.scale(SCALE, -SCALE, SCALE);
			int index = bubble.ordinal();
			font.drawInBatch(SIGNS[index], -HALF_WIDTHS[index], 0.0F, 0xFF000000, false, poseStack.last().pose(), buffers, Font.DisplayMode.NORMAL,
					0xFFFFFFFF, LightTexture.FULL_BRIGHT);
			poseStack.popPose();
			drawn++;
		}
		shown = drawn;
	}

	/** The signs in the current language (made again only when it changes). */
	private static void signs(Font font) {
		Language language = Language.getInstance();
		if (language == signsIn) {
			return;
		}
		signsIn = language;
		for (Bubble bubble : Bubble.values()) {
			String sign = I18n.get(bubble.key());
			SIGNS[bubble.ordinal()] = sign;
			HALF_WIDTHS[bubble.ordinal()] = font.width(sign) / 2.0F;
		}
	}

	/** The F7 line: other players in this level, bubbles drawn, players faded inside a blockade. */
	public static String debugLine() {
		ClientLevel level = Minecraft.getInstance().level;
		int others = level == null ? 0 : Math.max(0, level.players().size() - 1);
		return I18n.get("kantobridge.debug.players", others, shown, BlockadeFade.count(), SurfSprite.othersShown());
	}
}
