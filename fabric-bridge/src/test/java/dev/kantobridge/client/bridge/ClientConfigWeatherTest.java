package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SE6: the client's weather setting: on unless the file says off; a bad value warns and stays on. */
class ClientConfigWeatherTest {
	@TempDir
	Path dir;

	private ClientConfig load(String json, List<String> warnings) throws Exception {
		Files.writeString(this.dir.resolve(ClientConfig.FILE_NAME), json, StandardCharsets.UTF_8);
		return ClientConfig.load(this.dir, new Properties(), warnings::add);
	}

	@Test
	void onByDefaultOffWhenSaid() throws Exception {
		List<String> warnings = new ArrayList<>();
		assertTrue(this.load("{\"port\": 27151}", warnings).weather());
		assertFalse(this.load("{\"port\": 27151, \"weather\": \"off\"}", warnings).weather());
		assertFalse(this.load("{\"port\": 27151, \"weather\": false}", warnings).weather());
		assertTrue(this.load("{\"port\": 27151, \"weather\": \"on\"}", warnings).weather());
		assertEquals(List.of(), warnings);
		assertTrue(this.load("{\"port\": 27151, \"weather\": \"rainy\"}", warnings).weather());
		assertEquals(1, warnings.size());
		assertTrue(ClientConfig.DEFAULTS.weather());
		assertTrue(new ClientConfig(27151, true, null, true).weather(), "every older constructor keeps the weather on");
	}
}
