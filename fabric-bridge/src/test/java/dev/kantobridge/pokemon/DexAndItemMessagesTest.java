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
		DexSync dex = DexSync.of(List.of(19, 16, 151, 152, 0), List.of(25, 7));
		assertEquals(List.of(7, 16, 19, 25, 151), List.copyOf(dex.seen()), "sorted, owned included, beyond 151 dropped");
		assertEquals(List.of(7, 25), List.copyOf(dex.owned()));
		assertEquals("{\"owned\":[7,25],\"seen\":[7,16,19,25,151]}", ProtoJson.write(dex.toJson()));
		DexSync read = DexSync.read(ProtoJson.parseObject("{\"seen\":[1,4],\"owned\":[4]}"));
		assertEquals(Set.of(1, 4), read.seen());
		assertEquals(Set.of(4), read.owned());
		assertEquals(DexSync.of(List.of(), List.of()).toJson().toString(), "{\"seen\":[],\"owned\":[]}");
		assertThrows(DataException.class, () -> DexSync.read(ProtoJson.parseObject("{\"seen\":[152],\"owned\":[]}")));
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
		assertEquals(5, PartyItem.STONES.size(), "Gen 1's five stones");
		// Malformed: a bad uid, a slot without a move, a move beyond Gen 1, an extra field.
		for (String bad : List.of("{\"item\":\"RARE_CANDY\",\"uid\":\"nope\"}", "{\"item\":\"TM_X\",\"uid\":\"" + uid + "\",\"slot\":1}",
				"{\"item\":\"TM_X\",\"uid\":\"" + uid + "\",\"move\":166}", "{\"item\":\"RARE_CANDY\",\"uid\":\"" + uid + "\",\"qty\":1}")) {
			assertThrows(DataException.class, () -> PartyItem.read(ProtoJson.parseObject(bad)), bad);
		}
	}
}
