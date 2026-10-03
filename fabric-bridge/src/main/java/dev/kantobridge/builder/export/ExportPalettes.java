package dev.kantobridge.builder.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * SGB palettes (protocol/export/palettes.schema.json): name → 4 colours, lightest first (shade 0 = lightest).
 *
 * @param palettes name → {@code int[4]} of {@code 0xRRGGBB}
 */
public record ExportPalettes(Map<String, int[]> palettes) {
	public ExportPalettes {
		palettes = Collections.unmodifiableMap(new TreeMap<>(palettes));
	}

	public @Nullable int[] colours(String name) {
		return this.palettes.get(name);
	}

	static ExportPalettes parse(DataReader r) throws DataException {
		if (!"sgb".equals(r.string("mode"))) {
			throw new DataException(r.path("mode"), "unsupported palette mode");
		}
		Map<String, int[]> out = new TreeMap<>();
		DataReader p = r.object("palettes");
		for (String name : p.json().keySet()) {
			String path = p.path(name);
			if (!DataReader.SYMBOL.matcher(name).matches()) {
				throw new DataException(p.path(), "bad palette name");
			}
			JsonArray colours = p.array(name);
			if (colours.size() != 4) {
				throw new DataException(path, "a palette has 4 colours");
			}
			int[] rgb = new int[4];
			for (int i = 0; i < 4; i++) {
				JsonElement colour = colours.get(i);
				if (!colour.isJsonArray()) {
					throw new DataException(path + "/" + i, "not an array");
				}
				int[] c = DataReader.intArray(colour.getAsJsonArray(), path + "/" + i, 0, 255);
				if (c.length != 3) {
					throw new DataException(path + "/" + i, "a colour has 3 channels");
				}
				rgb[i] = (c[0] << 16) | (c[1] << 8) | c[2];
			}
			out.put(name, rgb);
		}
		return new ExportPalettes(out);
	}
}
