package dev.kantobridge.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import dev.kantobridge.KantoBridge;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The mirror screen (docs/PLAN.md P10, §4.3 "UI"): Gen 1's graphical screens (party, summary, Pokédex pages, town map,
 * slots, …) as the hidden game draws them. It subscribes to {@code mirror.frame}s while shown ({@link MirrorLink}),
 * uploads the newest into one reused texture of the frame's size with nearest filtering (160×144, or Gen 3's 240×160,
 * MG7), and draws it at the largest whole number of window pixels per game pixel that fits. Every button goes to the hidden game as {@code act.button} taps; the
 * key hints sit under the picture. The picture is ROM-derived art: it lives only in that texture. Native special
 * battles (the Safari Zone, the old man, the ghost) play here too, kept up between their own UIs ({@link NativeBattle}).
 */
public final class MirrorScreen extends BridgeScreen {
	/** GUI units kept free under the picture for the key hints. */
	static final int HINT_HEIGHT = 14;

	public MirrorScreen(UiRuntime runtime, UiRouter.View view) {
		super(runtime, view, Component.translatable("kantobridge.ui.mirror"));
	}

	@Override
	public void added() {
		super.added();
		Picture.forUi(this.view.main().id());
		this.runtime.mirror().show(this.view.main().id());
	}

	@Override
	public void removed() {
		this.runtime.mirror().hide();
		super.removed();
	}

	@Override
	protected Gen1Canvas canvas() {
		Window window = this.minecraft.getWindow();
		MirrorLink link = this.runtime.mirror();
		return Gen1Canvas.mirror(window.getWidth(), window.getHeight(), window.getGuiScale(), HINT_HEIGHT, link.width(), link.height());
	}

	@Override
	protected void drawView(GuiGraphics graphics, Gen1Canvas canvas, long now) {
		MirrorLink link = this.runtime.mirror();
		byte[] fresh = link.takeFresh(System.nanoTime());
		if (fresh != null && !Picture.upload(this.minecraft, fresh, link.width(), link.height())) {
			link.badFrame();
		}
		int w = link.width();
		int h = link.height();
		if (Picture.shown() && Picture.width() == w) {
			graphics.blit(Picture.id(), 0, 0, w, h, 0.0F, 0.0F, w, h, w, h);
		} else {
			new Gen1Box(0, 0, w, h).draw(graphics);
			String waiting = I18n.get("kantobridge.ui.mirror.waiting");
			graphics.drawCenteredString(this.font, waiting, w / 2, h / 2 - 4, Gen1Draw.BLACK);
		}
	}

	@Override
	protected void drawOverCanvas(GuiGraphics graphics, Gen1Canvas canvas, long now) {
		String hint = I18n.get("kantobridge.ui.mirror.keys", Keybinds.a().getTranslatedKeyMessage().getString(),
				Keybinds.b().getTranslatedKeyMessage().getString(), Keybinds.start().getTranslatedKeyMessage().getString(),
				Keybinds.select().getTranslatedKeyMessage().getString());
		int y = Math.round(canvas.y() + this.runtime.mirror().height() * canvas.scale()) + 3;
		graphics.drawCenteredString(this.font, hint, this.width / 2, Math.min(y, this.height - 10), 0xFFFFFFFF);
	}

	@Override
	protected void onButton(Keybinds.Button button, boolean repeat, long now) {
		// The top UI takes the keys; so does a native battle's mirror kept up between the battle's own UIs.
		if (this.runtime.takesInput(this.view.main())) {
			this.runtime.button(button);
		}
	}

