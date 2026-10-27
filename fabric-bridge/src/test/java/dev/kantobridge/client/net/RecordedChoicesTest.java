package dev.kantobridge.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.proto.ProtoJson;
import org.junit.jupiter.api.Test;

/** WORLD_SESSIONS_PLAN WS2: the recorded choices a server's session.bind names. */
class RecordedChoicesTest {
	@Test
	void theRegionsChoicesAreReadAndMalformedOnesLeftOut() throws Exception {
		RecordedChoices choices = RecordedChoices.of(ProtoJson.parseObject("{\"world\":\"w\",\"multiplayer\":true,\"regions\":{"
				+ "\"kanto\":{\"slot\":3,\"save\":\"copy\"},\"johto\":{\"save\":\"new\"},\"hoenn\":{\"slot\":2,\"save\":\"keep\"},\"Bad Id\":{\"slot\":1}}}"));
		assertEquals(new RecordedChoices.Choice(3, "copy"), choices.forRegion("kanto"));
		assertEquals(new RecordedChoices.Choice(null, "new"), choices.forRegion("johto"));
		assertEquals(new RecordedChoices.Choice(2, null), choices.forRegion("hoenn"), "an unknown choice is dropped, the slot kept");
		assertEquals(3, choices.byRegion().size());
		assertNull(RecordedChoices.of(ProtoJson.parseObject("{\"world\":\"w\",\"multiplayer\":false}")).forRegion("kanto"));
		assertEquals(RecordedChoices.NONE, RecordedChoices.of(null));
	}
}
