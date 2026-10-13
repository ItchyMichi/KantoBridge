package dev.kantobridge.client.world;

import dev.kantobridge.builder.BuildPhase;
import dev.kantobridge.builder.BuildStatus;
import dev.kantobridge.client.bridge.BridgeLink;
import dev.kantobridge.world.Games;
import dev.kantobridge.world.RegionTable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The "Add a region" screen (MG4): the picks (a world, a connected game's link, the region id, the slot), then what the
 * flow is doing with a progress bar, and Cancel (or Back after a failure). Like the "Create Kanto world" screen it never
 * pauses the game (the integrated server must keep building) and Esc does nothing.
 */
final class AddRegionScreen extends Screen {
	private static final int TEXT = 0xFFFFFFFF;
	private static final int DIM = 0xFFA0A0A0;
	private static final int WARN = 0xFFFF7060;
	private static final int BAR_BACK = 0xFF303030;
	private static final int BAR_FILL = 0xFF5FB04F;
	private static final int COVER = 0xFF101418;
	private static final int BAR_WIDTH = 200;

	private final AddRegionFlow flow;
	private @Nullable Button worldButton;
	private @Nullable Button linkButton;
	private @Nullable EditBox regionBox;
	private @Nullable Button startButton;
	private @Nullable Button cancel;
	private @Nullable Button back;

	AddRegionScreen(AddRegionFlow flow) {
		super(Component.translatable("kantobridge.add_region.title"));
		this.flow = flow;
	}

	@Override
	protected void init() {
		int centre = this.width / 2;
		int y = this.height / 2 - 40;
		this.worldButton = this.addRenderableWidget(Button.builder(this.worldLabel(), button -> {
			this.flow.nextWorld();
			button.setMessage(this.worldLabel());
		}).bounds(centre - 100, y, 200, 20).build());
		this.linkButton = this.addRenderableWidget(Button.builder(this.linkLabel(), button -> {
			this.flow.nextLink();
			button.setMessage(this.linkLabel());
			if (this.regionBox != null) {
				this.regionBox.setValue(this.flow.regionId());
			}
		}).bounds(centre - 100, y + 24, 200, 20).build());
		this.regionBox = new EditBox(this.font, centre - 100, y + 48, 200, 20, Component.translatable("kantobridge.add_region.region"));
		this.regionBox.setMaxLength(24);
		this.regionBox.setValue(this.flow.regionId());
		this.regionBox.setResponder(this.flow::setRegionId);
		this.addRenderableWidget(this.regionBox);
		this.startButton = this.addRenderableWidget(Button.builder(Component.translatable("kantobridge.add_region.start"), button -> this.flow.begin())
				.bounds(centre - 100, y + 84, 200, 20).build());
		int bottom = Math.min(this.height - 28, this.height / 2 + 84);
		this.cancel = this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.flow.cancel())
				.bounds(centre - 75, bottom, 150, 20).build());
		this.back = this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> this.flow.back())
				.bounds(centre - 75, bottom, 150, 20).build());
		this.updateWidgets();
	}

	private Component worldLabel() {
		AddRegionFlow.WorldChoice world = this.flow.world();
		return world == null ? Component.translatable("kantobridge.add_region.no_world")
				: Component.translatable("kantobridge.add_region.world", world.folder());
	}

	private Component linkLabel() {
		BridgeLink link = this.flow.link();
		if (link == null) {
			return Component.translatable("kantobridge.add_region.no_link");
		}
		String game = link.game();
		String label = game == null ? "?" : Games.label(game) == null ? game : Games.label(game);
		return Component.translatable("kantobridge.add_region.link", label, Integer.toString(link.port()));
	}

	private void updateWidgets() {
		AddRegionFlow.Step step = this.flow.step();
		boolean picking = step == AddRegionFlow.Step.PICK;
		if (this.worldButton != null) {
			this.worldButton.visible = picking;
		}
		if (this.linkButton != null) {
			this.linkButton.visible = picking;
		}
		if (this.regionBox != null) {
			this.regionBox.visible = picking;
		}
		if (this.startButton != null) {
			this.startButton.visible = picking;
			this.startButton.active = this.flow.refusal() == null;
		}
		if (this.cancel != null) {
			this.cancel.visible = step.cancellable();
		}
		if (this.back != null) {
			this.back.visible = step == AddRegionFlow.Step.FAILED;
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		this.updateWidgets();
		super.render(graphics, mouseX, mouseY, partialTick);
		int centre = this.width / 2;
		int y = this.height / 2 - 70;
		graphics.drawCenteredString(this.font, this.title, centre, y, TEXT);
		AddRegionFlow.Step step = this.flow.step();
		if (step == AddRegionFlow.Step.PICK) {
			int below = this.height / 2 - 40 + 70;
			graphics.drawCenteredString(this.font, Component.translatable("kantobridge.add_region.slot", this.flow.slot(), RegionTable.PITCH), centre,
					below, DIM);
			Component refusal = this.flow.refusal();
			if (refusal != null) {
				MultiLineLabel.create(this.font, refusal, Math.min(this.width - 40, 320)).renderCentered(graphics, centre, below + 12, 10, WARN);
			} else {
				MultiLineLabel.create(this.font, Component.translatable("kantobridge.add_region.pack_note"), Math.min(this.width - 40, 320))
						.renderCentered(graphics, centre, below + 12, 10, DIM);
			}
			return;
		}
		y += 20;
		if (step == AddRegionFlow.Step.FAILED) {
			graphics.drawCenteredString(this.font, Component.translatable("kantobridge.add_region.failed"), centre, y, WARN);
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

	private Component stepLine(AddRegionFlow.Step step) {
		BridgeLink link = this.flow.link();
		String game = link == null || link.game() == null ? "?" : link.game();
		String label = Games.label(game) == null ? game : Games.label(game);
		return switch (step) {
			case CHECK -> Component.translatable("kantobridge.add_region.step.check", label);
			case EXPORT -> Component.translatable("kantobridge.add_region.step.export", label);
			case OPEN, LOADING -> Component.translatable("kantobridge.add_region.step.open");
			case BUILD -> Component.translatable("kantobridge.add_region.step.build", this.flow.regionId());
			default -> Component.empty();
		};
	}

	private @Nullable Component detailLine(AddRegionFlow.Step step) {
		if (step == AddRegionFlow.Step.EXPORT && this.flow.indexed()) {
			return Component.translatable("kantobridge.create.export_progress", this.flow.exportDone(), this.flow.exportTotal(),
					this.flow.exportBytes() / 1024);
		}
		if (step == AddRegionFlow.Step.BUILD) {
			BuildStatus status = this.flow.buildStatus();
			if (status != null && status.phase() == BuildPhase.PLACING) {
				return Component.translatable("kantobridge.create.build_progress", status.doneChunks(), status.totalChunks());
			}
		}
		return null;
	}

	/** The bar's fill, or -1 for no bar. */
	private float fraction(AddRegionFlow.Step step) {
		if (step == AddRegionFlow.Step.EXPORT && this.flow.indexed()) {
			int total = this.flow.exportTotal();
			return total <= 0 ? 0 : Math.min(1.0F, this.flow.exportDone() / (float) total);
		}
		if (step == AddRegionFlow.Step.BUILD) {
			return CreateKantoWorldFlow.buildFraction(this.flow.buildStatus());
		}
		return -1;
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		if (this.minecraft != null && this.minecraft.level != null) {
			// In the world, hide the unfinished region completely.
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
