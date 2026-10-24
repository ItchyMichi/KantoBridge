package dev.kantobridge.field;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * FA2 (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §4.3): the gen1 game's collision parity on the real export (pod only):
 * {@code -Pkb.fieldParity=<g1_field_parity.json>;<export bundle>;<report out>}, plus the build's options
 * ({@code -Pkb.outdoorLevels}, {@code -Pkb.chainLevels}, {@code -Pkb.landscapeModels}). See {@link FieldParity}. Writes counts,
 * map ids and example cells only. Skipped everywhere else, and for another game's bundle.
 */
@EnabledIfSystemProperty(named = "kb.fieldParity", matches = ".+")
class RealGen1FieldParityTest {
	@Test
	void parity() throws Exception {
		FieldParity.runFromProperty("gen1");
	}
}
