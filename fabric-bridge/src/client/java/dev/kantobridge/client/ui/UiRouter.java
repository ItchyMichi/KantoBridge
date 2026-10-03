package dev.kantobridge.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.proto.MsgTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * The hidden game's open UIs and our answers to them (docs/PLAN.md §2.4 flows 2–5, PROTOCOL.md "UI"). Plain Java on the
 * client thread: {@code UiRuntime} feeds it gen1-bridge's messages and shows its {@link #view()} as one Minecraft
 * screen at a time.
 *
 * <ul>
 * <li><b>Open UIs</b> form a stack in opening order: {@code ui.open} pushes (or replaces the same id),
 * {@code ui.update} changes one, {@code ui.close} removes one wherever it is. Only the top takes input, as in the
 * hidden game. A choice over a text box is shown with that box ({@link View#overlay}); UIs under the shown one are
 * drawn beneath it, inactive.</li>
 * <li><b>Answers</b> ({@code ui.advance}, {@code ui.pick}, {@code ui.back}, {@code ui.qty}, {@code ui.name}) go only to
 * the top UI, at most one until it changes (an update, a UI above it closing, or {@link UiSession#ANSWER_TIMEOUT_MS}).
 * Text boxes ask for one advance per stop ({@link TextBoxModel}).</li>
 * <li><b>Resync.</b> UI ids are per connection: a dropped link closes everything, and each {@code snapshot} starts over
 * from its {@code ui} (the UI open at that moment, as its {@code ui.open} data). As a safety net, the hidden player staying
 * in mode {@code idle} for {@link #IDLE_GRACE_MS} closes whatever is still open: no UI can be up then (the grace lets a
 * {@code ui.close} sent just after the mode change arrive first).</li>
 * </ul>
 *
 * Nothing here logs: problems are counted by short code and reported through the {@link Port}.
 */
public final class UiRouter {
	/** gen1-bridge and the screen glue, as the router sees them. */
	public interface Port {
		/** Sends to gen1-bridge; false when not connected (the message is dropped). */
		boolean send(String type, JsonObject data);

		/** The open UIs or one of their models changed. */
		default void changed() {
		}

		/** A counted problem: a short code and how many so far. */
		default void warn(String code, long count) {
		}
	}

	/**
	 * What one screen shows: the main UI and, for a choice over a text box, the choice on top of it.
	 *
	 * @param main    the text box, or any other top UI (a choice with no text box under it is its own main)
	 * @param overlay the choice over {@code main}, or null
	 */
	public record View(UiSession main, @Nullable UiSession overlay) {
		/** The UI that takes input. */
		public UiSession active() {
			return this.overlay != null ? this.overlay : this.main;
		}
	}

	/** Counters for the debug overlay (no content). */
	public static final class Stats {
		public long opened;
		public long updated;
		public long closed;
		public long cleared;
		public long stale;
		public long refused;
		public long advances;
		public long picks;
		public long backs;
		public long quantities;
		public long names;
	}

	/** How long the hidden player must stay idle before UIs still open here count as stale. */
	public static final long IDLE_GRACE_MS = 500;

	private final Port port;
	private final List<UiSession> stack = new ArrayList<>();
	/** Since when the hidden player has been idle, or -1. */
	private long idleSince = -1;
	private final Map<String, Long> warnings = new TreeMap<>();
	private final Stats stats = new Stats();

	public UiRouter(Port port) {
		this.port = port;
	}

	// ---- from gen1-bridge ----

	public void onOpen(JsonObject data, long now) {
		UiSession session;
		try {
			session = UiParse.open(data, now);
		} catch (UiParse.Invalid e) {
			this.stats.refused++;
			this.warn("open." + e.code());
			return;
		}
		int at = this.indexOf(session.id());
		if (at >= 0) {
			this.stack.set(at, session);
		} else {
			this.stack.add(session);
		}
		this.idleSince = -1;
		this.stats.opened++;
		this.port.changed();
	}

	public void onUpdate(JsonObject data, long now) {
		UiParse.Update update;
		try {
			update = UiParse.update(data);
		} catch (UiParse.Invalid e) {
			this.stats.refused++;
			this.warn("update." + e.code());
			return;
		}
		UiSession session = this.find(update.id());
		if (session == null) {
			this.stats.stale++;
			return;
		}
		switch (session.kind()) {
			case TEXT -> session.text().apply(update.page(), update.line(), update.waiting(), update.done(), now);
			case CHOICE -> {
				if (update.index() != null) {
					session.choice().select(update.index());
				}
			}
			case MENU, LIST -> session.menu().apply(update.index(), update.top(), update.items());
			case QUANTITY -> {
				if (update.qty() != null) {
					session.quantity().apply(update.qty());
				}
			}
			default -> {
			}
		}
		session.clearAnswered();
		this.stats.updated++;
		this.port.changed();
	}

	public void onClose(JsonObject data) {
		int id;
		try {
			id = UiParse.id(data);
		} catch (UiParse.Invalid e) {
			this.stats.refused++;
			this.warn("close." + e.code());
			return;
		}
		int at = this.indexOf(id);
		if (at < 0) {
			this.stats.stale++;
			return;
		}
		this.stack.remove(at);
		if (at == this.stack.size() && !this.stack.isEmpty()) {
			// The UI under it is on top again and takes input, as when a Gen 1 submenu closes.
			this.stack.get(this.stack.size() - 1).clearAnswered();
		}
		this.stats.closed++;
		this.port.changed();
	}

	/** A {@code snapshot}: everything starts over from its {@code ui}. */
	public void onSnapshot(JsonObject data, long now) {
		this.clear();
		JsonObject ui = JsonFields.obj(data, "ui");
		if (ui != null) {
			this.onOpen(ui, now);
		}
	}

	/** The link dropped: every UI id is gone with it. */
	public void onDisconnected() {
		this.idleSince = -1;
		this.clear();
	}

	/** {@code player.state}'s mode: once the hidden player has stayed idle a moment, nothing can be open. */
	public void onMode(@Nullable String mode, long now) {
		if ("idle".equals(mode)) {
			if (this.idleSince < 0) {
				this.idleSince = now;
			}
		} else if (mode != null) {
			this.idleSince = -1;
		}
	}

	/** Advances the typewriters, and closes UIs left open while the hidden player is idle. */
	public void tick(long now) {
		if (this.idleSince >= 0 && now - this.idleSince >= IDLE_GRACE_MS && !this.stack.isEmpty()) {
			this.warn("stale_on_idle");
			this.clear();
		}
		for (UiSession session : this.stack) {
			TextBoxModel text = session.text();
			if (text != null) {
				text.tick(now);
			}
		}
	}

	// ---- answers from our screens ----

	/** A, B or a click on a text box. */
	public void press(UiSession session, long now) {
		TextBoxModel text = session.text();
		if (text == null || !this.isTop(session)) {
			return;
		}
		if (text.press(now) == TextBoxModel.Press.ADVANCE && this.port.send(MsgTypes.UI_ADVANCE, idOnly(session))) {
			this.stats.advances++;
		}
	}

	/** Picks an entry of a choice, menu or list. */
	public void pick(UiSession session, int index, long now) {
		if (!this.answerable(session, now)) {
			return;
		}
		int entries = session.choice() != null ? session.choice().labels().size()
				: session.menu() != null ? session.menu().items().size() : 0;
		if (index < 0 || index >= entries) {
			return;
		}
		JsonObject data = idOnly(session);
		data.addProperty("index", index);
		if (this.port.send(MsgTypes.UI_PICK, data)) {
			session.markAnswered(now);
			this.stats.picks++;
		}
	}

	/** B on a choice, menu, list or quantity. */
	public void back(UiSession session, long now) {
		if (!this.answerable(session, now) || session.kind() == UiKind.TEXT || session.kind() == UiKind.NAMING
				|| session.kind() == UiKind.MIRROR) {
			return;
		}
		if (this.port.send(MsgTypes.UI_BACK, idOnly(session))) {
			session.markAnswered(now);
			this.stats.backs++;
		}
	}

	/** Confirms a quantity box's count. */
	public void quantity(UiSession session, long now) {
		QuantityModel quantity = session.quantity();
		if (quantity == null || !this.answerable(session, now)) {
			return;
		}
		JsonObject data = idOnly(session);
		data.addProperty("qty", quantity.qty());
		if (this.port.send(MsgTypes.UI_QTY, data)) {
			session.markAnswered(now);
			this.stats.quantities++;
		}
	}

	/** Sends a name the naming model confirmed (checked again here: 1..max glyph tokens). */
	public void name(UiSession session, List<String> glyphs, long now) {
		NamingModel naming = session.naming();
		if (naming == null || !this.answerable(session, now) || !NamingModel.valid(glyphs, naming.max())) {
			return;
		}
		JsonObject data = idOnly(session);
		JsonArray array = new JsonArray();
		glyphs.forEach(array::add);
		data.add("glyphs", array);
		if (this.port.send(MsgTypes.UI_NAME, data)) {
			session.markAnswered(now);
			this.stats.names++;
		}
	}

	// ---- views ----

	/** True while any UI is open. */
	public boolean open() {
		return !this.stack.isEmpty();
	}

	public @Nullable UiSession top() {
		return this.stack.isEmpty() ? null : this.stack.get(this.stack.size() - 1);
	}

	public boolean isTop(UiSession session) {
		return this.top() == session;
	}

	/** What the screen shows now, or null when nothing is open. */
	public @Nullable View view() {
		UiSession top = this.top();
		if (top == null) {
			return null;
		}
		if (top.kind() == UiKind.CHOICE && this.stack.size() >= 2) {
			UiSession below = this.stack.get(this.stack.size() - 2);
			if (below.kind() == UiKind.TEXT) {
				return new View(below, top);
			}
		}
		return new View(top, null);
	}

	/** The UIs under a view's main one, bottom first (drawn beneath it). */
	public List<UiSession> under(View view) {
		int at = this.stack.indexOf(view.main());
		return at <= 0 ? List.of() : List.copyOf(this.stack.subList(0, at));
	}

	/** Every open UI, bottom first. */
	public List<UiSession> sessions() {
		return List.copyOf(this.stack);
	}

	public @Nullable UiSession find(int id) {
		int at = this.indexOf(id);
		return at < 0 ? null : this.stack.get(at);
	}

	public Stats stats() {
		return this.stats;
	}

	// ---- helpers ----

	private boolean answerable(UiSession session, long now) {
		return this.isTop(session) && !session.answered(now);
	}

	private void clear() {
		if (!this.stack.isEmpty()) {
			this.stats.cleared += this.stack.size();
			this.stack.clear();
			this.port.changed();
		}
	}

	private int indexOf(int id) {
		for (int i = 0; i < this.stack.size(); i++) {
			if (this.stack.get(i).id() == id) {
				return i;
			}
		}
		return -1;
	}

	private static JsonObject idOnly(UiSession session) {
		JsonObject data = new JsonObject();
		data.addProperty("ui", session.id());
		return data;
	}

	private void warn(String code) {
		long count = this.warnings.merge(code, 1L, Long::sum);
		this.port.warn(code, count);
	}
}
