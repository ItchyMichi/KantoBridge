package dev.kantobridge.item;

import dev.kantobridge.world.Items;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * This mod's items: the placeholders the bag mirror creates for hidden-game items Cobblemon lacks, one per
 * {@code placeholder} in {@code protocol/items.json} (namespace {@value Items#PLACEHOLDER_NAMESPACE}). Each has a model
 * under {@code assets/kantobridge/models/item/} and a name and a tooltip line in the lang file. Registered on both sides,
 * so the registries agree.
 */
public final class KbItems {
	private static final Map<String, Item> PLACEHOLDERS = new LinkedHashMap<>();

	private KbItems() {
	}

	/** Call once from common init, before anything resolves a mirrored item. */
	public static void register() {
		if (!PLACEHOLDERS.isEmpty()) {
			return;
		}
		for (String path : Items.placeholders()) {
			Item item = new PlaceholderItem(path, new Item.Properties());
			PLACEHOLDERS.put(path, Registry.register(BuiltInRegistries.ITEM,
					ResourceLocation.fromNamespaceAndPath(Items.PLACEHOLDER_NAMESPACE, path), item));
		}
	}

	/** The registered placeholders by path. */
	public static Map<String, Item> placeholders() {
		return Collections.unmodifiableMap(PLACEHOLDERS);
	}
}
