package dev.kantobridge.battle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.pokemon.Dvs;
import dev.kantobridge.pokemon.Gen1Mon;
import dev.kantobridge.pokemon.MonDetails;
import dev.kantobridge.pokemon.Ivs;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.DataException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The server's strict reading of {@code battle.start} (protocol/messages/battle.start.schema.json). */
class BattleStartTest {
	static final String RIVAL = "{\"battle\":\"b-7\",\"kind\":\"rival\",\"trainer\":{\"cls\":\"OPP_RIVAL1\",\"party\":1,\"name\":\"TEST\","
			+ "\"npc\":\"OAKS_LAB_obj_1\",\"map\":\"OAKS_LAB\",\"x\":4,\"y\":3},"
			+ "\"team\":[{\"dex\":7,\"level\":5,\"moves\":[33,39],\"dvs\":{\"atk\":9,\"def\":8,\"spd\":8,\"spc\":8}}],"
			+ "\"rules\":{\"catch\":false,\"flee\":false}}";

	@Test
	void theProtocolExamplesRead() throws Exception {
		Path file = Path.of(System.getProperty("kb.protocolDir"), "examples", "battle.start.json");
		JsonArray examples = ProtoJson.parse(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
		assertFalse(examples.isEmpty());
		for (JsonElement example : examples) {
			JsonObject data = example.getAsJsonObject().getAsJsonObject("data");
			BattleStart start = BattleStart.read(data);
			assertEquals(data.get("battle").getAsString(), start.battle());
		}
	}

	@Test
	void aRivalBattleReadsCompletely() throws Exception {
		BattleStart start = BattleStart.read(ProtoJson.parseObject(RIVAL));
		assertEquals("b-7", start.battle());
		assertEquals(BattleStart.Kind.RIVAL, start.kind());
		BattleStart.Trainer trainer = start.trainer();
		assertNotNull(trainer);
		assertEquals("OPP_RIVAL1", trainer.cls());
		assertEquals(1, trainer.party());
		assertTrue(trainer.hasCell());
		assertEquals("OAKS_LAB", trainer.map());
		assertEquals(4, trainer.x());
		assertEquals(3, trainer.y());
		assertEquals(List.of(new Gen1Mon(7, 5, List.of(33, 39), new Dvs(9, 8, 8, 8))), start.team());
		assertTrue(start.hasTeam());
		assertNull(start.wild());
		assertFalse(start.canCatch() || start.canFlee());
	}

	@Test
	void aGen3TrainerBattleReads() throws Exception {
		// MG7: national dex numbers past 151, Showdown moves past 165, IVs, Emerald's trainer ids past 255
		BattleStart start = BattleStart.read(json("{\"battle\":\"b-3\",\"kind\":\"trainer\","
				+ "\"trainer\":{\"cls\":\"TRAINER_CLASS_LEADER\",\"party\":854},"
				+ "\"team\":[{\"dex\":254,\"level\":45,\"moves\":[345,348],\"ivs\":{\"hp\":31,\"atk\":0,\"def\":1,\"spa\":2,\"spd\":3,\"spe\":4}}],"
				+ "\"rules\":{\"catch\":false,\"flee\":false}}"));
		Gen1Mon sceptile = start.team().get(0);
		assertEquals(254, sceptile.dex());
		assertEquals(List.of(345, 348), sceptile.moves());
		assertEquals(new Ivs(31, 0, 1, 2, 3, 4), sceptile.ivs());
		assertNull(sceptile.dvs());
		assertEquals(854, start.trainer().party());
		BattleStart wild = BattleStart.read(json("{\"battle\":\"b-4\",\"kind\":\"static\",\"wild\":{\"dex\":386,\"level\":30},"
				+ "\"rules\":{\"catch\":true,\"flee\":true}}"));
		assertEquals(386, wild.wild().dex());
	}

	@Test
	void aGen2TrainerAndWildCarryTheirDetails() throws Exception {
		// MG9 (G2F): Gen 2's DVs (Gen 1's shape), a held item by its canonical id, gender, shininess, Unown's letter
		BattleStart start = BattleStart.read(json("{\"battle\":\"b-5\",\"kind\":\"trainer\","
				+ "\"trainer\":{\"cls\":\"FALKNER\",\"party\":1},"
				+ "\"team\":[{\"dex\":16,\"level\":7,\"moves\":[33],\"dvs\":{\"atk\":9,\"def\":8,\"spd\":8,\"spc\":8},"
				+ "\"item\":\"oran_berry\",\"gender\":\"male\",\"shiny\":false}],"
				+ "\"rules\":{\"catch\":false,\"flee\":false}}"));
		Gen1Mon pidgey = start.team().get(0);
		assertEquals(new MonDetails("oran_berry", false, "male", null, null), pidgey.details());
		BattleStart wild = BattleStart.read(json("{\"battle\":\"b-6\",\"kind\":\"static\",\"wild\":{\"dex\":130,\"level\":30,"
				+ "\"dvs\":{\"atk\":14,\"def\":10,\"spd\":10,\"spc\":10},\"shiny\":true,\"gender\":\"female\"},"
				+ "\"rules\":{\"catch\":true,\"flee\":true}}"));
		assertEquals(14, wild.wild().dvs().atk());
		assertEquals(Boolean.TRUE, wild.wild().details().shiny());
		BattleStart unown = BattleStart.read(json("{\"battle\":\"b-7\",\"kind\":\"static\",\"wild\":{\"dex\":201,\"level\":5,"
				+ "\"form\":\"k\"},\"rules\":{\"catch\":true,\"flee\":true}}"));
		assertEquals("k", unown.wild().details().form());
		assertEquals(MonDetails.NONE, BattleStart.read(json("{\"battle\":\"a\",\"kind\":\"static\",\"wild\":{\"dex\":143,\"level\":30},"
				+ "\"rules\":{\"catch\":true,\"flee\":true}}")).wild().details(), "a Gen 1 wild says nothing");
		assertBad("{\"battle\":\"a\",\"kind\":\"static\",\"wild\":{\"dex\":143,\"level\":30,\"item\":\"ORAN_BERRY\"},"
				+ "\"rules\":{\"catch\":true,\"flee\":true}}");
		assertBad("{\"battle\":\"a\",\"kind\":\"static\",\"wild\":{\"dex\":143,\"level\":30,\"gender\":\"other\"},"
				+ "\"rules\":{\"catch\":true,\"flee\":true}}");
		assertBad("{\"battle\":\"a\",\"kind\":\"static\",\"wild\":{\"dex\":143,\"level\":30,\"friendship\":3},"
				+ "\"rules\":{\"catch\":true,\"flee\":true}}");
	}

	@Test
	void kindsNeedTheirParts() throws Exception {
		String rules = "\"rules\":{\"catch\":true,\"flee\":true}";
		String team = "\"team\":[{\"dex\":1,\"level\":5,\"moves\":[33],\"dvs\":{\"atk\":1,\"def\":1,\"spd\":1,\"spc\":1}}]";
		String trainer = "\"trainer\":{\"cls\":\"OPP_YOUNGSTER\",\"party\":0}";
		String wild = "\"wild\":{\"dex\":143,\"level\":30}";
		// Valid.
		BattleStart.read(json("{\"battle\":\"a\",\"kind\":\"trainer\"," + trainer + "," + team + "," + rules + "}"));
		BattleStart.read(json("{\"battle\":\"a\",\"kind\":\"static\"," + wild + "," + rules + "}"));
		BattleStart.read(json("{\"battle\":\"a\",\"kind\":\"scripted\"," + team + "," + rules + "}"));
		BattleStart.read(json("{\"battle\":\"a\",\"kind\":\"scripted\"," + wild + "," + rules + "}"));
		// Missing parts.
		assertBad("{\"battle\":\"a\",\"kind\":\"trainer\"," + team + "," + rules + "}");
		assertBad("{\"battle\":\"a\",\"kind\":\"rival\"," + trainer + "," + rules + "}");
		assertBad("{\"battle\":\"a\",\"kind\":\"surf\"," + rules + "}");
		assertBad("{\"battle\":\"a\",\"kind\":\"scripted\"," + rules + "}");
		assertBad("{\"battle\":\"a\",\"kind\":\"trainer\"," + trainer + "," + team + "}");
	}

	@Test
	void malformedPartsAreRefused() throws Exception {
		JsonObject good = json(RIVAL);
		assertBad(with(good, "kind", "\"gym\""));
		assertBad(with(good, "battle", "\"has space\""));
		assertBad(with(good, "battle", "\"\""));
		assertBad(with(good, "extra", "1"));
		assertBad(with(good, "team", "[]"));
		assertBad(with(good, "team", "[" + "{\"dex\":1,\"level\":5,\"moves\":[1],\"dvs\":{\"atk\":1,\"def\":1,\"spd\":1,\"spc\":1}},".repeat(6)
				+ "{\"dex\":1,\"level\":5,\"moves\":[1],\"dvs\":{\"atk\":1,\"def\":1,\"spd\":1,\"spc\":1}}]"));
		for (String mon : new String[] {
				"{\"dex\":2001,\"level\":5,\"moves\":[1],\"dvs\":{\"atk\":1,\"def\":1,\"spd\":1,\"spc\":1}}",
				"{\"dex\":1,\"level\":0,\"moves\":[1],\"dvs\":{\"atk\":1,\"def\":1,\"spd\":1,\"spc\":1}}",
				"{\"dex\":1,\"level\":101,\"moves\":[1],\"dvs\":{\"atk\":1,\"def\":1,\"spd\":1,\"spc\":1}}",
				"{\"dex\":1,\"level\":5,\"moves\":[],\"dvs\":{\"atk\":1,\"def\":1,\"spd\":1,\"spc\":1}}",
				"{\"dex\":1,\"level\":5,\"moves\":[1,2,3,4,5],\"dvs\":{\"atk\":1,\"def\":1,\"spd\":1,\"spc\":1}}",
				"{\"dex\":1,\"level\":5,\"moves\":[2001],\"dvs\":{\"atk\":1,\"def\":1,\"spd\":1,\"spc\":1}}",
				"{\"dex\":1,\"level\":5,\"moves\":[1]}",
				"{\"dex\":1,\"level\":5,\"moves\":[1],\"dvs\":{\"atk\":1,\"def\":1,\"spd\":1,\"spc\":1},"
						+ "\"ivs\":{\"hp\":1,\"atk\":1,\"def\":1,\"spa\":1,\"spd\":1,\"spe\":1}}",
				"{\"dex\":1,\"level\":5,\"moves\":[1],\"ivs\":{\"hp\":32,\"atk\":1,\"def\":1,\"spa\":1,\"spd\":1,\"spe\":1}}",
				"{\"dex\":1,\"level\":5,\"moves\":[1],\"ivs\":{\"hp\":1,\"atk\":1,\"def\":1,\"spa\":1,\"spd\":1}}",
				"{\"dex\":1,\"level\":5,\"moves\":[1],\"dvs\":{\"atk\":1,\"def\":1,\"spd\":1,\"spc\":1},\"ability\":1}",
				"{\"dex\":1,\"level\":5,\"moves\":[1],\"dvs\":{\"atk\":1,\"def\":1,\"spd\":1,\"spc\":1},\"friendship\":1}"}) {
			assertBad(with(good, "team", "[" + mon + "]"));
		}
		assertBad(with(good, "trainer", "{\"cls\":\"OPP RIVAL\",\"party\":1}"));
		assertBad(with(good, "trainer", "{\"cls\":\"OPP_RIVAL1\",\"party\":1024}"));
		assertBad(json("{\"battle\":\"a\",\"kind\":\"static\",\"wild\":{\"dex\":2001,\"level\":30},\"rules\":{\"catch\":true,\"flee\":true}}"));
		assertBad(with(good, "trainer", "{\"cls\":\"OPP_RIVAL1\",\"party\":1,\"name\":\"" + "X".repeat(33) + "\"}"));
		assertBad(with(good, "trainer", "{\"cls\":\"OPP_RIVAL1\",\"party\":1,\"map\":\"oaks_lab\"}"));
		assertBad(with(good, "trainer", "{\"cls\":\"OPP_RIVAL1\",\"party\":1,\"x\":5000}"));
		assertBad(with(good, "rules", "{\"catch\":false}"));
	}

	@Test
	void repeatedMovesAreKeptOnce() throws Exception {
		JsonObject data = with(json(RIVAL), "team", "[{\"dex\":7,\"level\":5,\"moves\":[33,39,33],\"dvs\":{\"atk\":9,\"def\":8,\"spd\":8,\"spc\":8}}]");
		assertEquals(List.of(33, 39), BattleStart.read(data).team().get(0).moves());
	}

	@Test
	void theIdOfABrokenMessageCanStillBeAnswered() throws Exception {
		assertEquals("b-7", BattleStart.idOf(with(json(RIVAL), "kind", "\"gym\"")));
		assertNull(BattleStart.idOf(json("{\"battle\":7}")));
		assertNull(BattleStart.idOf(json("{\"battle\":\"two words\"}")));
		assertNull(BattleStart.idOf(json("{}")));
	}

	@Test
	void errorsNameAPathNotAValue() throws Exception {
		DataException e = assertThrows(DataException.class, () -> BattleStart.read(with(json(RIVAL), "trainer",
				"{\"cls\":\"OPP_RIVAL1\",\"party\":1,\"name\":\"" + "SECRETNAME".repeat(4) + "\"}")));
		assertEquals("/trainer/name", e.path());
		assertFalse(e.getMessage().contains("SECRETNAME"), "the message must not quote the value");
	}

	@Test
	void theForeignPokemonFieldsRead() throws Exception {
		// FP1 (FOREIGN_POKEMON_PLAN §4.12): use, format, ally; absent they are the whole party, singles, no ally
		BattleStart plain = BattleStart.read(json(RIVAL));
		assertEquals(List.of(), plain.use());
		assertEquals(BattleStart.Format.SINGLES, plain.format());
		assertNull(plain.ally());
		String uid = "0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b";
		BattleStart lead = BattleStart.read(with(json(RIVAL), "use", "[\"" + uid + "\"]"));
		assertEquals(List.of(java.util.UUID.fromString(uid)), lead.use());
		String allyTeam = "{\"name\":\"PARTNER\",\"team\":[{\"dex\":376,\"level\":42,\"moves\":[232],"
				+ "\"ivs\":{\"hp\":31,\"atk\":31,\"def\":31,\"spa\":31,\"spd\":31,\"spe\":31}}]}";
		BattleStart multi = BattleStart.read(with(with(json(RIVAL), "format", "\"multi\""), "ally", allyTeam));
		assertEquals(BattleStart.Format.MULTI, multi.format());
		assertEquals("PARTNER", multi.ally().name());
		assertEquals(376, multi.ally().team().get(0).dex());
		assertEquals(BattleStart.Format.DOUBLE, BattleStart.read(with(json(RIVAL), "format", "\"double\"")).format());
		assertBad(with(json(RIVAL), "use", "[]"));
		assertBad(with(json(RIVAL), "use", "[\"" + uid + "\",\"" + uid + "\"]"));
		assertBad(with(json(RIVAL), "use", "[\"nope\"]"));
		assertBad(with(json(RIVAL), "format", "\"triple\""));
		assertBad(with(json(RIVAL), "ally", allyTeam));
		assertBad(with(with(json(RIVAL), "format", "\"multi\""), "ally", "{\"team\":[]}"));
		assertBad(with(with(json(RIVAL), "format", "\"multi\""), "ally", "{\"team\":[{\"dex\":1,\"level\":5,\"moves\":[33],"
				+ "\"dvs\":{\"atk\":1,\"def\":1,\"spd\":1,\"spc\":1}}],\"x\":1}"));
	}

	private static void assertBad(JsonObject data) {
		assertThrows(DataException.class, () -> BattleStart.read(data));
	}

	private static void assertBad(String text) throws Exception {
		assertBad(json(text));
	}

	private static JsonObject with(JsonObject base, String key, String value) throws Exception {
		JsonObject copy = base.deepCopy();
		copy.add(key, ProtoJson.parse(value));
		return copy;
	}

	private static JsonObject json(String text) throws Exception {
		return ProtoJson.parseObject(text);
	}
}
