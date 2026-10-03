package dev.kantobridge.builder.export;

import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * A greyscale export image: a tileset atlas, an animation strip, an effect (protocol/export/image.schema.json), or an
 * overworld sprite sheet (sprite.schema.json, with {@code frames}). Four shades: white, 2/3, 1/3, black.
 *
 * @param frames sprites: 16×16 frames in a vertical strip (0 stand down, 1 up, 2 left, 3-5 walking); 0 for images
 * @param png    the PNG file bytes
 */
public record ExportImage(String id, int w, int h, int frames, byte[] png) {
	private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

	static ExportImage parseImage(DataReader r) throws DataException {
		return new ExportImage(r.string("id", ID), r.integer("w", 1, 1024), r.integer("h", 1, 1024), 0, png(r));
	}

	static ExportImage parseSprite(DataReader r) throws DataException {
		int frames = r.integer("frames", 1, 64);
		return new ExportImage(r.string("id", DataReader.SYMBOL), r.integer("w", 16, 16), r.integer("h", 16, 1024), frames, png(r));
	}

	private static byte[] png(DataReader r) throws DataException {
		try {
			return Base64.getDecoder().decode(r.string("png"));
		} catch (IllegalArgumentException e) {
			throw new DataException(r.path("png"), "not base64");
		}
	}
}
