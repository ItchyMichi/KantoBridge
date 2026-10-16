package dev.kantobridge.client.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** SE6: looks blend and scale as the fades need. */
class WeatherLookTest {
	@Test
	void blendingGoesFromOneToTheOther() {
		WeatherLook rain = Weather.RAIN.look();
		WeatherLook fog = Weather.FOG.look();
		assertEquals(rain, WeatherLook.blend(rain, fog, 0));
		assertEquals(fog, WeatherLook.blend(rain, fog, 1));
		WeatherLook half = WeatherLook.blend(rain, fog, 0.5F);
		assertEquals(rain.rain() / 2, half.rain(), 1e-6);
		assertEquals(fog.fog() / 2, half.fog(), 1e-6);
		// a fog fading in keeps its own distance and colour (rain has none to blend with)
		assertEquals(fog.fogEnd(), half.fogEnd(), 1e-6);
		assertEquals(fog.tintRgb(), half.tintRgb());
		assertEquals(Math.round((fog.screenArgb() >>> 24) / 2.0F), half.screenArgb() >>> 24);
		assertEquals(fog.screenArgb() & 0xFFFFFF, half.screenArgb() & 0xFFFFFF);
	}

	@Test
	void switchesFlipHalfWay() {
		WeatherLook snow = Weather.SNOW.look();
		WeatherLook storm = Weather.THUNDER.look();
		assertTrue(WeatherLook.blend(snow, storm, 0.49F).snow());
		assertFalse(WeatherLook.blend(snow, storm, 0.5F).snow());
		assertFalse(WeatherLook.blend(snow, storm, 0.49F).lightning());
		assertTrue(WeatherLook.blend(snow, storm, 0.5F).lightning());
	}

	@Test
	void scalingFadesEverythingOut() {
		WeatherLook storm = Weather.DOWNPOUR.look();
		assertEquals(storm, storm.scaled(1));
		assertTrue(storm.scaled(0).none(), "nothing at zero strength");
		WeatherLook weak = storm.scaled(0.25F);
		assertEquals(0.25F, weak.rain(), 1e-6);
		assertFalse(weak.lightning(), "no lightning below half strength");
		assertTrue(storm.scaled(0.5F).lightning());
		WeatherLook sand = Weather.SAND.look().scaled(0.5F);
		assertEquals(Math.round((Weather.SAND.look().screenArgb() >>> 24) * 0.5F), sand.screenArgb() >>> 24);
		assertEquals(3.0F, sand.sand(), 1e-6);
	}

	@Test
	void valuesAreClamped() {
		WeatherLook wild = new WeatherLook(2, false, -1, false, Float.NaN, -5, 0, 3, 0, -1, -1, -1);
		assertEquals(1, wild.rain());
		assertEquals(0, wild.thunder());
		assertEquals(0, wild.fog());
		assertEquals(0, wild.fogEnd());
		assertEquals(1, wild.tint());
		assertEquals(0, wild.ash() + wild.sand() + wild.bubbles());
	}
}
