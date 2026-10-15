package dev.kantobridge.builder.pack;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportImage;
import dev.kantobridge.builder.export.ExportPalettes;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.builder.export.G3Data;
import dev.kantobridge.builder.plan.ArtCatalog;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.scene.gen3.QuarterPages;
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
 * <p>A Gen 3 export's catalogue is baked (MG7, {@link ArtCatalog#baked}): each tile texture is its quarter page's own
 * pixels ({@link BakedTextures}), without a palette. Since the follow-up's item 8 an animated quarter ({@code g3anims})
 * is a vertical strip of its frames with an {@code .mcmeta} of its loop, and the NPC sheets and the surf sheet come from
 * the export's {@code g3sprite} sheets in the generated layout ({@link BakedTextures#sheet}), under the one palette
 * {@link QuarterPages#PALETTE}.
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
		return write(List.of(new SetSource(art, bundle)), packDir, warnings);
	}

	/** Generates a pack of several art sets ({@link #generate(List, PackFiles, Warnings)}) and writes it to {@code packDir}. */
	public static Result write(List<SetSource> sets, Path packDir, Warnings warnings) throws IOException, BundleException {
		PackFiles files = new PackFiles();
		Result result = generate(sets, files, warnings);
		files.writeTo(packDir, "pack.mcmeta");
		return result;
	}

	/** One art set of a pack: an export and the catalogue of its art (MG7: a pack holds a set per game family). */
	public record SetSource(ArtCatalog art, ExportBundle bundle) {
	}

	public static Result generate(WorldPlan plan, ExportBundle bundle, PackFiles files, Warnings warnings) throws BundleException {
		return generate(plan.art(), bundle, files, warnings);
	}

	/**
	 * Generates the pack's files in memory. Everything comes from {@code art} (the whole export), never from the maps a
	 * build places, so every build of one export writes the same pack.
	 */
	public static Result generate(ArtCatalog art, ExportBundle bundle, PackFiles files, Warnings warnings) throws BundleException {
		return generate(List.of(new SetSource(art, bundle)), files, warnings);
	}

	/**
	 * Generates a pack of one or several art sets in memory (MG7, docs/specs/GEN3_BRIDGE_PLAN.md §6 "Two art sets in one
	 * pack"): each export's textures under its own art set (their paths never meet). The first set is the pack's own: the
	 * info file's top level names it, as a one-set pack's does; a pack of several lists every set in {@code sets}, and
	 * its counts are the sum. A one-set pack is byte for byte what it was before.
	 */
	public static Result generate(List<SetSource> sets, PackFiles files, Warnings warnings) throws BundleException {
		if (sets.isEmpty()) {
			throw new IllegalArgumentException("a pack has an art set");
		}
		JsonObject pack = new JsonObject();
		JsonObject meta = new JsonObject();
		meta.addProperty("pack_format", PACK_FORMAT);
		meta.addProperty("description", "Kanto Bridge (generated)");
		pack.add("pack", meta);
		files.put("pack.mcmeta", ProtoJson.write(pack));
		int tiles = 0;
		int sprites = 0;
		int animated = 0;
		com.google.gson.JsonArray list = new com.google.gson.JsonArray();
		for (SetSource set : sets) {
			int[] counts = generateSet(set.art(), set.bundle(), files, warnings);
			tiles += counts[0];
			sprites += counts[1];
			animated += counts[2];
			JsonObject entry = new JsonObject();
			entry.addProperty("artSet", set.bundle().game());
			entry.addProperty("game", set.bundle().game());
			entry.addProperty("exportHash", set.bundle().exportHash());
			entry.addProperty("tiles", counts[0]);
			entry.addProperty("sprites", counts[1]);
			list.add(entry);
		}
		ExportBundle first = sets.get(0).bundle();
		JsonObject info = new JsonObject();
		info.addProperty("layout", ArtKeys.LAYOUT);
		info.addProperty("artSet", first.game());
		info.addProperty("game", first.game());
		info.addProperty("exportHash", first.exportHash());
		info.addProperty("tiles", tiles);
		info.addProperty("sprites", sprites);
		info.addProperty("textures", tiles + sprites);
		if (sets.size() > 1) {
			info.add("sets", list);
		}
		files.put(INFO_FILE, ProtoJson.write(info));
		return new Result(tiles, sprites, animated, files.hash());
	}

	/** One art set's textures into {@code files}: {tile textures, NPC and surf sheets, animated tiles}. */
	private static int[] generateSet(ArtCatalog art, ExportBundle bundle, PackFiles files, Warnings warnings) throws BundleException {
		SlotMap slotMap = art.slotMap();
		ExportPalettes palettes = bundle.hasPalettes() ? bundle.palettes() : null;
		String artSet = bundle.game();
		Map<String, int[]> frameShades = new HashMap<>();

		// ---- tile textures ----
		Map<String, Png.Image> atlases = new HashMap<>();
		Map<String, String> textureOwners = new HashMap<>();
		int tileTextures = 0;
		int animated = 0;
		int blank = 0;
		// item 8: a Gen 3 export's animated quarters (none before them)
		Map<Integer, G3Data.Anims.Anim> quarterAnims = art.baked() ? bundle.g3anims().byQuarter() : Map.of();
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
			if (art.baked()) {
				// A Gen 3 quarter (MG7): its page's own pixels, never coloured.
				Png.Image page = atlases.computeIfAbsent(key.atlas(), id -> decodePage(bundle, id, warnings));
				int[] argb = page == null ? null : BakedTextures.quarter(page, art.tilesPerRow(key.atlas()), key.tile());
				if (argb == null) {
					blank++;
					argb = new int[64];
				}
				int quarter = QuarterPages.quarter(key.atlas(), key.tile());
				G3Data.Anims.Anim anim = quarter < 0 ? null : quarterAnims.get(quarter);
				Png.Image strip = anim == null ? null : bakedStrip(bundle, atlases, anim, warnings);
				if (strip != null) {
					// item 8: its frames, one below the other, and its loop in Minecraft ticks
					files.put(path, Png.encode(strip));
					files.put(path + ".mcmeta", BakedTextures.loopMeta(anim.loop()));
					animated++;
				} else {
					files.put(path, Png.encode(BakedTextures.tile(argb)));
				}
				tileTextures++;
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

		// ---- item 8: a Gen 3 export's NPC sheets and surf sheet, from its g3sprite sheets ----
		if (art.baked()) {
			return new int[] {tileTextures, bakedSheets(bundle, artSet, files, warnings), animated};
		}

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

		return new int[] {tileTextures, npcTextures, animated};
	}

	/**
	 * An animated quarter's frames (item 8) as a 16×(16·n) strip, each frame its own quarter of the pages, or null when a
	 * frame's page can't be read.
	 */
	private static Png.@Nullable Image bakedStrip(ExportBundle bundle, Map<String, Png.Image> pages, G3Data.Anims.Anim anim, Warnings warnings) {
		List<int[]> frames = new ArrayList<>();
		for (int frame : anim.frames()) {
			String atlas = QuarterPages.page(frame);
			Png.Image page = pages.computeIfAbsent(atlas, id -> decodePage(bundle, id, warnings));
			int[] argb = page == null ? null : BakedTextures.quarter(page, QuarterPages.PER_ROW, QuarterPages.index(frame));
			if (argb == null) {
				warnings.warn("anim_frame_missing");
				return null;
			}
			frames.add(argb);
		}
		return BakedTextures.strip(frames);
	}

	/**
	 * A Gen 3 export's NPC sheets (item 8): every object-event sheet in the generated layout under the one palette
	 * {@link QuarterPages#PALETTE}, and the surf blob as the surfing sheet. Returns how many were written.
	 */
	private static int bakedSheets(ExportBundle bundle, String artSet, PackFiles files, Warnings warnings) throws BundleException {
		int written = 0;
		for (String id : bundle.ids("g3sprite")) {
			G3Data.Sprite sprite = bundle.g3sprite(id);
			Png.Image strip;
			try {
				strip = Png.decode(sprite.png());
			} catch (IOException e) {
				warnings.warn("sprite_unreadable");
				continue;
			}
			Png.Image sheet = BakedTextures.sheet(strip, sprite.w(), sprite.h(), sprite.frames());
			if (sheet == null) {
				warnings.warn("sprite_unreadable");
				continue;
			}
			String file = G3Data.Sprite.SURF_BLOB.equals(id) ? ArtKeys.surfTexture(artSet, QuarterPages.PALETTE)
					: ArtKeys.npcTexture(artSet, QuarterPages.PALETTE, id);
			files.put(ArtKeys.file(file), Png.encode(sheet));
			written++;
		}
		return written;
	}

	/** The block-atlas sprite id of a slot key's tile in an art set: one texture per (atlas, palette, tile), both shapes. */
	public static String texture(String artSet, SlotMap.Key key) {
		return ArtKeys.spriteId(artSet, key.atlas(), key.palette(), key.tile());
	}

	private static int[] colours(@Nullable ExportPalettes palettes, String name) {
		int[] colours = palettes != null ? palettes.colours(name) : null;
		return colours != null ? colours : Textures.GREY_PALETTE;
	}

	/** A Gen 3 quarter page ({@code g3image}), or null when the export lacks it or it can't be read. */
	private static @Nullable Png.Image decodePage(ExportBundle bundle, String id, Warnings warnings) {
		if (!bundle.has("g3image", id)) {
			return null;
		}
		try {
			return Png.decode(bundle.g3image(id).png());
		} catch (IOException | BundleException e) {
			warnings.warn("image_unreadable");
			return null;
		}
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
