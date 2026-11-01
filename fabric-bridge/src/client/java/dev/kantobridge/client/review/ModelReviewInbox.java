package dev.kantobridge.client.review;

import com.google.gson.JsonObject;
import dev.kantobridge.client.move.InputLock;
import dev.kantobridge.review.ModelReview;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/** The local review queue, including proposals written by the companion CLI. Reloaded every time it is opened. */
final class ModelReviewInbox extends Screen implements InputLock.Holds {
	private final ModelReviewRuntime runtime;
	private final @Nullable Screen parent;
	private List<ModelReview> reviews = List.of();
	private int filter;
	private int page;
	private boolean loaded;
	private int loadGeneration;
	private Component status = Component.translatable("kantobridge.review.loading");

	ModelReviewInbox(ModelReviewRuntime runtime, @Nullable Screen parent) {
		super(Component.translatable("kantobridge.review.inbox"));
		this.runtime = runtime;
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.widgets();
		if (!this.loaded) {
			this.reload();
		}
	}

	void reload() {
		int generation = ++this.loadGeneration;
		this.loaded = true;
		this.status = Component.translatable("kantobridge.review.loading");
		Minecraft client = this.minecraft;
		CompletableFuture.supplyAsync(() -> {
			try {
				return this.runtime.store().list();
			} catch (Exception e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		}, Util.ioPool()).whenComplete((listing, error) -> client.execute(() -> {
			if (generation != this.loadGeneration) {
				return;
			}
			if (error != null) {
				this.status = Component.translatable("kantobridge.review.load_failed");
			} else {
				this.reviews = listing.reviews();
				this.status = listing.failures().isEmpty() ? Component.translatable("kantobridge.review.count", this.reviews.size())
						: Component.translatable("kantobridge.review.skipped", listing.failures().size());
			}
			if (client.screen == this) {
				this.widgets();
			}
		}));
	}

	private List<ModelReview> filtered() {
		return this.filter == 0 ? this.reviews : this.reviews.stream()
				.filter(review -> review.status() == ModelReview.Status.values()[this.filter - 1]).toList();
	}

	private int rows() {
		return Math.max(1, (this.height - 134) / 24);
	}

	private void widgets() {
		this.clearWidgets();
		int w = Math.min(460, this.width - 32);
		int x = (this.width - w) / 2;
		Component filterLabel = this.filter == 0 ? Component.translatable("kantobridge.review.all")
				: Component.translatable("kantobridge.review.status." + ModelReview.Status.values()[this.filter - 1].wire());
		this.addRenderableWidget(Button.builder(filterLabel, button -> {
			this.filter = (this.filter + 1) % (ModelReview.Status.values().length + 1);
			this.page = 0;
			this.widgets();
		}).bounds(x, 36, w - 84, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("kantobridge.review.refresh"), button -> this.reload())
				.bounds(x + w - 80, 36, 80, 20).build());
		List<ModelReview> found = this.filtered();
		int pages = Math.max(1, (found.size() + this.rows() - 1) / this.rows());
		this.page = Math.min(this.page, pages - 1);
		for (int row = 0; row < this.rows(); row++) {
			int i = this.page * this.rows() + row;
			if (i >= found.size()) {
				break;
			}
			ModelReview review = found.get(i);
			String label = location(review) + " | " + Component.translatable("kantobridge.review.category." + review.category().wire()).getString()
					+ " | " + Component.translatable("kantobridge.review.status." + review.status().wire()).getString();
			this.addRenderableWidget(Button.builder(Component.literal(this.font.plainSubstrByWidth(label, w - 12)),
					button -> this.minecraft.setScreen(new Detail(this.runtime, this, review))).bounds(x, 64 + row * 24, w, 20).build());
		}
		Button previous = this.addRenderableWidget(Button.builder(Component.literal("<"), button -> {
			this.page--;
			this.widgets();
		}).bounds(x, this.height - 70, 40, 20).build());
		previous.active = this.page > 0;
		Button next = this.addRenderableWidget(Button.builder(Component.literal(">"), button -> {
			this.page++;
			this.widgets();
		}).bounds(x + w - 40, this.height - 70, 40, 20).build());
		next.active = this.page + 1 < pages;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> this.onClose())
				.bounds(x, this.height - 28, w / 2 - 3, 20).build());
		Button folder = this.addRenderableWidget(Button.builder(Component.translatable("kantobridge.review.folder"),
				button -> Util.getPlatform().openFile(this.runtime.store().root().toFile()))
				.bounds(x + w / 2 + 3, this.height - 28, w / 2 - 3, 20).build());
		folder.active = Files.isDirectory(this.runtime.store().root());
	}

	static String location(ModelReview review) {
		JsonObject context = review.context();
		return field(context, "game") + " / " + field(context, "map") + " [" + review.id().substring(0, 8) + "]";
	}

	private static String field(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "?";
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
		int pages = Math.max(1, (this.filtered().size() + this.rows() - 1) / this.rows());
		graphics.drawCenteredString(this.font, Component.translatable("kantobridge.review.page", this.page + 1, pages),
				this.width / 2, this.height - 64, 0xFFFFFFFF);
		graphics.drawCenteredString(this.font, this.status, this.width / 2, this.height - 44, 0xFFBBBBBB);
		if (this.loaded && this.filtered().isEmpty()) {
			graphics.drawCenteredString(this.font, Component.translatable("kantobridge.review.empty"), this.width / 2, 80, 0xFFBBBBBB);
		}
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0xE0101820);
	}

	@Override
	public void onClose() {
		this.runtime.reloadFlags();
		this.minecraft.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Read-only, scrollable note/proposal with an explicit resolved/reopen action. */
	private static final class Detail extends Screen implements InputLock.Holds {
		private final ModelReviewRuntime runtime;
		private final ModelReviewInbox parent;
		private ModelReview review;
		private List<FormattedCharSequence> lines = List.of();
		private int scroll;
		private boolean busy;
		private Component status = Component.empty();
		private @Nullable Button state;

		Detail(ModelReviewRuntime runtime, ModelReviewInbox parent, ModelReview review) {
			super(Component.translatable("kantobridge.review.detail"));
			this.runtime = runtime;
			this.parent = parent;
			this.review = review;
		}

		@Override
		protected void init() {
			int w = Math.min(460, this.width - 32);
			int x = (this.width - w) / 2;
			this.lines = new ArrayList<>();
			this.addText(Component.translatable("kantobridge.review.category." + this.review.category().wire()).getString(), w);
			this.addText(this.review.comment(), w);
			this.addText("", w);
			ModelReview.Proposal proposal = this.review.proposal();
			if (proposal == null) {
				this.addText(Component.translatable("kantobridge.review.no_proposal").getString(), w);
			} else {
				this.addText(Component.translatable("kantobridge.review.proposal",
						Component.translatable("kantobridge.review.method." + proposal.method().wire())).getString(), w);
				this.addText(proposal.text(), w);
			}
			this.state = this.addRenderableWidget(Button.builder(this.stateLabel(), button -> this.changeStatus())
					.bounds(x, this.height - 56, w / 2 - 3, 20).build());
			this.state.active = !this.busy;
			this.addRenderableWidget(Button.builder(Component.translatable("kantobridge.review.evidence"), button ->
					Util.getPlatform().openFile(this.runtime.store().issueDirectory(this.review.id()).toFile()))
					.bounds(x + w / 2 + 3, this.height - 56, w / 2 - 3, 20).build());
			this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> this.onClose())
					.bounds(x, this.height - 30, w, 20).build());
		}

		private Component stateLabel() {
			return Component.translatable("resolved".equals(this.review.status().wire()) ? "kantobridge.review.reopen" : "kantobridge.review.resolve");
		}

		private void addText(String text, int width) {
			if (text.isEmpty()) {
				this.lines.add(FormattedCharSequence.EMPTY);
			} else {
				this.lines.addAll(this.font.split(Component.literal(text), width));
			}
		}

		private int visibleLines() {
			return Math.max(1, (this.height - 136) / 11);
		}

		private void changeStatus() {
			if (this.busy) {
				return;
			}
			this.busy = true;
			this.state.active = false;
			ModelReview before = this.review;
			ModelReview next = before.withStatus(ModelReview.Status.parse("resolved".equals(before.status().wire()) ? "open" : "resolved"));
			Minecraft client = this.minecraft;
			CompletableFuture.runAsync(() -> {
				try {
					this.runtime.store().update(next, before.updatedAt());
				} catch (Exception e) {
					throw new java.util.concurrent.CompletionException(e);
				}
			}, Util.ioPool()).whenComplete((unused, error) -> client.execute(() -> {
				this.busy = false;
				this.state.active = true;
				if (error == null) {
					this.review = next;
					this.state.setMessage(this.stateLabel());
					this.status = Component.translatable("kantobridge.review.status." + next.status().wire());
					this.runtime.reloadFlags();
				} else {
					this.status = Component.translatable("kantobridge.review.changed_or_failed");
				}
			}));
		}

		@Override
		public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
			this.scroll = Math.max(0, Math.min(Math.max(0, this.lines.size() - this.visibleLines()), this.scroll - (int) (vertical * 3)));
			return true;
		}

		@Override
		public boolean keyPressed(int key, int scan, int modifiers) {
			if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN || key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP) {
				int direction = key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1;
				this.scroll = Math.max(0, Math.min(Math.max(0, this.lines.size() - this.visibleLines()), this.scroll + direction * this.visibleLines()));
				return true;
			}
			return super.keyPressed(key, scan, modifiers);
		}

		@Override
		public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			super.render(graphics, mouseX, mouseY, partialTick);
			int w = Math.min(460, this.width - 32);
			int x = (this.width - w) / 2;
			graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
			graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(location(this.review), w), this.width / 2, 34, 0xFF80FFFF);
			this.scroll = Math.min(this.scroll, Math.max(0, this.lines.size() - this.visibleLines()));
			for (int row = 0; row < this.visibleLines() && this.scroll + row < this.lines.size(); row++) {
				graphics.drawString(this.font, this.lines.get(this.scroll + row), x, 54 + row * 11, 0xFFFFFFFF);
			}
			Component footer = this.status.getString().isEmpty() ? Component.translatable("kantobridge.review.scroll") : this.status;
			graphics.drawCenteredString(this.font, footer, this.width / 2, this.height - 74, 0xFFBBBBBB);
		}

		@Override
		public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			graphics.fill(0, 0, this.width, this.height, 0xE0101820);
		}

		@Override
		public void onClose() {
			if (!this.busy) {
				this.minecraft.setScreen(this.parent);
				this.parent.reload();
			}
		}

		@Override
		public boolean isPauseScreen() {
			return false;
		}
	}
}
