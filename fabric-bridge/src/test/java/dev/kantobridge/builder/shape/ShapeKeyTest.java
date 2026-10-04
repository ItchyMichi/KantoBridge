package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Shape keys (docs/specs/VOXEL_BLOCKS_PLAN.md §4.2): one canonical text per key, parsed back exactly, anything else
 * refused; ordered by kind name, atlas and then each number numerically, never by comparing the text of numbers.
 */
class ShapeKeyTest {
	@Test
	void everyKindHasOneCanonicalText() {
		assertEquals("cube:0|3,3,3,5,3,3", ShapeKey.cube(0, 3, 3, 3, 5, 3, 3).text());
		assertEquals("slab:0|9|4", ShapeKey.slab(0, 9, 4).text());
		assertEquals("roof:1|4,5,4|10,6|1,0", ShapeKey.roof(1, 4, 5, 4, 10, 6, true, false).text());
		assertEquals("tuft:0|7", ShapeKey.tuft(0, 7).text());
		assertEquals("cell:0|2,2,2,2|0|1,0,1", ShapeKey.cell(0, new int[] {2, 2, 2, 2}, 0, 1, 0, 1).text());
		assertEquals("tmpl:0|0123456789abcdef|2,3,0", ShapeKey.tmpl(0, "0123456789abcdef", 2, 3, 0).text());
		assertEquals("cube:0|1,1,1,1,1,1|tint=2|v=snow", ShapeKey.cube(0, 1, 1, 1, 1, 1, 1).withTint(2).withVariant("snow").text());
		assertEquals("cube:0|-1,1,1,1,1,1", ShapeKey.cube(0, -1, 1, 1, 1, 1, 1).text(), "a face without a tile");
	}

	@Test
	void parsingGivesTheSameKeyBack() {
		for (ShapeKey key : List.of(ShapeKey.cube(0, 3, 3, 3, 5, 3, 3), ShapeKey.slab(2, 9, 16), ShapeKey.roof(0, 4, 5, 4, 0, 16, false, true),
				ShapeKey.tuft(0, 7), ShapeKey.cell(3, new int[] {1, 2, 3, 4}, 5, 0, 1, 0), ShapeKey.tmpl(0, "ffffffffffffffff", 0, 0, 0),
				ShapeKey.cube(0, 1, 1, 1, 1, 1, 1).withTint(3), ShapeKey.tuft(0, 1).withVariant("a_1"))) {
			ShapeKey parsed = ShapeKey.parse(key.text());
			assertEquals(key, parsed);
			assertEquals(key.text(), parsed.text());
			assertEquals(0, key.compareTo(parsed));
		}
	}

	@Test
	void nonCanonicalTextIsRefused() {
		for (String text : List.of("", "cube", "cube:", "box:0|1", "cube:0|1,1,1,1,1", "cube:0|1,1,1,1,1,1,1", "cube:00|1,1,1,1,1,1",
				"cube:0|01,1,1,1,1,1", "cube:0|+1,1,1,1,1,1", "cube:0| 1,1,1,1,1,1", "cube:0|1,1,1,1,1,1|", "cube:0|1,1,1,1,1,1|tint=0",
				"cube:0|1,1,1,1,1,1|tint=4", "cube:0|1,1,1,1,1,1|v=snow|tint=1", "cube:0|1,1,1,1,1,1|x=1", "slab:0|9|0", "slab:0|9|17",
				"tmpl:0|0123|1,1,1", "tmpl:0|0123456789ABCDEF|1,1,1", "tmpl:0|1,1,1", "cube:-1|1,1,1,1,1,1", "cube:0|1,1,1,1,1,1|v=Snow")) {
			assertThrows(IllegalArgumentException.class, () -> ShapeKey.parse(text), text);
		}
	}

	@Test
	void numbersOrderNumericallyNotAsText() {
		ShapeKey nine = ShapeKey.cube(0, 9, 0, 0, 0, 0, 0);
		ShapeKey ten = ShapeKey.cube(0, 10, 0, 0, 0, 0, 0);
		assertTrue(nine.compareTo(ten) < 0, "9 before 10");
		assertTrue(nine.text().compareTo(ten.text()) > 0, "though the text of 9 sorts after the text of 10");
		assertTrue(ShapeKey.cube(2, 0, 0, 0, 0, 0, 0).compareTo(ShapeKey.cube(10, 0, 0, 0, 0, 0, 0)) < 0, "atlases numerically");
		assertTrue(ShapeKey.cube(0, 0, 0, 0, 0, 0, 0).compareTo(ShapeKey.cube(0, -1, 0, 0, 0, 0, 0)) > 0, "-1 first");
	}

	@Test
	void kindsOrderByNameThenModifiers() {
		List<ShapeKey> expected = List.of(ShapeKey.cell(0, new int[] {0, 0, 0, 0}, 0, 0, 0, 0), ShapeKey.cube(0, 0, 0, 0, 0, 0, 0),
				ShapeKey.cube(0, 0, 0, 0, 0, 0, 0).withVariant("a"), ShapeKey.cube(0, 0, 0, 0, 0, 0, 0).withTint(1),
				ShapeKey.roof(0, 0, 0, 0, 0, 0, false, false), ShapeKey.slab(0, 0, 1), ShapeKey.tmpl(0, "0000000000000000", 0, 0, 0),
				ShapeKey.tuft(0, 0));
		List<ShapeKey> shuffled = new ArrayList<>(expected);
		Collections.shuffle(shuffled, new Random(5));
		assertEquals(expected, new ArrayList<>(new TreeSet<>(shuffled)));
	}
}
