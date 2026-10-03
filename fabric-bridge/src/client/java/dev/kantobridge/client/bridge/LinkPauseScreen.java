package dev.kantobridge.client.bridge;

import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The pause overlay (M10 crash recovery; PLAN §2.4 flow 13): while the link to Gen1Recomp is down in a Kanto world, the
 * game waits behind this screen. It pauses (in singleplayer the integrated server stops too; a multiplayer world goes on
 * without this player, who can't move meanwhile) and says what is wrong, the port, when the next try comes and what to
 * do. Esc opens the game menu (Save and Quit); the overlay comes back while the link is still down, and it goes by
 * itself once the link is ready ({@link BridgeRuntime}, from {@link LinkPause}).
 *
 * <p>Its text is made once per tick, only when it changes; a frame only draws it.
 */
final class LinkPauseScreen extends Screen {
	private static final int TITLE = 0xFFFFFFFF;
	private static final int STATUS = 0xFFFFD070;
	private static final int TEXT = 0xFFD8D8D8;
	private static final int DIM = 0xFFA0A0A0;

	/** What the text was made from; the text is made again only when this changes. */
	private record Shown(LinkPause.Kind kind, @Nullable String reason, int theirProtocol, int ourProtocol, int port, long retrySeconds,
			int width) {
	}

	private final BridgeRuntime runtime;
	private @Nullable Shown shown;
	private Component headline = Component.empty();
	private Component status = Component.empty();
	private MultiLineLabel help = MultiLineLabel.EMPTY;
	private Component footer = Component.empty();

	LinkPauseScreen(BridgeRuntime runtime) {
		super(Component.translatable("kantobridge.pause.screen"));
		this.runtime = runtime;
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	@Override
	protected void init() {
		super.init();
		this.shown = null;
		this.refresh();
	}

	@Override
	public void tick() {
		super.tick();
		this.refresh();
	}

	/** Esc: the game menu, where Save and Quit is; the overlay returns once it closes, while the link is still down. */
	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(null);
			this.minecraft.pauseGame(false);
		}
	}

	private void refresh() {
		LinkPause.View view = this.runtime.pauseView();
		if (view == null || this.minecraft == null) {
			return;
		}
		long seconds = view.retryInMs() < 0 ? -1 : (view.retryInMs() + 999) / 1000;
		Shown next = new Shown(view.kind(), view.reason(), view.theirProtocol(), this.runtime.protocol(), this.runtime.port(), seconds, this.width);
		if (next.equals(this.shown)) {
			return;
		}
		this.shown = next;
		this.headline = switch (view.kind()) {
			case NOT_CONNECTED -> Component.translatable("kantobridge.pause.title.not_connected");
			case NOT_ANSWERING -> Component.translatable("kantobridge.pause.title.not_answering");
			case VERSION -> Component.translatable("kantobridge.pause.title.version");
			case REFUSED -> Component.translatable("kantobridge.pause.title.refused", Objects.requireNonNullElse(view.reason(), "?"));
		};
		this.status = seconds < 0 ? Component.translatable("kantobridge.pause.trying", next.port())
				: Component.translatable("kantobridge.pause.retry", next.port(), seconds);
		Component text = switch (view.kind()) {
			case NOT_CONNECTED -> Component.translatable("kantobridge.pause.help.not_connected");
			case NOT_ANSWERING -> Component.translatable("kantobridge.pause.help.not_answering");
			case VERSION -> Component.translatable("kantobridge.pause.help.version",
					next.theirProtocol() < 0 ? "?" : Integer.toString(next.theirProtocol()), next.ourProtocol());
			case REFUSED -> Component.translatable("kantobridge.pause.help.refused");
		};
		this.help = MultiLineLabel.create(this.font, text, Math.max(120, Math.min(this.width - 40, 360)));
		this.footer = Component.translatable("kantobridge.pause.menu");
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		int centre = this.width / 2;
		int y = Math.max(10, this.height / 2 - 60);
		graphics.drawCenteredString(this.font, this.headline, centre, y, TITLE);
		y += 18;
		graphics.drawCenteredString(this.font, this.status, centre, y, STATUS);
		y += 18;
		this.help.renderCentered(graphics, centre, y, 11, TEXT);
		y += this.help.getLineCount() * 11 + 14;
		graphics.drawCenteredString(this.font, this.footer, centre, y, DIM);
	}
}
