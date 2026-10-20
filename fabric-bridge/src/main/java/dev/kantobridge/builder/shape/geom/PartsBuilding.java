package dev.kantobridge.builder.shape.geom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * A building described as parts (docs/specs/BUILDING_PARTS.md): boxes, cylinders, gable and hip roofs, domes, cones,
 * barrels, profile roofs and carves, built into voxels and painted from the building's own drawing. The same geometry
 * and painting as tools/shape_fit/emerald/parts_model.py, which the agents check their specs with; the table holds only
 * the spec's numbers (part sizes in drawing pixels and voxels, paint rectangles of the drawing), so every client paints
 * from its own tiles. One voxel per drawing pixel, x east, y up, z south, z = 0 the grid's north edge.
 *
 * <pre>
 * parts     in order; later parts add to the earlier ones, or cut ("carve": true)
 * paint     a voxel shows the rectangle of its most open face: up ("top"; on a barrel or profile roof the rectangle laid
 *           along the curve), south ("front"), east/west ("side", default the front's outer columns), north ("back",
 *           default "side"); a round part's front wraps round its visible half by angle
 * </pre>
 */
public final class PartsBuilding {
	private static final int[][] FACES = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}, {-1, 0, 0}}; // dy dz dx
	private static final String[] FACE = {"up", "south", "east", "west", "north", "down"};

	private PartsBuilding() {
	}

	/** The model's height in voxels ("height", else 64). */
	public static int height(JsonObject spec) {
		return spec.has("height") ? Math.max(1, spec.get("height").getAsInt()) : 64;
	}

	/** One part, its numbers read once. */
	private record Part(String shape, double[] x, double[] z, double[] y, double[] center, double rx, double rz, String axis,
			double[] profile, double[] cross, boolean carve, JsonObject paint) {
		static Part of(JsonObject o) {
			String shape = o.get("shape").getAsString();
			double rx = 0;
			double rz = 0;
			if (o.has("radius")) {
				JsonElement r = o.get("radius");
				if (r.isJsonArray()) {
					rx = r.getAsJsonArray().get(0).getAsDouble();
					rz = r.getAsJsonArray().get(1).getAsDouble();
				} else {
					rx = r.getAsDouble();
					rz = rx;
				}
			}
			String axis = o.has("ridge") ? o.get("ridge").getAsString() : o.has("along") ? o.get("along").getAsString() : "x";
			double[] profile = nums(o, "profile");
			if ("barrel".equals(shape)) {
				double[] y = nums(o, "y");
				double h = y[1] - y[0];
				int n = 33;
				profile = new double[n];
				for (int i = 0; i < n; i++) {
					double t = 2.0 * i / (n - 1) - 1;
					profile[i] = h * Math.sqrt(Math.max(0, 1 - t * t));
				}
			}
			return new Part(shape, nums(o, "x"), nums(o, "z"), nums(o, "y"), nums(o, "center"), rx, rz, axis, profile, nums(o, "cross"),
					o.has("carve") && o.get("carve").getAsBoolean(), o.has("paint") ? o.getAsJsonObject("paint") : new JsonObject());
		}

		boolean round() {
			return "cylinder".equals(this.shape) || "dome".equals(this.shape) || "cone".equals(this.shape);
		}

		boolean curved() {
			return "profile".equals(this.shape) || "barrel".equals(this.shape);
		}

		/** West, east, north and south bounds. */
		double[] bounds() {
			if (this.x != null) {
				return new double[] {this.x[0], this.x[1], this.z[0], this.z[1]};
			}
			return new double[] {this.center[0] - this.rx, this.center[0] + this.rx, this.center[1] - this.rz, this.center[1] + this.rz};
		}

		boolean inside(int vx, int vy, int vz) {
			double cx = vx + 0.5;
			double cy = vy + 0.5;
			double cz = vz + 0.5;
			switch (this.shape) {
				case "box", "gable", "hip" -> {
					if (cx < this.x[0] || cx > this.x[1] || cz < this.z[0] || cz > this.z[1] || cy < this.y[0] || cy > this.y[1]) {
						return false;
					}
					if ("box".equals(this.shape)) {
						return true;
					}
					double t = (cy - this.y[0]) / Math.max(1e-6, this.y[1] - this.y[0]);
					if ("gable".equals(this.shape)) {
						if ("x".equals(this.axis)) {
							return Math.abs(cz - (this.z[0] + this.z[1]) / 2) <= (this.z[1] - this.z[0]) / 2 * (1 - t) + 1e-6;
						}
						return Math.abs(cx - (this.x[0] + this.x[1]) / 2) <= (this.x[1] - this.x[0]) / 2 * (1 - t) + 1e-6;
					}
					double hx = (this.x[1] - this.x[0]) / 2;
					double hz = (this.z[1] - this.z[0]) / 2;
					double inset = Math.min(hx, hz) * t;
					return Math.abs(cx - (this.x[0] + this.x[1]) / 2) <= hx - inset + 1e-6
							&& Math.abs(cz - (this.z[0] + this.z[1]) / 2) <= hz - inset + 1e-6;
				}
				case "profile", "barrel" -> {
					if (cx < this.x[0] || cx > this.x[1] || cz < this.z[0] || cz > this.z[1] || cy < this.y[0]) {
						return false;
					}
					double tz = (cz - this.z[0]) / Math.max(1e-6, this.z[1] - this.z[0]);
					double tx = (cx - this.x[0]) / Math.max(1e-6, this.x[1] - this.x[0]);
					boolean alongX = "x".equals(this.axis);
					double h = sample(this.profile, alongX ? tz : tx);
					if (this.cross != null && this.cross.length > 0) {
						h = Math.min(h, sample(this.cross, alongX ? tx : tz));
					}
					return cy - this.y[0] <= h;
				}
				default -> {
					if (cy < this.y[0] || cy > this.y[1]) {
						return false;
					}
					double dx = (cx - this.center[0]) / this.rx;
					double dz = (cz - this.center[1]) / this.rz;
					double d = Math.sqrt(dx * dx + dz * dz);
					return switch (this.shape) {
						case "cylinder" -> d <= 1;
						case "dome" -> {
							double hy = (cy - this.y[0]) / Math.max(1e-6, this.y[1] - this.y[0]);
							yield d * d + hy * hy <= 1;
						}
						case "cone" -> d <= 1 - (cy - this.y[0]) / Math.max(1e-6, this.y[1] - this.y[0]);
						default -> false;
					};
				}
			}
		}
	}

	private static double[] nums(JsonObject o, String key) {
		if (!o.has(key) || !o.get(key).isJsonArray()) {
			return null;
		}
		JsonArray a = o.getAsJsonArray(key);
		double[] out = new double[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).getAsDouble();
		}
		return out;
	}

	/** A profile's height at fraction t (0..1) across it, linearly between its points. */
	static double sample(double[] prof, double t) {
		if (prof == null || prof.length == 0) {
			return 0;
		}
		if (prof.length == 1) {
			return prof[0];
		}
		double f = Math.max(0, Math.min(1, t)) * (prof.length - 1);
		int i = Math.min((int) Math.floor(f), prof.length - 2);
		double k = f - i;
		return prof[i] * (1 - k) + prof[i + 1] * k;
	}

	/** Cumulative surface length along a profile (0..1) at its points, for paint that follows the curve. */
	private static double[] arc(double[] prof, double span) {
		if (prof == null || prof.length < 2) {
			return new double[] {0, 1};
		}
		double step = span / (prof.length - 1);
		double[] c = new double[prof.length];
		for (int i = 1; i < prof.length; i++) {
			double dh = prof[i] - prof[i - 1];
			c[i] = c[i - 1] + Math.sqrt(step * step + dh * dh);
		}
		double total = Math.max(1e-6, c[prof.length - 1]);
		for (int i = 0; i < c.length; i++) {
			c[i] /= total;
		}
		return c;
	}

	private static double interp(double t, double[] a) {
		double f = Math.max(0, Math.min(1, t)) * (a.length - 1);
		int i = Math.min((int) Math.floor(f), a.length - 2);
		double k = f - i;
		return a[i] * (1 - k) + a[i + 1] * k;
	}

	/** The drawing pixel (index z * w + x) at fractions (a across, b down) of a rectangle. */
	private static int pick(double[] rect, double a, double b, int w, int d) {
		int u = (int) (rect[0] + Math.min(0.999, Math.max(0.0, a)) * (rect[2] - rect[0]));
		int v = (int) (rect[1] + Math.min(0.999, Math.max(0.0, b)) * (rect[3] - rect[1]));
		return Math.max(0, Math.min(d - 1, v)) * w + Math.max(0, Math.min(w - 1, u));
	}

	/**
	 * The voxels of a parts spec over a drawing w x d pixels (a texel per pixel), its grid {@code px} voxels a block.
	 * Null when the spec has no parts.
	 */
	public static VoxelGrid voxels(JsonObject spec, int[] texel, int w, int d, int px) {
		List<Part> parts = new ArrayList<>();
		if (spec.has("parts")) {
			for (JsonElement el : spec.getAsJsonArray("parts")) {
				parts.add(Part.of(el.getAsJsonObject()));
			}
		}
		if (parts.isEmpty()) {
			return null;
		}
		int h = height(spec);
		int[] owner = new int[w * h * d];
		java.util.Arrays.fill(owner, -1);
		for (int i = 0; i < parts.size(); i++) {
			Part p = parts.get(i);
			double[] b = p.bounds();
			double top = p.y != null && p.y.length > 1 ? p.y[1] : h;
			if (p.curved() && p.profile != null) {
				double m = 0;
				for (double v : p.profile) {
					m = Math.max(m, v);
				}
				top = p.y[0] + m;
			}
			int x0 = Math.max(0, (int) Math.floor(b[0]) - 1);
			int x1 = Math.min(w - 1, (int) Math.ceil(b[1]));
			int z0 = Math.max(0, (int) Math.floor(b[2]) - 1);
			int z1 = Math.min(d - 1, (int) Math.ceil(b[3]));
			int y0 = Math.max(0, (int) Math.floor(p.y[0]) - 1);
			int y1 = Math.min(h - 1, (int) Math.ceil(top));
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					for (int x = x0; x <= x1; x++) {
						if (p.inside(x, y, z)) {
							owner[(y * d + z) * w + x] = p.carve ? -1 : i;
						}
					}
				}
			}
		}
		int gh = Math.max(px, ((h + px - 1) / px) * px);
		VoxelGrid grid = new VoxelGrid(w, gh, d, px);
		for (int y = 0; y < h; y++) {
			for (int z = 0; z < d; z++) {
				for (int x = 0; x < w; x++) {
					int o = owner[(y * d + z) * w + x];
					if (o < 0) {
						continue;
					}
					grid.set(x, y, z, texel[paint(parts.get(o), owner, x, y, z, w, h, d)]);
				}
			}
		}
		return grid;
	}

	/** The drawing pixel a voxel shows (parts_model.build's painting). */
	private static int paint(Part part, int[] owner, int x, int y, int z, int w, int h, int d) {
		JsonObject paint = part.paint;
		double[] b = part.bounds();
		String face = "south";
		for (int f = 0; f < FACES.length; f++) {
			int ny = y + FACES[f][0];
			int nz = z + FACES[f][1];
			int nx = x + FACES[f][2];
			if (ny < 0 || ny >= h || nz < 0 || nz >= d || nx < 0 || nx >= w || owner[(ny * d + nz) * w + nx] < 0) {
				face = FACE[f];
				break;
			}
		}
		double[] front = nums(paint, "front");
		double[] topRect = nums(paint, "top");
		if (front == null) {
			front = topRect != null ? topRect : new double[] {0, 0, w, d};
		}
		double across = (x + 0.5 - b[0]) / Math.max(1e-6, b[1] - b[0]);
		double south = (z + 0.5 - b[2]) / Math.max(1e-6, b[3] - b[2]);
		boolean wrap = !paint.has("wrap") || paint.get("wrap").getAsBoolean();
		if (part.round() && wrap && ("south".equals(face) || "east".equals(face) || "west".equals(face))) {
			double ang = Math.atan2((x + 0.5 - part.center[0]) / part.rx, (z + 0.5 - part.center[1]) / part.rz);
			across = 0.5 + Math.max(-Math.PI / 2, Math.min(Math.PI / 2, ang)) / Math.PI;
			face = "south";
		}
		double down = 1 - (y + 0.5 - part.y[0]) / Math.max(1e-6, part.y[1] - part.y[0]);
		if (part.curved() && part.y.length < 2) {
			down = 0.5;
		}
		if ("up".equals(face) && topRect != null && part.curved()) {
			boolean alongX = "x".equals(part.axis);
			double t = alongX ? south : across;
			double span = alongX ? b[3] - b[2] : b[1] - b[0];
			double f = interp(t, arc(part.profile, span));
			return alongX ? pick(topRect, across, f, w, d) : pick(topRect, f, south, w, d);
		}
		if ("up".equals(face) && topRect != null) {
			return pick(topRect, across, south, w, d);
		}
		if ("east".equals(face) || "west".equals(face)) {
			double[] side = nums(paint, "side");
			return side != null ? pick(side, south, down, w, d) : pick(front, "west".equals(face) ? 0.02 : 0.98, down, w, d);
		}
		if ("north".equals(face)) {
			double[] back = nums(paint, "back");
			if (back == null) {
				back = nums(paint, "side");
			}
			return back != null ? pick(back, 1 - across, down, w, d) : pick(front, 0.5, down, w, d);
		}
		return pick(front, across, down, w, d);
	}
}
