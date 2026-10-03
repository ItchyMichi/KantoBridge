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

/** The mirror subscription: sub on show, unsub on hide, frames kept only while subscribed and newest first. */
class MirrorLinkTest {
	final List<String> sent = new ArrayList<>();
	boolean connected = true;
	final MirrorLink link = new MirrorLink((type, data) -> {
		if (this.connected) {
			this.sent.add(type + " " + ProtoJson.write(data));
		}
		return this.connected;
	});

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
		assertArrayEquals("three".getBytes(StandardCharsets.US_ASCII), this.link.takeFresh());
		assertNull(this.link.takeFresh(), "taken");
		JsonObject wrongSize = frame(4, "x");
		wrongSize.addProperty("w", 320);
		this.link.onFrame(wrongSize);
		JsonObject notBase64 = frame(5, "x");
		notBase64.addProperty("png", "@@@");
		this.link.onFrame(notBase64);
		assertEquals(2, this.link.badFrames());
	}

	@Test
	void showingTheSameUiAgainOffersItsPictureAnotherDoesNot() {
		this.link.show(7);
		this.link.onFrame(frame(1, "seven"));
		assertNotNull(this.link.takeFresh());
		this.link.hide();
		this.link.show(7);
		assertArrayEquals("seven".getBytes(StandardCharsets.US_ASCII), this.link.takeFresh(), "the pause menu came and went");
		this.link.hide();
		this.link.show(8);
		assertNull(this.link.takeFresh(), "another UI's picture is dropped");
		assertFalse(this.link.hasPicture());
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
