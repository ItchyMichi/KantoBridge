package dev.kantobridge.bag;

import dev.kantobridge.world.Items;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * The Gen 1 items Minecraft mirrors into the inventory (docs/PLAN.md Appendix B, P17): the item catalogue's mirrored
 * entries ({@link Items#mirrored()}), keyed by their Gen 1 symbol, each with the item the mirror creates: a Cobblemon item
 * (class {@code ball}, {@code medicine} or {@code battle}: the battle items, Rare Candy, the vitamins, PP Up) or one of
 * this mod's placeholder items for an item Cobblemon lacks (Repel, Escape Rope, Nugget). The one place the Minecraft side
 * reads them; since MG6 the table is a view over {@code protocol/items.json}, so a change there changes the mirror.
 */
public final class BagItems {
	public static final String NAMESPACE = Items.COBBLEMON_NAMESPACE;

	private static volatile @Nullable Map<String, String> table;

	private BagItems() {
	}

	/** Every mirrored Gen 1 item and the id ({@code namespace:path}) of the item the mirror creates, in the catalogue's order. */
	public static Map<String, String> table() {
		Map<String, String> out = table;
		if (out == null) {
			synchronized (BagItems.class) {
				out = table;
				if (out == null) {
					Map<String, String> built = new LinkedHashMap<>();
					for (String id : Items.mirrored()) {
						Items.Item item = Items.get(id);
						String symbol = Items.symbolOf("gen1", id);
						String created = item == null ? null : item.mirrorItem();
						if (symbol != null && created != null) {
							built.put(symbol, created);
						}
					}
					out = Collections.unmodifiableMap(built);
					table = out;
				}
			}
		}
		return out;
	}

	public static Set<String> gen1Items() {
		return table().keySet();
	}

	/** The id ({@code namespace:path}) of the item mirrored for a Gen 1 symbol, or null when it stays in the hidden bag. */
	public static @Nullable String itemId(String gen1) {
		return table().get(gen1);
	}

	/** The Cobblemon item path (in namespace {@value #NAMESPACE}) mirrored for a Gen 1 symbol, or null (a placeholder, or none). */
	public static @Nullable String cobblemonPath(String gen1) {
		String id = itemId(gen1);
		String prefix = NAMESPACE + ":";
		return id != null && id.startsWith(prefix) ? id.substring(prefix.length()) : null;
	}

	/** True when a Gen 1 symbol is mirrored as one of this mod's placeholder items. */
	public static boolean placeholder(String gen1) {
		String id = itemId(gen1);
		return id != null && id.startsWith(Items.PLACEHOLDER_NAMESPACE + ":");
	}

	/** The Gen 1 symbol mirrored as an item ({@code namespace:path}, or a bare Cobblemon path), or null. */
	public static @Nullable String gen1Of(String itemId) {
		String full = itemId.indexOf(':') < 0 ? NAMESPACE + ":" + itemId : itemId;
		for (Map.Entry<String, String> entry : table().entrySet()) {
			if (entry.getValue().equals(full)) {
				return entry.getKey();
			}
		}
		return null;
	}
}
