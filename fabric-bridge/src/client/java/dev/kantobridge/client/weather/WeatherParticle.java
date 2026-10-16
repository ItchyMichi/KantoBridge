package dev.kantobridge.client.weather;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

/**
 * The weather's own particles (SE6) where Minecraft has none that fit: blowing sand (a tan grain from the vanilla
 * {@code generic} sprites, blown sideways) and rising bubbles in the open air (the vanilla {@code bubble} sprite; the
 * vanilla bubble particles die out of water). Client-only, added straight to the particle engine, never registered; no
 * physics, no collisions, faded out at the end of their lives.
 */
final class WeatherParticle extends TextureSheetParticle {
	private static final ResourceLocation BUBBLE = ResourceLocation.withDefaultNamespace("bubble");
	private static final ResourceLocation GRAIN = ResourceLocation.withDefaultNamespace("generic_1");

	private final float baseAlpha;
	private final float wobble;
	private final float phase;

	private WeatherParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, TextureAtlasSprite sprite,
			int lifetime, float size, int rgb, float alpha, float wobble, RandomSource random) {
		super(level, x, y, z);
		this.xd = xd;
		this.yd = yd;
		this.zd = zd;
		this.setSprite(sprite);
		this.lifetime = lifetime;
		this.quadSize = size;
		this.setColor((rgb >> 16 & 0xFF) / 255.0F, (rgb >> 8 & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F);
		this.baseAlpha = alpha;
		this.setAlpha(alpha);
		this.hasPhysics = false;
		this.gravity = 0;
		this.friction = 1.0F;
		this.wobble = wobble;
		this.phase = random.nextFloat() * Mth.TWO_PI;
	}

	/** A grain of sand blown along +x (the wind) at the given place, or null while the particle atlas is missing. */
	static @Nullable WeatherParticle sand(ClientLevel level, double x, double y, double z, RandomSource random) {
		TextureAtlasSprite grain = sprite(GRAIN);
		if (grain == null) {
			return null;
		}
		int rgb = random.nextBoolean() ? 0xD2B478 : 0xC09A60;
		return new WeatherParticle(level, x, y, z, 0.35 + random.nextDouble() * 0.25, (random.nextDouble() - 0.5) * 0.04,
				(random.nextDouble() - 0.5) * 0.08, grain, 30 + random.nextInt(20), 0.04F + random.nextFloat() * 0.05F, rgb, 0.85F, 0.0F,
				random);
	}

	/** A bubble rising, swaying a little, at the given place, or null while the particle atlas is missing. */
	static @Nullable WeatherParticle bubble(ClientLevel level, double x, double y, double z, RandomSource random) {
		TextureAtlasSprite bubble = sprite(BUBBLE);
		if (bubble == null) {
			return null;
		}
		return new WeatherParticle(level, x, y, z, 0, 0.06 + random.nextDouble() * 0.05, 0, bubble, 40 + random.nextInt(40),
				0.06F + random.nextFloat() * 0.06F, 0xFFFFFF, 0.8F, 0.02F, random);
	}

	/**
	 * A sprite of the particle atlas, or null before it exists. The particle engine registers that atlas with the texture
	 * manager; the model manager's atlas set ({@code Minecraft.getTextureAtlas}) holds only the block and item atlases.
	 */
	static @Nullable TextureAtlasSprite sprite(ResourceLocation id) {
		AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_PARTICLES);
		return texture instanceof TextureAtlas atlas ? atlas.getSprite(id) : null;
	}

	@Override
	public void tick() {
		if (this.wobble > 0) {
			this.xd = Mth.sin(this.phase + this.age * 0.3F) * this.wobble;
		}
		super.tick();
		int left = this.lifetime - this.age;
		if (left < 10) {
			this.setAlpha(this.baseAlpha * Math.max(0, left) / 10.0F);
		}
	}

	@Override
	public ParticleRenderType getRenderType() {
		return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
	}
}
