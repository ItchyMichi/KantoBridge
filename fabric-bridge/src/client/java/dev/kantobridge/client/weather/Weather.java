package dev.kantobridge.client.weather;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * The hidden game's weathers as {@code player.state.weather} names them (SE6; protocol/defs.schema.json playerState,
 * Emerald's field weathers: refs/gen1recomp src/core/game3/weather.lua:13-31) and how each looks here. The game draws
 * rain as diagonal streaks over a field darkened about a fifth, thunder and downpour add lightning (the palette flashing
 * white), fog scrolls over the field, ash and sand blow across it, drought bleaches it and shade darkens it
 * (field_weather_rse.lua); Minecraft gets its own precipitation, fog, flashes, particles and a light screen tint for
 * each, never the game's art:
 *
 * <ul>
 * <li>{@code rain}: light rain (the game's ten streaks); {@code thunder}: heavier rain, a darker sky and lightning;
 * {@code downpour}: the heaviest rain, the darkest sky, a little fog and lightning;</li>
 * <li>{@code snow}: snow and a pale haze;</li>
 * <li>{@code fog}, {@code fog_diagonal}: thick pale fog; {@code underwater}: a faint blue haze; {@code bubbles}: that haze
 * and rising bubbles;</li>
 * <li>{@code ash}: falling white ash and a grey haze; {@code sand}: blowing sand, a tan haze and tint;</li>
 * <li>{@code drought}: a bright warm tint; {@code shade}: a darker one;</li>
 * <li>{@code none}, {@code sunny}, {@code clouds}: nothing (the game's three drifting clouds stay its own).</li>
 * </ul>
 */
public enum Weather {
	NONE(WeatherLook.NONE),
	CLOUDS(WeatherLook.NONE),
	SUNNY(WeatherLook.NONE),
	RAIN(new WeatherLook(0.45F, false, 0, false, 0, 0, 0, 0, 0, 0, 0, 0)),
	SNOW(new WeatherLook(0.7F, true, 0, false, 0.3F, 96, 0xE6EAF0, 0.3F, 0, 0, 0, 0)),
	THUNDER(new WeatherLook(0.75F, false, 0.6F, true, 0.2F, 128, 0x8A9098, 0.15F, 0, 0, 0, 0)),
	FOG(new WeatherLook(0, false, 0, false, 0.85F, 40, 0xC8CCD2, 0.6F, 0x14F0F0F4, 0, 0, 0)),
	ASH(new WeatherLook(0, false, 0, false, 0.25F, 112, 0x8C8C8C, 0.3F, 0x10404040, 4, 0, 0)),
	SAND(new WeatherLook(0, false, 0, false, 0.6F, 56, 0xD2AA6E, 0.6F, 0x24C89650, 0, 6, 0)),
	FOG_DIAGONAL(new WeatherLook(0, false, 0, false, 0.85F, 40, 0xC8CCD2, 0.6F, 0x14F0F0F4, 0, 0, 0)),
	UNDERWATER(new WeatherLook(0, false, 0, false, 0.4F, 96, 0x3060B0, 0.4F, 0x183060B0, 0, 0, 0)),
	SHADE(new WeatherLook(0, false, 0, false, 0, 0, 0x606870, 0.25F, 0x30000000, 0, 0, 0)),
	DROUGHT(new WeatherLook(0, false, 0, false, 0, 0, 0xFFF6E0, 0.35F, 0x2CFFF4D8, 0, 0, 0)),
	DOWNPOUR(new WeatherLook(1.0F, false, 1.0F, true, 0.35F, 96, 0x707880, 0.25F, 0, 0, 0, 0)),
	BUBBLES(new WeatherLook(0, false, 0, false, 0.4F, 96, 0x3060B0, 0.4F, 0x183060B0, 0, 0, 1.5F));

	private final WeatherLook look;

	Weather(WeatherLook look) {
		this.look = look;
	}

	/** Its protocol name ({@code fog_diagonal}, ...). */
	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public WeatherLook look() {
		return this.look;
	}

	/** The weather a protocol name names, or null (unknown or absent: nothing is drawn). */
	public static @Nullable Weather byId(@Nullable String id) {
		if (id == null) {
			return null;
		}
		for (Weather weather : values()) {
			if (weather.id().equals(id)) {
				return weather;
			}
		}
		return null;
	}
}
