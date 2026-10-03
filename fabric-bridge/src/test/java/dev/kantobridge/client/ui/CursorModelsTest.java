package dev.kantobridge.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Choice, menu and list cursors with their scrolling, and the quantity box's bounds. */
class CursorModelsTest {
	static List<MenuModel.Item> items(int count) {
		List<MenuModel.Item> out = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			out.add(new MenuModel.Item("ITEM " + i, i % 2 == 0 ? "×" + i : null));
		}
		return out;
	}

	@Test
	void aChoiceToggles() {
		ChoiceModel choice = new ChoiceModel(List.of("YES", "NO"), 0, null);
		choice.move(1);
		assertEquals(1, choice.index());
		choice.move(1);
		assertEquals(0, choice.index(), "two labels toggle");
		choice.move(-1);
		assertEquals(1, choice.index());
		choice.select(7);
		assertEquals(1, choice.index(), "clamped");
		assertEquals(0, new ChoiceModel(List.of("HEAL", "CANCEL"), -3, "q").index());
		assertEquals(List.of(""), new ChoiceModel(List.of(), 0, null).labels(), "never empty");
	}

	@Test
	void aMenuWrapsRound() {
		MenuModel menu = new MenuModel(false, items(7), 0, 0, 7, null, null, null, "StartMenu");
		menu.move(-1);
		assertEquals(6, menu.index());
		menu.move(1);
		assertEquals(0, menu.index());
		menu.page(1);
		assertEquals(0, menu.index(), "menus don't page");
		assertEquals(7, menu.window().size());
		assertFalse(menu.moreAbove() || menu.moreBelow());
	}

	@Test
	void aListStopsAtItsEndsAndScrollsItsWindow() {
		MenuModel list = new MenuModel(true, items(10), 0, 0, 4, null, null, 3000, "BagMenu");
		list.move(-1);
		assertEquals(0, list.index(), "stops at the top");
		for (int i = 0; i < 5; i++) {
			list.move(1);
		}
		assertEquals(5, list.index());
		assertEquals(2, list.top(), "the window followed the cursor down");
		assertEquals(4, list.window().size());
		assertEquals("ITEM 2", list.window().get(0).label());
		assertTrue(list.moreAbove() && list.moreBelow());
		for (int i = 0; i < 20; i++) {
			list.move(1);
		}
		assertEquals(9, list.index(), "stops at the bottom");
		assertEquals(6, list.top());
		assertFalse(list.moreBelow());
		list.move(-1);
		list.move(-1);
		list.move(-1);
		list.move(-1);
		assertEquals(5, list.index());
		assertEquals(5, list.top(), "and up again");
		list.page(-1);
		assertEquals(1, list.index());
		assertEquals(1, list.top());
		list.page(1);
		assertEquals(5, list.index());
		assertEquals(Integer.valueOf(3000), list.money());
	}

	@Test
	void theWrapFieldOverridesTheKindsDefault() {
		assertTrue(new MenuModel(false, null, items(3), 0, 0, 3, null, null, null, null).wrap(), "menus wrap by default");
		assertFalse(new MenuModel(true, null, items(3), 0, 0, 3, null, null, null, null).wrap(), "lists stop by default");
		MenuModel stoppingMenu = new MenuModel(false, false, items(3), 0, 0, 3, null, null, null, null);
		stoppingMenu.move(-1);
		assertEquals(0, stoppingMenu.index(), "wrap:false stops at the top");
		for (int i = 0; i < 5; i++) {
			stoppingMenu.move(1);
		}
		assertEquals(2, stoppingMenu.index(), "and at the bottom");
		MenuModel wrappingList = new MenuModel(true, true, items(10), 9, 6, 4, null, null, null, null);
		wrappingList.move(1);
		assertEquals(0, wrappingList.index(), "wrap:true goes round from the last item to the first");
		assertEquals(0, wrappingList.top(), "and the window follows");
		wrappingList.move(-1);
		assertEquals(9, wrappingList.index());
		assertEquals(6, wrappingList.top());
		wrappingList.page(1);
		assertEquals(9, wrappingList.index(), "paging still stops at the end");
		wrappingList.page(-1);
		wrappingList.page(-1);
		wrappingList.page(-1);
		assertEquals(0, wrappingList.index(), "and at the start");
	}

	@Test
	void updatesReplaceItemsCursorAndWindowAndStayInBounds() {
		MenuModel list = new MenuModel(true, items(10), 9, 6, 4, null, null, null, null);
		list.apply(null, null, items(3));
		assertEquals(2, list.index(), "the cursor is kept on the shorter list");
		assertEquals(0, list.top());
		list.apply(1, null, null);
		assertEquals(1, list.index());
		MenuModel longer = new MenuModel(true, items(20), 0, 0, 4, null, null, null, null);
		longer.apply(10, 15, null);
		assertEquals(10, longer.index());
		assertTrue(longer.top() <= 10 && longer.top() + 4 > 10, "the window holds the cursor");
		longer.apply(null, 0, null);
		assertTrue(longer.top() <= 10 && longer.top() + 4 > 10);
	}

	@Test
	void openingValuesAreClampedAndRowsCapped() {
		MenuModel list = new MenuModel(true, items(30), 200, 200, 32, "T", "F", null, null);
		assertEquals(29, list.index());
		assertEquals(MenuModel.MAX_ROWS, list.rows());
		assertEquals(30 - MenuModel.MAX_ROWS, list.top());
		MenuModel empty = new MenuModel(true, List.of(), 0, 0, 4, null, null, null, null);
		assertTrue(empty.empty());
		empty.move(1);
		assertEquals(0, empty.index());
		assertTrue(empty.window().isEmpty());
	}

	@Test
	void quantityStepsThroughOneToMaxAndWraps() {
		QuantityModel quantity = new QuantityModel(1, 15, 200);
		quantity.step(-1);
		assertEquals(15, quantity.qty(), "below 1 wraps to max");
		quantity.step(1);
		assertEquals(1, quantity.qty(), "above max wraps to 1");
		quantity.step(10);
		assertEquals(11, quantity.qty());
		quantity.step(10);
		assertEquals(6, quantity.qty(), "21 wraps round to 6");
		assertEquals(Long.valueOf(1200), quantity.total());
		quantity.apply(99);
		assertEquals(15, quantity.qty(), "the hidden count is clamped too");
		quantity.apply(0);
		assertEquals(1, quantity.qty());
	}

	@Test
	void quantityOpeningValuesAreClamped() {
		QuantityModel zero = new QuantityModel(0, 5, null);
		assertEquals(1, zero.qty());
		assertNull(zero.total());
		QuantityModel huge = new QuantityModel(5000, 5000, 999_999);
		assertEquals(QuantityModel.LIMIT, huge.max());
		assertEquals(QuantityModel.LIMIT, huge.qty());
		assertEquals(Long.valueOf(999L * 999_999L), huge.total(), "no int overflow");
	}
}