	/**
	 * The textures the picture goes into, one per frame size (the Game Boy's, Gen 3's), each made on first use (on the
	 * render thread) and kept for the session.
	 */
	static final class Picture {
		static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "mirror");
		static final ResourceLocation GBA_ID = ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "mirror_gba");
		private static @Nullable DynamicTexture texture;
		private static @Nullable DynamicTexture gbaTexture;
		private static int shownUi;
		private static boolean hasPicture;
		/** The size of the picture shown: 160×144, or Gen 3's 240×160. */
		private static int shownWidth = MirrorLink.WIDTH;
		private static int shownHeight = MirrorLink.HEIGHT;

		private Picture() {
		}

		/** Decodes a PNG frame of {@code width}×{@code height} into its texture; false when it isn't one. */
		static boolean upload(Minecraft minecraft, byte[] png, int width, int height) {
			if (!MirrorLink.validSize(width, height)) {
				return false;
			}
			DynamicTexture target = texture(minecraft, width);
			NativeImage pixels = target.getPixels();
			if (pixels == null) {
				return false;
			}
			// read(InputStream) copies to native memory itself; read(byte[]) would put the whole frame on the small stack.
			try (NativeImage frame = NativeImage.read(new ByteArrayInputStream(png))) {
				if (frame.getWidth() != width || frame.getHeight() != height || frame.format() != pixels.format()) {
					return false;
				}
				pixels.copyFrom(frame);
			} catch (IOException | RuntimeException e) {
				return false;
			}
			target.upload();
			hasPicture = true;
			shownWidth = width;
			shownHeight = height;
			return true;
		}

		static boolean shown() {
			return hasPicture;
		}

		static int width() {
			return shownWidth;
		}

		/** The texture of the picture shown. */
		static ResourceLocation id() {
			return shownWidth == MirrorLink.GBA_WIDTH ? GBA_ID : ID;
		}

		/**
		 * The last picture of mirror UI {@code ui}, if it is the one kept, drawn in canvas pixels under another UI's
		 * dialog canvas (160×144): Gen 3's 240×160 picture is scaled to the canvas's width, its bottom on the canvas's
		 * bottom, where the game's own text box sits.
		 */
		static void drawUnder(GuiGraphics graphics, int ui) {
			if (!hasPicture || ui != shownUi) {
				return;
			}
			if (shownWidth == MirrorLink.WIDTH) {
				graphics.blit(ID, 0, 0, MirrorLink.WIDTH, MirrorLink.HEIGHT, 0.0F, 0.0F, MirrorLink.WIDTH, MirrorLink.HEIGHT, MirrorLink.WIDTH,
						MirrorLink.HEIGHT);
				return;
			}
			float scale = (float) Gen1Canvas.WIDTH / shownWidth;
			graphics.pose().pushPose();
			graphics.pose().translate(0.0F, Gen1Canvas.HEIGHT - shownHeight * scale, 0.0F);
			graphics.pose().scale(scale, scale, 1.0F);
			graphics.blit(GBA_ID, 0, 0, shownWidth, shownHeight, 0.0F, 0.0F, shownWidth, shownHeight, shownWidth, shownHeight);
			graphics.pose().popPose();
		}

		/** A new mirror UI starts without a picture (until its first frame, or the link's kept one, is uploaded). */
		static void forUi(int ui) {
			if (ui != shownUi) {
				shownUi = ui;
				hasPicture = false;
			}
		}

		/** The link dropped: UI ids start over, so no picture belongs to anything any more. */
		static void reset() {
			shownUi = 0;
			hasPicture = false;
		}

		private static DynamicTexture texture(Minecraft minecraft, int width) {
			if (width == MirrorLink.GBA_WIDTH) {
				if (gbaTexture == null) {
					gbaTexture = new DynamicTexture(MirrorLink.GBA_WIDTH, MirrorLink.GBA_HEIGHT, true);
					minecraft.getTextureManager().register(GBA_ID, gbaTexture);
				}
				return gbaTexture;
			}
			if (texture == null) {
				texture = new DynamicTexture(MirrorLink.WIDTH, MirrorLink.HEIGHT, true);
				minecraft.getTextureManager().register(ID, texture);
			}
			return texture;
		}
	}
}
