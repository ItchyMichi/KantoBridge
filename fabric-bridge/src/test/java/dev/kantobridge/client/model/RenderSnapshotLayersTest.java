package dev.kantobridge.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.JsonArray;
import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.builder.shape.ShapeParams;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.world.ShapeIndex;
import dev.kantobridge.world.ShapeMap;
import java.util.BitSet;
import java.util.List;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.junit.jupiter.api.Test;

/**
 * Several regions draw their own shapes at once (MG3): the followed region's shapes are layer 0, each other region's a
 * layer of its own, found by position, its ids carrying the layer, each drawn from its own table.
 */
class RenderSnapshotLayersTest {
	private static final String HASH = "a".repeat(64);
	private static final int PITCH = 8192;

	/** A shape map with one column at (x0, 64.., 0) holding the given ids. */
	private static ShapeMap.Lookup column(int x0, int... ids) {
		ShapeMap map = new ShapeMap(HASH, HASH, List.of(new ShapeMap.Grid(x0, 0, 1, 1, 64, new int[][] {{}, ids}, new int[] {1})), List.of());
		return map.lookup();
	}

	private static ShapeTable table(int lipHeight) {
		ShapeIndex index = ShapeIndex.of("kb-shapes/2", ShapeParams.v3().toJson(), List.of("a"), List.of("P"),
				List.of(new ShapeIndex.Use(ShapeKey.slab(0, lipHeight, 4), "P")), new JsonArray());
		return ShapeTable.build(index, ArtSource.NONE);
	}

	private static RenderSnapshot snapshot(ShapeMap.Lookup followed, ShapeTable followedTable, List<RenderSnapshot.Layer> others) {
		return new RenderSnapshot(1, null, null, new TextureAtlasSprite[2][], new Mesh[2][], new Mesh[2], null, true, null, 0, 0, followed,
				followedTable, new TextureAtlasSprite[0], new int[0], null, null, null, new BitSet(), others, 0);
	}

	@Test
	void everyRegionDrawsItsOwnShapes() {
		ShapeTable kanto = table(5);
		ShapeTable johto = table(4);
		ShapeTable hoenn = table(3);
		RenderSnapshot snapshot = snapshot(column(0, 1), kanto, List.of(
				new RenderSnapshot.Layer(column(PITCH, 1), johto, new TextureAtlasSprite[0], new int[0]),
				new RenderSnapshot.Layer(column(2 * PITCH, 1), hoenn, new TextureAtlasSprite[0], new int[0])));
		assertEquals(2, snapshot.otherLayers());
		assertEquals(1, snapshot.shapeAt(0, 64, 0), "the followed region's ids are plain");
		int second = snapshot.shapeAt(PITCH, 64, 0);
		int third = snapshot.shapeAt(2 * PITCH, 64, 0);
		assertEquals(1 | (1 << RenderSnapshot.LAYER_SHIFT), second, "another region's id carries its layer");
		assertEquals(1 | (2 << RenderSnapshot.LAYER_SHIFT), third);
		assertEquals(0, snapshot.shapeAt(PITCH + 1, 64, 0), "outside every region's grids: no shape");
		assertNotNull(snapshot.mesh(1));
		assertSame(kanto.mesh(1), snapshot.mesh(1));
		assertSame(johto.mesh(1), snapshot.mesh(second), "drawn from its own region's table");
		assertSame(hoenn.mesh(1), snapshot.mesh(third));
		assertNull(snapshot.mesh(1 | (3 << RenderSnapshot.LAYER_SHIFT)), "a layer that isn't there draws the cube");
	}

	@Test
	void withoutTheFollowedRegionsShapesTheOthersStillDraw() {
		RenderSnapshot snapshot = snapshot(null, null,
				List.of(new RenderSnapshot.Layer(column(PITCH, 1), table(4), new TextureAtlasSprite[0], new int[0])));
		assertEquals(0, snapshot.shapeAt(0, 64, 0));
		assertEquals(1 | (1 << RenderSnapshot.LAYER_SHIFT), snapshot.shapeAt(PITCH, 64, 0));
		assertEquals(snapshot.otherLayers(), snapshot.withClosed(new BitSet()).otherLayers(), "a new closed set keeps the layers");
	}
}
