package dev.kantobridge.client.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SE6: every weather the protocol names has a look here, and the looks keep the game's order of things. */
class WeatherTest {
	/** The protocol's weather names (protocol/defs.schema.json playerState.weather). */
	static List<String> protocolNames() throws Exception {
		String dir = System.getProperty("kb.protocolDir");
		JsonObject defs = JsonParser.parseString(Files.readString(Path.of(dir, "defs.schema.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		List<String> out = new ArrayList<>();
		for (JsonElement name : defs.getAsJsonObject("$defs").getAsJsonObject("playerState").getAsJsonObject("properties")
				.getAsJsonObject("weather").getAsJsonArray("enum")) {
			out.add(name.getAsString());
		}
		return out;
	}

	@Test
	void everyProtocolNameIsAWeatherAndBack() throws Exception {
		List<String> names = protocolNames();
		assertEquals(Weather.values().length, names.size(), "one weather per protocol name");
		for (String name : names) {
			Weather weather = Weather.byId(name);
			assertNotNull(weather, name);
			assertEquals(name, weather.id());
		}
		assertNull(Weather.byId("abnormal"), "the game's abnormal weather is drawn as drought and downpour, never named");
		assertNull(Weather.byId(null));
	}

	@Test
	void theLooksKeepTheGamesOrder() {
		assertTrue(Weather.NONE.look().none() && Weather.SUNNY.look().none() && Weather.CLOUDS.look().none());
		// rain, thunder, downpour: heavier each time; only the storms flash
		assertTrue(Weather.RAIN.look().rain() < Weather.THUNDER.look().rain());
		assertTrue(Weather.THUNDER.look().rain() < Weather.DOWNPOUR.look().rain());
		assertTrue(Weather.THUNDER.look().thunder() < Weather.DOWNPOUR.look().thunder());
		assertFalse(Weather.RAIN.look().lightning());
		assertTrue(Weather.THUNDER.look().lightning() && Weather.DOWNPOUR.look().lightning());
		assertTrue(Weather.SNOW.look().snow() && !Weather.RAIN.look().snow());
		// the fogs close in more than the haze of ash or snow
		assertTrue(Weather.FOG.look().fog() > Weather.ASH.look().fog() && Weather.FOG.look().fogEnd() < Weather.ASH.look().fogEnd());
		assertEquals(Weather.FOG.look(), Weather.FOG_DIAGONAL.look());
		// particles where the game has them
		assertTrue(Weather.ASH.look().ash() > 0 && Weather.SAND.look().sand() > 0 && Weather.BUBBLES.look().bubbles() > 0);
		assertEquals(0, Weather.UNDERWATER.look().bubbles());
		// drought bleaches (a light tint), shade darkens (a dark one)
		assertTrue((Weather.DROUGHT.look().screenArgb() & 0xFFFFFF) > 0xE00000);
		assertEquals(0, Weather.SHADE.look().screenArgb() & 0xFFFFFF);
		for (Weather weather : Weather.values()) {
			assertTrue(weather.look().rain() <= 1 && weather.look().fog() <= 1, weather.id());
		}
	}
}
