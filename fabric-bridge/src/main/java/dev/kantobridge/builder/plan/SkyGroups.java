package dev.kantobridge.builder.plan;

import dev.kantobridge.scene.gen3.QuarterPages;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * The open-air maps the game connects to each other (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §14, SC3: Emerald's Safari
 * Zone, Crystal's Silver Cave outside and Route 28): each placed right beside the one it is entered from, as their
 * connection says, on that map's layer, so the seam is walked across as on the surface. One per build; pure Java.
 */
final class SkyGroups {
	/** Each placed map's group, by the group's first map. */
	private final Map<String, String> group = new HashMap<>();
	/** Each group's footprints. */
	private final Map<String, List<InteriorPacker.Box>> boxes = new HashMap<>();

	/**
	 * Map {@code id} ({@code w} x {@code h} cells) entered from open-air {@code parent} across its {@code dir} side
	 * ("north", "south", "east", "west"), {@code offset} cells along it: its placement beside {@code parent}, or null when
	 * the spot is taken ({@link InteriorPacker#placeSkyBeside}; the map is then placed on its own).
	 */
	@Nullable MapPlacement beside(String id, String dir, int offset, int w, int h, boolean dark, MapPlacement parent, InteriorPacker packer,
			InteriorPacker.Box sky, Warnings warnings) {
		if (!WorldPlan.openSky(parent)) {
			return null;
		}
		int off = Scale.cellToBlock(offset);
		int x;
		int z;
		switch (dir) {
			case "north" -> {
				x = parent.x() + off;
				z = parent.z() - Scale.cellToBlock(h);
			}
			case "south" -> {
				x = parent.x() + off;
				z = parent.z() + Scale.cellToBlock(parent.h());
			}
			case "west" -> {
				x = parent.x() - Scale.cellToBlock(w);
				z = parent.z() + off;
			}
			case "east" -> {
				x = parent.x() + Scale.cellToBlock(parent.w());
				z = parent.z() + off;
			}
			default -> {
				return null;
			}
		}
		String root = this.group.getOrDefault(parent.id(), parent.id());
		List<InteriorPacker.Box> mine = this.boxes.computeIfAbsent(root,
				r -> new ArrayList<>(List.of(InteriorPacker.footprint(parent.x(), parent.z(), parent.blockWidth(), parent.blockDepth()))));
		Map<String, String> detail = Warnings.symbols("map", id, "parent", parent.id());
		InteriorPacker.Spot spot = packer.placeSkyBeside(x, z, Scale.cellToBlock(w), Scale.cellToBlock(h), parent.layer(), sky, mine);
		if (spot == null) {
			warnings.note("open_air_beside_taken", detail);
			return null;
		}
		mine.add(InteriorPacker.footprint(x, z, Scale.cellToBlock(w), Scale.cellToBlock(h)));
		this.group.put(id, root);
		warnings.note("open_air_beside", detail);
		return new MapPlacement(id, x, parent.y(), z, w, h, parent.layer(), MapPlacement.Kind.INTERIOR, QuarterPages.PALETTE, WorldPlan.BIOME_OUTDOOR,
				dark, parent.id());
	}
}
