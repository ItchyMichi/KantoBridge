package dev.kantobridge.client.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** SE6: the weather fades in and out over two seconds, and a change blends from what is showing. */
class WeatherFadeTest {
	private static void ticks(WeatherFade fade, int n) {
		for (int i = 0; i < n; i++) {
			fade.tick();
		}
	}

	@Test
	void fadesInOverTwoSecondsThenHolds() {
		WeatherFade fade = new WeatherFade();
		assertTrue(fade.current().none());
		fade.want(Weather.RAIN, true);
		ticks(fade, WeatherFade.FADE_TICKS / 2);
		assertEquals(Weather.RAIN.look().rain() / 2, fade.current().rain(), 1e-5);
		ticks(fade, WeatherFade.FADE_TICKS / 2);
		assertEquals(Weather.RAIN.look(), fade.current());
		ticks(fade, 100);
		assertEquals(Weather.RAIN.look(), fade.current());
		assertEquals(Weather.RAIN, fade.target());
	}

	@Test
	void aChangeBlendsFromWhatIsShowing() {
		WeatherFade fade = new WeatherFade();
		fade.want(Weather.RAIN, true);
		ticks(fade, WeatherFade.FADE_TICKS);
		fade.want(Weather.DOWNPOUR, true);
		ticks(fade, WeatherFade.FADE_TICKS / 2);
		WeatherLook mid = fade.current();
		assertEquals((Weather.RAIN.look().rain() + Weather.DOWNPOUR.look().rain()) / 2, mid.rain(), 1e-5);
		// half way, a third weather starts from that blend, not from rain or downpour
		fade.want(Weather.FOG, true);
		fade.tick();
		assertTrue(fade.current().rain() < mid.rain() && fade.current().rain() > 0.9F * mid.rain());
		ticks(fade, WeatherFade.FADE_TICKS);
		assertEquals(Weather.FOG.look(), fade.current());
	}

	@Test
	void stoppingFadesOutAndForgets() {
		WeatherFade fade = new WeatherFade();
		fade.want(Weather.SAND, true);
		ticks(fade, WeatherFade.FADE_TICKS);
		fade.want(Weather.SAND, false);
		ticks(fade, WeatherFade.FADE_TICKS / 2);
		assertEquals(Weather.SAND.look().sand() / 2, fade.current().sand(), 1e-5);
		ticks(fade, WeatherFade.FADE_TICKS / 2);
		assertTrue(fade.current().none());
		assertNull(fade.target());
		// applying again starts from nothing
		fade.want(Weather.SAND, true);
		fade.tick();
		assertEquals(Weather.SAND.look().sand() / WeatherFade.FADE_TICKS, fade.current().sand(), 1e-5);
	}

	@Test
	void noWeatherIsNone() {
		WeatherFade fade = new WeatherFade();
		fade.want(null, true);
		ticks(fade, WeatherFade.FADE_TICKS);
		assertTrue(fade.current().none());
		assertEquals(Weather.NONE, fade.target());
	}
}
