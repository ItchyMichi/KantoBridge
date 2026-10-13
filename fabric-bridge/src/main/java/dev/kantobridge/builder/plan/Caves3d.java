package dev.kantobridge.builder.plan;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

/**
 * Which caves are built in 3D (docs/specs/CAVES_3D_PLAN.md §0, the pilot switch): raised ground (H4), and pieces and
 * shafts (H5). Every complex is (H7), unless the system property {@value #PROPERTY} names some: a complex is named by
 * any of its map ids, and a value that names no cave ({@code none}, say) builds every cave flat, with its fades.
 */
public final class Caves3d {
	/** The system property naming the complexes built in 3D: map ids, comma-separated, or {@value #ALL}. */
	public static final String PROPERTY = "kantobridge.caves3d";
	public static final String ALL = "all";
	/** The pilot (HD11): Mt. Moon, and the synthetic world's cave (the dry run's {@code -Pkb.caves3d=pilot}). */
	public static final Set<String> PILOT = Set.of("MT_MOON_1F", "TEST_CAVE_1F");
	/** Every complex (H7). */
	public static final Set<String> EVERY = Set.of(ALL);

	private Caves3d() {
	}

	/** The complexes the system property names, or every one. */
	public static Set<String> fromProperty() {
		return parse(System.getProperty(PROPERTY));
	}

	/** The complexes {@code value} names (map ids, comma-separated, or {@value #ALL}); none given: every one. */
	static Set<String> parse(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return EVERY;
		}
		Set<String> out = new TreeSet<>();
		Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
		return out.isEmpty() ? EVERY : Set.copyOf(out);
	}

	/** True when the complex of {@code maps} is built in 3D under {@code chosen}. */
	public static boolean in3d(Set<String> chosen, Collection<String> maps) {
		return chosen.contains(ALL) || maps.stream().anyMatch(chosen::contains);
	}
}
