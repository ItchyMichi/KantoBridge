package dev.kantobridge.server;

import dev.kantobridge.world.Items;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

/**
 * The one trainer economy's rules (docs/specs/MULTI_GAME_PLAN.md §4.9, MG6), plain Java over a {@link Ledger} and the
 * item catalogue; {@link LedgerService} is the Minecraft glue.
 *
 * <ul>
 * <li><b>Projection:</b> the ledger is written into the followed game as {@code bag.set}: every canonical item the game
 * has a symbol for (class {@code key} never; a TM by the move it teaches), in class order then by id, at most
 * {@link Limits#stack} a stack and {@link Limits#slots} distinct symbols; the rest stays in the ledger ("in your
 * trunk").</li>
 * <li><b>Read-back:</b> while followed, each {@code bag.sync} whose stamp is the projection's moves the ledger by the
 * difference between that game's bag and the projection (prize money, purchases, found items, a blackout's halving),
 * and becomes the new baseline.</li>
 * <li><b>Adoption:</b> a save never projected (no stamp) joins the ledger once: its money is added and its crossing items
 * join the ledger; then it is projected.</li>
 * <li>A save restored to an older state (a stamp the server didn't project last) keeps the ledger's view: no diff, a
 * fresh projection.</li>
 * </ul>
 */
public final class LedgerRules {
	/** The kind an {@code act.result} answers {@code bag.set} with. */
	public static final String KIND = "bag.set";

	private LedgerRules() {
	}

	/**
	 * A game's bag limits.
	 *
	 * @param slots distinct item kinds the bag holds (Gen 1: 20)
	 * @param stack the most of one item in a slot (Gen 1: 99)
	 */
	public record Limits(int slots, int stack) {
		/** Gen 1: 20 bag slots, 99 a stack (src/inventory/Bag.lua). */
		public static final Limits GEN1 = new Limits(20, 99);
		/**
		 * Gen 3 (MG7): the most kinds {@code bag.set} carries (its {@code items} hold 128; Emerald's non-key pockets hold 156:
		 * items 30, balls 16, TM case 64, berries 46), 99 a slot; what a full pocket can't take comes back as
		 * {@code act.result.left}.
		 */
		public static final Limits GEN3 = new Limits(128, 99);
		/** Other families until their adapters say. */
		public static final Limits DEFAULT = new Limits(30, 99);

		public Limits {
			if (slots < 1 || stack < 1) {
				throw new IllegalArgumentException("bad limits");
			}
		}

		public static Limits of(@Nullable String family) {
			if ("gen1".equals(family)) {
				return GEN1;
			}
			return "gen3".equals(family) ? GEN3 : DEFAULT;
		}
	}

	/**
	 * What the server last projected into a region's game: the bag it told the game to hold.
	 *
	 * @param items symbol → quantity, as sent (then less what the game answered it could not hold)
	 * @param money the wallet as sent
	 * @param stamp the projection's stamp
	 */
	public record Projection(Map<String, Integer> items, long money, String stamp) {
		public Projection {
			items = Collections.unmodifiableMap(new TreeMap<>(items));
		}

		/** The projection after the game answered what did not fit ({@code act.result.left}): those are not in its bag. */
		public Projection less(Map<String, Integer> left) {
			Map<String, Integer> out = new TreeMap<>(this.items);
			for (Map.Entry<String, Integer> entry : left.entrySet()) {
				int have = out.getOrDefault(entry.getKey(), 0) - entry.getValue();
				if (have > 0) {
					out.put(entry.getKey(), have);
				} else {
					out.remove(entry.getKey());
				}
			}
			return new Projection(out, this.money, this.stamp);
		}
	}

	/**
	 * A projection worked out from the ledger.
	 *
	 * @param items symbol → quantity to send, in class order
	 * @param left  canonical id → quantity the game cannot take (no symbol, no slot, over the stack)
	 */
	public record Projected(Map<String, Integer> items, Map<String, Integer> left) {
		public Projected {
			items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
			left = Collections.unmodifiableMap(new TreeMap<>(left));
		}
	}

	/** A game's bag by symbol → the same by canonical id, crossing items only (unknown symbols and key items left out). */
	public static Map<String, Integer> canonical(Items catalogue, String family, Map<String, Integer> bySymbol) {
		Map<String, Integer> out = new TreeMap<>();
		for (Map.Entry<String, Integer> entry : bySymbol.entrySet()) {
			String id = catalogue.canonical(family, entry.getKey());
			Items.Item item = id == null ? null : catalogue.entry(id);
			if (item != null && item.crosses() && entry.getValue() > 0) {
				out.merge(id, entry.getValue(), Integer::sum);
			}
		}
		return out;
	}

