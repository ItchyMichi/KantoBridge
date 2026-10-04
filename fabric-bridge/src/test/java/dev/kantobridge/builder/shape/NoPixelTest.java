package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportImage;
import dev.kantobridge.builder.pack.Png;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.world.ShapeMap;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * No pixel reaches a shape key (docs/specs/VOXEL_BLOCKS_PLAN.md I8) but the probe's answers (docs/specs/PROFILE_PLAN.md
 * PD3): the planner takes no {@link ArtSource} and the scene it reads holds no image, and two bundles that differ only in
 * their pixels plan the same shape index and the same shape map when the probe answers the same.
 */
class NoPixelTest {
	@Test
	void thePlannersInputsHoldNoPixels() {
		for (Method method : ShapePlanner.class.getDeclaredMethods()) {
			for (Class<?> type : method.getParameterTypes()) {
				assertFalse(ArtSource.class.isAssignableFrom(type), method.getName() + " takes an ArtSource");
			}
		}
		// Every type reachable through the scene's record components.
		Set<Class<?>> seen = new HashSet<>();
		Deque<Type> queue = new ArrayDeque<>();
		queue.add(Scene.class);
		while (!queue.isEmpty()) {
			Type type = queue.poll();
			if (type instanceof ParameterizedType parameterized) {
				queue.addAll(java.util.List.of(parameterized.getActualTypeArguments()));
				type = parameterized.getRawType();
			}
			if (!(type instanceof Class<?> c) || !seen.add(c)) {
				continue;
			}
			assertFalse(ArtSource.class.isAssignableFrom(c) || ExportImage.class.isAssignableFrom(c) || Png.Image.class.isAssignableFrom(c),
					c + " is reachable from the scene");
			if (c.isArray()) {
				queue.add(c.getComponentType());
			}
			if (c.isRecord()) {
				for (RecordComponent component : c.getRecordComponents()) {
					queue.add(component.getGenericType());
				}
			}
		}
	}

	@Test
	void bundlesDifferingOnlyInPixelsPlanTheSameShapes(@TempDir Path temp) throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		// The same atlas with every pixel changed.
		JsonObject image = bundle.json("image", "tilesets.test").deepCopy();
		Png.Image pixels = Png.decode(Base64.getDecoder().decode(image.get("png").getAsString()));
		for (int i = 0; i < pixels.argb().length; i++) {
			pixels.argb()[i] = 0xFF000000 | (~pixels.argb()[i] & 0xFFFFFF);
		}
		image.addProperty("png", Base64.getEncoder().encodeToString(Png.encode(pixels)));
		ExportBundle repainted = ExportBundle.open(TestData.syntheticCopyWith(temp, Map.of("image/tilesets.test", image)));
		assertNotEquals(bundle.exportHash(), repainted.exportHash(), "another export");

		WorldPlan a = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, 0, 0, TestData.MIN_BUILD_HEIGHT));
		WorldPlan b = WorldPlanner.plan(repainted, new WorldPlanner.Options(null, 0, 0, TestData.MIN_BUILD_HEIGHT));
		// The probe of PD3 is the planner's one pixel question: answered alike, the plans are the same.
		for (boolean answer : new boolean[] {false, true}) {
			UnitProbe probe = (atlas, tiles, context) -> answer;
			ShapePlan pa = ShapePlanner.plan(a.scene(), a.index().maps().values(), ShapeParams.current(), Occupancy.NONE, probe);
			ShapePlan pb = ShapePlanner.plan(b.scene(), b.index().maps().values(), ShapeParams.current(), Occupancy.NONE, probe);
			assertEquals(pa.index().toJson(), pb.index().toJson());
			assertEquals(pa.index().hash(), pb.index().hash());
			assertEquals(unbound(pa.map()).hash(), unbound(pb.map()).hash());
		}
		// The builder's own plans differ only where the probe's answers do.
		assertEquals(a.shapes().index().hash().equals(b.shapes().index().hash()), sameAnswers(a, b));
	}

	/** True when the two builds' probes answer every repeating cell alike (their units by source match). */
	private static boolean sameAnswers(WorldPlan a, WorldPlan b) {
		return a.shapes().unitTally().equals(b.shapes().unitTally());
	}

	private static ShapeMap unbound(ShapeMap map) {
		return map.withHeader(ShapeMap.NO_HASH, map.shapeHash());
	}
}
