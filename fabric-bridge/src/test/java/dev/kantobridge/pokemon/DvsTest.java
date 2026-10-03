package dev.kantobridge.pokemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.DataException;
import org.junit.jupiter.api.Test;

/** Gen 1 DVs to Cobblemon IVs (docs/PLAN.md P11). */
class DvsTest {
	@Test
	void eachDvBecomesTwiceItPlusOne() {
		assertEquals(1, Dvs.iv(0));
		assertEquals(17, Dvs.iv(8));
		assertEquals(19, Dvs.iv(9));
		assertEquals(31, Dvs.iv(15));
		for (int dv = 0; dv <= 15; dv++) {
			int iv = Dvs.iv(dv);
			assertEquals(1, iv % 2, "odd IVs only");
			assertEquals(dv, (iv - 1) / 2, "the DV is recoverable");
		}
		assertThrows(IllegalArgumentException.class, () -> Dvs.iv(16));
		assertThrows(IllegalArgumentException.class, () -> Dvs.iv(-1));
	}

	@Test
	void hpComesFromTheLowestBits() {
		// Gen 1's trainer DVs (attack 9, the rest 8): HP DV = 1000b = 8.
		Dvs trainer = new Dvs(9, 8, 8, 8);
		assertEquals(8, trainer.hp());
		assertEquals(0, new Dvs(8, 8, 8, 8).hp());
		assertEquals(15, new Dvs(15, 15, 15, 15).hp());
		assertEquals(0b0101, new Dvs(2, 3, 4, 5).hp());
		assertEquals(0b1010, new Dvs(1, 2, 3, 4).hp());
	}

	@Test
	void specialCoversBothSpecialStats() {
		Dvs dvs = new Dvs(9, 8, 7, 6);
		assertEquals(19, dvs.ivFor(Dvs.Stat.ATTACK));
		assertEquals(17, dvs.ivFor(Dvs.Stat.DEFENCE));
		assertEquals(15, dvs.ivFor(Dvs.Stat.SPEED));
		assertEquals(13, dvs.ivFor(Dvs.Stat.SPECIAL_ATTACK));
		assertEquals(13, dvs.ivFor(Dvs.Stat.SPECIAL_DEFENCE));
		// HP DV: atk 1, def 0, spd 1, spc 0 → 1010b = 10 → IV 21.
		assertEquals(21, dvs.ivFor(Dvs.Stat.HP));
	}

	@Test
	void readingIsStrict() throws Exception {
		assertEquals(new Dvs(1, 2, 3, 4), Dvs.read(json("{\"atk\":1,\"def\":2,\"spd\":3,\"spc\":4}"), "/dvs"));
		for (String bad : new String[] {"{\"atk\":1,\"def\":2,\"spd\":3}", "{\"atk\":16,\"def\":2,\"spd\":3,\"spc\":4}",
				"{\"atk\":1,\"def\":2,\"spd\":3,\"spc\":4,\"hp\":5}", "{\"atk\":1.5,\"def\":2,\"spd\":3,\"spc\":4}",
				"{\"atk\":\"1\",\"def\":2,\"spd\":3,\"spc\":4}"}) {
			DataException e = assertThrows(DataException.class, () -> Dvs.read(json(bad), "/dvs"), bad);
			assertEquals(true, e.path().startsWith("/dvs"), "the path names the field");
		}
	}

	private static JsonObject json(String text) throws Exception {
		return ProtoJson.parseObject(text);
	}
}
