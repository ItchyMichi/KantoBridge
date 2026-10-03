package dev.kantobridge.client.act;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.McBootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Plain right-clicks on blocks become A on the cell; item use (balls, medicine, anything usable) goes to Minecraft. */
class ClickPolicyTest {
	@BeforeAll
	static void bootstrap() {
		McBootstrap.ensure();
	}

	@Test
	void plainClicksAndItemUse() {
		assertTrue(ClickPolicy.plainClick(ItemStack.EMPTY), "an empty hand talks");
		assertTrue(ClickPolicy.plainClick(new ItemStack(Items.STICK)), "a plain item does nothing to blocks");
		assertTrue(ClickPolicy.plainClick(new ItemStack(Items.DIAMOND)));
		assertTrue(ClickPolicy.plainClick(new ItemStack(Items.DIRT)), "blocks can't be placed in adventure mode");
		assertFalse(ClickPolicy.plainClick(new ItemStack(Items.BREAD)), "food has a use");
		assertFalse(ClickPolicy.plainClick(new ItemStack(Items.SNOWBALL)), "a throwable");
		assertFalse(ClickPolicy.plainClick(new ItemStack(Items.FLINT_AND_STEEL)), "a tool used on blocks");
		// Anything from Cobblemon (its balls and medicine aren't registered in a unit test): always used.
		assertFalse(ClickPolicy.plainClick(new ItemStack(Items.STICK), "cobblemon"));
		assertTrue(ClickPolicy.plainClick(new ItemStack(Items.STICK), "minecraft"));
	}
}
