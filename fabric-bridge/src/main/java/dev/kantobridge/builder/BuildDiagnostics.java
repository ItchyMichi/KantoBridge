package dev.kantobridge.builder;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.plan.Warnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code build_diagnostics.json}, written beside {@code build_report.json}: for each warning or note code that names
 * what it is about, the rows of engine symbols (map ids, swap kinds, event flags, layers, a site's position in the
 * export's list; never coordinates, block ids or game text). It answers "which maps?" for a report's counts without
 * anyone opening game data (docs/PLAN.md §0.3), and it never goes to the logs.
 */
public final class BuildDiagnostics {
	public static final String FILE = "build_diagnostics.json";
	public static final int VERSION = 1;

	private BuildDiagnostics() {
	}

	public static JsonObject toJson(Warnings warnings, int mapsUnplaced) {
		JsonObject out = new JsonObject();
		out.addProperty("version", VERSION);
		JsonObject counts = new JsonObject();
		new TreeMap<>(warnings.warnings()).forEach(counts::addProperty);
		out.add("warnings", counts);
		JsonObject notes = new JsonObject();
		new TreeMap<>(warnings.notes()).forEach(notes::addProperty);
		out.add("notes", notes);
		out.addProperty("maps_unplaced", mapsUnplaced);
		JsonObject details = new JsonObject();
		for (Map.Entry<String, List<Map<String, String>>> code : warnings.details().entrySet()) {
			JsonArray rows = new JsonArray();
			for (Map<String, String> row : code.getValue()) {
				JsonObject item = new JsonObject();
				new TreeMap<>(row).forEach(item::addProperty);
				rows.add(item);
			}
			details.add(code.getKey(), rows);
		}
		out.add("details", details);
		return out;
	}

	public static String toText(Warnings warnings, int mapsUnplaced) {
		return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(toJson(warnings, mapsUnplaced)) + "\n";
	}

	/** Writes the diagnostics next to {@code reportFile} (a {@code build_report.json} path). */
	public static void writeBeside(Path reportFile, Warnings warnings, int mapsUnplaced) throws IOException {
		Path parent = reportFile.toAbsolutePath().getParent();
		Path file = parent == null ? Path.of(FILE) : parent.resolve(FILE);
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.writeString(file, toText(warnings, mapsUnplaced), StandardCharsets.UTF_8);
	}
}
