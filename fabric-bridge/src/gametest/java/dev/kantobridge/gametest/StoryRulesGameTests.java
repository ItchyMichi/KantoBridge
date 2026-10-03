package dev.kantobridge.gametest;

import com.google.gson.JsonObject;
import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.block.StoryCellBlockEntity;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.story.StoryRules;
import dev.kantobridge.story.StoryStateMessages;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * The story-cell rules wave 1 left to check on a real server (docs/PROTOCOL.md "Story rules (M8)", M8 wave 2): two mock
 * players whose made-up hidden games differ get their cells from {@link StoryRules}, relay them as {@code story.state},
 * and the story blocks laid over the test's own floor close per player. A cut tree grows back once its player's map
 * changes (live-block overrides belong to one map visit); objects absent from {@code toggles} are at their default; a
 * sailed ship's water bars walkers but not surfers; a seal whose script writes its block on every entry (Agatha's exit)
 * is open away from its room and follows the live block inside it.
 */
public class StoryRulesGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_story_rules";
	private static final String HASH = "0".repeat(64);
	private static final String TREE = "TOWN:10:14";
	private static final String BALL = "TOWN:3:4";
	private static final String GUARD = "TOWN:5:4";
	private static final String DECK = "DOCK:2:0";
	private static final String HULL = "DOCK:2:1";
	private static final String SEAL = "SEAL:4:0";

	/** A made-up world: a town with a cut tree, an item ball and a hidden guard; a dock with a ship; a sealed room. */
	static WorldIndex world() {
		List<Integer> art = List.of(1, 1, 1, 1);
		return new WorldIndex("red", HASH, List.of(
				new MapPlacement("TOWN", 0, 64, 0, 20, 18, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null),
				new MapPlacement("HOUSE", 0, 42, 0, 8, 8, 1, MapPlacement.Kind.INTERIOR, "PAL", "kantobridge:kanto_interior", false, "TOWN"),
				new MapPlacement("DOCK", 40, 42, 0, 8, 6, 1, MapPlacement.Kind.INTERIOR, "PAL", "kantobridge:kanto_interior", false, "TOWN"),
				new MapPlacement("SEAL", 80, 42, 0, 8, 8, 1, MapPlacement.Kind.INTERIOR, "PAL", "kantobridge:kanto_interior", false, "HOUSE")),
				HASH, StoryCellTable.of(List.of(
						new StoryCellTable.Entry(CellPos.parse(TREE), StoryCellType.TILE, null, new StoryCellTable.Swap(5, 7, "cut", 14, 15), false, 14,
								new StoryCellTable.Closed(14, StoryCellTable.Pass.SOLID, 2, art), false),
						new StoryCellTable.Entry(CellPos.parse(BALL), StoryCellType.OBJECT, "TOWN_BALL", null),
						new StoryCellTable.Entry(CellPos.parse(GUARD), StoryCellType.OBJECT, "TOWN_GUARD", null, true, null, null),
						// The ship's deck (built walkable, closing into water) and hull (built as the water it becomes).
						new StoryCellTable.Entry(CellPos.parse(DECK), StoryCellType.TILE, null, new StoryCellTable.Swap(1, 0, "script", 1, 2), false, 1,
								new StoryCellTable.Closed(2, StoryCellTable.Pass.WATER, 0, art), false),
						new StoryCellTable.Entry(CellPos.parse(HULL), StoryCellType.TILE, null, new StoryCellTable.Swap(1, 0, "script", 1, 2), false, 1,
								new StoryCellTable.Closed(1, StoryCellTable.Pass.SOLID, 2, art), true),
						// The seal: its map data holds neither block (3); the engine writes 1 (shut) or 2 (open) on entry.
						new StoryCellTable.Entry(CellPos.parse(SEAL), StoryCellType.TILE, null, new StoryCellTable.Swap(2, 0, "script", 1, 2), false, 3,
								new StoryCellTable.Closed(1, StoryCellTable.Pass.SOLID, 2, art), false))),
				List.of());
	}

	private static JsonObject json(String text) {
		try {
			return ProtoJson.parseObject(text);
		} catch (ProtocolException e) {
			throw new GameTestAssertException("bad test JSON: " + e.code());
		}
	}

	private static String state(boolean surf) {
		return "{\"mode\":\"idle\",\"bike\":false,\"surf\":" + surf + ",\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}";
	}

	private static JsonObject snapshot(String map, boolean surf, String toggles, String blocks) {
		return json("{\"ingame\":true,\"player\":{\"map\":\"" + map + "\",\"x\":1,\"y\":1,\"facing\":\"down\"},\"state\":" + state(surf)
				+ ",\"toggles\":" + toggles + ",\"blocks\":" + blocks + ",\"lit\":[]}");
	}

	/** Lays one story block per cell over the test's floor, its block entity naming the cell; returns where each went. */
	private static Map<String, BlockPos> lay(GameTestHelper helper, WorldIndex index) {
		ServerLevel level = helper.getLevel();
		Map<String, BlockPos> at = new LinkedHashMap<>();
		int i = 0;
		for (StoryCellTable.Entry entry : index.storyCells().entries()) {
			BlockPos pos = helper.absolutePos(new BlockPos(1 + (i % 3) * 2, 2, 1 + (i / 3) * 2));
			boolean tile = entry.type() == StoryCellType.TILE;
			level.setBlock(pos, (tile ? KbBlocks.STORY_TILE : KbBlocks.STORY_CELL).defaultBlockState(), Block.UPDATE_ALL);
			if (!(level.getBlockEntity(pos) instanceof StoryCellBlockEntity cell)) {
				throw new GameTestAssertException("no story-cell block entity for " + entry.key());
			}
			cell.set(entry.key(), entry.type());
			at.put(entry.key(), pos);
			i++;
		}
		return at;
	}

	private static void relay(ServerPlayer player, long seq, StoryRules rules) {
		JsonObject data = StoryStateMessages.full(rules.cells().active(), rules.surf(), rules.bike(), rules.lit());
		RelayService.get().receive(player, "{\"type\":\"story.state\",\"seq\":" + seq + ",\"data\":" + ProtoJson.write(data) + "}");
	}

	private static boolean closedFor(ServerLevel level, BlockPos pos, ServerPlayer player) {
		return !level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.of(player)).isEmpty();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void storyCellsFollowEachPlayersVisits(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		WorldIndex index = world();
		Map<String, BlockPos> at = lay(helper, index);
		ServerPlayer walker = MockPlayers.join(helper);
		ServerPlayer surfer = MockPlayers.join(helper);
		try {
			// The walker is in town; the surfer is on the dock's water after the ship sailed.
			StoryRules a = new StoryRules();
			a.setIndex(index);
			a.onSnapshot(snapshot("TOWN", false, "{}", "[]"));
			StoryRules b = new StoryRules();
			b.setIndex(index);
			b.onSnapshot(snapshot("DOCK", true, "{}", "[{\"map\":\"DOCK\",\"bx\":1,\"by\":0,\"block\":2}]"));
			relay(walker, 1, a);
			relay(surfer, 1, b);
			helper.assertTrue(closedFor(level, at.get(TREE), walker) && closedFor(level, at.get(BALL), walker), "the tree and the ball stand");
			helper.assertFalse(closedFor(level, at.get(GUARD), walker), "the guard is hidden by default");
			helper.assertTrue(closedFor(level, at.get(HULL), walker) && !closedFor(level, at.get(DECK), walker),
					"away from the dock the ship lies moored: her hull bars the way, her deck doesn't");
			helper.assertFalse(closedFor(level, at.get(SEAL), walker), "away from its room the seal shows its data: open");
			helper.assertFalse(closedFor(level, at.get(HULL), surfer) || closedFor(level, at.get(DECK), surfer), "the surfer crosses where she lay");

			// The walker cuts the tree, then leaves the town: the tree grows back (the override was the town visit's).
			a.onBlocks(json("{\"map\":\"TOWN\",\"changes\":[{\"bx\":5,\"by\":7,\"block\":15}]}"));
			relay(walker, 2, a);
			helper.assertFalse(closedFor(level, at.get(TREE), walker), "cut");
			a.onPlayerMap("HOUSE");
			relay(walker, 3, a);
			helper.assertTrue(closedFor(level, at.get(TREE), walker), "the tree grew back once the map changed");

			// Toggles: reported changes hold, and a snapshot without them puts every object back at its default.
			a.onToggles(json("{\"map\":\"TOWN\",\"obj\":\"TOWN_BALL\",\"visible\":false}"));
			a.onToggles(json("{\"map\":\"TOWN\",\"obj\":\"TOWN_GUARD\",\"visible\":true}"));
			relay(walker, 4, a);
			helper.assertTrue(!closedFor(level, at.get(BALL), walker) && closedFor(level, at.get(GUARD), walker), "toggled");
			a.onSnapshot(snapshot("HOUSE", false, "{}", "[]"));
			relay(walker, 5, a);
			helper.assertTrue(closedFor(level, at.get(BALL), walker) && !closedFor(level, at.get(GUARD), walker),
					"objects absent from toggles are at their default");

			// The seal: shut while the hidden game writes the wall, open once it writes the floor, open again away from it.
			a.onPlayerMap("SEAL");
			a.onBlocks(json("{\"map\":\"SEAL\",\"changes\":[{\"bx\":2,\"by\":0,\"block\":1}]}"));
			relay(walker, 6, a);
			helper.assertTrue(closedFor(level, at.get(SEAL), walker), "sealed");
			a.onBlocks(json("{\"map\":\"SEAL\",\"changes\":[{\"bx\":2,\"by\":0,\"block\":2}]}"));
			relay(walker, 7, a);
			helper.assertFalse(closedFor(level, at.get(SEAL), walker), "unsealed");

			// The surfer climbs onto the dock: where she lay is water again for them.
			b.onPlayerState(json(state(false)));
			relay(surfer, 2, b);
			helper.assertTrue(closedFor(level, at.get(HULL), surfer) && closedFor(level, at.get(DECK), surfer), "water bars a walker");
			helper.assertTrue(closedFor(level, at.get(TREE), surfer), "the surfer's own town still has its tree");
		} finally {
			MockPlayers.leave(helper, walker);
			MockPlayers.leave(helper, surfer);
			at.values().forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
		}
		helper.succeed();
	}
}
