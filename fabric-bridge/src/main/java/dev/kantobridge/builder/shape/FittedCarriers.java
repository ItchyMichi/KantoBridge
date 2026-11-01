package dev.kantobridge.builder.shape;

import dev.kantobridge.builder.shape.geom.PartsBuilding;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.profile.Profile.Building;
import dev.kantobridge.world.MapLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** Source-pinned forest art over the exact native collision and floor plan. */
final class FittedCarriers {
	private FittedCarriers() { }

	record Profile(int[] solid, int[] air, int[] floor) { }

	/** Fail closed: no counter, warp, story, profile pin, other template, hanging, ramp or deck can be consumed. */
	static @Nullable Profile profile(SceneMap map, Buildings.Claim claim, FittedShapes.Building building,
			ShapePlanner.Columns source, @Nullable ProfileClasses classes, @Nullable MapLayout layout) {
		if (!FittedShapes.preserveCarriersBuilding(building) || !map.outdoors() || !map.env().outdoor()) return null;
		int width = claim.width(), count = width * claim.rows(), per = map.perCell();
		int high = Math.min(30, Math.min(ShapeHeights.headroom(map), Math.max(2, (PartsBuilding.height(building.box()) + 7) / 8)));
		int[] solid = new int[count], air = new int[count], floor = new int[count];
		boolean visible = false;
		if (!FittedCarrierLevels.valid(map, claim, building.box(), layout)) return null;
		boolean berrySoil = FittedShapes.berrySoilBuilding(building);
		if (berrySoil && !FittedBerrySoil.shallow(building.box())) return null;
		for (int local = 0; local < count; local++) {
			if (!claim.claimed()[local]) continue;
			int x = claim.tx() + local % width, z = claim.ty() + local / width, i = z * map.artCols() + x;
			int cx = x / per, cy = z / per;
			CellKind kind = map.kind(cx, cy);
			if ((kind != CellKind.SOLID && kind != CellKind.WALK) || map.tags(cx, cy) != 0 || source.modelled()[i]
					|| source.hungMask()[i] != 0 || source.hungLeaves()[i] != 0 || source.hung()[i] != null
					|| (classes != null && classes.unitClass(cx, cy) != null)) return null;
			for (var site : map.swapSites()) for (var cell : site.cells()) if (cell.x() == cx && cell.y() == cy) return null;
			// Gen3 berry/story objects can be represented as SceneObject.PERSON without
			// a cell tag or swap site. Never replace any occupied source object cell.
			if (berrySoil) {
				if (kind != CellKind.SOLID || !FittedBerrySoil.matches(map, cx, cy)) return null;
			} else for (var object : map.objects()) if (object.x() == cx && object.y() == cy) return null;
			if (layout != null && (layout.ramp(cx, cy) != null || layout.deck(cx, cy) != null)) return null;
			int blocks = source.blocks()[i], decor = source.decor()[i];
			// This first source-audited use has SOLID forest columns and floor-only WALK cells.
			// No new overhang/decor interpretation is inferred from the template's shape.
			if (blocks < 0 || blocks > high || decor != 0 || (kind == CellKind.WALK && blocks != 0)) return null;
			ShapeKey[] keys = source.keys()[i];
			if (keys != null) {
				if (keys.length != blocks) return null;
				for (ShapeKey key : keys) if (key != null && key.kind() != ShapeKey.Kind.CUBE
						&& key.kind() != ShapeKey.Kind.ROOF && key.kind() != ShapeKey.Kind.CELL) return null;
			}
			if ((source.shapeMask()[i] >>> blocks) != 0) return null;
			solid[local] = blocks; floor[local] = source.floor()[i]; visible |= blocks > 0;
		}
		return visible ? new Profile(solid, air, floor) : null;
	}

	/** Overlay only the accepted claims on a fresh ordinary plan; every other column remains byte-for-byte identical. */
	static ShapePlanner.Columns overlay(SceneMap map, int atlas, ShapePlanner.Columns source,
			List<Buildings.Claim> claims, Map<Building, FittedShapes.Building> definitions,
			Map<Buildings.Claim, Profile> profiles, Map<String, Integer> tally) {
		List<Templates.Placed> templates = new ArrayList<>(source.templates());
		boolean[] replaced = new boolean[source.blocks().length], modelled = source.modelled().clone();
		for (Buildings.Claim claim : claims) {
			if (!profiles.containsKey(claim)) continue;
			for (int j = 0; j < claim.claimed().length; j++) if (claim.claimed()[j]) {
				int i = (claim.ty() + j / claim.width()) * map.artCols() + claim.tx() + j % claim.width();
				replaced[i] = true; modelled[i] = true;
			}
			templates.add(FittedBuildings.place(map, atlas, claim, definitions.get(claim.building()), map.tile(claim.tx(), claim.ty()),
					source.keys(), source.blocks(), source.shapeMask(), source.decor(), source.floor(), tally,
					Math.min(ShapeHeights.headroom(map), 30), profiles.get(claim)));
		}
		int per = map.perCell();
		var units = source.units().stream().filter(unit -> {
			for (int dz = 0; dz < per; dz++) for (int dx = 0; dx < per; dx++)
				if (!replaced[(unit.cy() * per + dz) * map.artCols() + unit.cx() * per + dx]) return true;
			return false;
		}).toList();
		tally.merge("fitted_native_carriers_preserved", profiles.size(), Integer::sum);
		return new ShapePlanner.Columns(source.keys(), source.blocks(), source.shapeMask(), source.floor(), source.decor(), units,
				source.runs(), List.copyOf(templates), source.wallTile(), source.ceiling(), source.hung(), source.hungMask(),
				source.hungLeaves(), source.curtain(), modelled);
	}
}
