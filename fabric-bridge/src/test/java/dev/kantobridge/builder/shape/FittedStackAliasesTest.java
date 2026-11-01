package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FittedStackAliasesTest {
	@Test
	void optionalAliasesKeepThePrimaryLinkAndDeduplicateExactAlternateKeys() {
		String text = """
			{"cells":{"g3q/1-2-3-4":{"class":"voxels","on":"g3q/5-6-7-8",
			"alsoOn":["g3q/9-10-11-12","g3q/13-14-15-16","g3q/9-10-11-12"]}}}
			""";
		Map<String, String> primary = new HashMap<>();
		Map<String, Set<String>> aliases = new HashMap<>();
		FittedShapes.parseStacks(text, primary);
		FittedShapes.parseAlsoStacks(text, aliases);
		assertEquals(Map.of("g3q/1-2-3-4", "g3q/5-6-7-8"), primary);
		assertEquals(Set.of("g3q/9-10-11-12", "g3q/13-14-15-16"), aliases.get("g3q/1-2-3-4"));
	}

	@Test
	void missingPrimaryAndMalformedAliasListsNeverCreatePartialMatches() {
		for (String entry : new String[] {
				"{\"alsoOn\":[\"g3q/1-2-3-4\"]}",
				"{\"on\":\"g3q/5-6-7-8\",\"alsoOn\":\"g3q/1-2-3-4\"}",
				"{\"on\":\"g3q/5-6-7-8\",\"alsoOn\":[\"g3q/1-2-3-4\",null]}",
				"{\"on\":\"g3q/5-6-7-8\",\"alsoOn\":[\"g3q/1-2-3-4\",7]}",
				"{\"on\":\"g3q/5-6-7-8\",\"alsoOn\":[\"\"]}"}) {
			Map<String, Set<String>> aliases = new HashMap<>();
			FittedShapes.parseAlsoStacks("{\"cells\":{\"g3q/0-0-0-0\":" + entry + "}}", aliases);
			assertTrue(aliases.isEmpty());
		}
	}
}
