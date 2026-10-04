package dev.kantobridge.client.puppet.style;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.pack.PackKey;
import dev.kantobridge.character.SpriteFrames;
import dev.kantobridge.character.StyleFallback;
import dev.kantobridge.client.packs.KbPackManifest;
import dev.kantobridge.client.packs.KbPackReload;
import dev.kantobridge.client.packs.PackOverrides;
import dev.kantobridge.client.shape.vox.VoxFile;
import dev.kantobridge.client.shape.vox.VoxMesher;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.jetbrains.annotations.Nullable;

/**
 * Which style each character is drawn in (docs/specs/VOXEL_BLOCKS_PLAN.md §5.11, V8), decided once per sheet and kept
 * until the next resource reload or pack change. The style asked for: a pack's model of the sprite, else a pack's style for
 * it, else the player's setting ({@code characters.style}), else a pack's suggestion, else thick cards; then
 * {@link StyleFallback}'s chain on the sheet's own masks (rigged, carved, thick, flat). {@code characters.packs: false}
 * leaves packs out of it. Render thread only.
 */
public final class CharacterStyles {
	private final StyleFallback.@Nullable Style setting;
	private final boolean packs;
	private final Map<String, CharacterStyle> styles = new HashMap<>();
	private final Map<String, Integer> drawn = new TreeMap<>();
	private final Map<String, Integer> fallbacks = new TreeMap<>();

	/**
	 * @param setting the player's {@code characters.style}, or null when unset
	 * @param packs   packs may give sprites models and styles
	 */
	public CharacterStyles(StyleFallback.@Nullable Style setting, boolean packs) {
		this.setting = setting;
		this.packs = packs;
	}

	/** The style a sprite with this sheet is drawn in. */
	public CharacterStyle styleFor(String sprite, String kind, ResourceLocation sheet) {
		String key = sheet + "|" + sprite + "|" + kind;
		CharacterStyle style = this.styles.get(key);
		if (style == null) {
			style = this.decide(sprite, kind, sheet);
			this.styles.put(key, style);
			this.drawn.merge(style.id(), 1, Integer::sum);
		}
		return style;
	}

	/** Forgets every decision (a resource reload, a pack change). */
	public void clear() {
		this.styles.clear();
		this.drawn.clear();
		this.fallbacks.clear();
	}

	private CharacterStyle decide(String sprite, String kind, ResourceLocation sheet) {
		PackOverrides overrides = this.packs ? KbPackReload.current() : PackOverrides.NONE;
		String packStyle = overrides.characterStyle(sprite);
		PackKey key = this.packs ? spriteKey(sprite) : null;
		KbPackManifest.Model model = key == null ? null : overrides.model(key);
		if (model != null && (packStyle == null || "model".equals(packStyle))) {
			ModelStyle meshed = model(model);
			if (meshed != null) {
				return meshed;
			}
			this.fallbacks.merge("model", 1, Integer::sum);
		}
		StyleFallback.Style asked = StyleFallback.Style.byId(packStyle);
		if (asked == null || asked == StyleFallback.Style.MODEL) {
			asked = this.setting != null ? this.setting : StyleFallback.Style.byId(overrides.characterStyle());
		}
		if (asked == null || asked == StyleFallback.Style.MODEL) {
			asked = StyleFallback.Style.THICK;
		}
		if (asked == StyleFallback.Style.FLAT) {
			return FlatStyle.INSTANCE;
		}
		SpriteFrames frames = frames(sheet);
		if (frames == null) {
			this.fallbacks.merge("sheet", 1, Integer::sum);
			return FlatStyle.INSTANCE;
		}
		boolean revolves = frames.frames() == 1 && ("item".equals(kind) || "boulder".equals(kind));
		StyleFallback.Decision decision = StyleFallback.decide(frames, asked, revolves);
		if (decision.reason() != null) {
			this.fallbacks.merge(decision.reason(), 1, Integer::sum);
		}
		return switch (decision.style()) {
			case RIGGED -> new RiggedStyle(decision.rig(), frames.size());
			case CARVED -> new CarvedStyle(decision.carved()[0], decision.carved()[1], frames.size());
			case THICK -> new ThickStyle(decision.thick(), frames.size());
			default -> FlatStyle.INSTANCE;
		};
	}

	private static @Nullable PackKey spriteKey(String sprite) {
		try {
			return PackKey.sprite(sprite);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** A sheet's frames from its pixels (opaque: alpha of at least half), or null when it can't be read. */
	static @Nullable SpriteFrames frames(ResourceLocation sheet) {
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(sheet);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
			int w = image.getWidth();
			int h = image.getHeight();
			if (w < 4 || w != 2 * h) {
				return null;
			}
			boolean[] opaque = new boolean[w * h];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					opaque[y * w + x] = (image.getPixelRGBA(x, y) >>> 24) >= 128;
				}
			}
			return SpriteFrames.ofSheet(opaque, w, h);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** A pack's sprite model, meshed, or null when its file can't be read or meshed. */
	private static @Nullable ModelStyle model(KbPackManifest.Model model) {
		ResourceLocation file = ResourceLocation.tryParse(model.vox());
		Optional<Resource> resource = file == null ? Optional.empty() : Minecraft.getInstance().getResourceManager().getResource(file);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open()) {
			VoxFile.Model vox = VoxFile.read(in.readAllBytes());
			return new ModelStyle(VoxMesher.mesh(vox, model.voxelsPerBlock(), model.turn(), model.flip(), model.origin()));
		} catch (IOException | VoxFile.VoxException | RuntimeException e) {
			KantoBridge.LOGGER.warn("characters: a pack's sprite model couldn't be used ({})", e.getClass().getSimpleName());
			return null;
		}
	}

	/** The F7 line: the setting, the sprites by the style they are drawn in, what fell back and why. */
	public String debugLine() {
		StringBuilder byStyle = new StringBuilder();
		this.drawn.forEach((style, n) -> byStyle.append(byStyle.isEmpty() ? "" : " ").append(style).append(' ').append(n));
		StringBuilder why = new StringBuilder();
		this.fallbacks.forEach((reason, n) -> why.append(why.isEmpty() ? "" : " ").append(reason).append('×').append(n));
		return I18n.get("kantobridge.debug.characters", this.setting == null ? "-" : this.setting.id(), this.packs ? "on" : "off",
				byStyle.isEmpty() ? "-" : byStyle.toString(), why.isEmpty() ? "-" : why.toString());
	}
}
