package dev.kantobridge.client.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.McBootstrap;
import dev.kantobridge.client.fx.SpeechBubbles.Bubble;
import dev.kantobridge.story.Activity;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Who gets a speech bubble (DESIGN §8 "Smoothing it over"): other players busy with their own hidden game (a dialogue, a
 * cutscene, a battle), never the local player, spectators, invisible players or anyone beyond name-tag range.
 */
class SpeechBubblesTest {
	private static final double NEAR = 10.0 * 10.0;

	@BeforeAll
	static void setUp() {
		McBootstrap.ensure();
	}

	@Test
	void eachActivityHasItsBubble() {
		assertEquals(Bubble.DIALOGUE, SpeechBubbles.bubble(Activity.DIALOGUE, false, false, false, NEAR));
		assertEquals(Bubble.CUTSCENE, SpeechBubbles.bubble(Activity.CUTSCENE, false, false, false, NEAR));
		assertEquals(Bubble.BATTLE, SpeechBubbles.bubble(Activity.BATTLE, false, false, false, NEAR));
		assertEquals(Bubble.PAUSED, SpeechBubbles.bubble(Activity.PAUSED, false, false, false, NEAR), "their Gen1Recomp is away (M10)");
		assertNull(SpeechBubbles.bubble(Activity.NONE, false, false, false, NEAR), "nothing to show while they do nothing");
	}

	@Test
	void neverOverTheLocalPlayerSpectatorsOrInvisiblePlayers() {
		for (Activity activity : Activity.values()) {
			assertNull(SpeechBubbles.bubble(activity, true, false, false, 0.0), "the local player's own bubble");
			assertNull(SpeechBubbles.bubble(activity, false, true, false, NEAR), "a spectator");
			assertNull(SpeechBubbles.bubble(activity, false, false, true, NEAR), "an invisible player");
		}
	}

	@Test
	void onlyWithinNameTagRange() {
		double edge = SpeechBubbles.RANGE * SpeechBubbles.RANGE;
		assertEquals(Bubble.BATTLE, SpeechBubbles.bubble(Activity.BATTLE, false, false, false, edge));
		assertNull(SpeechBubbles.bubble(Activity.BATTLE, false, false, false, Math.nextUp(edge)));
		assertNull(SpeechBubbles.bubble(Activity.BATTLE, false, false, false, Double.NaN), "an unknown distance shows nothing");
	}

	@Test
	void everyBubbleHasItsOwnLangKey() {
		Set<String> keys = new java.util.HashSet<>();
		for (Bubble bubble : EnumSet.allOf(Bubble.class)) {
			keys.add(bubble.key());
		}
		assertEquals(Bubble.values().length, keys.size());
	}

	@Test
	void theActivityTravelsByOrdinal() {
		for (Activity activity : Activity.values()) {
			io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
			Activity.STREAM_CODEC.encode(buf, activity);
			assertEquals(activity, Activity.STREAM_CODEC.decode(buf));
		}
		io.netty.buffer.ByteBuf unknown = io.netty.buffer.Unpooled.buffer();
		net.minecraft.network.VarInt.write(unknown, 42);
		assertEquals(Activity.NONE, Activity.STREAM_CODEC.decode(unknown), "an unknown value reads as nothing");
		// The wire form is the ordinal: the M9 values keep theirs, paused came after them.
		assertEquals(List.of(Activity.NONE, Activity.DIALOGUE, Activity.CUTSCENE, Activity.BATTLE, Activity.PAUSED), List.of(Activity.values()));
		assertEquals(Activity.PAUSED, Activity.byId("paused"));
		assertEquals(true, Activity.TYPE.isSynced(), "the attachment syncs to clients");
	}
}
