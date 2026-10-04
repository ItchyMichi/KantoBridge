package dev.kantobridge.character;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportImage;
import dev.kantobridge.builder.pack.Png;
import dev.kantobridge.builder.pack.Textures;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The build report's Characters group (docs/specs/VOXEL_BLOCKS_PLAN.md §4.10, V8), from the export's own sprites: how
 * many sheets have 1, 3 and 6 frames, and how many sprites would fall back from carved to thick, from rigged to carved
 * and from rigged to thick. The styles' pure parts run here exactly as each client runs them (on masks, so the palette
 * doesn't matter). Counts only.
 *
 * @param spritesByFrames sheets by their frame count ({@code 1}, {@code 3}, {@code 6}; any other count under its own key)
 * @param fallbacks       {@code carved_to_thick}, {@code rigged_to_carved}, {@code rigged_to_thick}, {@code thick_to_flat}
 */
public record CharacterReport(Map<String, Long> spritesByFrames, Map<String, Long> fallbacks) {
	/** The fallback keys, in report order. */
	public static final String[] FALLBACKS = {"carved_to_thick", "rigged_to_carved", "rigged_to_thick", "thick_to_flat"};

	/** The report of an export's sprites. */
	public static CharacterReport of(ExportBundle bundle) throws BundleException {
		Map<String, Long> byFrames = new LinkedHashMap<>();
		for (String key : new String[] {"1", "3", "6"}) {
			byFrames.put(key, 0L);
		}
		Map<String, Long> fallbacks = new LinkedHashMap<>();
		for (String key : FALLBACKS) {
			fallbacks.put(key, 0L);
		}
		for (String id : bundle.ids("sprite")) {
			ExportImage sprite = bundle.sprite(id);
			byFrames.merge(Integer.toString(sprite.frames()), 1L, Long::sum);
			Png.Image strip;
			try {
				strip = Png.decode(sprite.png());
			} catch (java.io.IOException | RuntimeException e) {
				continue;
			}
			SpriteFrames frames = frames(strip, sprite.frames());
			boolean revolves = sprite.frames() == 1;
			StyleFallback.Decision rigged = StyleFallback.decide(frames, StyleFallback.Style.RIGGED, revolves);
			if (rigged.style() == StyleFallback.Style.CARVED) {
				fallbacks.merge("rigged_to_carved", 1L, Long::sum);
			} else if (rigged.style() == StyleFallback.Style.THICK) {
				fallbacks.merge("rigged_to_thick", 1L, Long::sum);
			}
			StyleFallback.Decision carved = StyleFallback.decide(frames, StyleFallback.Style.CARVED, revolves);
			if (carved.style() == StyleFallback.Style.THICK) {
				fallbacks.merge("carved_to_thick", 1L, Long::sum);
			}
			if (StyleFallback.decide(frames, StyleFallback.Style.THICK, revolves).style() == StyleFallback.Style.FLAT) {
				fallbacks.merge("thick_to_flat", 1L, Long::sum);
			}
		}
		return new CharacterReport(byFrames, fallbacks);
	}

	/** A strip's frames laid out as the generated sheet, as masks (shade 0 is transparent). */
	static SpriteFrames frames(Png.Image strip, int frames) {
		Png.Image sheet = Textures.spriteSheet(strip, frames, Textures.GREY_PALETTE);
		boolean[] opaque = new boolean[sheet.width() * sheet.height()];
		for (int i = 0; i < opaque.length; i++) {
			opaque[i] = sheet.argb()[i] >>> 24 != 0;
		}
		return SpriteFrames.of(opaque, sheet.width(), sheet.height(), frames);
	}
}
