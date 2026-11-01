package dev.kantobridge.builder.shape;

import com.google.gson.JsonObject;
import dev.kantobridge.scene.SceneMap;
import java.util.Set;

/** Five exported berry-body source drawings; no generic object/terrain eligibility exception. */
final class FittedBerrySoil {
	private FittedBerrySoil() { }
	private static final Set<String> KEYS = Set.of("11197-11467-11204-11207", "11204-11205-11205-11204",
			"11205-11204-11220-11221", "11460-11198-11462-11205", "11463-11464-11465-11466");
	static boolean shallow(JsonObject box) {
		if (!box.has("parts") || box.getAsJsonArray("parts").isEmpty()) return false;
		for (var value : box.getAsJsonArray("parts")) {
			if (!value.isJsonObject()) return false;
			var part = value.getAsJsonObject();
			if (!part.has("shape") || !"box".equals(part.get("shape").getAsString()) || part.has("carve")
					|| !part.has("y") || !part.get("y").isJsonArray() || part.getAsJsonArray("y").size() != 2) return false;
			double low = part.getAsJsonArray("y").get(0).getAsDouble(), high = part.getAsJsonArray("y").get(1).getAsDouble();
			if (!(low == 0 && high > 0 && high <= 4)) return false;
		}
		return true;
	}

	static boolean matches(SceneMap map, int cx, int cy) {
		if (!"g3q".equals(map.atlas()) || map.perCell() != 2) return false;
		String key = map.tile(cx*2,cy*2)+"-"+map.tile(cx*2+1,cy*2)+"-"+map.tile(cx*2,cy*2+1)+"-"+map.tile(cx*2+1,cy*2+1);
		if (!KEYS.contains(key)) return false;
		var objects = map.objects().stream().filter(o -> o.x() == cx && o.y() == cy).toList();
		if (objects.size() != 1) return false;
		var berry = objects.getFirst();
		return "OBJ_EVENT_GFX_BERRY_TREE".equals(berry.sprite()) && berry.stays() && !berry.hidden()
				&& SceneMap.SceneObject.PERSON.equals(berry.kind()) && berry.name().matches("O[0-9]+");
	}
}
