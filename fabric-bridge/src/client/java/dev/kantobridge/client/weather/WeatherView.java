package dev.kantobridge.client.weather;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.kantobridge.client.bridge.BridgeLink;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ClientBridgeState;
import dev.kantobridge.client.move.Spot;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.world.WorldIndex;
import java.util.Locale;
import java.util.random.RandomGenerator;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The hidden game's weather, drawn for this player only (SE6, docs/specs/STORY_EVENTS_PLAN.md §4.1 S8; the user,
 * 2026-10-16: "mirror it per player"). The server's weather stays off (DESIGN.md: Minecraft's own weather is off), so
 * everything here is the local client's: each tick the followed link's {@code player.state.weather} ({@link Weather}),
 * where {@link WeatherRules} says it applies, fades in or out ({@link WeatherFade}) and drives:
 *
 * <ul>
 * <li><b>precipitation</b>: the client level's rain and thunder levels (Minecraft's own rain and snow renderer, its rain
 * sounds and splashes, the darker sky), with the biome's "no precipitation" overridden for the renderer only
 * ({@code LevelRendererMixin}: Kanto's and Hoenn's biomes have none);</li>
 * <li><b>lightning</b>: the sky flash Minecraft's own lightning gives ({@code ClientLevel.setSkyFlashTime}, which also
 * brightens the lightmap), a white screen flash and distant thunder, timed as the game times them ({@link LightningClock});
 * no lightning entity;</li>
 * <li><b>fog and tints</b>: fog drawn closer and tinted ({@code FogRendererMixin}), the sky tinted
 * ({@code ClientLevelMixin}), a translucent screen tint under the HUD ({@code GuiMixin});</li>
 * <li><b>particles</b>: vanilla white ash, and {@link WeatherParticle}'s sand and bubbles, around the player (fewer with
 * reduced particles, none with minimal).</li>
 * </ul>
 * Client thread only (ticks and rendering share it).
 */
public final class WeatherView {
	/** The screen's lightning flash at its brightest (alpha). */
	static final float FLASH_ALPHA = 0.35F;
	/** Particles spawn this far around the player, in blocks. */
	static final double ASH_RADIUS = 12;
	static final double BUBBLE_RADIUS = 8;

	private static final WeatherRules RULES = new WeatherRules();
	private static final WeatherFade FADE = new WeatherFade();
	private static final LightningClock LIGHTNING = new LightningClock(RandomGenerator.getDefault());
	private static final RandomSource RANDOM = RandomSource.create();

	private static WeatherLook look = WeatherLook.NONE;
	private static float flash;
	/** True while this view has set the level's rain or thunder level (so it puts them back to 0 once, when it stops). */
	private static boolean touchedLevel;
	private static @Nullable ClientLevel lastLevel;
	private static boolean applies;
	private static @Nullable String lastWeather;
	private static long bolts;

	private WeatherView() {
	}

	/** Call once from the client entrypoint, after {@link BridgeRuntime#init}. */
	public static void init(BridgeRuntime bridge) {
		ClientTickEvents.END_CLIENT_TICK.register(WeatherView::tick);
		bridge.addDebugLine(WeatherView::debugLine);
	}

	private static void tick(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		LocalPlayer player = minecraft.player;
		if (level != lastLevel) {
			// a new world (or none): nothing carries over
			lastLevel = level;
			RULES.reset();
			FADE.want(null, false);
			touchedLevel = false;
			flash = 0;
		}
		if (level == null || player == null) {
			look = WeatherLook.NONE;
			return;
		}
		BridgeRuntime bridge = BridgeRuntime.get();
		BridgeLink link = bridge == null ? null : bridge.links().followed();
		ClientBridgeState state = link == null ? null : link.state();
		String weather = state == null ? null : state.weather();
		WorldIndex index = ClientWorldIndex.current();
		boolean onMap = index != null && Spot.at(index, player.getX(), player.getY(), player.getZ()) != null;
		applies = RULES.applies(bridge != null && bridge.config().weather(), link != null && link.connected(), state != null && state.ingame(),
				weather, onMap);
		lastWeather = weather;
		FADE.want(Weather.byId(weather), applies);
		FADE.tick();
		look = FADE.current();

		// precipitation and thunder through the level's own levels
		if (look.rain() > 0 || look.thunder() > 0 || touchedLevel) {
			level.setRainLevel(look.rain());
			level.setThunderLevel(look.thunder());
			touchedLevel = look.rain() > 0 || look.thunder() > 0;
		}

		// lightning
		LightningClock.Tick strike = LIGHTNING.tick(look.lightning());
		if (strike.bolt()) {
			level.setSkyFlashTime(2);
			bolts++;
		}
		flash = Math.max(flash * 0.6F, strike.flash());
		if (flash < 0.02F) {
			flash = 0;
		}
		if (strike.thunder()) {
			double angle = RANDOM.nextDouble() * Mth.TWO_PI;
			double distance = 40 + RANDOM.nextDouble() * 40;
			level.playLocalSound(player.getX() + Math.cos(angle) * distance, player.getY() + 16, player.getZ() + Math.sin(angle) * distance,
					SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 6.0F, 0.8F + RANDOM.nextFloat() * 0.2F, false);
		}

		// particles
		ParticleStatus particles = minecraft.options.particles().get();
		if (particles != ParticleStatus.MINIMAL) {
			float share = particles == ParticleStatus.DECREASED ? 0.5F : 1.0F;
			spawn(minecraft, level, player, share);
		}
	}

