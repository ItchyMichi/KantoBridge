package dev.kantobridge.client.review;

import com.google.gson.JsonObject;
import dev.kantobridge.client.move.InputLock;
import dev.kantobridge.review.ModelReview;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** A note about a frozen selection. Typing never reaches the Game Boy controls. */
final class ModelReviewScreen extends Screen implements InputLock.Holds {
	private final ModelReviewRuntime runtime;
	private final String target;
	private ModelReview.Category category = ModelReview.Category.values()[0];
	private String comment = "";
	private @Nullable JsonObject context;
	private byte @Nullable [] screenshot;
	private boolean busy;
	private Component status = Component.translatable("kantobridge.review.capturing");
	private @Nullable Button save;
	private @Nullable Button cancel;
	private @Nullable Button inbox;

	ModelReviewScreen(ModelReviewRuntime runtime, String target) {
		super(Component.translatable("kantobridge.review.title"));
		this.runtime = runtime;
		this.target = target;
	}

	@Override
	protected void init() {
		if (this.height < 160 || this.width < 220) {
			this.save = null;
			this.cancel = null;
			this.inbox = null;
			this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
					.bounds(this.width / 2 - 60, this.height - 26, 120, 20).build());
			return;
		}
		boolean compact = this.height < 220;
		int w = Math.min(420, this.width - 32);
		int x = (this.width - w) / 2;
		this.addRenderableWidget(Button.builder(this.categoryLabel(), button -> {
			ModelReview.Category[] categories = ModelReview.Category.values();
			this.category = categories[(this.category.ordinal() + 1) % categories.length];
			button.setMessage(this.categoryLabel());
		}).bounds(x, compact ? 40 : 52, w, 20).build());
		int textY = compact ? 66 : 94;
		MultiLineEditBox text = new MultiLineEditBox(this.font, x, textY, w, this.height - textY - (compact ? 66 : 84),
				Component.translatable("kantobridge.review.placeholder"), Component.translatable("kantobridge.review.comment"));
		text.setCharacterLimit(4000);
		text.setValue(this.comment);
		text.setValueListener(value -> {
			this.comment = value;
			this.updateButtons();
		});
		this.addRenderableWidget(text);
		this.setInitialFocus(text);
		this.save = this.addRenderableWidget(Button.builder(Component.translatable("kantobridge.review.save"), button -> this.save())
				.bounds(x, this.height - (compact ? 48 : 56), w / 2 - 3, 20).build());
		this.cancel = this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
				.bounds(x + w / 2 + 3, this.height - (compact ? 48 : 56), w / 2 - 3, 20).build());
		this.inbox = this.addRenderableWidget(Button.builder(Component.translatable("kantobridge.review.inbox"), button -> this.runtime.openInbox(this))
				.bounds(x, this.height - (compact ? 24 : 30), w, 20).build());
		this.updateButtons();
	}

	private Component categoryLabel() {
		return Component.translatable("kantobridge.review.category", Component.translatable("kantobridge.review.category." + this.category.wire()));
	}

	void ready(JsonObject context, byte @Nullable [] png, boolean detailsMissing) {
		this.context = context;
		this.screenshot = png;
		this.status = Component.translatable(detailsMissing ? "kantobridge.review.partial_details"
				: png == null ? "kantobridge.review.no_screenshot" : "kantobridge.review.ready");
		this.updateButtons();
	}

	void captureFailed() {
		this.status = Component.translatable("kantobridge.review.capture_failed");
	}

	private void updateButtons() {
		if (this.save != null) {
			this.save.active = !this.busy && this.context != null && !this.comment.isBlank();
			this.cancel.active = !this.busy;
			this.inbox.active = !this.busy;
		}
	}

	private void save() {
		if (this.busy || this.context == null || this.comment.isBlank()) {
			return;
		}
		this.busy = true;
		this.status = Component.translatable("kantobridge.review.saving");
		this.updateButtons();
		ModelReview review = ModelReview.create(this.category, this.comment, this.context);
		byte[] png = this.screenshot;
		Minecraft client = this.minecraft;
		CompletableFuture.runAsync(() -> {
			try {
				this.runtime.store().create(review, png);
			} catch (Exception e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		}, Util.ioPool()).whenComplete((unused, error) -> client.execute(() -> {
			this.busy = false;
			if (error != null) {
				this.status = Component.translatable("kantobridge.review.save_failed");
				this.updateButtons();
				return;
			}
			this.runtime.reloadFlags();
			this.screenshot = null;
			if (client.screen == this) {
				client.setScreen(null);
			}
			if (client.player != null) {
				client.player.displayClientMessage(Component.translatable("kantobridge.review.saved", review.id().substring(0, 8)), false);
			}
		}));
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		int w = Math.min(420, this.width - 32);
		int x = (this.width - w) / 2;
		boolean compact = this.height < 220;
		graphics.drawCenteredString(this.font, this.title, this.width / 2, compact ? 8 : 16, 0xFFFFFFFF);
		if (this.height < 160 || this.width < 220) {
			net.minecraft.client.gui.components.MultiLineLabel.create(this.font,
					Component.translatable("kantobridge.review.small_window"), Math.max(60, w))
					.renderCentered(graphics, this.width / 2, 32, 10, 0xFFFFFFFF);
			return;
		}
		graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(this.target, w), this.width / 2, compact ? 24 : 34, 0xFF80FFFF);
		if (!compact) {
			graphics.drawString(this.font, Component.translatable("kantobridge.review.comment"), x, 80, 0xFFFFFFFF);
		}
		graphics.drawString(this.font, this.font.plainSubstrByWidth(this.status.getString(), w), x,
				this.height - (compact ? 62 : 74), 0xFFDDDDDD);
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0xC0101820);
	}

	@Override
	public void onClose() {
		if (!this.busy) {
			this.minecraft.setScreen(null);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
