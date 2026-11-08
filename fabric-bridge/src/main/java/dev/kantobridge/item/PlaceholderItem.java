package dev.kantobridge.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * A stand-in for a hidden-game item Cobblemon has no item for (Repel, Escape Rope, Nugget): the bag mirror shows it in the
 * inventory like any other bag item, and it does nothing in Minecraft. The hidden game's own bag uses or sells it, and the
 * mirror follows. Its tooltip says where it works ({@code item.kantobridge.<path>.desc}).
 */
public class PlaceholderItem extends Item {
	private final String descKey;

	public PlaceholderItem(String path, Properties properties) {
		super(properties);
		this.descKey = "item.kantobridge." + path + ".desc";
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable(this.descKey).withStyle(ChatFormatting.GRAY));
	}
}
