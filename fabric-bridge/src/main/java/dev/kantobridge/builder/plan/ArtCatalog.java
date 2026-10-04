package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.world.SlotMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

/**
 * Every piece of art the whole export can put on screen, and the slot map that names it (docs/PLAN.md §4.5 "Slot map,
 * textures and pack").
 *
 * <p>It depends on the export alone, never on which maps a build places: the generated resource pack is shared by
 * every world in a game folder, so a filtered or test build must write exactly the pack (and slot-map hash) a full
 * build writes. The keys cover, for every map: each tile its blocks show (half-height where the cell is water), its
 * border block (interior ceilings) and the border the game draws around it (the void fill and the rings since the voxel
 * plan's V3), and both blocks of its swap sites (the closed art
 * the story-cell renderer draws, whichever state a cell is built in). Besides the slot map it lists the NPC sheets and
 * the palettes that need the player's surfing sheet (maps with water).
 */
public final class ArtCatalog {
	private final SlotMap slotMap;
	private final Map<String, Integer> tilesPerRow;
	private final Map<String, List<ExportTileset.Animated>> animations;
	private final Set<String> npcs;
	private final @Nullable String surfSprite;
	private final Set<String> surfPalettes;

	private ArtCatalog(SlotMap slotMap, Map<String, Integer> tilesPerRow, Map<String, List<ExportTileset.Animated>> animations,
			Set<String> npcs, @Nullable String surfSprite, Set<String> surfPalettes) {
		this.slotMap = slotMap;
		this.tilesPerRow = Collections.unmodifiableMap(tilesPerRow);
		this.animations = Collections.unmodifiableMap(animations);
		this.npcs = Collections.unmodifiableSet(npcs);
		this.surfSprite = surfSprite;
		this.surfPalettes = Collections.unmodifiableSet(surfPalettes);
	}

