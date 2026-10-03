package dev.kantobridge.builder.export;

import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * The exported surface layout (protocol/export/layout.schema.json): block origins of the surface maps relative to the
 * root ({@code PALLET_TOWN}), from the engine's own neighbour arithmetic. The builder computes its own layout and
 * cross-checks it against this one.
 */
public record ExportLayout(String root, Map<String, Rect> maps) {
	/** Origin and size in Gen 1 blocks. */
	public record Rect(int x, int y, int w, int h) {
	}

	public ExportLayout {
		maps = Collections.unmodifiableMap(new TreeMap<>(maps));
	}

	static ExportLayout parse(DataReader r) throws DataException {
		Map<String, Rect> maps = new TreeMap<>();
		DataReader m = r.object("maps");
		for (String id : m.json().keySet()) {
			if (!DataReader.MAP_ID.matcher(id).matches()) {
				throw new DataException(m.path(), "bad map id");
			}
			DataReader rect = m.object(id);
			maps.put(id, new Rect(rect.integer("x", -4096, 4096), rect.integer("y", -4096, 4096), rect.integer("w", 1, 255),
					rect.integer("h", 1, 255)));
		}
		return new ExportLayout(r.string("root", DataReader.MAP_ID), maps);
	}
}
