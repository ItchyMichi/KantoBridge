package dev.kantobridge.client.act;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;

/**
 * Which right-clicks on a block are the Game Boy's A on that cell, and which are item use (docs/PLAN.md §4.3 "Clicks",
 * M6): a plain click (an empty hand, or an item that does nothing to blocks) talks to, reads or checks the cell; anything
 * usable goes to Minecraft, so Poké Balls can be thrown and Cobblemon's medicine used while aiming at the ground.
 *
 * <ul>
 * <li>Cobblemon's items (balls, medicine, X items, …) are always used.</li>
 * <li>Block items are plain clicks: Kanto is adventure mode, they can't be placed.</li>
 * <li>Other items are plain clicks only when they are plain {@link Item}s with no use of their own (sticks, gems); any
 * subclass (tools, bows, potions) or anything with a use animation (food) goes to Minecraft.</li>
 * </ul>
 * No game state: unit-tested with Minecraft's registries.
 */
public final class ClickPolicy {
	static final String COBBLEMON = "cobblemon";

	private ClickPolicy() {
	}

	/** True when a right-click on a block with {@code stack} in hand is A on the cell rather than an item use. */
	public static boolean plainClick(ItemStack stack) {
		if (stack.isEmpty()) {
			return true;
		}
		return plainClick(stack, BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
	}

	/** {@link #plainClick(ItemStack)} for a non-empty stack of an item registered in {@code namespace}. */
	static boolean plainClick(ItemStack stack, String namespace) {
		if (COBBLEMON.equals(namespace)) {
			return false;
		}
		Item item = stack.getItem();
		if (item instanceof BlockItem) {
			return true;
		}
		return item.getClass() == Item.class && stack.getUseAnimation() == UseAnim.NONE;
	}
}
