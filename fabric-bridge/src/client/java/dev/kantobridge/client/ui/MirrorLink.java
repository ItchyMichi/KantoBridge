package dev.kantobridge.client.ui;

import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.proto.MsgTypes;
import java.util.Base64;
import org.jetbrains.annotations.Nullable;

/**
 * The mirror screen's subscription to the hidden game's picture (docs/PROTOCOL.md {@code mirror.sub},
 * {@code mirror.unsub}, {@code mirror.frame}; PLAN P10): subscribed while a mirror screen is shown, unsubscribed when it
 * goes, and the newest frame kept for the screen to upload once per render. A subscription dies with its connection.
 * Frames are PNG bytes (ROM-derived art): they stay in memory and are never logged or written anywhere.
 *
 * <p>M10: the screen uploads at most {@link #FPS} frames a second ({@link FrameLimiter}), however fast frames arrive or
 * the screen renders; a frame that a newer one replaces before its upload is dropped (counted for F7), never decoded.
 */
public final class MirrorLink {
	/** Frames a second asked for: the protocol's maximum, smooth enough for the slot machine's reels. */
	public static final int FPS = 30;
	public static final int WIDTH = 160;
	public static final int HEIGHT = 144;

	/** Sends to gen1-bridge; false when not connected. */
	@FunctionalInterface
	public interface Port {
		boolean send(String type, JsonObject data);
	}

	private final Port port;
	private final FrameLimiter limiter = new FrameLimiter(FPS);
	private boolean subscribed;
	/** A frame from the stream waits for its upload (a newer one replacing it is a drop). */
	private boolean waiting;
	private long dropped;
	private long lastN;
	private byte @Nullable [] latest;
	private boolean fresh;
	/** The UI the kept picture belongs to. */
	private int pictureUi;
	private long frames;
	private long badFrames;
	private long ignoredFrames;
	private long subscriptions;

	public MirrorLink(Port port) {
		this.port = port;
	}

	/**
	 * A mirror screen for UI {@code ui} is shown: subscribe (once). The picture kept from that same UI (a screen shown
	 * again after the pause menu) is offered again at once, since gen1-bridge sends nothing while the picture stays the
	 * same; another UI's picture is dropped.
	 */
	public void show(int ui) {
		if (ui != this.pictureUi) {
			this.latest = null;
			this.pictureUi = ui;
		}
		this.fresh = this.latest != null;
		if (this.subscribed) {
			return;
		}
		JsonObject data = new JsonObject();
		data.addProperty("fps", FPS);
		if (this.port.send(MsgTypes.MIRROR_SUB, data)) {
			this.subscribed = true;
			this.subscriptions++;
			this.lastN = 0;
		}
	}

	/** No mirror screen is shown any more: unsubscribe. */
	public void hide() {
		if (this.subscribed) {
			this.port.send(MsgTypes.MIRROR_UNSUB, new JsonObject());
			this.subscribed = false;
		}
		this.fresh = false;
	}

	/** The link dropped; the subscription went with it, and UI ids start over. */
	public void connectionLost() {
		this.subscribed = false;
		this.latest = null;
		this.fresh = false;
		this.waiting = false;
		this.pictureUi = 0;
		this.limiter.reset();
	}

	/** A {@code mirror.frame}: kept when it is newer than the last and well formed. */
	public void onFrame(JsonObject data) {
		if (!this.subscribed) {
			this.ignoredFrames++;
			return;
		}
		long n = JsonFields.integer(data, "n", -1);
		String png = JsonFields.str(data, "png");
		if (JsonFields.integer(data, "w", -1) != WIDTH || JsonFields.integer(data, "h", -1) != HEIGHT || n < 1 || png == null) {
			this.badFrames++;
			return;
		}
		if (n <= this.lastN) {
			this.ignoredFrames++;
			return;
		}
		if (this.waiting) {
			// The one before was never uploaded: frames came faster than the screen takes them.
			this.dropped++;
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(png);
		} catch (IllegalArgumentException e) {
			this.badFrames++;
			return;
		}
		this.lastN = n;
		this.latest = bytes;
		this.fresh = true;
		this.waiting = true;
		this.frames++;
	}

	/**
	 * The newest frame's PNG bytes if one arrived since the last upload and an upload is allowed now ({@code nowNanos}:
	 * {@link System#nanoTime}), else null; a frame held back by the rate limit stays for a later call.
	 */
	public byte @Nullable [] takeFresh(long nowNanos) {
		if (!this.fresh || !this.limiter.tryAcquire(nowNanos)) {
			return null;
		}
		this.fresh = false;
		this.waiting = false;
		return this.latest;
	}

	/** Counts a frame the screen couldn't decode. */
	public void badFrame() {
		this.badFrames++;
	}

	public boolean subscribed() {
		return this.subscribed;
	}

	public boolean hasPicture() {
		return this.latest != null;
	}

	public long frames() {
		return this.frames;
	}

	public long badFrames() {
		return this.badFrames;
	}

	/** Frames a newer one replaced before they were uploaded (the 30-a-second limit, or a slow screen). */
	public long dropped() {
		return this.dropped;
	}

	public long ignoredFrames() {
		return this.ignoredFrames;
	}

	public long subscriptions() {
		return this.subscriptions;
	}
}
