package dev.kantobridge.scene.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The data-only Lua reader (docs/specs/PROFILE_PLAN.md §5.2): the literals a shape profile uses, comments anywhere, and
 * a refusal of everything that would run. Made-up text.
 */
class LuaDataTest {
	@Test
	void readsTheLiteralsAProfileUses() throws Exception {
		LuaData.Table t = LuaData.parse("""
				-- a header comment
				return {
				  version = 1,
				  heights = { wall = 16, water = -2, ratio = 0.5, big = 1e3, hex = 0x1F },
				  [7] = "seven", ["key with spaces"] = true, off = false, gone = nil,
				  list = { 3, 4, 5; 6, },
				  --[[ a long
				       comment ]]
				  text = 'single\\'s "double" \\n\\t\\65\\x41',
				  long = [==[
				first line ]] still]==],
				}
				""");
		assertEquals(1L, t.integer("version", 0));
		LuaData.Table heights = t.table("heights");
		assertEquals(16L, heights.integer("wall", 0));
		assertEquals(-2L, heights.integer("water", 0));
		assertEquals(0.5, heights.number("ratio", 0), 1e-9);
		assertEquals(1000.0, heights.number("big", 0), 1e-9);
		assertEquals(31L, heights.integer("hex", 0));
		assertEquals("seven", t.get(7));
		assertEquals(Boolean.TRUE, t.get("key with spaces"));
		assertFalse(t.bool("off", true));
		assertNull(t.get("gone"), "a nil value leaves no key, as in Lua");
		assertEquals(List.of(3L, 4L, 5L, 6L), t.table("list").integers());
		assertEquals("single's \"double\" \n\tAA", t.string("text"));
		assertEquals("first line ]] still", t.string("long"));
	}

	@Test
	void positionalAndKeyedFieldsKeepTheirOrder() throws Exception {
		LuaData.Table t = LuaData.parse("{ 'a', x = 1, 'b', [2] = 'kept apart', y = 2 }");
		assertEquals(List.of("a", "b"), t.strings());
		assertEquals(List.of("x", 2L, "y"), List.copyOf(t.hash().keySet()));
		assertEquals("b", t.get(2), "a positional value answers its index first");
	}

	@Test
	void aBareTableAndATrailingSemicolonAreFine() throws Exception {
		assertTrue(LuaData.parse("{}").array().isEmpty());
		assertTrue(LuaData.parse("\uFEFFreturn {};").hash().isEmpty());
	}

	@Test
	void anythingThatWouldRunOrIsBrokenIsRefused() {
		for (String source : List.of("return { x = os.exit() }", "return { x = 1 + 2 }", "return { x = 'a' .. 'b' }", "return { x = y }",
				"return { function() end }", "local t = {} return t", "return { x = 'open }", "return { x = 1 } extra", "return { 12abc }",
				"return { [nil] = 1 }", "return { x = 1", "return { x = 1 } }", "return { --[[ never closed }")) {
			assertThrows(LuaData.DataException.class, () -> LuaData.parse(source), source);
		}
	}

	@Test
	void anErrorNamesALineAndAColumnNotTheText() {
		LuaData.DataException e = assertThrows(LuaData.DataException.class, () -> LuaData.parse("return {\n  secret = call(),\n}"));
		assertEquals(2, e.line());
		assertEquals(12, e.column());
		assertFalse(e.getMessage().contains("secret") || e.getMessage().contains("call"));
	}

	@Test
	void nestingHasALimit() {
		assertThrows(LuaData.DataException.class, () -> LuaData.parse("{".repeat(LuaData.MAX_DEPTH + 1) + "}".repeat(LuaData.MAX_DEPTH + 1)));
	}
}