	/** The symbol a family's games hold a canonical item as: TMs by the move they teach; null when the game lacks it. */
	public static @Nullable String symbolFor(Items catalogue, String family, String id) {
		Items.Item item = catalogue.entry(id);
		if (item == null || !item.crosses()) {
			return null;
		}
		if (Items.TM.equals(item.clazz()) && item.move() != null) {
			String tm = catalogue.tmByMove(family, item.move());
			return tm == null ? null : catalogue.symbol(family, tm);
		}
		return item.symbol(family);
	}

	/**
	 * The ledger after the followed game reported its bag and money (a sync whose stamp is {@code projected}'s): the
	 * difference since the projection is what the player earned, bought, found or lost there.
	 */
	public static Ledger readBack(Ledger ledger, Items catalogue, String family, Projection projected, Map<String, Integer> sync, long money) {
		Map<String, Integer> now = canonical(catalogue, family, sync);
		Map<String, Integer> before = canonical(catalogue, family, projected.items());
		Map<String, Integer> deltas = new TreeMap<>();
		for (String id : new TreeSet<>(union(now.keySet(), before.keySet()))) {
			int delta = now.getOrDefault(id, 0) - before.getOrDefault(id, 0);
			if (delta != 0) {
				deltas.put(id, delta);
			}
		}
		Ledger out = ledger.adjust(deltas);
		if (money >= 0) {
			out = out.addMoney(money - projected.money());
		}
		return out;
	}

	/** The ledger after a save never projected joined it once: its money and its crossing items. */
	public static Ledger adopt(Ledger ledger, Items catalogue, String family, Map<String, Integer> bag, long money, String key) {
		Ledger out = ledger.adjust(canonical(catalogue, family, bag));
		if (money > 0) {
			out = out.addMoney(money);
		}
		return out.adopt(key);
	}

	/** The ledger's projection into a family's games within its limits; what doesn't fit is {@code left}. */
	public static Projected project(Ledger ledger, Items catalogue, String family, Limits limits) {
		List<String> ids = new ArrayList<>(ledger.items().keySet());
		// class order first (balls, medicine, battle, field, stone, valuables, TMs), then the kinds the player holds most of,
		// so a stack of Potions outranks a lone Antidote when the bag's 20 slots run out; ids break the tie
		ids.sort(Comparator.<String>comparingInt(id -> {
			Items.Item item = catalogue.entry(id);
			return item == null ? Integer.MAX_VALUE : item.classRank();
		}).thenComparingInt(id -> -ledger.count(id)).thenComparing(Comparator.<String>naturalOrder()));
		Map<String, Integer> items = new LinkedHashMap<>();
		Map<String, Integer> left = new TreeMap<>();
		for (String id : ids) {
			int count = ledger.count(id);
			String symbol = symbolFor(catalogue, family, id);
			if (symbol == null) {
				left.merge(id, count, Integer::sum);
				continue;
			}
			int have = items.getOrDefault(symbol, 0);
			if (have == 0 && items.size() >= limits.slots()) {
				left.merge(id, count, Integer::sum);
				continue;
			}
			int room = limits.stack() - have;
			int put = Math.max(0, Math.min(room, count));
			if (put > 0) {
				items.put(symbol, have + put);
			}
			if (count > put) {
				left.merge(id, count - put, Integer::sum);
			}
		}
		return new Projected(items, left);
	}

	/** The ledger after Minecraft consumed a mirrored item (a thrown ball, a potion): the canonical count falls. */
	public static Ledger use(Ledger ledger, Items catalogue, String symbol, int qty) {
		String id = catalogue.canonical("gen1", symbol);
		return id == null ? ledger : ledger.remove(id, qty);
	}

	/** The mirror's targets: how many of each mirrored item (by its Gen 1 symbol, the mirror's key) the ledger holds. */
	public static Map<String, Integer> mirrorTargets(Ledger ledger, Items catalogue) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (String id : catalogue.mirroredIds()) {
			String symbol = catalogue.symbol("gen1", id);
			if (symbol != null) {
				out.put(symbol, Math.min(Ledger.MAX_ITEM, ledger.count(id)));
			}
		}
		return out;
	}

	/** A fresh projection stamp: 16 hex digits. */
	public static String newStamp(Random random) {
		StringBuilder out = new StringBuilder(16);
		for (int i = 0; i < 16; i++) {
			out.append(Character.forDigit(random.nextInt(16), 16));
		}
		return out.toString();
	}

	private static TreeSet<String> union(Iterable<String> a, Iterable<String> b) {
		TreeSet<String> out = new TreeSet<>();
		a.forEach(out::add);
		b.forEach(out::add);
		return out;
	}
}
