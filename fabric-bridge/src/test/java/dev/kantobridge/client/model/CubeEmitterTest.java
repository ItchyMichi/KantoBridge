package dev.kantobridge.client.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * "The same cubes, exactly" (docs/specs/VOXEL_BLOCKS_PLAN.md §5.1, §6.1): the runtime model's corner and UV tables equal
 * what vanilla's face bakery makes of {@code cube_all} (a 0..16 box, default UVs, a cull face per side) and of the jar's
 * {@code models/block/tile_half_placeholder.json}, vertex for vertex, using vanilla's own {@link FaceInfo} and
 * {@link BlockFaceUV}.
 */
class CubeEmitterTest {
	private static final float EPS = 1e-6F;

	/** Vanilla's corner of vertex {@code i} of a face of the box from..to (in 1/16 units), as block units. */
	private static float[] vanillaCorners(Direction face, float[] from, float[] to) {
		float[] byFace = new float[6];
		byFace[Direction.DOWN.get3DDataValue()] = from[1] / 16;
		byFace[Direction.UP.get3DDataValue()] = to[1] / 16;
		byFace[Direction.NORTH.get3DDataValue()] = from[2] / 16;
		byFace[Direction.SOUTH.get3DDataValue()] = to[2] / 16;
		byFace[Direction.WEST.get3DDataValue()] = from[0] / 16;
		byFace[Direction.EAST.get3DDataValue()] = to[0] / 16;
		float[] out = new float[12];
		for (int i = 0; i < 4; i++) {
			FaceInfo.VertexInfo info = FaceInfo.fromFacing(face).getVertexInfo(i);
			out[i * 3] = byFace[info.xFace];
			out[i * 3 + 1] = byFace[info.yFace];
			out[i * 3 + 2] = byFace[info.zFace];
		}
		return out;
	}

	/** Vanilla's UV of each vertex for a face's UV rectangle (rotation 0). */
	private static float[] vanillaUvs(float[] rect) {
		BlockFaceUV uv = new BlockFaceUV(rect, 0);
		float[] out = new float[8];
		for (int i = 0; i < 4; i++) {
			out[i * 2] = uv.getU(i);
			out[i * 2 + 1] = uv.getV(i);
		}
		return out;
	}

	@Test
	void theFullCubeIsCubeAll() {
		float[] from = {0, 0, 0};
		float[] to = {16, 16, 16};
		for (Direction face : Direction.values()) {
			assertArrayEquals(vanillaCorners(face, from, to), CubeEmitter.corners(CubeEmitter.FULL, face), EPS, face + " corners");
			assertArrayEquals(vanillaUvs(new float[] {0, 0, 16, 16}), CubeEmitter.uvs(CubeEmitter.FULL, face), EPS, face + " uvs");
			assertEquals(true, CubeEmitter.culls(CubeEmitter.FULL, face), face + " has its cull face");
		}
	}

	@Test
	void theHalfCubeIsTheJarsPlaceholderModel() throws Exception {
		JsonObject model;
		try (InputStream in = CubeEmitterTest.class.getResourceAsStream("/assets/kantobridge/models/block/tile_half_placeholder.json")) {
			assertNotNull(in, "the jar's half-cube model");
			model = ProtoJson.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		JsonArray elements = model.getAsJsonArray("elements");
		assertEquals(1, elements.size());
		JsonObject element = elements.get(0).getAsJsonObject();
		float[] from = floats(element.getAsJsonArray("from"));
		float[] to = floats(element.getAsJsonArray("to"));
		JsonObject faces = element.getAsJsonObject("faces");
		assertEquals(6, faces.size());
		for (Direction face : Direction.values()) {
			JsonObject json = faces.getAsJsonObject(face.getSerializedName().toLowerCase(Locale.ROOT));
			assertNotNull(json, face.toString());
			assertArrayEquals(vanillaCorners(face, from, to), CubeEmitter.corners(CubeEmitter.HALF, face), EPS, face + " corners");
			assertArrayEquals(vanillaUvs(floats(json.getAsJsonArray("uv"))), CubeEmitter.uvs(CubeEmitter.HALF, face), EPS, face + " uvs");
			assertEquals(json.has("cullface"), CubeEmitter.culls(CubeEmitter.HALF, face), face + " cull face");
		}
	}

	@Test
	void uvsShrinkTowardTheFaceCentreAsVanillaDoes() {
		float[] uv = {0, 8, 0, 16, 16, 16, 16, 8};
		float[] shrunk = CubeEmitter.shrunk(uv, 0.25F);
		assertArrayEquals(new float[] {2, 9, 2, 15, 14, 15, 14, 9}, shrunk, EPS, "lerp(0.25, uv, centre (8, 12))");
		assertArrayEquals(uv, CubeEmitter.shrunk(uv, 0), EPS);
	}

	private static float[] floats(JsonArray array) {
		float[] out = new float[array.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = array.get(i).getAsFloat();
		}
		return out;
	}
}
