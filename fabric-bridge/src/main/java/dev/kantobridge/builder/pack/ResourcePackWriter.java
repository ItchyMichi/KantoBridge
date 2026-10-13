package dev.kantobridge.builder.pack;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportImage;
import dev.kantobridge.builder.export.ExportPalettes;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.builder.plan.ArtCatalog;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.SlotMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Writes the per-player resource pack (docs/PLAN.md §4.5 "Resource pack writer"; layout 2 from the voxel plan's V1,
 * docs/specs/VOXEL_BLOCKS_PLAN.md §4.7) into {@code <gameDir>/kantobridge/resourcepack/}, the folder the client's
 * pack-source mixin offers as a required pack. Textures only, addressed by key and art set ({@link ArtKeys}), never by
 * slot, so one pack fits every world of a compatible game:
 *
 * <ul>
 * <li>{@code pack.mcmeta}, {@code pack_format} 34 (1.21.1 resources);</li>
 * <li>{@code kantobridge.json}: the layout, the art set (the export's game), the export hash and the counts;</li>
 * <li>tile textures per (atlas, palette, tile) of the whole export, 16×16, with {@code .mcmeta} animations for water
 * and flowers;</li>
 * <li>NPC textures per (palette, sprite) in the fixed sheet layout of {@link Textures#spriteSheet};</li>
 * <li>the surfing player's sheet per palette of a map with water.</li>
 * </ul>
 *
 * <p>The client's runtime model draws the tile blocks: no blockstate or model file is written. The art comes from this
 * player's own export and never leaves the machine.
 */
public final class ResourcePackWriter {
	public static final int PACK_FORMAT = 34;
	public static final String NAMESPACE = "kantobridge";
	/** Written at the pack's root (outside {@code assets/}): the layout, the art set, the export and the counts. */
	public static final String INFO_FILE = "kantobridge.json";

	/** What was written. */
	public record Result(int tileTextures, int npcTextures, int animated, String hash) {
		public int textures() {
			return this.tileTextures + this.npcTextures;
		}
	}

	private ResourcePackWriter() {
	}

	/** Generates the pack for a plan's export and writes it to {@code packDir}, replacing any earlier generated pack. */
	public static Result write(WorldPlan plan, ExportBundle bundle, Path packDir, Warnings warnings) throws IOException, BundleException {
		return write(plan.art(), bundle, packDir, warnings);
	}

	/** Generates the pack and writes it to {@code packDir}, replacing any earlier generated pack there. */
	public static Result write(ArtCatalog art, ExportBundle bundle, Path packDir, Warnings warnings) throws IOException, BundleException {
		PackFiles files = new PackFiles();
		Result result = generate(art, bundle, files, warnings);
		files.writeTo(packDir, "pack.mcmeta");
		return result;
	}

	public static Result generate(WorldPlan plan, ExportBundle bundle, PackFiles files, Warnings warnings) throws BundleException {
		return generate(plan.art(), bundle, files, warnings);
	}

	/**
	 * Generates the pack's files in memory. Everything comes from {@code art} (the whole export), never from the maps a
	 * build places, so every build of one export writes the same pack.
	 */
	public static Result generate(ArtCatalog art, ExportBundle bundle, PackFiles files, Warnings warnings) throws BundleException {
		SlotMap slotMap = art.slotMap();
		ExportPalettes palettes = bundle.hasPalettes() ? bundle.palettes() : null;
		String artSet = bundle.game();

		JsonObject pack = new JsonObject();
		JsonObject meta = new JsonObject();
		meta.addProperty("pack_format", PACK_FORMAT);
		meta.addProperty("description", "Kanto Bridge (generated)");
		pack.add("pack", meta);
		files.put("pack.mcmeta", ProtoJson.write(pack));
		Map<String, int[]> frameShades = new HashMap<>();

		// ---- tile textures ----
		Map<String, Png.Image> atlases = new HashMap<>();
		Map<String, String> textureOwners = new HashMap<>();
		int tileTextures = 0;
		int animated = 0;
		int blank = 0;
		for (SlotMap.Key key : slotMap.slots().keySet()) {
			String path = ArtKeys.textureFile(artSet, key.atlas(), key.palette(), key.tile());
			String owner = key.atlas() + "|" + key.palette() + "|" + key.tile();
			String previous = textureOwners.putIfAbsent(path, owner);
			if (previous != null) {
				if (!previous.equals(owner)) {
					// Ids that differ only in case would share a file; the art could be wrong, so stop.
					throw new IllegalStateException("two tile textures map to one file name");
				}
				continue;
			}
			Png.Image atlas = atlases.computeIfAbsent(key.atlas(), id -> decodeImage(bundle, id, warnings));
			int perRow = art.tilesPerRow(key.atlas());
			int[] colours = colours(palettes, key.palette());
			int[] shades = atlas != null ? Textures.tileShades(atlas, perRow, key.tile()) : new int[64];
			if (atlas == null || !Textures.inAtlas(atlas, perRow, key.tile())) {
				blank++;
			}
			Png.Image image = null;
			String mcmeta = null;
			// The export's animated tiles (V2): a sideways shift, or frames from images, in the engine's steps.
			ExportTileset.Animated animation = art.animation(key.atlas(), key.tile());
			if (animation != null && animation.kind().equals("hshift") && animation.offsets().length > 0) {
				image = Textures.shiftStrip(shades, colours, animation.offsets());
				mcmeta = Textures.stripMeta(Textures.frameTicks(animation.period()));
			} else if (animation != null && animation.kind().equals("frames") && !animation.images().isEmpty()) {
				List<int[]> frames = frames(bundle, animation.images(), frameShades, warnings);
				if (frames != null) {
					image = Textures.flowerStrip(frames, colours);
					mcmeta = animation.sequence().length > 0
							? Textures.sequenceMeta(animation.sequence(), frames.size(), Textures.frameTicks(animation.period()))
							: Textures.stripMeta(Textures.frameTicks(animation.period()));
				}
			}
			if (image == null) {
				image = Textures.tile(shades, colours);
			}
			files.put(path, Png.encode(image));
			if (mcmeta != null) {
				files.put(path + ".mcmeta", mcmeta);
				animated++;
			}
			tileTextures++;
		}
		warnings.note("tile_outside_atlas", blank);

		// ---- NPC textures: each sprite in the palette of every map it appears on ----
		int npcTextures = 0;
		Set<String> missingSprites = new HashSet<>();
		for (String pair : art.npcs()) {
			String palette = pair.substring(0, pair.indexOf('|'));
			String sprite = pair.substring(pair.indexOf('|') + 1);
			if (!bundle.has("sprite", sprite)) {
				if (missingSprites.add(sprite)) {
					warnings.warn("sprite_missing");
				}
				continue;
			}
			ExportImage export = bundle.sprite(sprite);
			Png.Image sheet;
			try {
				sheet = Png.decode(export.png());
			} catch (IOException e) {
				warnings.warn("sprite_unreadable");
				continue;
			}
			Png.Image texture = Textures.spriteSheet(sheet, export.frames(), colours(palettes, palette));
			files.put(ArtKeys.file(ArtKeys.npcTexture(artSet, palette, sprite)), Png.encode(texture));
			npcTextures++;
		}

		// ---- the surfing player's sheet, per palette of a map with water (drawn under a surfer by the client) ----
		String surf = art.surfSprite();
		if (surf != null && !art.surfPalettes().isEmpty()) {
			Png.Image sheet = null;
			ExportImage export = bundle.sprite(surf);
			try {
				sheet = Png.decode(export.png());
			} catch (IOException e) {
				warnings.warn("sprite_unreadable");
			}
			if (sheet != null) {
				for (String palette : art.surfPalettes()) {
					Png.Image texture = Textures.spriteSheet(sheet, export.frames(), colours(palettes, palette));
					files.put(ArtKeys.file(ArtKeys.surfTexture(artSet, palette)), Png.encode(texture));
					npcTextures++;
				}
			}
		}

		JsonObject info = new JsonObject();
		info.addProperty("layout", ArtKeys.LAYOUT);
		info.addProperty("artSet", artSet);
		info.addProperty("game", bundle.game());
		info.addProperty("exportHash", bundle.exportHash());
		info.addProperty("tiles", tileTextures);
		info.addProperty("sprites", npcTextures);
		info.addProperty("textures", tileTextures + npcTextures);
		files.put(INFO_FILE, ProtoJson.write(info));

		return new Result(tileTextures, npcTextures, animated, files.hash());
	}

	/** The block-atlas sprite id of a slot key's tile in an art set: one texture per (atlas, palette, tile), both shapes. */
	public static String texture(String artSet, SlotMap.Key key) {
		return ArtKeys.spriteId(artSet, key.atlas(), key.palette(), key.tile());
	}

	private static int[] colours(@Nullable ExportPalettes palettes, String name) {
		int[] colours = palettes != null ? palettes.colours(name) : null;
		return colours != null ? colours : Textures.GREY_PALETTE;
	}

	private static @Nullable Png.Image decodeImage(ExportBundle bundle, String id, Warnings warnings) {
		if (!bundle.has("image", id)) {
			return null;
		}
		try {
			return Png.decode(bundle.image(id).png());
		} catch (IOException | BundleException e) {
			warnings.warn("image_unreadable");
			return null;
		}
	}

	/**
	 * The export image a frame names: that id when the bundle has it, else (an older export's implied frames) an image of
	 * the same name in another folder, {@code tilesets.} preferred; null when there is none.
	 */
	static @Nullable String frameImage(List<String> imageIds, String id) {
		if (imageIds.contains(id)) {
			return id;
		}
		String name = id.substring(id.indexOf('.') + 1);
		String found = null;
		for (String candidate : imageIds) {
			if (candidate.endsWith("." + name) && (found == null || candidate.startsWith("tilesets."))) {
				found = candidate;
			}
		}
		return found;
	}

	/** An animation's frames (8×8 shades each), or null (noted) when the export lacks one of its images. */
	private static @Nullable List<int[]> frames(ExportBundle bundle, List<String> images, Map<String, int[]> cache, Warnings warnings) {
		List<int[]> frames = new ArrayList<>();
		List<String> imageIds = bundle.ids("image");
		for (String wanted : images) {
			String id = frameImage(imageIds, wanted);
			if (id == null) {
				warnings.note("animation_image_missing");
				return null;
			}
			int[] shades = cache.get(id);
			if (shades == null) {
				try {
					Png.Image image = Png.decode(bundle.image(id).png());
					shades = Textures.tileShades(image, Math.max(1, image.width() / 8), 0);
				} catch (IOException | BundleException e) {
					warnings.warn("image_unreadable");
					return null;
				}
				cache.put(id, shades);
			}
			frames.add(shades);
		}
		return frames;
	}
}
