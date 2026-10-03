package dev.kantobridge.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.kantobridge.client.move.InputLock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The base of our screens for hidden-game UIs (docs/PLAN.md §4.3 "UI", U1): one at a time shows the router's
 * {@link UiRouter.View}, drawn on the Gen 1 canvas over the world, with the UIs under it drawn beneath.
 *
 * <ul>
 * <li>Never pauses the game (a pausing screen would stop the integrated server), and draws no background: the world
 * stays visible.</li>
 * <li>Holds the player ({@link InputLock.Holds}).</li>
 * <li>Keys become Game Boy buttons ({@link Keybinds}); a key already held when the screen opened (the A that started
 * the talk) acts only after it is pressed again, and held keys repeat only for the directions.</li>
 * <li>Esc opens the pause menu; the screen comes back when the pause menu closes, since the hidden UI is still open.</li>
 * </ul>
 */
public abstract class BridgeScreen extends Screen implements InputLock.Holds {
	protected final UiRuntime runtime;
	protected UiRouter.View view;
	/** Keys down now; a press of a key in here is the OS's repeat. */
	private final Set<Integer> held = new HashSet<>();

	protected BridgeScreen(UiRuntime runtime, UiRouter.View view, Component title) {
		super(title);
		this.runtime = runtime;
		this.view = view;
	}

	/** The view shown. */
	public UiRouter.View view() {
		return this.view;
	}

	/** True when this screen can show {@code next} itself (the same main UI; a choice may come and go over it). */
	public boolean accepts(UiRouter.View next) {
		return next.main() == this.view.main();
	}

	public void setView(UiRouter.View next) {
		this.view = next;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** No blur and no menu background: the world stays visible around the boxes. */
	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
	}

	@Override
	protected void init() {
		super.init();
		if (this.minecraft == null) {
			return;
		}
		long window = this.minecraft.getWindow().getWindow();
		for (int key : this.buttonKeys()) {
			if (key > 0 && InputConstants.isKeyDown(window, key)) {
				this.held.add(key);
			}
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		long now = this.runtime.now();
		this.runtime.router().tick(now);
		Gen1Canvas canvas = this.canvas();
		Gen1Draw.begin(graphics, canvas);
		for (UiSession under : this.runtime.router().under(this.view)) {
			UiLayers.draw(graphics, this.font, under, now);
		}
		this.drawView(graphics, canvas, now);
		Gen1Draw.end(graphics);
		this.drawOverCanvas(graphics, canvas, now);
	}

	/** Draws the view in canvas pixels. */
	protected abstract void drawView(GuiGraphics graphics, Gen1Canvas canvas, long now);

	/** Draws anything outside the canvas, in GUI units (hints); nothing by default. */
	protected void drawOverCanvas(GuiGraphics graphics, Gen1Canvas canvas, long now) {
	}

	/** A Game Boy button was pressed ({@code repeat}: the OS repeats a held direction). */
	protected abstract void onButton(Keybinds.Button button, boolean repeat, long now);

	/** A left click at canvas pixel {@code x, y}; nothing by default. */
	protected void onClick(double x, double y, long now) {
	}

	/** The canvas this screen draws on: the dialog canvas unless overridden. */
	protected Gen1Canvas canvas() {
		Window window = this.minecraft.getWindow();
		return Gen1Canvas.dialog(window.getWidth(), window.getHeight(), window.getGuiScale());
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			this.runtime.pause(this);
			return true;
		}
		Keybinds.Button button = Keybinds.button(this.minecraft, keyCode, scanCode);
		if (button == null) {
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		boolean repeat = !this.held.add(keyCode);
		if (!repeat || button.direction()) {
			this.onButton(button, repeat, this.runtime.now());
		}
		return true;
	}

	@Override
	public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
		this.held.remove(keyCode);
		return super.keyReleased(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
		long now = this.runtime.now();
		Keybinds.Button button = Keybinds.mouseButton(mouseButton);
		if (button != null) {
			this.onButton(button, false, now);
			return true;
		}
		if (mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			Gen1Canvas canvas = this.canvas();
			this.onClick(canvas.toCanvasX(mouseX), canvas.toCanvasY(mouseY), now);
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, mouseButton);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0) {
			this.onButton(scrollY > 0 ? Keybinds.Button.UP : Keybinds.Button.DOWN, true, this.runtime.now());
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/** Esc opens the pause menu instead (the hidden UI stays open, so this screen returns afterwards). */
	@Override
	public void onClose() {
		this.runtime.pause(this);
	}

	/** True when this screen's view takes input (its active UI is on top, with no answer on its way). */
	protected boolean live(@Nullable UiSession session, long now) {
		return session != null && this.runtime.router().isTop(session) && !session.answered(now);
	}

	/** The keys our buttons are on, for telling a held key from a new press when the screen opens. */
	private List<Integer> buttonKeys() {
		List<Integer> keys = new ArrayList<>(List.of(GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT));
		for (KeyMapping mapping : List.of(Keybinds.a(), Keybinds.b(), Keybinds.start(), Keybinds.select(), this.minecraft.options.keyUp,
				this.minecraft.options.keyDown, this.minecraft.options.keyLeft, this.minecraft.options.keyRight)) {
			InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(mapping);
			if (key.getType() == InputConstants.Type.KEYSYM) {
				keys.add(key.getValue());
			}
		}
		return keys;
	}
}
