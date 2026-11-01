package dev.kantobridge.dev;

/** Pure geometry shared by the offline gallery's placement, framing and visibility checks. */
public final class GalleryGeometry {
	public static final double FOV = 35;
	public static final int PAD_MARGIN = 4;
	private static final int MAX_HORIZONTAL_SPAN = 128;
	private static final int MAX_FOOTPRINT = 64 * 64;
	private static final int MAX_VOLUME = 64 * 40 * 64;
	// Preserve the old 64-square pad budget, including the accepted one-block mesh margin.
	private static final int MAX_PAD_AREA = (64 + 2 * (PAD_MARGIN + 1)) * (64 + 2 * (PAD_MARGIN + 1));
	private GalleryGeometry() { }

	public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		public Bounds {
			if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
					|| !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
					|| maxX < minX || maxY < minY || maxZ < minZ) throw new IllegalArgumentException("invalid mesh bounds");
		}
		public double width() { return maxX - minX; }
		public double height() { return maxY - minY; }
		public double depth() { return maxZ - minZ; }
		public double centreX() { return (minX + maxX) / 2; }
		public double centreY() { return (minY + maxY) / 2; }
		public double centreZ() { return (minZ + maxZ) / 2; }
		public double radius() { return Math.sqrt(width() * width() + height() * height() + depth() * depth()) / 2; }
		public double distanceSquared(double x, double y, double z) {
			double dx = Math.max(Math.max(minX - x, 0), x - maxX);
			double dy = Math.max(Math.max(minY - y, 0), y - maxY);
			double dz = Math.max(Math.max(minZ - z, 0), z - maxZ);
			return dx * dx + dy * dy + dz * dz;
		}
	}

	public record Camera(double x, double y, double z, float yaw, float pitch) {
		public double distance(Bounds b) {
			return Math.sqrt(Math.pow(x - b.centreX(), 2) + Math.pow(y - b.centreY(), 2) + Math.pow(z - b.centreZ(), 2));
		}
	}

	/** Bounded input still protects the pad builder from unbounded manifest dimensions. */
	public static void validateDimensions(double width, double height, double depth) {
		double padWidth = Math.ceil(width) + 2 * (PAD_MARGIN + 1);
		double padDepth = Math.ceil(depth) + 2 * (PAD_MARGIN + 1);
		if (!(width > 0 && width <= MAX_HORIZONTAL_SPAN && height > 0 && height <= 64
				&& depth > 0 && depth <= MAX_HORIZONTAL_SPAN && width * depth <= MAX_FOOTPRINT
				&& width * height * depth <= MAX_VOLUME && padWidth * padDepth <= MAX_PAD_AREA)) {
			throw new IllegalArgumentException("invalid exhibit bounds (maximum span 128, height 64, volume 163840, footprint 4096 and padded area 5476 blocks)");
		}
	}

	public static int padMin(double meshMin) { return (int) Math.floor(Math.min(0, meshMin)) - PAD_MARGIN; }
	public static int padMax(double declaredSize, double meshMax) { return (int) Math.ceil(Math.max(declaredSize, meshMax)) + PAD_MARGIN; }

	/** Preserve the old small-model grid, expanding it to leave eight blocks between large pads. */
	public static int spacing(int smallestPadMin, int largestPadMax) {
		return Math.max(24, largestPadMax - smallestPadMin + 8);
	}

	public static Camera camera(Bounds b, String view, double aspect) {
		if (!(aspect > 0) || !Double.isFinite(aspect)) throw new IllegalArgumentException("invalid viewport aspect");
		if ("three_quarter".equals(view)) {
			double halfFov = Math.atan(Math.tan(Math.toRadians(FOV / 2)) * Math.min(1, aspect));
			double distance = Math.max(0.2, b.radius()) / Math.sin(halfFov) * 1.15;
			double horizontal = distance * Math.cos(Math.toRadians(30)) / Math.sqrt(2);
			return new Camera(b.centreX() + horizontal, b.centreY() + distance * 0.5, b.centreZ() + horizontal, 135, 30);
		}
		boolean side = "side".equals(view);
		boolean north = "north".equals(view);
		if (!side && !north && !"front".equals(view)) throw new IllegalArgumentException("unknown gallery view");
		double frame = Math.max(b.height(), (side ? b.depth() : b.width()) / aspect);
		double distance = Math.max(0.3, frame) / (2 * Math.tan(Math.toRadians(FOV / 2))) * 1.35;
		distance += (side ? b.width() : b.depth()) / 2;
		return new Camera(b.centreX() + (side ? distance : 0), b.centreY(), b.centreZ() + (side ? 0 : north ? -distance : distance), side ? 90 : north ? 0 : 180, 0);
	}

	/** Include the farthest corner, with two chunks of headroom for fog and chunk loading. */
	public static int renderDistance(Bounds bounds, double aspect) {
		double reach = 0;
		for (String view : new String[] {"front", "side", "three_quarter"}) {
			reach = Math.max(reach, camera(bounds, view, aspect).distance(bounds) + bounds.radius());
		}
		int required = Math.max(8, (int) Math.ceil(reach / 16) + 2);
		if (required > 32) throw new IllegalArgumentException("gallery viewport cannot frame this exhibit within 32 chunks; use a wider viewport or source-context sections");
		return required;
	}
}