	private static void spawn(Minecraft minecraft, ClientLevel level, LocalPlayer player, float share) {
		double px = player.getX();
		double py = player.getY();
		double pz = player.getZ();
		for (int i = count(look.ash() * share); i > 0; i--) {
			level.addParticle(ParticleTypes.WHITE_ASH, px + spread(ASH_RADIUS), py + 2 + RANDOM.nextDouble() * 10, pz + spread(ASH_RADIUS), 0, 0, 0);
		}
		for (int i = count(look.sand() * share); i > 0; i--) {
			// upwind of the player (the wind blows along +x), low over the ground
			minecraft.particleEngine.add(WeatherParticle.sand(level, px - 14 + RANDOM.nextDouble() * 20, py + RANDOM.nextDouble() * 3,
					pz + spread(10), RANDOM));
		}
		for (int i = count(look.bubbles() * share); i > 0; i--) {
			minecraft.particleEngine.add(WeatherParticle.bubble(level, px + spread(BUBBLE_RADIUS), py - 0.5 + RANDOM.nextDouble() * 2,
					pz + spread(BUBBLE_RADIUS), RANDOM));
		}
	}

	/** A whole number of particles for a rate per tick (the fraction by chance). */
	private static int count(float rate) {
		int n = (int) rate;
		return RANDOM.nextFloat() < rate - n ? n + 1 : n;
	}

	private static double spread(double radius) {
		return (RANDOM.nextDouble() * 2 - 1) * radius;
	}

	// ---- for the mixins (the render thread is the client thread) ----

	/** What precipitation the renderer draws instead of the biome's, or null to leave the biome's. */
	public static Biome.@Nullable Precipitation precipitation() {
		return look.rain() > 0.01F ? look.snow() ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN : null;
	}

	/** After {@link FogRenderer#setupFog}: closes the fog in where the weather has fog (not under water, lava or snow). */
	public static void fog(Camera camera, FogRenderer.FogMode mode) {
		float strength = look.fog();
		if (strength <= 0 || camera.getFluidInCamera() != FogType.NONE) {
			return;
		}
		float end = RenderSystem.getShaderFogEnd();
		float start = RenderSystem.getShaderFogStart();
		float target = Math.min(end, look.fogEnd());
		RenderSystem.setShaderFogStart(Mth.lerp(strength, start, Math.min(start, target * 0.1F)));
		RenderSystem.setShaderFogEnd(Mth.lerp(strength, end, target));
	}

	/** The fog and sky tint as {r, g, b, strength} (0..1), or null for none. */
	public static float @Nullable [] tint(Camera camera) {
		if (look.tint() <= 0 || camera.getFluidInCamera() != FogType.NONE) {
			return null;
		}
		return rgbStrength();
	}

	private static float @Nullable [] rgbStrength() {
		if (look.tint() <= 0) {
			return null;
		}
		int rgb = look.tintRgb();
		return new float[] {(rgb >> 16 & 0xFF) / 255.0F, (rgb >> 8 & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F, look.tint()};
	}

	/** The sky's colour with the weather's tint over it. */
	public static Vec3 sky(Vec3 color) {
		float[] tint = rgbStrength();
		if (tint == null) {
			return color;
		}
		float k = tint[3];
		return new Vec3(Mth.lerp(k, color.x, tint[0]), Mth.lerp(k, color.y, tint[1]), Mth.lerp(k, color.z, tint[2]));
	}

	/** Under the HUD: the screen tint, then the lightning's white flash. */
	public static void screen(GuiGraphics graphics) {
		int tint = look.screenArgb();
		if (tint >>> 24 != 0) {
			graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), tint);
		}
		if (flash > 0) {
			int alpha = Math.round(flash * FLASH_ALPHA * 255);
			graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24 | 0xFFFFFF);
		}
	}

	/** SE5: the weather this view shows now (the followed game's, where it applies), or null; the remote pilot reports it. */
	public static @Nullable String shown() {
		Weather target = FADE.target();
		return applies && target != null ? target.id() : null;
	}

	/** F3's line: the game's weather, whether it applies here, how strongly it shows, the bolts so far. */
	public static String debugLine() {
		Weather target = FADE.target();
		return String.format(Locale.ROOT, "weather: game %s, %s, showing %s at %.2f, bolts %d", lastWeather == null ? "-" : lastWeather,
				applies ? "here" : "not here", target == null ? "-" : target.id(), FADE.strength(), bolts);
	}
}
