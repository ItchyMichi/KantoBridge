package dev.kantobridge.client.world;

import dev.kantobridge.client.bridge.BridgeLink;
import dev.kantobridge.world.Games;
import dev.kantobridge.world.WorldRecord;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * WORLD_SESSIONS_PLAN §2.2 (WS6): "Create world": the world's name and one row per game the client runs (its links: the
 * game's label and world space, its save, a tick, "start here", "continue this save / new game"); Create and Cancel. The
 * rules are {@link CreateWorldRules}'; Create starts {@link CreateKantoWorldFlow#startWith}.
 */
public final class CreateWorldScreen extends Screen {
	private static final int TEXT = 0xFFFFFFFF;
	private static final int DIM = 0xFFA0A0A0;
	private static final int WARN = 0xFFFF7060;
	private static final int ROW = 24;

	private final @Nullable Screen back;
	private final List<BridgeLink> links;
	private final CreateWorldRules rules;
	private @Nullable EditBox name;
	private @Nullable Button create;

	public CreateWorldScreen(@Nullable Screen back) {
		super(Component.translatable("kantobridge.create_world.title"));
		this.back = back;
		dev.kantobridge.client.bridge.BridgeRuntime runtime = dev.kantobridge.client.bridge.BridgeRuntime.get();
		this.links = runtime == null ? List.of() : runtime.links().all();
		this.rules = rulesFor(this.links);
		// the first connected game ticked to begin with, the others' rows ready
		for (CreateWorldRules.Row row : this.rules.rows()) {
			if (row.connected()) {
				this.rules.toggle(row.game());
				break;
			}
		}
	}

	/** The rules' rows for these links: each non-wrong link's game, its world space, whether it is up, whether it has a save. */
	static CreateWorldRules rulesFor(List<BridgeLink> links) {
		List<CreateWorldRules.Row> rows = new ArrayList<>();
		for (BridgeLink link : links) {
			String game = link.game();
			String region = Games.region(game);
			if (game == null || region == null || link.wrongGame() || !Games.adapter(game)) {
				continue;
			}
			BridgeLink.Status status = link.lastStatus();
			boolean hasSave = status != null && (status.save() != null || "saved".equals(status.playthrough()));
			rows.add(new CreateWorldRules.Row(game, region, link.connected(), hasSave));
		}
		return new CreateWorldRules(rows);
	}

	/** The links of the chosen rows, in their order. */
	static List<BridgeLink> linksFor(List<BridgeLink> links, List<CreateWorldRules.Row> chosen) {
		List<BridgeLink> out = new ArrayList<>();
		for (CreateWorldRules.Row row : chosen) {
			for (BridgeLink link : links) {
				if (row.game().equals(link.game()) && !link.wrongGame()) {
					out.add(link);
					break;
				}
			}
		}
		return out;
	}

	private @Nullable BridgeLink link(String game) {
		for (BridgeLink link : this.links) {
			if (game.equals(link.game())) {
				return link;
			}
		}
		return null;
	}

	private Component saveLabel(CreateWorldRules.Row row) {
		if (!row.hasSave()) {
			return Component.translatable("kantobridge.create_world.no_save");
		}
		if (!this.rules.keepsSave(row.game())) {
			return Component.translatable("kantobridge.create_world.new_game");
		}
		BridgeLink link = this.link(row.game());
		BridgeLink.Status status = link == null ? null : link.lastStatus();
		String name = status == null ? null : status.save() != null ? status.save().name() : status.name();
		int badges = status == null ? 0 : status.save() != null ? status.save().badges() : status.badges();
		return Component.translatable("kantobridge.create_world.continue", name == null ? "?" : name, badges);
	}

	private Component tickLabel(CreateWorldRules.Row row) {
		String blocked = this.rules.blockedBy(row);
		Component game = Component.translatable("kantobridge.game." + row.game());
		if (blocked != null) {
			return Component.translatable("kantobridge.create_world.same_world", game, Component.translatable("kantobridge.game." + blocked));
		}
		return Component.translatable(this.rules.ticked(row.game()) ? "kantobridge.create_world.ticked" : "kantobridge.create_world.unticked", game,
				row.region());
	}

	private Component startLabel(CreateWorldRules.Row row) {
		return Component.translatable(row.game().equals(this.rules.start()) ? "kantobridge.create_world.start_here" : "kantobridge.create_world.start_there");
	}

	@Override
	protected void init() {
		int centre = this.width / 2;
		int y = 40;
		this.name = new EditBox(this.font, centre - 100, y, 200, 20, Component.translatable("kantobridge.create_world.name"));
		this.name.setMaxLength(32);
		this.name.setValue(KantoWorldNames.BASE);
		this.addRenderableWidget(this.name);
		y += 30;
		for (CreateWorldRules.Row row : this.rules.rows()) {
			int rowY = y;
			this.addRenderableWidget(Button.builder(this.tickLabel(row), button -> {
				this.rules.toggle(row.game());
				this.rebuildWidgets();
			}).bounds(centre - 210, rowY, 160, 20).build());
			Button save = Button.builder(this.saveLabel(row), button -> {
				this.rules.toggleSave(row.game());
				button.setMessage(this.saveLabel(row));
			}).bounds(centre - 46, rowY, 170, 20).build();
			save.active = row.hasSave() && this.rules.ticked(row.game());
			this.addRenderableWidget(save);
			Button start = Button.builder(this.startLabel(row), button -> {
				this.rules.startAt(row.game());
				this.rebuildWidgets();
			}).bounds(centre + 128, rowY, 82, 20).build();
			start.active = this.rules.ticked(row.game());
			this.addRenderableWidget(start);
			y += ROW;
		}
		y += 12;
		this.create = this.addRenderableWidget(Button.builder(Component.translatable("kantobridge.create_world.create"), button -> this.create())
				.bounds(centre - 102, y, 100, 20).build());
		this.create.active = this.rules.problem() == null;
		this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> this.onClose()).bounds(centre + 2, y, 100, 20).build());
	}

	private void create() {
		WorldRecord record = this.rules.record();
		if (record == null || this.minecraft == null) {
			return;
		}
		CreateKantoWorldFlow.startWith(this.minecraft, linksFor(this.links, this.rules.chosen()), record, this.rules.choices(),
				this.name == null ? null : this.name.getValue());
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.back);
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, TEXT);
		if (this.rules.rows().isEmpty()) {
			graphics.drawCenteredString(this.font, Component.translatable("kantobridge.create_world.no_games"), this.width / 2, 76, WARN);
			return;
		}
		CreateWorldRules.Problem problem = this.rules.problem();
		int y = 70 + this.rules.rows().size() * ROW + 40;
		if (problem == CreateWorldRules.Problem.LINK_DOWN) {
			graphics.drawCenteredString(this.font, Component.translatable("kantobridge.create_world.link_down",
					Component.translatable("kantobridge.game." + this.rules.downGame())), this.width / 2, y, WARN);
		} else if (problem == CreateWorldRules.Problem.NONE_TICKED) {
			graphics.drawCenteredString(this.font, Component.translatable("kantobridge.create_world.none"), this.width / 2, y, DIM);
		}
	}
}
