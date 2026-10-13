package dev.kantobridge.bag;

import dev.kantobridge.world.Items;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * The Gen 1 battle items Minecraft mirrors into the inventory as Cobblemon items (docs/PLAN.md Appendix B, P17): the
 * item catalogue's mirrored entries ({@link Items#mirrored()}: class {@code ball}, {@code medicine} or {@code battle}
 * with a Cobblemon item), keyed by their Gen 1 symbol. The one place the Minecraft side reads them; since MG6 the table
 * is a view over {@code protocol/items.json}, so a change there changes the mirror.
 */
public final class BagItems {
	public static final String NAMESPACE = "cobblemon";

	private static volatile @Nullable Map<String, String> table;

	private BagItems() {
	}

	/** Every mirrored Gen 1 item and its Cobblemon item path, in a fixed order (the catalogue's). */
	public static Map<String, String> table() {
		Map<String, String> out = table;
		if (out == null) {
			synchronized (BagItems.class) {
				out = table;
				if (out == null) {
					Map<String, String> built = new LinkedHashMap<>();
					for (String id : Items.mirrored()) {
						String symbol = Items.symbolOf("gen1", id);
						String path = Items.cobblemonPath(id);
						if (symbol != null && path != null) {
							built.put(symbol, path);
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

	/** The Cobblemon item path (in namespace {@value #NAMESPACE}) for a Gen 1 item symbol, or null when it stays Gen 1-only. */
	public static @Nullable String cobblemonPath(String gen1) {
		return table().get(gen1);
	}

	/** The Gen 1 item symbol of a Cobblemon item path, or null. */
	public static @Nullable String gen1Of(String cobblemonPath) {
		for (Map.Entry<String, String> entry : table().entrySet()) {
			if (entry.getValue().equals(cobblemonPath)) {
				return entry.getKey();
			}
		}
		return null;
	}
}
