package dev.kantobridge.pokemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.DataException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code dex.sync} bodies and {@code party.item} requests, read and written without a server. */
class DexAndItemMessagesTest {
	@Test
	void ownedSpeciesCountAsSeen() throws Exception {
		DexSync dex = DexSync.of(List.of(19, 16, 151, 386, 2001, 0), List.of(25, 7));
		assertEquals(List.of(7, 16, 19, 25, 151, 386), List.copyOf(dex.seen()), "sorted, owned included, outside the protocol dropped");
		assertEquals(List.of(7, 25), List.copyOf(dex.owned()));
		assertEquals("{\"owned\":[7,25],\"seen\":[7,16,19,25,151,386]}", ProtoJson.write(dex.toJson()));
		DexSync read = DexSync.read(ProtoJson.parseObject("{\"seen\":[1,4],\"owned\":[4]}"));
		assertEquals(Set.of(1, 4), read.seen());
		assertEquals(Set.of(4), read.owned());
		assertEquals(DexSync.of(List.of(), List.of()).toJson().toString(), "{\"seen\":[],\"owned\":[]}");
		assertEquals(Set.of(152, 386), DexSync.read(ProtoJson.parseObject("{\"seen\":[152,386],\"owned\":[]}")).seen(), "MG7: any game's");
		assertThrows(DataException.class, () -> DexSync.read(ProtoJson.parseObject("{\"seen\":[2001],\"owned\":[]}")));
		assertThrows(DataException.class, () -> DexSync.read(ProtoJson.parseObject("{\"seen\":[1]}")));
		assertThrows(DataException.class, () -> DexSync.read(ProtoJson.parseObject("{\"seen\":[],\"owned\":[],\"caught\":[]}")));
	}

	@Test
	void itemsUsedOnAPokemon() throws Exception {
		String uid = "0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b";
		PartyItem stone = PartyItem.read(ProtoJson.parseObject("{\"item\":\"THUNDER_STONE\",\"uid\":\"" + uid + "\"}"));
		assertEquals(PartyItem.Kind.STONE, stone.kind());
		assertEquals(UUID.fromString(uid), stone.uid());
		assertEquals("thunder_stone", PartyItem.STONES.get(stone.item()));
		PartyItem machine = PartyItem.read(ProtoJson.parseObject("{\"item\":\"TM_BODY_SLAM\",\"uid\":\"" + uid + "\",\"slot\":2,\"move\":34}"));
		assertEquals(PartyItem.Kind.MACHINE, machine.kind());
		assertEquals(2, machine.slot());
		assertEquals(34, machine.move());
		PartyItem free = PartyItem.read(ProtoJson.parseObject("{\"item\":\"HM_CUT\",\"uid\":\"" + uid + "\",\"move\":15}"));
		assertNull(free.slot(), "a free slot");
		assertEquals(PartyItem.Kind.RARE_CANDY, PartyItem.read(ProtoJson.parseObject("{\"item\":\"RARE_CANDY\",\"uid\":\"" + uid + "\"}")).kind());
		assertEquals(PartyItem.Kind.OTHER, PartyItem.read(ProtoJson.parseObject("{\"item\":\"PP_UP\",\"uid\":\"" + uid + "\"}")).kind());
		assertEquals(6, PartyItem.STONES.size(), "Gen 1's five stones and Gen 3's Sun Stone");
		assertEquals("sun_stone", PartyItem.STONES.get("SUN_STONE"));
		assertEquals(354, PartyItem.read(ProtoJson.parseObject("{\"item\":\"TM50\",\"uid\":\"" + uid + "\",\"move\":354}")).move(), "MG7");
		// Malformed: a bad uid, a slot without a move, a move beyond the protocol, an extra field.
		for (String bad : List.of("{\"item\":\"RARE_CANDY\",\"uid\":\"nope\"}", "{\"item\":\"TM_X\",\"uid\":\"" + uid + "\",\"slot\":1}",
				"{\"item\":\"TM_X\",\"uid\":\"" + uid + "\",\"move\":2001}", "{\"item\":\"RARE_CANDY\",\"uid\":\"" + uid + "\",\"qty\":1}")) {
			assertThrows(DataException.class, () -> PartyItem.read(ProtoJson.parseObject(bad)), bad);
		}
	}

	@Test
	void anItemCarriesItsIdKindAndTheMoveForgotten() throws Exception {
		// FP1 (FOREIGN_POKEMON_PLAN §4.4): id, kind and forget; an old sender's message still reads
		String uid = "0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b";
		PartyItem machine = PartyItem.read(ProtoJson.parseObject("{\"item\":\"TM_EARTHQUAKE\",\"uid\":\"" + uid
				+ "\",\"id\":\"it-1\",\"kind\":\"machine\",\"move\":89,\"forget\":337,\"region\":\"hoenn\"}"));
		assertEquals(PartyItem.Kind.MACHINE, machine.kind());
		assertEquals("it-1", machine.id());
		assertEquals("machine", machine.sentKind());
		assertEquals(337, machine.forget());
		assertNull(machine.slot());
		PartyItem old = PartyItem.read(ProtoJson.parseObject("{\"item\":\"RARE_CANDY\",\"uid\":\"" + uid + "\"}"));
		assertNull(old.id());
		assertNull(old.sentKind());
		assertEquals(new PartyItem("RARE_CANDY", UUID.fromString(uid), null, null), old);
		// the kinds Cobblemon has no rule for yet are OTHER (FP4 applies them), whatever the item
		for (String kind : List.of("medicine", "pp", "vitamin", "other")) {
			assertEquals(PartyItem.Kind.OTHER, PartyItem.read(ProtoJson.parseObject("{\"item\":\"RARE_CANDY\",\"uid\":\"" + uid
					+ "\",\"kind\":\"" + kind + "\",\"id\":\"it-2\"}")).kind(), kind);
		}
		assertEquals(PartyItem.Kind.STONE, PartyItem.read(ProtoJson.parseObject("{\"item\":\"MOON_STONE\",\"uid\":\"" + uid
				+ "\",\"kind\":\"stone\"}")).kind());
		assertEquals(PartyItem.Kind.RARE_CANDY, PartyItem.read(ProtoJson.parseObject("{\"item\":\"RARE_CANDY\",\"uid\":\"" + uid
				+ "\",\"kind\":\"candy\"}")).kind());
		// Malformed: an unknown kind, forget without a move, a bad id, forget past the protocol.
		for (String bad : List.of("{\"item\":\"POTION\",\"uid\":\"" + uid + "\",\"kind\":\"potion\"}",
				"{\"item\":\"TM_X\",\"uid\":\"" + uid + "\",\"forget\":33}", "{\"item\":\"POTION\",\"uid\":\"" + uid + "\",\"id\":\"a b\"}",
				"{\"item\":\"TM_X\",\"uid\":\"" + uid + "\",\"move\":89,\"forget\":2001}")) {
			assertThrows(DataException.class, () -> PartyItem.read(ProtoJson.parseObject(bad)), bad);
		}
	}
}
