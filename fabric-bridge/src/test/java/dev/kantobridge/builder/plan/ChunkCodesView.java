package dev.kantobridge.builder.plan;

import java.util.List;
import java.util.Map;

/** Renders a plan's chunks on demand and reads block codes at world positions (test helper). */
public final class ChunkCodesView {
	private final WorldPlan plan;
	private final Map<Long, ChunkCodes> chunks = new java.util.HashMap<>();

	public ChunkCodesView(WorldPlan plan) {
		this.plan = plan;
	}

	public int code(int x, int y, int z) {
		int cx = x >> 4;
		int cz = z >> 4;
		ChunkCodes codes = this.chunks.computeIfAbsent(((long) cx << 32) ^ (cz & 0xFFFFFFFFL), k -> this.plan.renderChunk(cx, cz, List.of()));
		return codes.get(x, y, z);
	}

	public int kind(int x, int y, int z) {
		return BlockCode.kind(this.code(x, y, z));
	}
}
