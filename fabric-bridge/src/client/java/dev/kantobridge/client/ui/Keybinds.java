package dev.kantobridge.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The Game Boy buttons (docs/PLAN.md P20), rebindable under Options → Controls → Key Binds → Kanto Bridge: A = Z (and
 * right-click on what the crosshair targets), B = X inside our screens, START = Enter, SELECT = Backspace. The
 * direction buttons are the arrow keys and Minecraft's own movement keys. Vanilla's creative-only "load toolbar" also
 * defaults to X; inside our screens that does no harm, since key mappings don't fire while a screen is open.
 */
public final class Keybinds {
	public static final String CATEGORY = "key.categories.kantobridge";

	/** A Game Boy button ({@code act.button}'s names). */
	public enum Button {
		A("a"),
		B("b"),
		START("start"),
		SELECT("select"),
		UP("up"),
		DOWN("down"),
		LEFT("left"),
		RIGHT("right");

		private final String id;

		Button(String id) {
			this.id = id;
		}

		public String id() {
			return this.id;
		}

		public boolean direction() {
			return this == UP || this == DOWN || this == LEFT || this == RIGHT;
		}
	}

	private static @Nullable KeyMapping a;
	private static @Nullable KeyMapping b;
	private static @Nullable KeyMapping start;
	private static @Nullable KeyMapping select;
	/** BIKE_PLAN D4: gets on and off the bike ({@code act.field{bicycle}}); C is free in vanilla and Cobblemon 1.8.1. */
	private static @Nullable KeyMapping bicycle;

	private Keybinds() {
	}

	/** Registers the four mappings (once, from client init). */
	public static void register() {
		if (a != null) {
			return;
		}
		a = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.kantobridge.a", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, CATEGORY));
		b = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.kantobridge.b", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, CATEGORY));
		start = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.kantobridge.start", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, CATEGORY));
		select = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.kantobridge.select", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSPACE, CATEGORY));
		bicycle = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.kantobridge.bicycle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY));
	}

	/** BIKE_PLAN D4: the Bicycle key. */
	public static KeyMapping bicycle() {
		return require(bicycle);
	}

	public static KeyMapping a() {
		return require(a);
	}

	public static KeyMapping b() {
		return require(b);
	}

	public static KeyMapping start() {
		return require(start);
	}

	public static KeyMapping select() {
		return require(select);
	}

	/** The button a key press inside one of our screens means, or null. */
	public static @Nullable Button button(Minecraft minecraft, int keyCode, int scanCode) {
		if (a().matches(keyCode, scanCode)) {
			return Button.A;
		}
		if (b().matches(keyCode, scanCode)) {
			return Button.B;
		}
		if (start().matches(keyCode, scanCode) || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			return Button.START;
		}
		if (select().matches(keyCode, scanCode)) {
			return Button.SELECT;
		}
		Options options = minecraft.options;
		if (keyCode == GLFW.GLFW_KEY_UP || options.keyUp.matches(keyCode, scanCode)) {
			return Button.UP;
		}
		if (keyCode == GLFW.GLFW_KEY_DOWN || options.keyDown.matches(keyCode, scanCode)) {
			return Button.DOWN;
		}
		if (keyCode == GLFW.GLFW_KEY_LEFT || options.keyLeft.matches(keyCode, scanCode)) {
			return Button.LEFT;
		}
		if (keyCode == GLFW.GLFW_KEY_RIGHT || options.keyRight.matches(keyCode, scanCode)) {
			return Button.RIGHT;
		}
		return null;
	}

	/** The button a mouse button means when A or B is bound to one, or null. */
	public static @Nullable Button mouseButton(int button) {
		if (a().matchesMouse(button)) {
			return Button.A;
		}
		if (b().matchesMouse(button)) {
			return Button.B;
		}
		return null;
	}

	private static KeyMapping require(@Nullable KeyMapping mapping) {
		if (mapping == null) {
			throw new IllegalStateException("Keybinds.register() was not called");
		}
		return mapping;
	}
}
