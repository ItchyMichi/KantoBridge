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
 * uploads the newest into one reused 160×144 texture with nearest filtering, and draws it at the largest whole number
 * of window pixels per Game Boy pixel that fits. Every button goes to the hidden game as {@code act.button} taps; the
 * key hints sit under the picture. The picture is ROM-derived art: it lives only in that texture.
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
		return Gen1Canvas.mirror(window.getWidth(), window.getHeight(), window.getGuiScale(), HINT_HEIGHT);
	}

	@Override
	protected void drawView(GuiGraphics graphics, Gen1Canvas canvas, long now) {
		byte[] fresh = this.runtime.mirror().takeFresh();
		if (fresh != null && !Picture.upload(this.minecraft, fresh)) {
			this.runtime.mirror().badFrame();
		}
		if (Picture.shown()) {
			graphics.blit(Picture.ID, 0, 0, MirrorLink.WIDTH, MirrorLink.HEIGHT, 0.0F, 0.0F, MirrorLink.WIDTH, MirrorLink.HEIGHT,
					MirrorLink.WIDTH, MirrorLink.HEIGHT);
		} else {
			new Gen1Box(0, 0, MirrorLink.WIDTH, MirrorLink.HEIGHT).draw(graphics);
			String waiting = I18n.get("kantobridge.ui.mirror.waiting");
			graphics.drawCenteredString(this.font, waiting, MirrorLink.WIDTH / 2, MirrorLink.HEIGHT / 2 - 4, Gen1Draw.BLACK);
		}
	}

	@Override
	protected void drawOverCanvas(GuiGraphics graphics, Gen1Canvas canvas, long now) {
		String hint = I18n.get("kantobridge.ui.mirror.keys", Keybinds.a().getTranslatedKeyMessage().getString(),
				Keybinds.b().getTranslatedKeyMessage().getString(), Keybinds.start().getTranslatedKeyMessage().getString(),
				Keybinds.select().getTranslatedKeyMessage().getString());
		int y = Math.round(canvas.y() + MirrorLink.HEIGHT * canvas.scale()) + 3;
		graphics.drawCenteredString(this.font, hint, this.width / 2, Math.min(y, this.height - 10), 0xFFFFFFFF);
	}

	@Override
	protected void onButton(Keybinds.Button button, boolean repeat, long now) {
		if (this.runtime.router().isTop(this.view.main())) {
			this.runtime.button(button);
		}
	}

	/** The one texture the picture goes into, made on first use (on the render thread) and kept for the session. */
	static final class Picture {
		static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "mirror");
		private static @Nullable DynamicTexture texture;
		private static int shownUi;
		private static boolean hasPicture;

		private Picture() {
		}

		/** Decodes a PNG frame into the texture; false when it isn't a 160×144 PNG. */
		static boolean upload(Minecraft minecraft, byte[] png) {
			DynamicTexture target = texture(minecraft);
			NativeImage pixels = target.getPixels();
			if (pixels == null) {
				return false;
			}
			// read(InputStream) copies to native memory itself; read(byte[]) would put the whole frame on the small stack.
			try (NativeImage frame = NativeImage.read(new ByteArrayInputStream(png))) {
				if (frame.getWidth() != MirrorLink.WIDTH || frame.getHeight() != MirrorLink.HEIGHT || frame.format() != pixels.format()) {
					return false;
				}
				pixels.copyFrom(frame);
			} catch (IOException | RuntimeException e) {
				return false;
			}
			target.upload();
			hasPicture = true;
			return true;
		}

		static boolean shown() {
			return hasPicture;
		}

		/** The last picture of mirror UI {@code ui}, if it is the one kept, drawn in canvas pixels. */
		static void drawUnder(GuiGraphics graphics, int ui) {
			if (hasPicture && ui == shownUi) {
				graphics.blit(ID, 0, 0, MirrorLink.WIDTH, MirrorLink.HEIGHT, 0.0F, 0.0F, MirrorLink.WIDTH, MirrorLink.HEIGHT, MirrorLink.WIDTH,
						MirrorLink.HEIGHT);
			}
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

		private static DynamicTexture texture(Minecraft minecraft) {
			if (texture == null) {
				texture = new DynamicTexture(MirrorLink.WIDTH, MirrorLink.HEIGHT, true);
				minecraft.getTextureManager().register(ID, texture);
			}
			return texture;
		}
	}
}
