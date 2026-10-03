package dev.kantobridge.client.world;

import dev.kantobridge.builder.BuildPhase;
import dev.kantobridge.builder.BuildStatus;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The "Create Kanto world" screen: what the flow is doing, a progress bar, and Cancel (or Back after a failure). It
 * never pauses the game, because the integrated server must keep ticking to build, and Esc does nothing: leaving goes
 * through Cancel, which cleans up. In the new world it covers the unfinished build.
 */
final class KantoWorldScreen extends Screen {
	private static final int TEXT = 0xFFFFFFFF;
	private static final int DIM = 0xFFA0A0A0;
	private static final int BAR_BACK = 0xFF303030;
	private static final int BAR_FILL = 0xFF5FB04F;
	private static final int COVER = 0xFF101418;
	private static final int BAR_WIDTH = 200;

	private final CreateKantoWorldFlow flow;
	private @Nullable Button cancel;
	private @Nullable Button back;

	KantoWorldScreen(CreateKantoWorldFlow flow) {
		super(Component.translatable("kantobridge.create.title"));
		this.flow = flow;
	}

	@Override
	protected void init() {
		int y = Math.min(this.height - 28, this.height / 2 + 60);
		this.cancel = this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.flow.cancel())
				.bounds(this.width / 2 - 75, y, 150, 20).build());
		this.back = this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> this.flow.back())
				.bounds(this.width / 2 - 75, y, 150, 20).build());
		this.updateButtons();
	}

	private void updateButtons() {
		CreateKantoWorldFlow.Step step = this.flow.step();
		if (this.cancel != null) {
			this.cancel.visible = step.cancellable();
		}
		if (this.back != null) {
			this.back.visible = step == CreateKantoWorldFlow.Step.FAILED;
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		this.updateButtons();
		super.render(graphics, mouseX, mouseY, partialTick);
		int centre = this.width / 2;
		int y = this.height / 2 - 50;
		graphics.drawCenteredString(this.font, this.title, centre, y, TEXT);
		y += 20;
		CreateKantoWorldFlow.Step step = this.flow.step();
		if (step == CreateKantoWorldFlow.Step.FAILED) {
			graphics.drawCenteredString(this.font, Component.translatable("kantobridge.create.failed"), centre, y, 0xFFFF7060);
			Component error = this.flow.error();
			if (error != null) {
				MultiLineLabel.create(this.font, error, Math.min(this.width - 40, 320)).renderCentered(graphics, centre, y + 16, 10, DIM);
			}
			return;
		}
		graphics.drawCenteredString(this.font, this.stepLine(step), centre, y, TEXT);
		Component detail = this.detailLine(step);
		if (detail != null) {
			graphics.drawCenteredString(this.font, detail, centre, y + 14, DIM);
		}
		float fraction = this.fraction(step);
		if (fraction >= 0) {
			int left = centre - BAR_WIDTH / 2;
			int top = y + 30;
			graphics.fill(left, top, left + BAR_WIDTH, top + 6, BAR_BACK);
			graphics.fill(left, top, left + Math.round(BAR_WIDTH * fraction), top + 6, BAR_FILL);
		}
	}

	private Component stepLine(CreateKantoWorldFlow.Step step) {
		return switch (step) {
			case CHECK -> Component.translatable("kantobridge.create.step.check");
			case EXPORT -> Component.translatable(this.flow.indexed() ? "kantobridge.create.step.export" : "kantobridge.create.step.export_wait");
			case PACK -> Component.translatable("kantobridge.create.step.pack");
			case RELOAD -> Component.translatable("kantobridge.create.step.reload");
			case CREATE, LOADING -> Component.translatable("kantobridge.create.step.create");
			case BUILD -> {
				BuildStatus status = this.flow.buildStatus();
				BuildPhase phase = status == null ? null : status.phase();
				if (phase == BuildPhase.PLACING) {
					yield Component.translatable("kantobridge.create.step.build");
				}
				yield Component.translatable(phase == BuildPhase.FINISHING || phase == BuildPhase.DONE ? "kantobridge.create.step.build_finishing"
						: "kantobridge.create.step.build_planning");
			}
			case CLEANUP -> Component.translatable("kantobridge.create.cleanup");
			case FAILED, DONE -> Component.empty();
		};
	}

	private @Nullable Component detailLine(CreateKantoWorldFlow.Step step) {
		if (step == CreateKantoWorldFlow.Step.EXPORT && this.flow.indexed()) {
			return Component.translatable("kantobridge.create.export_progress", this.flow.exportDone(), this.flow.exportTotal(),
					this.flow.exportBytes() / 1024);
		}
		if (step == CreateKantoWorldFlow.Step.BUILD) {
			BuildStatus status = this.flow.buildStatus();
			if (status != null && status.phase() == BuildPhase.PLACING) {
				return Component.translatable("kantobridge.create.build_progress", status.doneChunks(), status.totalChunks());
			}
		}
		if (step == CreateKantoWorldFlow.Step.CREATE || step == CreateKantoWorldFlow.Step.LOADING) {
			String folder = this.flow.levelId();
			return folder == null ? null : Component.translatable("kantobridge.create.folder", folder);
		}
		return null;
	}

	/** The bar's fill, or -1 for no bar. */
	private float fraction(CreateKantoWorldFlow.Step step) {
		if (step == CreateKantoWorldFlow.Step.EXPORT && this.flow.indexed()) {
			int total = this.flow.exportTotal();
			return total <= 0 ? 0 : Math.min(1.0F, this.flow.exportDone() / (float) total);
		}
		if (step == CreateKantoWorldFlow.Step.BUILD) {
			return CreateKantoWorldFlow.buildFraction(this.flow.buildStatus());
		}
		return -1;
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		if (this.minecraft != null && this.minecraft.level != null) {
			// In the new world, hide the unfinished build completely.
			graphics.fill(0, 0, this.width, this.height, COVER);
		} else {
			super.renderBackground(graphics, mouseX, mouseY, partialTick);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
