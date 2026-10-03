package dev.kantobridge.pokemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.world.DataException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Gen 1's daycare (docs/PLAN.md P15): the experience a withdrawal brings, and the per-player file that keeps the Pokémon. */
class DaycareTest {
	private static final UUID FIRST = UUID.fromString("0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b");
	private static final UUID SECOND = UUID.fromString("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d");

	@TempDir
	Path temp;

	private static JsonObject pokemon(String species, int level) {
		// Made-up stand-ins for Cobblemon's own Pokémon JSON: the store keeps whatever object it is given.
		JsonObject data = new JsonObject();
		data.addProperty("Species", species);
		data.addProperty("Level", level);
		data.addProperty("ScaleModifier", 1.0);
		JsonObject nested = new JsonObject();
		nested.addProperty("Nickname", "Débora");
		data.add("Extra", nested);
		return data;
	}

	@Test
	void eachStepIsOneExperiencePointUpToLevelOneHundred() {
		assertEquals(420, DaycareRules.experience(420, 1_000, 1_000_000));
		assertEquals(0, DaycareRules.experience(0, 1_000, 1_000_000), "no steps, nothing");
		assertEquals(0, DaycareRules.experience(-5, 1_000, 1_000_000));
		assertEquals(200, DaycareRules.experience(500, 999_800, 1_000_000), "never past the cap's experience");
		assertEquals(0, DaycareRules.experience(500, 1_000_000, 1_000_000), "already at level 100");
		assertEquals(0, DaycareRules.experience(500, 1_200_000, 1_000_000), "beyond it (a lower cap): nothing taken away");
		assertEquals(1_000_000 - 7, DaycareRules.experience(Integer.MAX_VALUE, 7, 1_000_000));
		assertEquals(100, DaycareRules.levelCap(100));
		assertEquals(50, DaycareRules.levelCap(50), "a lower configured cap holds");
		assertEquals(100, DaycareRules.levelCap(250), "Gen 1 stops at 100");
	}

	@Test
	void theFileKeepsThePokemonAcrossRestarts() throws Exception {
		Path file = this.temp.resolve("kantobridge/daycare/player.json");
		DaycareStore.Loaded none = DaycareStore.load(file);
		assertEquals(DaycareStore.Origin.EMPTY, none.origin());
		DaycareStore store = none.store();
		store.put(FIRST, pokemon("cobblemon:psyduck", 21));
		store.put(SECOND, pokemon("cobblemon:geodude", 12));
		store.write(file);
		assertFalse(Files.exists(file.resolveSibling("player.json.tmp")), "written through a temporary file");

		DaycareStore.Loaded read = DaycareStore.load(file);
		assertEquals(DaycareStore.Origin.READ, read.origin());
		assertEquals(List.of(FIRST, SECOND), read.store().uids(), "in the order they came");
		assertEquals(pokemon("cobblemon:psyduck", 21), read.store().get(FIRST), "Cobblemon's data comes back as it was");
		assertTrue(read.store().contains(SECOND));

		// A copy goes out: changing it changes nothing kept.
		read.store().get(FIRST).addProperty("Level", 99);
		assertEquals(21, read.store().get(FIRST).get("Level").getAsInt());

		assertTrue(read.store().remove(FIRST));
		assertFalse(read.store().remove(FIRST));
		read.store().write(file);
		DaycareStore again = DaycareStore.load(file).store();
		assertEquals(List.of(SECOND), again.uids());
		assertNull(again.get(FIRST));
	}

	@Test
	void anUnreadableFileIsMovedAsideNeverOverwritten() throws Exception {
		Path file = this.temp.resolve("daycare.json");
		Files.writeString(file, "{\"format\":1,\"pokemon\":[{\"uid\":\"not-a-uid\",\"data\":{}}]}", StandardCharsets.UTF_8);
		DaycareStore.Loaded loaded = DaycareStore.load(file);
		assertEquals(DaycareStore.Origin.REPLACED, loaded.origin());
		assertEquals(0, loaded.store().size());
		assertFalse(Files.exists(file));
		assertTrue(Files.readString(file.resolveSibling("daycare.json" + DaycareStore.BROKEN_SUFFIX)).contains("not-a-uid"), "kept for recovery");
	}

	@Test
	void parsingIsStrictAboutItsOwnFields() {
		assertThrows(DataException.class, () -> DaycareStore.parse("[]"));
		assertThrows(DataException.class, () -> DaycareStore.parse("{\"format\":2,\"pokemon\":[]}"));
		assertThrows(DataException.class, () -> DaycareStore.parse("{\"format\":1}"));
		assertThrows(DataException.class, () -> DaycareStore.parse("{\"format\":1,\"pokemon\":[{\"uid\":\"" + FIRST + "\",\"data\":3}]}"));
		String twice = "{\"uid\":\"" + FIRST + "\",\"data\":{}}";
		assertThrows(DataException.class, () -> DaycareStore.parse("{\"format\":1,\"pokemon\":[" + twice + "," + twice + "]}"), "a duplicate");
		assertThrows(DataException.class, () -> DaycareStore.parse("{not json"));
	}
}
