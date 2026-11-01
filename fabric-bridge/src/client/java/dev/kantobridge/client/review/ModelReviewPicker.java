package dev.kantobridge.client.review;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.builder.shape.geom.FaceMasks;
import dev.kantobridge.builder.shape.geom.ShapeMesh;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.client.model.ClosedArt;
import dev.kantobridge.client.model.RenderSnapshot;
import dev.kantobridge.client.shape.ClientShapes;
import dev.kantobridge.client.world.ClientRegions;
import dev.kantobridge.review.ReviewRaycast;
import dev.kantobridge.review.ReviewShapeContext;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.ShapeMap;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Independent visual picking for review mode; never changes gameplay collision or Minecraft's normal hit result. */
public final class ModelReviewPicker {
	public static final double REACH = 16;
	private static final Map<Block, KbBlocks.Carrier> CARRIERS = new IdentityHashMap<>();

	/** The outline is the selected mesh fragment, not a promise that a whole building was selected. */
	public record Selection(AABB bounds, JsonObject context, String label, String modelIdentity, BlockPos anchor,
			@Nullable Supplier<String> shapeSource, @Nullable WorldIndex worldIndex) {
		public Selection {
			context = context.deepCopy();
			anchor = anchor.immutable();
		}

		@Override
		public JsonObject context() {
			return this.context.deepCopy();
		}
	}

	private record Hit(BlockPos block, int id, boolean closed, double distance, AABB bounds) {
	}

	private ModelReviewPicker() {
	}

