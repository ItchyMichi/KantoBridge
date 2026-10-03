package dev.kantobridge.bag;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * One player's bookkeeping between the hidden game's bag and the Minecraft inventory (docs/PLAN.md P17): the bag is
 * authoritative, its battle items are mirrored into the inventory, and what Minecraft consumes goes back as
 * {@code bag.use}. Plain Java; counts are by Gen 1 item symbol ({@link BagItems}), times in server ticks.
 *
 * <ul>
 * <li>A {@code bag.sync} carries the whole mirrored bag (absent items: none left) and names the change per item that
 * makes the inventory match; a sync that matches changes nothing, so the mirror never echoes. After applying it, the
 * inventory is the new reference.</li>
 * <li>Fewer items than the reference were consumed (a thrown ball, a potion): reported once, and the reference drops.
 * More than the reference (a missed ball picked up again, a refund) is never reported and never raises the reference:
 * the hidden game already paid for it, and its next sync takes the extra back.</li>
 * <li>While {@code hold} (a battle: Cobblemon takes an item when it is chosen and gives it back if the turn never came)
 * nothing is reported; the net change is reported once the battle is over.</li>
 * <li><b>Uses in flight.</b> A sync can cross {@code bag.use}s the hidden game hasn't applied yet (gen1-bridge applies a
 * use, never below zero, and answers with a fresh sync). Reported uses wait in flight until a sync's count falls by
 * them; meanwhile a sync's target is its count minus what is still in flight, so a crossing sync neither gives a used
 * item back nor counts it twice. A use no sync acknowledges within {@link #IN_FLIGHT_TICKS} (lost with a link) is
 * dropped, and the last bag applies again.</li>
 * </ul>
 */
public final class BagLedger {
	/** The largest count a bag entry may carry (protocol {@code bag.sync}). */
	public static final int MAX = 9999;
	/** How long a reported use may wait for a sync to show it: a minute (a reconnecting link queues uses that long). */
	public static final long IN_FLIGHT_TICKS = 1200;

	/** One reported use not yet seen applied. */
	private static final class Use {
		int qty;
		final long at;

		Use(int qty, long at) {
			this.qty = qty;
			this.at = at;
		}
	}

	private @Nullable Map<String, Integer> reference;
	/** The counts of the last sync, as the hidden game sent them. */
	private @Nullable Map<String, Integer> lastBag;
	private final Map<String, Deque<Use>> inFlight = new HashMap<>();

	/** True once a sync has set the reference. */
	public boolean known() {
		return this.reference != null;
	}

	/**
	 * The change per mirrored item that makes {@code actual} (the inventory) hold what {@code bag} says, less the uses
	 * still in flight; items missing from {@code bag} have none left. Unmirrored items in {@code bag} are ignored. A fall
	 * in an item's count since the last sync acknowledges uses in flight, oldest first. Empty when they already agree.
	 */
	public Map<String, Integer> sync(Map<String, Integer> bag, Map<String, Integer> actual) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String item : BagItems.gen1Items()) {
			counts.put(item, Math.max(0, Math.min(MAX, bag.getOrDefault(item, 0))));
		}
		if (this.lastBag != null) {
			for (Map.Entry<String, Integer> entry : counts.entrySet()) {
				int fall = this.lastBag.getOrDefault(entry.getKey(), 0) - entry.getValue();
				if (fall > 0) {
					this.acknowledge(entry.getKey(), fall);
				}
			}
		}
		this.lastBag = counts;
		return this.changes(actual);
	}

	/** The change per item that makes {@code actual} hold the last sync's counts less the uses in flight. */
	private Map<String, Integer> changes(Map<String, Integer> actual) {
		Map<String, Integer> changes = new LinkedHashMap<>();
		if (this.lastBag == null) {
			return changes;
		}
		for (Map.Entry<String, Integer> entry : this.lastBag.entrySet()) {
			int target = Math.max(0, entry.getValue() - this.inFlight(entry.getKey()));
			int delta = target - actual.getOrDefault(entry.getKey(), 0);
			if (delta != 0) {
				changes.put(entry.getKey(), delta);
			}
		}
		return changes;
	}

	/** The inventory after a sync was applied: the new reference (an item that didn't fit counts as it is). */
	public void settled(Map<String, Integer> actual) {
		Map<String, Integer> reference = new LinkedHashMap<>();
		for (String item : BagItems.gen1Items()) {
			reference.put(item, actual.getOrDefault(item, 0));
		}
		this.reference = reference;
	}

	/**
	 * The inventory now: what was consumed since the reference, to report as {@code bag.use} (it goes in flight), and the
	 * reference follows it down. Nothing while {@code hold}, and nothing before the first sync.
	 */
	public Map<String, Integer> observe(Map<String, Integer> actual, boolean hold, long now) {
		Map<String, Integer> used = new LinkedHashMap<>();
		if (this.reference == null || hold) {
			return used;
		}
		for (Map.Entry<String, Integer> entry : this.reference.entrySet()) {
			int have = actual.getOrDefault(entry.getKey(), 0);
			if (have < entry.getValue()) {
				int qty = entry.getValue() - have;
				used.put(entry.getKey(), qty);
				entry.setValue(have);
				this.inFlight.computeIfAbsent(entry.getKey(), key -> new ArrayDeque<>()).addLast(new Use(qty, now));
			}
		}
		return used;
	}

	/**
	 * Drops uses in flight that no sync acknowledged in time (lost with a link); returns the changes that make
	 * {@code actual} hold the last bag again, or nothing when no use expired.
	 */
	public Map<String, Integer> expire(Map<String, Integer> actual, long now) {
		boolean expired = false;
		for (Deque<Use> uses : this.inFlight.values()) {
			while (!uses.isEmpty() && now - uses.peekFirst().at > IN_FLIGHT_TICKS) {
				uses.removeFirst();
				expired = true;
			}
		}
		return expired ? this.changes(actual) : Map.of();
	}

	private void acknowledge(String item, int fall) {
		Deque<Use> uses = this.inFlight.get(item);
		int left = fall;
		while (uses != null && !uses.isEmpty() && left > 0) {
			Use use = uses.peekFirst();
			int taken = Math.min(use.qty, left);
			use.qty -= taken;
			left -= taken;
			if (use.qty == 0) {
				uses.removeFirst();
			}
		}
	}

	/** Uses of an item reported and not yet seen applied. */
	public int inFlight(String item) {
		Deque<Use> uses = this.inFlight.get(item);
		int total = 0;
		if (uses != null) {
			for (Use use : uses) {
				total += use.qty;
			}
		}
		return total;
	}

	/** The reference count of an item (tests, diagnostics); -1 before the first sync. */
	public int reference(String item) {
		return this.reference == null ? -1 : this.reference.getOrDefault(item, 0);
	}
}