	/**
	 * Catalogues the whole export. Maps whose tileset is missing are left out (noted); a build that places one fails
	 * later on its own.
	 */
	public static ArtCatalog of(ExportBundle bundle, Warnings warnings) throws BundleException, PlanException {
		Map<String, List<ExportSwaps.Site>> swapsByMap = new HashMap<>();
		for (ExportSwaps.Site site : bundle.swaps().sites()) {
			swapsByMap.computeIfAbsent(site.map(), k -> new ArrayList<>()).add(site);
		}
		Set<SlotMap.Key> keys = new HashSet<>();
		Map<String, Integer> tilesPerRow = new TreeMap<>();
		Map<String, List<ExportTileset.Animated>> animations = new TreeMap<>();
		Map<String, Set<String>> animationSeen = new HashMap<>();
		Set<String> npcs = new TreeSet<>();
		Set<String> waterPalettes = new TreeSet<>();
		Map<String, ExportTileset> tilesets = new HashMap<>();
		// Anomalies in maps are the planner's to report for the maps it places; here they would only repeat.
		Warnings scratch = new Warnings();
		for (String id : bundle.ids("map")) {
			ExportMap map = bundle.map(id);
			ExportTileset tileset = tilesets.get(map.tileset());
			if (tileset == null) {
				if (!bundle.has("tileset", map.tileset())) {
					warnings.note("catalog_tileset_missing");
					continue;
				}
				tileset = bundle.tileset(map.tileset());
				tilesets.put(map.tileset(), tileset);
			}
			String atlas = tileset.image();
			String palette = map.palette();
			tilesPerRow.putIfAbsent(atlas, tileset.tilesPerRow());
			// The export's animated tiles (from V2; older exports imply today's water and flowers), each entry once per atlas.
			for (ExportTileset.Animated entry : tileset.animated()) {
				if (animationSeen.computeIfAbsent(atlas, k -> new java.util.HashSet<>()).add(entry.kind() + java.util.Arrays.toString(entry.tiles()))) {
					animations.computeIfAbsent(atlas, k -> new ArrayList<>()).add(entry);
				}
			}
			for (ExportMap.MapObject object : map.objects()) {
				npcs.add(palette + "|" + object.sprite());
			}

			List<ExportSwaps.Site> sites = swapsByMap.getOrDefault(id, List.of());
			MapModel model = MapModel.build(map, tileset, sites, !map.surface(), scratch);
			for (int ty = 0; ty < model.cellHeight() * 2; ty++) {
				for (int tx = 0; tx < model.cellWidth() * 2; tx++) {
					boolean half = model.kind(tx / 2, ty / 2) == MapModel.Kind.WATER;
					keys.add(new SlotMap.Key(atlas, palette, model.tile(tx, ty), half ? SlotMap.Shape.HALF : SlotMap.Shape.FULL));
					if (half) {
						waterPalettes.add(palette);
					}
				}
			}
			for (int t = 0; t < 16; t++) {
				keys.add(new SlotMap.Key(atlas, palette, model.borderTile(t % 4, t / 4), SlotMap.Shape.FULL));
				keys.add(new SlotMap.Key(atlas, palette, model.drawnBorderTile(t % 4, t / 4), SlotMap.Shape.FULL));
			}
			for (ExportSwaps.Site site : sites) {
				// The data's own block too: for an overwritten script site it is never built, but listing it keeps the slot
				// map the same whichever sites the planner accepts (for every other site it is one of the two already).
				int data = site.bx() < map.width() && site.by() < map.height() ? map.block(site.bx(), site.by()) : site.before();
				for (int block : new int[] {site.before(), site.after(), data}) {
					for (int t = 0; t < 16; t++) {
						int tile = tileset.tile(block, t % 4, t / 4);
						if (tile >= 0) {
							keys.add(new SlotMap.Key(atlas, palette, tile, SlotMap.Shape.FULL));
						}
					}
				}
			}
		}
		// The surfing player's sheet in the palette of every map with water (docs/PLAN.md §6 M8 item 5).
		String surfSprite = bundle.field().surfSprite();
		if (surfSprite != null && !bundle.has("sprite", surfSprite)) {
			warnings.note("surf_sprite_missing");
			surfSprite = null;
		}
		SlotMap slotMap;
		try {
			slotMap = SlotMap.enumerate(keys);
		} catch (SlotMap.FullException e) {
			throw new PlanException("slot_map.full", e.getMessage());
		}
		return new ArtCatalog(slotMap, tilesPerRow, animations, npcs, surfSprite,
				surfSprite == null ? Set.of() : waterPalettes);
	}

	/** The slot map of the whole export. */
	public SlotMap slotMap() {
		return this.slotMap;
	}

	/** Tiles per atlas row, by atlas image id. */
	public int tilesPerRow(String atlas) {
		return this.tilesPerRow.getOrDefault(atlas, 16);
	}

	/**
	 * The animation that moves a tile of an atlas in the generated pack: its first {@code hshift} or {@code frames} entry,
	 * or null ({@code toggle} entries are gated effects the pack doesn't play).
	 */
	public ExportTileset.@Nullable Animated animation(String atlas, int tile) {
		for (ExportTileset.Animated entry : this.animations.getOrDefault(atlas, List.of())) {
			if (entry.kind().equals("toggle")) {
				continue;
			}
			for (int t : entry.tiles()) {
				if (t == tile) {
					return entry;
				}
			}
		}
		return null;
	}

	/** Every (palette, sprite) pair an object of some map needs, as {@code "PALETTE|SPRITE"}, sorted. */
	public Set<String> npcs() {
		return this.npcs;
	}

	/** The sprite the hidden game draws for a surfing player ({@code field.playerSprites.surf}), or null. */
	public @Nullable String surfSprite() {
		return this.surfSprite;
	}

	/** The palettes of the maps with water, each of which gets the surfing sheet; empty without a surf sprite. */
	public Set<String> surfPalettes() {
		return this.surfPalettes;
	}
}
