package dev.kantobridge.client.weather;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** SE6: the weather shows only for the followed game's field, on its region's maps, with the setting on. */
class WeatherRulesTest {
	@Test
	void everyConditionCounts() {
		WeatherRules rules = new WeatherRules();
		assertTrue(rules.applies(true, true, true, "rain", true));
		assertFalse(new WeatherRules().applies(false, true, true, "rain", true), "the setting off");
		assertFalse(new WeatherRules().applies(true, false, true, "rain", true), "disconnected");
		assertFalse(new WeatherRules().applies(true, true, false, "rain", true), "at the title screen");
		assertFalse(new WeatherRules().applies(true, true, true, null, true), "a game without weather (Gen 1, Gen 2)");
		assertFalse(new WeatherRules().applies(true, true, true, "abnormal", true), "a name the protocol doesn't have");
		assertFalse(new WeatherRules().applies(true, true, true, "rain", false), "never on a map yet");
	}

	@Test
	void aMomentOffTheMapsKeepsIt() {
		WeatherRules rules = new WeatherRules();
		assertTrue(rules.applies(true, true, true, "fog", true));
		for (int i = 1; i < WeatherRules.GRACE_TICKS; i++) {
			assertTrue(rules.applies(true, true, true, "fog", false), "tick " + i + " off the maps");
		}
		assertFalse(rules.applies(true, true, true, "fog", false), "out in a zone, another region or the void for three seconds");
		assertTrue(rules.applies(true, true, true, "fog", true), "back on a map");
		rules.reset();
		assertFalse(rules.applies(true, true, true, "fog", false), "a reset forgets the grace");
	}
}
