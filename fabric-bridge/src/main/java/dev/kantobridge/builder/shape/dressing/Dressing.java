package dev.kantobridge.builder.shape.dressing;

import dev.kantobridge.scene.SceneMap;
import java.util.List;

/**
 * The dressing rules of V7 (VD7: interior walls and ceilings only), in order (§5.12). Open-air interiors (forests) get
 * none: the builder gives them the sky instead of a ceiling when their placement allows it. Building backs are Tier A's
 * plain north faces (V3); trunks, cave roofs and canopies are later decisions.
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
}
