package dev.kantobridge.field;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * FA6 (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §4.3): the gen3 game's live sweep replayed through the client's movement
 * logic on the planned world (pod only): {@code -Pkb.fieldReplay=<export bundle>;<reports dir>;g3;<report out>} plus the
 * build's options. See {@link FieldReplay}. Counts, map ids and cells only. Skipped everywhere else.
 */
@EnabledIfSystemProperty(named = "kb.fieldReplay", matches = ".+")
class RealGen3FieldReplayTest {
	@Test
	void replay() throws Exception {
		FieldReplay.runFromProperty("gen3");
	}
}
