package dev.kantobridge.client.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.entity.BikeEntity;
import java.io.DataInputStream;
import java.io.InputStream;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import org.junit.jupiter.api.Test;

/** BIKE_PLAN B4: the bike's hand-made model (its parts per kind) and the three 64 x 32 textures it is drawn with. */
class BikeModelTest {
	@Test
	void everyKindHasTheBikesPartsAndOnlyTheAcroHasPegs() {
		for (BikeEntity.Kind kind : BikeEntity.Kind.values()) {
			MeshDefinition mesh = BikeModel.mesh(kind);
			for (String part : BikeModel.PARTS) {
				assertTrue(has(mesh, part), kind + " lacks " + part);
			}
			assertEquals(kind == BikeEntity.Kind.ACRO, has(mesh, BikeModel.PEGS), kind + ": pegs");
		}
	}

	private static boolean has(MeshDefinition mesh, String part) {
		try {
			return mesh.getRoot().getChild(part) != null;
		} catch (RuntimeException e) {
			return false;
		}
	}

	@Test
	void eachKindHasItsOwnLayer() {
		assertEquals(BikeModel.MACH, BikeModel.layerOf(BikeEntity.Kind.MACH));
		assertEquals(BikeModel.ACRO, BikeModel.layerOf(BikeEntity.Kind.ACRO));
		assertEquals(BikeModel.PLAIN, BikeModel.layerOf(BikeEntity.Kind.BIKE));
	}

	@Test
	void theTexturesAre64By32() throws Exception {
		for (String name : new String[] {"bike_mach", "bike_acro", "bike_plain"}) {
			try (InputStream in = BikeModelTest.class.getClassLoader().getResourceAsStream("assets/kantobridge/textures/entity/" + name + ".png")) {
				assertNotNull(in, name + " missing");
				DataInputStream data = new DataInputStream(in);
				data.skipBytes(16);
				assertEquals(BikeModel.TEXTURE_WIDTH, data.readInt(), name);
				assertEquals(BikeModel.TEXTURE_HEIGHT, data.readInt(), name);
			}
		}
	}

	@Test
	void theWheelsTurnWithTheDistance() {
		assertEquals(-4.0F, BikeModel.spin(1.0F), 1.0E-6F);  // a block is 4 radians on a quarter-block wheel
		RideLook.Pose wheelie = RideLook.of(BikeEntity.Trick.WHEELIE, 10.0F, -1.0F);
		assertEquals(RideLook.WHEELIE_DEGREES, wheelie.pitch());
		RideLook.Pose hop = RideLook.of(BikeEntity.Trick.HOP, (float) (RideLook.HOP_TICKS / 2.0), -1.0F);
		assertEquals(RideLook.HOP_LIFT, hop.lift(), 1.0E-6);
		assertTrue(RideLook.of(BikeEntity.Trick.NONE, 0.0F, 1.0F).back() > 0.0);
		assertEquals(0.0, RideLook.of(BikeEntity.Trick.NONE, 0.0F, RideLook.JOLT_TICKS).back());
	}
}
