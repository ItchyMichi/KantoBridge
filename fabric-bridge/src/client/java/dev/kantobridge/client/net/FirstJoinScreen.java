package dev.kantobridge.client.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * WORLD_SESSIONS_PLAN §2.3, D13 (WS3): the first-join screen. Once per game of the world whose title says its slot holds
 * a save and for which this player has no recorded choice: "continue your save (NAME, N badges)" or "new game", each a
 * button that cycles; Done answers all ({@code current} singleplayer / {@code copy} on a server, else {@code new}); Esc
 * answers {@link JoinDecision#DECLINED} for each (the game stays at its title this join and is asked again next time).
 * The answers become the binds' {@code save}; the server records them with the slots, so a later join asks nothing.
 */
public final class FirstJoinScreen extends Screen {
	private static final int TEXT = 0xFFFFFFFF;
	private static final int DIM = 0xFFA0A0A0;

	/** One game to ask about: its key (the caller's), its label, and the title's summary of the save. */
	public record Entry(String key, Component game, @Nullable String name, int badges) {
	}

	private final List<Entry> entries;
	private final boolean multiplayer;
	private final Consumer<Map<String, String>> answered;
	private final Map<String, Boolean> keep = new LinkedHashMap<>();
	private boolean done;

	public FirstJoinScreen(List<Entry> entries, boolean multiplayer, Consumer<Map<String, String>> answered) {
		super(Component.translatable("kantobridge.first_join.title"));
		this.entries = List.copyOf(entries);
		this.multiplayer = multiplayer;
		this.answered = answered;
		for (Entry entry : this.entries) {
			this.keep.put(entry.key(), true);
		}
	}

	/** The answers as the binds carry them (the choices currently shown). */
	public static Map<String, String> answers(Map<String, Boolean> keep, boolean multiplayer) {
		Map<String, String> out = new LinkedHashMap<>();
		keep.forEach((key, continueSave) -> out.put(key, continueSave ? JoinDecision.continueChoice(multiplayer) : "new"));
		return out;
	}

	/** Esc's answers: every game declined for this join. */
	public static Map<String, String> declined(List<Entry> entries) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Entry entry : entries) {
			out.put(entry.key(), JoinDecision.DECLINED);
		}
		return out;
	}

	private Component label(Entry entry) {
		if (this.keep.getOrDefault(entry.key(), true)) {
			return Component.translatable(this.multiplayer ? "kantobridge.first_join.copy" : "kantobridge.first_join.continue",
					entry.name() == null ? "?" : entry.name(), entry.badges());
		}
		return Component.translatable("kantobridge.first_join.new");
	}

	@Override
	protected void init() {
		int centre = this.width / 2;
		int y = Math.max(40, this.height / 2 - 20 - this.entries.size() * 14);
		List<Entry> list = new ArrayList<>(this.entries);
		for (int i = 0; i < list.size(); i++) {
			Entry entry = list.get(i);
			int row = y + i * 28;
			this.addRenderableWidget(Button.builder(this.label(entry), button -> {
				this.keep.put(entry.key(), !this.keep.getOrDefault(entry.key(), true));
				button.setMessage(this.label(entry));
			}).bounds(centre - 40, row, 200, 20).build());
		}
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.finish(answers(this.keep, this.multiplayer)))
				.bounds(centre - 100, y + list.size() * 28 + 10, 200, 20).build());
	}

	private void finish(Map<String, String> out) {
		if (this.done) {
			return;
		}
		this.done = true;
		this.answered.accept(out);
		if (this.minecraft != null) {
			this.minecraft.setScreen(null);
		}
	}

	@Override
	public void onClose() {
		this.finish(declined(this.entries));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		int centre = this.width / 2;
		int y = Math.max(40, this.height / 2 - 20 - this.entries.size() * 14);
		graphics.drawCenteredString(this.font, this.title, centre, y - 30, TEXT);
		graphics.drawCenteredString(this.font, Component.translatable("kantobridge.first_join.hint"), centre, y - 16, DIM);
		for (int i = 0; i < this.entries.size(); i++) {
			graphics.drawString(this.font, this.entries.get(i).game(), centre - 150, y + i * 28 + 6, TEXT);
		}
	}
}
