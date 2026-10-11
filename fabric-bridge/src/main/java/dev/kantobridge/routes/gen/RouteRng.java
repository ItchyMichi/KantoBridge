package dev.kantobridge.routes.gen;

import java.util.Collections;
import java.util.List;

/**
 * The generators' random numbers (docs/specs/WILD_ROUTES_PLAN.md §4.3): xoshiro256** seeded through SplitMix64,
 * written out here rather than taken from the JDK, so one seed makes the same zone on every JVM and every Java version.
 * A world keeps its zone for life, and the tests pin layouts by their fingerprints.
 *
 * <p>Not thread-safe: one generator per layout.
 */
public final class RouteRng {
	private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

	private long s0;
	private long s1;
	private long s2;
	private long s3;

	public RouteRng(long seed) {
		long x = seed;
		x += GOLDEN_GAMMA;
		this.s0 = mix(x);
		x += GOLDEN_GAMMA;
		this.s1 = mix(x);
		x += GOLDEN_GAMMA;
		this.s2 = mix(x);
		x += GOLDEN_GAMMA;
		this.s3 = mix(x);
	}

	/** SplitMix64's finaliser: a bijective scramble of 64 bits. */
	public static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	public long nextLong() {
		long result = Long.rotateLeft(this.s1 * 5, 7) * 9;
		long t = this.s1 << 17;
		this.s2 ^= this.s0;
		this.s3 ^= this.s1;
		this.s1 ^= this.s2;
		this.s0 ^= this.s3;
		this.s2 ^= t;
		this.s3 = Long.rotateLeft(this.s3, 45);
		return result;
	}

	/** A uniform int in {@code [0, bound)}, without modulo bias (Lemire's multiply-and-reject). */
	public int nextInt(int bound) {
		if (bound <= 0) {
			throw new IllegalArgumentException("bound must be positive: " + bound);
		}
		long r = this.nextLong() >>> 32;
		long m = r * bound;
		long low = m & 0xFFFFFFFFL;
		if (low < bound) {
			long threshold = (0x100000000L - bound) % bound;
			while (low < threshold) {
				r = this.nextLong() >>> 32;
				m = r * bound;
				low = m & 0xFFFFFFFFL;
			}
		}
		return (int) (m >>> 32);
	}

	/** A uniform int in {@code [lo, hi]}, both included. */
	public int between(int lo, int hi) {
		if (hi < lo) {
			throw new IllegalArgumentException("empty range: " + lo + ".." + hi);
		}
		return lo + this.nextInt(hi - lo + 1);
	}

	/** A uniform double in {@code [0, 1)}. */
	public double nextDouble() {
		return (this.nextLong() >>> 11) * 0x1.0p-53;
	}

	/** A uniform double in {@code [a, b)}. */
	public double uniform(double a, double b) {
		return a + (b - a) * this.nextDouble();
	}

	/** True with probability {@code p}. */
	public boolean chance(double p) {
		return this.nextDouble() < p;
	}

	public <T> T pick(List<T> list) {
		return list.get(this.nextInt(list.size()));
	}

	/** Fisher–Yates, in place. */
	public <T> void shuffle(List<T> list) {
		for (int i = list.size() - 1; i > 0; i--) {
			Collections.swap(list, i, this.nextInt(i + 1));
		}
	}
}
