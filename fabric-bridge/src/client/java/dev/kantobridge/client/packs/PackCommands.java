package dev.kantobridge.client.packs;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.plan.PlanException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The player's pack actions (docs/specs/VOXEL_BLOCKS_PLAN.md V6 step 4): a "Pack template" button beside "Create Kanto
 * world" on the title screen, and the client command {@code /kbpack}: {@code template} writes the pack template and the
 * key index ({@link PackTemplateWriter}), {@code share <folder>} copies a pack folder of {@code resourcepacks/} to
 * {@code kantobridge/shared/} without the files made from the player's game, {@code check} (the default) shows the packs
 * applied and what was skipped by code. One action at a time, off the client thread; what they report is counts and
 * folders, never art.
 */
public final class PackCommands {
	private static final AtomicBoolean RUNNING = new AtomicBoolean();
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(10_000L);

	private PackCommands() {
	}

	/** Call once from client init. */
	public static void init() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(literal("kbpack")
				.executes(c -> check(c.getSource()))
				.then(literal("check").executes(c -> check(c.getSource())))
				.then(literal("template").executes(c -> {
					FabricClientCommandSource source = c.getSource();
					template(source.getClient(), source::sendFeedback);
					return 1;
				}))
				.then(literal("share").then(argument("folder", StringArgumentType.greedyString())
						.executes(c -> share(c.getSource(), StringArgumentType.getString(c, "folder")))))));
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof TitleScreen) {
				Screens.getButtons(screen).add(titleButton(minecraft, width, height));
			}
		});
	}

	/**
	 * Beside "Create Kanto world" (which stands below the title screen's buttons when it fits, else in the top-right
	 * corner): on its right when the screen is wide enough, else below it.
	 */
	private static Button titleButton(Minecraft minecraft, int width, int height) {
		int y = height / 4 + 48 + 72 + 12 + 24;
		Button.Builder builder = Button.builder(Component.translatable("kantobridge.packs.button"), button -> template(minecraft, message -> toast(
				minecraft, message)));
		if (y + 20 > height - 12) {
			return builder.bounds(width - 154, 28, 150, 20).build();
		}
		if (width / 2 + 104 + 96 <= width - 4) {
			return builder.bounds(width / 2 + 104, y, 96, 20).build();
		}
		return builder.bounds(width / 2 - 100, Math.min(y + 24, height - 32), 200, 20).build();
	}

	private static void toast(Minecraft minecraft, Component message) {
		SystemToast.addOrUpdate(minecraft.getToasts(), TOAST, Component.translatable("kantobridge.packs.button"), message);
	}

	/** Writes the template off the client thread, then reports and opens its folder. */
	static void template(Minecraft minecraft, Consumer<Component> report) {
		if (!RUNNING.compareAndSet(false, true)) {
			report.accept(Component.translatable("kantobridge.packs.busy"));
			return;
		}
		report.accept(Component.translatable("kantobridge.packs.template.running"));
		Path gameDir = FabricLoader.getInstance().getGameDir();
		Util.backgroundExecutor().execute(() -> {
			Component message;
			Path opened = null;
			try {
				PackTemplateWriter.Result result = PackTemplateWriter.write(gameDir);
				KantoBridge.LOGGER.info("packs: template written ({} textures, {} models, {} tiles, {} cells)", result.textures(), result.models(),
						result.tiles(), result.cells());
				message = Component.translatable("kantobridge.packs.template.done", result.textures(), result.models(), folder(result.dir()));
				opened = result.dir();
			} catch (PlanException e) {
				message = Component.translatable("kantobridge.packs.template.failed", e.code());
			} catch (BundleException e) {
				message = Component.translatable("kantobridge.packs.template.failed", e.code());
			} catch (IOException e) {
				message = Component.translatable("kantobridge.packs.template.failed", "io." + e.getClass().getSimpleName());
			} catch (RuntimeException e) {
				KantoBridge.LOGGER.error("packs: unexpected {} while writing the template", e.getClass().getName(), e);
				message = Component.translatable("kantobridge.packs.template.failed", e.getClass().getSimpleName());
			} finally {
				RUNNING.set(false);
			}
			Component done = message;
			Path folder = opened;
			minecraft.execute(() -> {
				report.accept(done);
				if (folder != null) {
					Util.getPlatform().openFile(folder.toFile());
				}
			});
		});
	}

	/** "Prepare for sharing": a copy of {@code resourcepacks/<folder>} without the files made from the player's game. */
	static int share(FabricClientCommandSource source, String name) {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		Path pack = gameDir.resolve("resourcepacks").resolve(name).normalize();
		if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..") || !Files.isDirectory(pack)) {
			source.sendError(Component.translatable("kantobridge.packs.share.missing", name));
			return 0;
		}
		if (!RUNNING.compareAndSet(false, true)) {
			source.sendError(Component.translatable("kantobridge.packs.busy"));
			return 0;
		}
		Path out = gameDir.resolve("kantobridge").resolve("shared").resolve(name);
		Minecraft minecraft = source.getClient();
		Util.backgroundExecutor().execute(() -> {
			Component message;
			try {
				PackTemplateWriter.Share result = PackTemplateWriter.share(pack, out);
				message = Component.translatable("kantobridge.packs.share.done", result.copied(), folder(result.dir()), result.skipped());
			} catch (IOException | RuntimeException e) {
				message = Component.translatable("kantobridge.packs.share.failed", name, e.getClass().getSimpleName());
			} finally {
				RUNNING.set(false);
			}
			Component done = message;
			minecraft.execute(() -> source.sendFeedback(done));
		});
		return 1;
	}

	/** The packs applied, their entries and what was skipped, by code. */
	static int check(FabricClientCommandSource source) {
		PackOverrides packs = KbPackReload.current();
		if (packs.packs().isEmpty() && packs.warnings().isEmpty()) {
			source.sendFeedback(Component.translatable("kantobridge.packs.check.none"));
		} else {
			source.sendFeedback(Component.translatable("kantobridge.packs.check", String.join(", ", packs.packs()), packs.applied(),
					packs.warnings().isEmpty() ? "-" : packs.warnings().toString()));
		}
		Path gameDir = FabricLoader.getInstance().getGameDir();
		PackInfo pack = PackInfo.read(GeneratedPack.directory(gameDir));
		if (pack != null && pack.game() != null && !Files.isRegularFile(KeyIndex.file(gameDir, pack.game()))) {
			source.sendFeedback(Component.translatable("kantobridge.packs.check.nokeys"));
		}
		return 1;
	}

	/** A folder as a link that opens it. */
	private static MutableComponent folder(Path dir) {
		String path = dir.toAbsolutePath().toString();
		return Component.literal(path).withStyle(style -> style.withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, path)));
	}
}
