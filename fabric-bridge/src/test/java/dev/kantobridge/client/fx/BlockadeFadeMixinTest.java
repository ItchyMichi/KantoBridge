package dev.kantobridge.client.fx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.McBootstrap;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The fade's three hooks apply to {@code LivingEntityRenderer} (fabric-loader-junit runs Knot on the client side, with
 * Cobblemon's own mixins present): a target that no longer matches would fail this class load here, not in the play-test.
 */
class BlockadeFadeMixinTest {
	@BeforeAll
	static void setUp() {
		McBootstrap.ensure();
	}

	@Test
	void theFadeHooksAreMergedIntoTheRenderer() {
		List<String> names = Arrays.stream(LivingEntityRenderer.class.getDeclaredMethods()).map(Method::getName).toList();
		for (String handler : List.of("kantobridge$fadeInBlockade", "kantobridge$fadeAlpha", "kantobridge$noLayersWhenFaded")) {
			assertTrue(names.stream().anyMatch(name -> name.endsWith(handler)), "the " + handler + " hook didn't apply");
		}
	}
}
