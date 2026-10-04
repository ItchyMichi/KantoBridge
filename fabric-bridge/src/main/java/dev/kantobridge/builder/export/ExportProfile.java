package dev.kantobridge.builder.export;

import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Another installed mod's shape profile, as the export carries it (protocol/export/profile.schema.json,
 * docs/specs/PROFILE_PLAN.md): Dramaless Shape's {@code data/voxel_heights.lua}, as text. Not ROM-derived, but it stays
 * on this machine like the rest of the bundle; nothing here logs or reports the text, only its length and hash.
 *
 * @param mod     the mod id it was read from
 * @param version the mod's manifest version ({@code unknown} when the manifest had none fit to export)
 * @param file    the file, relative to the mod's folder
 * @param enabled whether Gen1Recomp runs the mod (the bridge only reads it, so it should be off)
 * @param text    the file's text: Lua that is only data, read by {@code scene.profile.LuaData}, never run
 */
public record ExportProfile(String mod, String version, String file, boolean enabled, String text) {
	public static final String MOD = "DRAMALESS_SHAPE";
	public static final String FILE = "data/voxel_heights.lua";
	/** The resource id ({@code profile/dramaless}). */
	public static final String ID = "dramaless";
	private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z.+_-]{1,32}$");
	private static final int MAX_TEXT = 1024 * 1024;

	static ExportProfile parse(DataReader r) throws DataException {
		r.allowOnly(Set.of("mod", "version", "file", "enabled", "text"));
		String mod = r.string("mod");
		if (!MOD.equals(mod)) {
			throw new DataException(r.path("mod"), "an unknown profile mod");
		}
		String file = r.string("file");
		if (!FILE.equals(file)) {
			throw new DataException(r.path("file"), "an unknown profile file");
		}
		String text = r.string("text");
		if (text.isEmpty() || text.length() > MAX_TEXT) {
			throw new DataException(r.path("text"), "a profile text of an impossible length");
		}
		return new ExportProfile(mod, r.string("version", VERSION), file, r.bool("enabled"), text);
	}
}