	/** A lightweight hover selection. No export, shape-document inflation, filesystem or bridge request is performed. */
	public static @Nullable Selection pick(Minecraft minecraft) {
		if (minecraft.level == null || minecraft.getCameraEntity() == null) {
			return null;
		}
		if (CARRIERS.isEmpty()) {
			for (KbBlocks.Carrier carrier : KbBlocks.carriers()) {
				CARRIERS.put(carrier.block(), carrier);
			}
		}
		net.minecraft.client.Camera camera = minecraft.gameRenderer.getMainCamera();
		Vec3 eye = camera.getPosition();
		Vec3 direction = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot()).normalize();
		Vec3 end = eye.add(direction.scale(REACH));
		RenderSnapshot snapshot = RenderSnapshot.current();
		Hit best = null;
		double obstruction = REACH + 1;
		for (long packed : cells(eye, direction)) {
			BlockPos pos = BlockPos.of(packed);
			if (!minecraft.level.hasChunkAt(pos)) {
				continue;
			}
			BlockState state = minecraft.level.getBlockState(pos);
			KbBlocks.Carrier carrier = CARRIERS.get(state.getBlock());
			if (carrier == null) {
				BlockHitResult hit = state.getShape(minecraft.level, pos).clip(eye, end, pos);
				if (hit != null) {
					obstruction = Math.min(obstruction, hit.getLocation().distanceTo(eye));
				}
				continue;
			}
			int id = snapshot.shapeAt(pos.getX(), pos.getY(), pos.getZ());
			int closed = carrier.kind().carriesStoryArt() ? snapshot.closedIdAt(pos.getX(), pos.getY(), pos.getZ()) : 0;
			for (int pass = 0; pass < 2; pass++) {
				int selected = pass == 0 ? id : closed;
				boolean overlay = pass == 1;
				ShapeMesh mesh = selected == 0 ? null : snapshot.mesh(selected);
				double distance;
				AABB bounds;
				if (mesh != null) {
					distance = ReviewRaycast.mesh(mesh, eye.x - pos.getX(), eye.y - pos.getY(), eye.z - pos.getZ(),
							direction.x, direction.y, direction.z, REACH, q -> {
								int cull = mesh.cull(q);
								if (overlay || cull < 0) {
									return true;
								}
								BlockPos neighbour = pos.offset(FaceMasks.DX[cull], FaceMasks.DY[cull], FaceMasks.DZ[cull]);
								if (minecraft.level.getBlockState(neighbour).isSolidRender(minecraft.level, neighbour)) {
									return false;
								}
								return !FaceMasks.culled(mesh, q, snapshot.mesh(snapshot.shapeAt(neighbour.getX(), neighbour.getY(), neighbour.getZ())));
							});
					bounds = Double.isFinite(distance) ? bounds(mesh, pos) : null;
				} else if (overlay && closed != 0) {
					ClosedArt art = snapshot.closedArt();
					int[] info = art == null ? null : art.at(pos.getX(), pos.getY(), pos.getZ());
					boolean flat = info != null && art.flat(info[0]);
					bounds = new AABB(pos.getX(), pos.getY() + (flat ? 1.0 / 16 : 0), pos.getZ(), pos.getX() + 1,
							pos.getY() + (flat ? 1.0 / 16 : 1), pos.getZ() + 1);
					distance = bounds.clip(eye, end).map(point -> point.distanceTo(eye)).orElse(Double.POSITIVE_INFINITY);
				} else if (!overlay && carrier.kind().drawsCube()) {
					bounds = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1,
							pos.getY() + (carrier.kind().half() ? 0.5 : 1), pos.getZ() + 1);
					distance = bounds.clip(eye, end).map(point -> point.distanceTo(eye)).orElse(Double.POSITIVE_INFINITY);
				} else {
					continue;
				}
				if (Double.isFinite(distance) && (best == null || distance < best.distance() - 1.0E-7
						|| overlay && Math.abs(distance - best.distance()) < 1.0E-7)) {
					best = new Hit(pos, selected, overlay, distance, bounds);
				}
			}
		}
		return best == null || best.distance() > obstruction + 1.0E-7 ? null : selection(minecraft, snapshot, best, eye, direction);
	}

	/** DDA cells and a one-block halo: vines can extend outside their own carrier block. */
	private static Set<Long> cells(Vec3 eye, Vec3 direction) {
		Set<Long> cells = new HashSet<>();
		int[] at = {(int) Math.floor(eye.x), (int) Math.floor(eye.y), (int) Math.floor(eye.z)};
		double[] origin = {eye.x, eye.y, eye.z};
		double[] delta = {direction.x, direction.y, direction.z};
		int[] step = new int[3];
		double[] next = new double[3];
		double[] stride = new double[3];
		for (int axis = 0; axis < 3; axis++) {
			step[axis] = delta[axis] > 0 ? 1 : delta[axis] < 0 ? -1 : 0;
			stride[axis] = step[axis] == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(delta[axis]);
			next[axis] = step[axis] == 0 ? Double.POSITIVE_INFINITY
					: (step[axis] > 0 ? at[axis] + 1 - origin[axis] : origin[axis] - at[axis]) * stride[axis];
		}
		for (int visited = 0; visited < 64; visited++) {
			for (int x = -1; x <= 1; x++) {
				for (int y = -1; y <= 1; y++) {
					for (int z = -1; z <= 1; z++) {
						cells.add(BlockPos.asLong(at[0] + x, at[1] + y, at[2] + z));
					}
				}
			}
			double distance = Math.min(next[0], Math.min(next[1], next[2]));
			if (distance > REACH) {
				break;
			}
			for (int axis = 0; axis < 3; axis++) {
				if (next[axis] <= distance + 1.0E-9) {
					at[axis] += step[axis];
					next[axis] += stride[axis];
				}
			}
		}
		return cells;
	}

	private static Selection selection(Minecraft minecraft, RenderSnapshot snapshot, Hit hit, Vec3 eye, Vec3 direction) {
		BlockPos pos = hit.block();
		RegionRecord region = hit.closed() ? ClientRegions.followed() : ClientRegions.at(pos.getX(), pos.getY(), pos.getZ());
		String regionId = region == null ? null : region.id();
		WorldIndex index = ClientRegions.indexOf(regionId);
		ShapeMap map = ClientShapes.map(regionId);
		int shapeId = hit.id() & 0xFFFFFF;
		JsonObject context = new JsonObject();
		context.addProperty("scope", "surface");
		context.add("block", array(pos.getX(), pos.getY(), pos.getZ()));
		context.add("bounds", array(hit.bounds().minX, hit.bounds().minY, hit.bounds().minZ, hit.bounds().maxX, hit.bounds().maxY, hit.bounds().maxZ));
		Vec3 point = eye.add(direction.scale(hit.distance()));
		context.add("hit", array(point.x, point.y, point.z));
		context.addProperty("blockId", BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(pos).getBlock()).toString());
		context.addProperty("shapeId", shapeId);
		context.addProperty("closedStoryArt", hit.closed());
		context.addProperty("geometryVersion", ShapeTable.GEOMETRY);
		context.addProperty("renderGeneration", snapshot.generation());
		if (regionId != null) {
			context.addProperty("region", regionId);
			context.addProperty("worldIndexHash", ClientRegions.indexHash(regionId));
		}
		if (index != null) {
			context.addProperty("game", index.game());
			addCell(context, cellAt(index, pos));
		}
		if (map != null) {
			context.addProperty("shapeIndexHash", map.shapeHash());
			context.addProperty("shapeMapHash", map.hash());
		}
		if (hit.closed()) {
			ClosedArt art = snapshot.closedArt();
			int[] info = art == null ? null : art.at(pos.getX(), pos.getY(), pos.getZ());
			if (info != null) {
				context.addProperty("storyCell", art.key(info[0]));
				addCell(context, CellPos.parse(art.key(info[0])));
			}
		}
		KbBlocks.Carrier carrier = CARRIERS.get(minecraft.level.getBlockState(pos).getBlock());
		if (carrier != null) {
			context.addProperty("carrier", carrier.kind().name());
			if (carrier.kind().hasSlot()) {
				context.addProperty("slot", carrier.slotIndex(minecraft.level.getBlockState(pos)));
			}
		}
		String identity = (regionId == null ? "unknown" : regionId) + ":" + (map == null ? "unshaped" : map.shapeHash())
				+ ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ() + (hit.closed() ? ":closed" : ":base");
		context.addProperty("modelIdentity", identity);
		String label = (shapeId == 0 ? "Tile surface" : "Model surface") + (context.has("map") ? " — " + context.get("map").getAsString() : "");
		return new Selection(hit.bounds(), context, label, identity, pos, shapeId == 0 ? null : ClientRegions.shapeIndexSource(regionId), index);
	}

	/** Resolve a captured selection off-thread. A later world change cannot retarget its document or coordinates. */
	public static JsonObject describe(Selection selection) {
		JsonObject context = selection.context();
		if (selection.shapeSource() == null) {
			context.addProperty("provenance", "surface_only");
			return context;
		}
		try {
			context = ReviewShapeContext.describe(selection.shapeSource().get(), context.get("shapeId").getAsInt(), context);
			if (context.has("modelOrigin") && selection.worldIndex() != null) {
				JsonArray origin = context.getAsJsonArray("modelOrigin");
				CellPos cell = cellAt(selection.worldIndex(), new BlockPos(origin.get(0).getAsInt(), origin.get(1).getAsInt(), origin.get(2).getAsInt()));
				if (cell != null) {
					context.addProperty("modelCell", cell.key());
				}
			}
		} catch (IOException | RuntimeException e) {
			context.addProperty("provenance", "unavailable");
		}
		return context;
	}

	/** Includes roofs/cliffs above ordinary walking reach, and displaced cave pieces. */
	static @Nullable CellPos cellAt(WorldIndex index, BlockPos pos) {
		CellPos direct = index.toCell(pos).orElse(null);
		if (direct != null) {
			return direct;
		}
		CellPos best = null;
		int closest = Integer.MAX_VALUE;
		for (MapPlacement map : index.maps().values()) {
			MapLayout layout = index.layout(map.id());
			if (layout == null) {
				int distance = pos.getY() - map.y();
				if (map.containsColumn(pos.getX(), pos.getZ()) && distance >= -1 && distance < 128 && distance < closest) {
					closest = distance;
					best = new CellPos(map.id(), Scale.blockToCell(pos.getX() - map.x()), Scale.blockToCell(pos.getZ() - map.z()));
				}
			} else {
				for (MapLayout.Piece piece : layout.pieces()) {
					int cx = Scale.blockToCell(pos.getX() - map.x() - piece.dx());
					int cy = Scale.blockToCell(pos.getZ() - map.z() - piece.dz());
					if (!piece.contains(cx, cy)) {
						continue;
					}
					int distance = pos.getY() - map.y() - piece.dy() - layout.level(cx, cy);
					if (distance >= -1 && distance < 128 && distance < closest) {
						closest = distance;
						best = new CellPos(map.id(), cx, cy);
					}
				}
			}
		}
		return best;
	}

	private static void addCell(JsonObject context, @Nullable CellPos cell) {
		if (cell != null) {
			context.addProperty("map", cell.map());
			context.add("cell", array(cell.x(), cell.y()));
			context.addProperty("cellKey", cell.key());
		}
	}

	private static AABB bounds(ShapeMesh mesh, BlockPos pos) {
		double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
		double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
		for (int q = 0; q < mesh.size(); q++) {
			for (int v = 0; v < 4; v++) {
				minX = Math.min(minX, mesh.x(q, v) / 16.0); maxX = Math.max(maxX, mesh.x(q, v) / 16.0);
				minY = Math.min(minY, mesh.y(q, v) / 16.0); maxY = Math.max(maxY, mesh.y(q, v) / 16.0);
				minZ = Math.min(minZ, mesh.z(q, v) / 16.0); maxZ = Math.max(maxZ, mesh.z(q, v) / 16.0);
			}
		}
		return new AABB(minX + pos.getX(), minY + pos.getY(), minZ + pos.getZ(), maxX + pos.getX(), maxY + pos.getY(), maxZ + pos.getZ());
	}

	private static JsonArray array(double... numbers) {
		JsonArray out = new JsonArray();
		for (double number : numbers) {
			out.add(number);
		}
		return out;
	}
}
