package dev.kantobridge.builder.shape.dressing;

import dev.kantobridge.builder.shape.ShapeParams;
import dev.kantobridge.scene.SceneMap;
import java.util.List;

/**
 * The dressing rules of V7 (VD7: interior walls and ceilings only), in order (§5.12), and the hangings of the caves and
 * woods plan (docs/specs/CAVES_AND_WOODS_PLAN.md: cave rock from CW1, the canopy and vines from CW2). Open-air interiors
 * (forests) get no walls or ceiling: the builder gives them the sky instead of a ceiling when their placement allows it.
 * Building backs are Tier A's plain north faces (V3); trunks are a later decision.
 */
public final class Dressing {
	/** The rules, in the order they run. */
	public static final List<DressingRule> RULES = List.of(new InteriorWalls(), new Ceilings());

	private Dressing() {
	}

	/** Runs every rule that applies to the map. */
	public static Dress dress(SceneMap map, Dress dress) {
		for (DressingRule rule : RULES) {
			if (rule.applies(map)) {
				rule.dress(map, dress);
			}
		}
		return dress;
	}

	/** The hanging rules {@code params} plans, in the order they run. */
	public static List<HangingRule> hangings(ShapeParams params) {
		List<HangingRule> out = new java.util.ArrayList<>(2);
		if (params.caveRock()) {
			out.add(new CaveRock());
		}
		if (params.canopy()) {
			out.add(new Canopy());
		}
		return out;
	}

	/** Runs every hanging rule of {@code params} that applies to the map, over its final columns. */
	public static Dress hang(SceneMap map, Dress dress, ShapeParams params) {
		for (HangingRule rule : hangings(params)) {
			if (rule.applies(map)) {
				rule.hang(map, dress);
			}
		}
		return dress;
	}
}
