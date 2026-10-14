package dev.kantobridge.client.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The mirror subscription: sub on show, unsub on hide, frames kept only while subscribed and newest first; uploads at
 * most 30 a second (M10), frames replaced before their upload counted as dropped.
 */
class MirrorLinkTest {
	/** A render clock: each take is 100 ms after the one before, well within the upload limit. */
	long now;
	final List<String> sent = new ArrayList<>();
	boolean connected = true;
	final MirrorLink link = new MirrorLink((type, data) -> {
		if (this.connected) {
			this.sent.add(type + " " + ProtoJson.write(data));
		}
		return this.connected;
	});

	byte[] take() {
		this.now += 100_000_000L;
		return this.link.takeFresh(this.now);
	}

	static JsonObject frame(long n, String bytes) {
		JsonObject data = new JsonObject();
		data.addProperty("w", 160);
		data.addProperty("h", 144);
		data.addProperty("png", Base64.getEncoder().encodeToString(bytes.getBytes(StandardCharsets.US_ASCII)));
		data.addProperty("n", n);
		return data;
	}

	@Test
	void subscribesOnceWhileShownAndUnsubscribesWhenHidden() {
		this.link.show(7);
		this.link.show(7);
		assertEquals(List.of("mirror.sub {\"fps\":" + MirrorLink.FPS + "}"), this.sent);
		assertTrue(this.link.subscribed());
		this.link.hide();
		this.link.hide();
		assertEquals(List.of("mirror.sub {\"fps\":30}", "mirror.unsub {}"), this.sent);
		assertFalse(this.link.subscribed());
	}

	@Test
	void framesAreKeptNewestFirstAndOnlyWhileSubscribed() {
		this.link.onFrame(frame(1, "early"));
		assertEquals(1, this.link.ignoredFrames(), "not subscribed yet");
		this.link.show(7);
		this.link.onFrame(frame(1, "one"));
		this.link.onFrame(frame(3, "three"));
		this.link.onFrame(frame(2, "late"));
		assertEquals(2, this.link.frames());
		assertArrayEquals("three".getBytes(StandardCharsets.US_ASCII), this.take());
		assertNull(this.take(), "taken");
		JsonObject wrongSize = frame(4, "x");
		wrongSize.addProperty("w", 320);
		this.link.onFrame(wrongSize);
		JsonObject notBase64 = frame(5, "x");
		notBase64.addProperty("png", "@@@");
		this.link.onFrame(notBase64);
		assertEquals(2, this.link.badFrames());
	}

	@Test
	void gen3FramesAre240By160AndThePictureKeepsItsSize() {
		this.link.show(7);
		assertEquals(160, this.link.width(), "the Game Boy's size until a frame says otherwise");
		JsonObject gba = frame(1, "gba");
		gba.addProperty("w", 240);
		gba.addProperty("h", 160);
		this.link.onFrame(gba);
		assertArrayEquals("gba".getBytes(StandardCharsets.US_ASCII), this.take());
		assertEquals(240, this.link.width());
		assertEquals(160, this.link.height());
		JsonObject mixed = frame(2, "x");
		mixed.addProperty("w", 240);
		this.link.onFrame(mixed);
		assertEquals(1, this.link.badFrames(), "240x144 is no screen");
		this.link.onFrame(frame(3, "gb"));
		assertArrayEquals("gb".getBytes(StandardCharsets.US_ASCII), this.take());
		assertEquals(144, this.link.height());
		assertTrue(MirrorLink.validSize(160, 144) && MirrorLink.validSize(240, 160));
		assertFalse(MirrorLink.validSize(160, 160));
	}

	@Test
	void showingTheSameUiAgainOffersItsPictureAnotherDoesNot() {
		this.link.show(7);
		this.link.onFrame(frame(1, "seven"));
		assertNotNull(this.take());
		this.link.hide();
		this.link.show(7);
		assertArrayEquals("seven".getBytes(StandardCharsets.US_ASCII), this.take(), "the pause menu came and went");
		this.link.hide();
		this.link.show(8);
		assertNull(this.take(), "another UI's picture is dropped");
		assertFalse(this.link.hasPicture());
	}

	@Test
	void uploadsAreLimitedAndReplacedFramesCounted() {
		this.link.show(7);
		this.link.onFrame(frame(1, "one"));
		assertArrayEquals("one".getBytes(StandardCharsets.US_ASCII), this.link.takeFresh(1_000_000_000L));
		this.link.onFrame(frame(2, "two"));
		assertNull(this.link.takeFresh(1_010_000_000L), "10 ms after an upload: not yet");
		this.link.onFrame(frame(3, "three"));
		assertEquals(1, this.link.dropped(), "two never went up: three replaced it");
		assertArrayEquals("three".getBytes(StandardCharsets.US_ASCII), this.link.takeFresh(1_040_000_000L), "the newest goes at the next slot");
		assertNull(this.link.takeFresh(1_200_000_000L), "nothing new");
		this.link.onFrame(frame(4, "four"));
		assertArrayEquals("four".getBytes(StandardCharsets.US_ASCII), this.link.takeFresh(1_300_000_000L));
		assertEquals(1, this.link.dropped(), "a frame taken in time is no drop");
		assertEquals(4, this.link.frames());
	}

	@Test
	void aDroppedLinkEndsTheSubscription() {
		this.link.show(7);
		this.link.connectionLost();
		assertFalse(this.link.subscribed());
		this.sent.clear();
		this.link.hide();
		assertEquals(List.of(), this.sent, "nothing to unsubscribe from");
		// While disconnected the sub fails and is tried again on the next show.
		this.connected = false;
		this.link.show(1);
		assertFalse(this.link.subscribed());
		this.connected = true;
		this.link.show(1);
		assertTrue(this.link.subscribed());
		assertEquals(2, this.link.subscriptions());
	}
}
