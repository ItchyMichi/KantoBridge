package dev.kantobridge.client.review;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.client.move.InputLock;
import dev.kantobridge.review.ModelReview;
import dev.kantobridge.review.ModelReviewStore;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Player-directed model feedback. All evidence and proposals stay in this client's game directory. */
public final class ModelReviewRuntime {
	private static @Nullable ModelReviewRuntime instance;
	private final ModelReviewStore store;
	private final KeyMapping toggle;
	private final KeyMapping comment;
	private boolean active;
	private boolean pendingCapture;
	private ModelReviewPicker.@Nullable Selection selection;
	private Set<String> flagged = Set.of();

	private ModelReviewRuntime(Minecraft minecraft) {
		this.store = new ModelReviewStore(minecraft.gameDirectory.toPath().resolve("kantobridge/model-reviews"));
		this.toggle = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.kantobridge.model_review",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, "key.categories.kantobridge"));
		this.comment = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.kantobridge.model_review_note",
				InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, "key.categories.kantobridge"));
	}

	public static void init() {
		if (instance != null) {
			return;
		}
		ModelReviewRuntime runtime = new ModelReviewRuntime(Minecraft.getInstance());
		instance = runtime;
		ClientTickEvents.END_CLIENT_TICK.register(runtime::tick);
		WorldRenderEvents.AFTER_ENTITIES.register(runtime::outline);
		WorldRenderEvents.END.register(context -> runtime.captureFrame());
		InputLock.holdWhile(() -> runtime.pendingCapture);
		HudRenderCallback.EVENT.register((graphics, delta) -> runtime.hud(graphics));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			runtime.active = false;
			runtime.selection = null;
			runtime.flagged = Set.of();
			runtime.pendingCapture = false;
		});
	}

	ModelReviewStore store() {
		return this.store;
	}

	private void tick(Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null || minecraft.screen != null) {
			while (this.toggle.consumeClick()) { }
			while (this.comment.consumeClick()) { }
			if (minecraft.level == null) {
				this.selection = null;
				this.active = false;
			}
			this.pendingCapture = false;
			return;
		}
		while (this.toggle.consumeClick()) {
			this.active = !this.active;
			if (this.active) {
				this.reloadFlags();
			} else {
				this.selection = null;
			}
		}
		this.selection = this.active ? ModelReviewPicker.pick(minecraft) : null;
		while (this.comment.consumeClick()) {
			if (Screen.hasShiftDown()) {
				this.openInbox(null);
				break;
			}
			if (!this.active) {
				this.active = true;
				this.reloadFlags();
				this.selection = ModelReviewPicker.pick(minecraft);
			}
			// Capture after the next world's outline has been drawn, so the screenshot names the same surface.
			this.pendingCapture = true;
			break;
		}
	}

	private void captureFrame() {
		if (!this.pendingCapture) {
			return;
		}
		this.pendingCapture = false;
		Minecraft client = Minecraft.getInstance();
		if (client.screen != null || client.level == null || client.player == null) {
			return;
		}
		if (this.selection == null) {
			client.player.displayClientMessage(Component.translatable("kantobridge.review.aim"), true);
			return;
		}
		this.capture(this.selection);
	}

	/** Native framebuffer read on the render thread, then compression and provenance lookup on the IO pool. */
	private void capture(ModelReviewPicker.Selection picked) {
		Minecraft minecraft = Minecraft.getInstance();
		JsonObject extra = new JsonObject();
		extra.addProperty("capturedAt", java.time.Instant.now().toString());
		extra.addProperty("dimension", minecraft.level.dimension().location().toString());
		extra.addProperty("pickIdentity", picked.modelIdentity());
		JsonObject camera = new JsonObject();
		Vec3 eye = minecraft.gameRenderer.getMainCamera().getPosition();
		camera.addProperty("x", eye.x);
		camera.addProperty("y", eye.y);
		camera.addProperty("z", eye.z);
		camera.addProperty("yaw", minecraft.gameRenderer.getMainCamera().getYRot());
		camera.addProperty("pitch", minecraft.gameRenderer.getMainCamera().getXRot());
		extra.add("camera", camera);
		FabricLoader.getInstance().getModContainer("kantobridge").ifPresent(mod ->
				extra.addProperty("modVersion", mod.getMetadata().getVersion().getFriendlyString()));
		NativeImage captured = null;
		try {
			captured = Screenshot.takeScreenshot(minecraft.getMainRenderTarget());
		} catch (RuntimeException e) {
			KantoBridge.LOGGER.warn("model review: screenshot capture failed ({})", e.getClass().getSimpleName());
		}
		NativeImage image = captured;
		ModelReviewScreen screen = new ModelReviewScreen(this, picked.label());
		minecraft.setScreen(screen);
		CompletableFuture.supplyAsync(() -> {
			JsonObject context;
			boolean detailsMissing = false;
			try {
				context = ModelReviewPicker.describe(picked);
				detailsMissing = context.has("provenance") && "unavailable".equals(context.get("provenance").getAsString());
			} catch (RuntimeException e) {
				context = picked.context().deepCopy();
				context.addProperty("provenanceError", e.getClass().getSimpleName());
				detailsMissing = true;
			}
			for (var entry : extra.entrySet()) {
				context.add(entry.getKey(), entry.getValue());
			}
			byte[] png = null;
			if (image != null) {
				try (image) {
					png = image.asByteArray();
				} catch (Exception e) {
					KantoBridge.LOGGER.warn("model review: screenshot encoding failed ({})", e.getClass().getSimpleName());
				}
			}
			return new Capture(context, png, detailsMissing);
		}, Util.ioPool()).whenComplete((result, error) -> minecraft.execute(() -> {
			if (error != null) {
				screen.captureFailed();
			} else {
				screen.ready(result.context(), result.png(), result.detailsMissing());
			}
		}));
	}

	private record Capture(JsonObject context, byte @Nullable [] png, boolean detailsMissing) { }

	void openInbox(@Nullable Screen parent) {
		Minecraft.getInstance().setScreen(new ModelReviewInbox(this, parent));
	}

	void reloadFlags() {
		Minecraft minecraft = Minecraft.getInstance();
		CompletableFuture.supplyAsync(() -> {
			try {
				Set<String> identities = new HashSet<>();
				for (ModelReview review : this.store.list().reviews()) {
					var identity = review.context().get("pickIdentity");
					if (!"resolved".equals(review.status().wire()) && identity != null && identity.isJsonPrimitive()
							&& identity.getAsJsonPrimitive().isString()) {
						identities.add(identity.getAsString());
					}
				}
				return Set.copyOf(identities);
			} catch (Exception e) {
				return Set.<String>of();
			}
		}, Util.ioPool()).thenAccept(found -> minecraft.execute(() -> this.flagged = found));
	}

	private boolean flagged(ModelReviewPicker.Selection selected) {
		return this.flagged.contains(selected.modelIdentity());
	}

	private void outline(WorldRenderContext context) {
		if (this.pendingCapture) {
			this.selection = ModelReviewPicker.pick(Minecraft.getInstance());
		}
		ModelReviewPicker.Selection selected = this.selection;
		if (!this.active || selected == null || context.consumers() == null || context.matrixStack() == null) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen != null && !(minecraft.screen instanceof ModelReviewScreen)) {
			return;
		}
		PoseStack poses = context.matrixStack();
		Vec3 eye = context.camera().getPosition();
		poses.pushPose();
		poses.translate(-eye.x, -eye.y, -eye.z);
		boolean marked = this.flagged(selected);
		LevelRenderer.renderLineBox(poses, context.consumers().getBuffer(RenderType.lines()), selected.bounds().inflate(0.015),
				marked ? 1.0F : 0.2F, marked ? 0.65F : 1.0F, marked ? 0.15F : 1.0F, 1.0F);
		poses.popPose();
	}

	private void hud(GuiGraphics graphics) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!this.active || minecraft.screen != null || minecraft.options.hideGui || minecraft.level == null) {
			return;
		}
		int y = graphics.guiHeight() - 76;
		Component line = Component.translatable("kantobridge.review.controls", this.comment.getTranslatedKeyMessage(),
				this.toggle.getTranslatedKeyMessage());
		graphics.drawCenteredString(minecraft.font, line, graphics.guiWidth() / 2, y, 0xFF80FFFF);
		ModelReviewPicker.Selection picked = this.selection;
		Component detail = picked == null ? Component.translatable("kantobridge.review.aim")
				: Component.translatable(this.flagged(picked) ? "kantobridge.review.flagged" : "kantobridge.review.target", picked.label());
		graphics.drawCenteredString(minecraft.font, detail, graphics.guiWidth() / 2, y + 12, 0xFFFFFFFF);
	}
}
