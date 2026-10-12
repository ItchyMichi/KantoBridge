package dev.kantobridge.builder.plan;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which caves are built in 3D (docs/specs/CAVES_3D_PLAN.md §0, the pilot switch): raised ground (H4), and pieces and
 * shafts (H5). Until you approve the pilot (H6), only the complexes named in the system property
 * {@value #PROPERTY} are, else {@link #PILOT}; {@code all} names every complex. A complex is named by any of its map ids.
 */
public final class Caves3d {
	/** The system property naming the complexes built in 3D: map ids, comma-separated, or {@value #ALL}. */
	public static final String PROPERTY = "kantobridge.caves3d";
	public static final String ALL = "all";
	/** The pilot (HD11): Mt. Moon, and the synthetic world's cave. */
	public static final Set<String> PILOT = Set.of("MT_MOON_1F", "TEST_CAVE_1F");

	private Caves3d() {
	}

	/** The complexes the system property names, or the pilot. */
	public static Set<String> fromProperty() {
		String value = System.getProperty(PROPERTY);
		if (value == null || value.isBlank()) {
			return PILOT;
		}
		Set<String> out = new TreeSet<>();
		Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
		return out.isEmpty() ? PILOT : Set.copyOf(out);
	}

	/** True when the complex of {@code maps} is built in 3D under {@code chosen}. */
	public static boolean in3d(Set<String> chosen, Collection<String> maps) {
		return chosen.contains(ALL) || maps.stream().anyMatch(chosen::contains);
	}
}
