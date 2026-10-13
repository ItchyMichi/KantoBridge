package dev.kantobridge.story;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * docs/PROTOCOL.md "Story rules (M8)" on a made-up world: every story-cell type and every source that decides it (map
 * defaults, the snapshot, {@code story.toggles}, {@code story.blocks}, the live NPC list, the current map), plus surf,
 * bike and the lit maps.
 */
class StoryRulesTest {
	private static final String HASH = "0".repeat(64);
	private static final String BALL = "TOWN:3:4";
	private static final String HIDDEN = "TOWN:5:4";
	private static final String MOM = "HOUSE:2:2";
	private static final String TREE = "TOWN:10:14";
	private static final String DOOR = "HOUSE:0:2";
	private static final String CARPET = "HOUSE:3:3";
	private static final String WATER = "TOWN:12:12";
	private static final String BOULDER_A = "CAVE:1:1";
	private static final String BOULDER_B = "CAVE:2:1";
	private static final String HULL = "TOWN:0:16";
	private static final String DECK = "TOWN:2:16";

	private StoryRules rules;

	static WorldIndex world() {
		List<MapPlacement> maps = List.of(
				new MapPlacement("TOWN", 0, 64, 0, 20, 18, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null),
				new MapPlacement("HOUSE", 2, 42, -6, 10, 10, 1, MapPlacement.Kind.INTERIOR, "PAL", "kantobridge:kanto_interior", false, "TOWN"),
				new MapPlacement("CAVE", 60, 42, 0, 10, 10, 1, MapPlacement.Kind.INTERIOR, "PAL", "kantobridge:kanto_cave", true, "TOWN"));
		StoryCellTable cells = StoryCellTable.of(List.of(
				new StoryCellTable.Entry(CellPos.parse(BALL), StoryCellType.OBJECT, "TOWN_BALL", null),
				new StoryCellTable.Entry(CellPos.parse(HIDDEN), StoryCellType.OBJECT, "TOWN_HIDDEN", null, true, null, null),
				new StoryCellTable.Entry(CellPos.parse(MOM), StoryCellType.OBJECT, "HOUSE_MOM", null),
				// A Cut tree: the map data shows it standing (start = before).
				new StoryCellTable.Entry(CellPos.parse(TREE), StoryCellType.TILE, null, new StoryCellTable.Swap(5, 7, "cut", 14, 15), false, 14,
						new StoryCellTable.Closed(14, true, 2, List.of(13, 13, 13, 13))),
				// A card-key door the engine stamps shut on map load: the map data shows it open (start = after).
				new StoryCellTable.Entry(CellPos.parse(DOOR), StoryCellType.TILE, null, new StoryCellTable.Swap(0, 1, "card_key", 2, 3), false, 3,
						new StoryCellTable.Closed(2, true, 2, List.of(7, 7, 7, 7))),
				// An art-only swap: drawn while closed, never solid.
				new StoryCellTable.Entry(CellPos.parse(CARPET), StoryCellType.TILE, null, new StoryCellTable.Swap(1, 1, "script", 0, 4), false, 0,
						new StoryCellTable.Closed(0, false, 0, List.of(1, 1, 1, 1))),
				new StoryCellTable.Entry(CellPos.parse(WATER), StoryCellType.WATER, null, null),
				new StoryCellTable.Entry(CellPos.parse(BOULDER_A), StoryCellType.BOULDER_AREA, null, null),
				new StoryCellTable.Entry(CellPos.parse(BOULDER_B), StoryCellType.BOULDER_AREA, null, null),
				// A ship that sails: her hull is built as the water it becomes, her deck as the deck.
				new StoryCellTable.Entry(CellPos.parse(HULL), StoryCellType.TILE, null, new StoryCellTable.Swap(0, 8, "script", 40, 1), false, 40,
						new StoryCellTable.Closed(40, StoryCellTable.Pass.SOLID, 2, List.of(9, 9, 9, 9)), true),
				new StoryCellTable.Entry(CellPos.parse(DECK), StoryCellType.TILE, null, new StoryCellTable.Swap(1, 8, "script", 41, 1), false, 41,
						new StoryCellTable.Closed(1, StoryCellTable.Pass.WATER, 0, List.of(6, 6, 6, 6)), false)));
		return new WorldIndex("red", HASH, maps, HASH, cells, List.of());
	}

	private static JsonObject json(String text) throws Exception {
		return ProtoJson.parseObject(text);
	}

	private Set<String> active() {
		return this.rules.cells().active();
	}

	private Set<String> drawn() {
		return this.rules.cells().drawn();
	}

	@BeforeEach
	void setUp() {
		this.rules = new StoryRules();
		this.rules.setIndex(world());
	}

	/** A world with a connecting route's half whose far doorway is a pass story cell (MG5). */
	static WorldIndex passWorld() {
		List<MapPlacement> maps = List.of(
				new MapPlacement("KB_ROUTE_EASTERN_PASS", 0, 64, 4000, 2, 2, 0, MapPlacement.Kind.ROUTE, "PAL", "kantobridge:wild_forest", false,
						null));
		List<StoryCellTable.Entry> cells = List.of(
				new StoryCellTable.Entry(CellPos.parse("KB_ROUTE_EASTERN_PASS:40:0"), StoryCellType.PASS, "eastern_pass", null),
				new StoryCellTable.Entry(CellPos.parse("KB_ROUTE_EASTERN_PASS:40:1"), StoryCellType.PASS, "eastern_pass", null));
		return new WorldIndex("red", HASH, maps, HASH, StoryCellTable.of(cells), List.of(), List.of());
	}

	@Test
	void aPassIsClosedUntilItsRouteIsOpen() {
		this.rules.setIndex(passWorld());
		assertTrue(active().containsAll(Set.of("KB_ROUTE_EASTERN_PASS:40:0", "KB_ROUTE_EASTERN_PASS:40:1")), "pass_closed by default");
		this.rules.setOpenPasses(Set.of("western_pass"));
		assertTrue(active().contains("KB_ROUTE_EASTERN_PASS:40:0"), "another route's pass opening changes nothing here");
		this.rules.setOpenPasses(Set.of("eastern_pass"));
		assertFalse(active().contains("KB_ROUTE_EASTERN_PASS:40:0"), "the far region is built and its game linked: open");
		assertFalse(active().contains("KB_ROUTE_EASTERN_PASS:40:1"));
		assertEquals(Set.of("eastern_pass"), this.rules.openPasses());
		this.rules.setOpenPasses(Set.of());
		assertTrue(active().contains("KB_ROUTE_EASTERN_PASS:40:0"), "the link dropped: shut again");
	}

	/** A world with a Wild Routes zone whose two balls are route story cells (WR5). */
	static WorldIndex zonedWorld() {
		WorldIndex base = world();
		List<MapPlacement> maps = new java.util.ArrayList<>(base.maps().values());
		maps.add(new MapPlacement("KB_ROUTE_Z", 0, 64, 4000, 2, 2, 0, MapPlacement.Kind.ROUTE, "PAL", "kantobridge:wild_forest", false, null));
		List<StoryCellTable.Entry> cells = new java.util.ArrayList<>(base.storyCells().entries());
		cells.add(new StoryCellTable.Entry(CellPos.parse("KB_ROUTE_Z:12:-3"), StoryCellType.ROUTE, "a1.i1", null));
		cells.add(new StoryCellTable.Entry(CellPos.parse("KB_ROUTE_Z:30:4"), StoryCellType.ROUTE, "a2.i1", null));
		WorldIndex.Zone zone = new WorldIndex.Zone("z_zone", "KB_ROUTE_Z", "forest", -20, 64, 3980, 200, 200, "kantobridge:wild_forest",
				List.of(), List.of(new WorldIndex.ZoneBall("a1.i1", 12, -3, "POTION"), new WorldIndex.ZoneBall("a2.i1", 30, 4, "POKE_BALL")), "BALL");
		return new WorldIndex("red", HASH, maps, HASH, StoryCellTable.of(cells), List.of(), List.of(zone));
	}

	@Test
	void aZonesBallsAreClosedUntilTheProgressSaysTaken() throws Exception {
		this.rules.setIndex(zonedWorld());
		assertTrue(active().containsAll(Set.of("KB_ROUTE_Z:12:-3", "KB_ROUTE_Z:30:4")), "no progress yet: both balls are there");
		this.rules.onSnapshot(json("{\"ingame\":true,\"player\":{\"map\":\"KB_ROUTE_Z\",\"x\":1,\"y\":0,\"facing\":\"right\"},"
				+ "\"routes\":{\"z_zone\":{\"taken\":[\"a1.i1\"],\"wins\":{}}}}"));
		assertFalse(active().contains("KB_ROUTE_Z:12:-3"), "taken in the save");
		assertTrue(active().contains("KB_ROUTE_Z:30:4"));
		this.rules.onRouteProgress(json("{\"routes\":{\"z_zone\":{\"taken\":[\"a1.i1\",\"a2.i1\"],\"wins\":{}}}}"));
		assertFalse(active().contains("KB_ROUTE_Z:30:4"), "taken just now");
		assertTrue(this.rules.routeProgress().taken("z_zone", "a2.i1"));
		// another save's snapshot without that progress brings both back
		this.rules.onSnapshot(json("{\"ingame\":true,\"player\":{\"map\":\"TOWN\",\"x\":1,\"y\":1,\"facing\":\"up\"}}"));
		assertTrue(active().containsAll(Set.of("KB_ROUTE_Z:12:-3", "KB_ROUTE_Z:30:4")));
	}

	@Test
	void mapDefaultsDecideBeforeAnyReport() {
		assertEquals(Set.of(BALL, MOM, TREE, HULL), active(), "visible objects, the standing tree and the ship's hull; the stamped door "
				+ "shows its map data");
		assertEquals(Set.of(TREE, CARPET, HULL), drawn(), "closed tile cells are drawn, art-only ones too");
		assertFalse(active().contains(WATER), "water is the surf flag's, never an active cell");
		assertFalse(active().contains(CARPET), "an art-only cell never collides");
	}

	@Test
	void objectsFollowTheSnapshotAndToggles() throws Exception {
		this.rules.onSnapshot(json("{\"ingame\":true,\"player\":{\"map\":\"CAVE\",\"x\":1,\"y\":1,\"facing\":\"up\"},"
				+ "\"toggles\":{\"TOWN\":{\"TOWN_BALL\":false,\"TOWN_HIDDEN\":true}},\"blocks\":[],\"lit\":[]}"));
		assertFalse(active().contains(BALL), "the ball was taken");
		assertTrue(active().contains(HIDDEN), "the hidden object was shown");
		this.rules.onToggles(json("{\"map\":\"HOUSE\",\"obj\":\"HOUSE_MOM\",\"visible\":false}"));
		assertFalse(active().contains(MOM));
		this.rules.onToggles(json("{\"map\":\"TOWN\",\"obj\":\"TOWN_BALL\",\"visible\":true}"));
		assertTrue(active().contains(BALL));
		// A new snapshot starts over: back to the defaults.
		this.rules.onSnapshot(json("{\"player\":{\"map\":\"CAVE\",\"x\":1,\"y\":1,\"facing\":\"up\"},\"toggles\":{},\"blocks\":[],\"lit\":[]}"));
		assertEquals(Set.of(BALL, MOM, TREE, HULL), active());
	}

	@Test
	void onTheLiveListsMapTheNpcsDecide() {
		this.rules.setNpcs("TOWN", List.of(new StoryRules.Npc(5, 4, "person")));
		assertFalse(active().contains(BALL), "no NPC stands on the ball's cell");
		assertTrue(active().contains(HIDDEN), "someone stands on the hidden object's cell");
		assertTrue(active().contains(MOM), "other maps keep their defaults");
		// The NPC walks off: its cell opens.
		this.rules.setNpcs("TOWN", List.of(new StoryRules.Npc(6, 4, "person")));
		assertFalse(active().contains(HIDDEN));
	}

	@Test
	void tileCellsFollowTheCurrentMapsLiveBlocks() throws Exception {
		this.rules.onPlayerMap("TOWN");
		this.rules.onBlocks(json("{\"map\":\"TOWN\",\"changes\":[{\"bx\":5,\"by\":7,\"block\":15}]}"));
		assertFalse(active().contains(TREE), "cut");
		assertFalse(drawn().contains(TREE));
		// Leaving the map: it shows its map data again (cut trees grow back).
		this.rules.onPlayerMap("HOUSE");
		assertTrue(active().contains(TREE), "the tree grew back");
		// The stamped door: closed once the hidden game reports the stamp, open again when unlocked.
		this.rules.onBlocks(json("{\"map\":\"HOUSE\",\"changes\":[{\"bx\":0,\"by\":1,\"block\":2}]}"));
		assertTrue(active().contains(DOOR));
		assertTrue(drawn().contains(DOOR));
		this.rules.onBlocks(json("{\"map\":\"HOUSE\",\"changes\":[{\"bx\":0,\"by\":1,\"block\":3}]}"));
		assertFalse(active().contains(DOOR));
		// The art-only swap: once the live block is the other one, nothing is drawn.
		this.rules.onBlocks(json("{\"map\":\"HOUSE\",\"changes\":[{\"bx\":1,\"by\":1,\"block\":4}]}"));
		assertFalse(drawn().contains(CARPET));
	}

	@Test
	void liveBlocksBelongToOneVisitOfTheirMap() throws Exception {
		// A warp's arrival comes before the new map's story.blocks in the same logic step (PROTOCOL.md "Story rules").
		this.rules.onPlayerMap("TOWN");
		this.rules.onBlocks(json("{\"map\":\"TOWN\",\"changes\":[{\"bx\":5,\"by\":7,\"block\":15}]}"));
		assertFalse(active().contains(TREE), "cut");
		// A reload of the same map re-sends a block that went back to the map data: the regrown tree.
		this.rules.onBlocks(json("{\"map\":\"TOWN\",\"changes\":[{\"bx\":5,\"by\":7,\"block\":14}]}"));
		assertTrue(active().contains(TREE), "regrown on reload");
		this.rules.onBlocks(json("{\"map\":\"TOWN\",\"changes\":[{\"bx\":5,\"by\":7,\"block\":15}]}"));
		// Into the house: the town's overrides go, the house's own arrive after the warp.
		this.rules.onPlayerMap("HOUSE");
		this.rules.onBlocks(json("{\"map\":\"HOUSE\",\"changes\":[{\"bx\":0,\"by\":1,\"block\":2}]}"));
		assertTrue(active().containsAll(Set.of(TREE, DOOR)), "the tree regrew; the house's door is stamped shut");
		// A position on the same map changes nothing; back out to town, the door's override goes too.
		this.rules.onPlayerMap("HOUSE");
		assertTrue(active().contains(DOOR));
		this.rules.onPlayerMap("TOWN");
		assertFalse(active().contains(DOOR), "other maps show their data again (the door's data is open)");
		assertTrue(active().contains(TREE), "nothing was cut on this visit");
	}

	@Test
	void theSnapshotsBlocksAreTheCurrentMapsLiveBlocks() throws Exception {
		this.rules.onSnapshot(json("{\"player\":{\"map\":\"HOUSE\",\"x\":4,\"y\":8,\"facing\":\"up\"},\"toggles\":{},\"lit\":[],"
				+ "\"blocks\":[{\"map\":\"HOUSE\",\"bx\":0,\"by\":1,\"block\":2},{\"map\":\"TOWN\",\"bx\":5,\"by\":7,\"block\":15}]}"));
		assertTrue(active().contains(DOOR), "the stamped door is closed");
		assertTrue(active().contains(TREE), "another map's entry is not the current map's: the tree stands");
	}

	@Test
	void waterStatesOfTileCellsOpenOnlyForSurfers() throws Exception {
		JsonObject walking = json("{\"mode\":\"idle\",\"bike\":false,\"surf\":false,\"slope\":false,\"hidden\":false,\"dark\":false,"
				+ "\"lit\":false}");
		JsonObject surfing = json("{\"mode\":\"idle\",\"bike\":false,\"surf\":true,\"slope\":false,\"hidden\":false,\"dark\":false,"
				+ "\"lit\":false}");
		assertTrue(active().contains(HULL) && !active().contains(DECK), "she's moored: her hull bars the way, her deck doesn't");
		this.rules.onPlayerMap("TOWN");
		this.rules.onBlocks(json("{\"map\":\"TOWN\",\"changes\":[{\"bx\":0,\"by\":8,\"block\":1},{\"bx\":1,\"by\":8,\"block\":1}]}"));
		assertTrue(active().containsAll(Set.of(HULL, DECK)), "she sailed: water where she lay, closed to walkers");
		assertTrue(drawn().contains(DECK) && !drawn().contains(HULL), "the deck draws its water; the hull's cell is built water");
		this.rules.onPlayerState(surfing);
		assertFalse(active().contains(HULL) || active().contains(DECK), "a surfer crosses both");
		this.rules.onPlayerState(walking);
		assertTrue(active().containsAll(Set.of(HULL, DECK)));
	}

	@Test
	void bouldersCloseTheirCellsOnTheirMap() {
		this.rules.setNpcs("CAVE", List.of(new StoryRules.Npc(1, 1, "boulder"), new StoryRules.Npc(2, 1, "person")));
		assertTrue(active().contains(BOULDER_A));
		assertFalse(active().contains(BOULDER_B), "only boulders close boulder-area cells");
		this.rules.setNpcs("CAVE", List.of(new StoryRules.Npc(2, 1, "boulder")));
		assertFalse(active().contains(BOULDER_A), "pushed away");
		assertTrue(active().contains(BOULDER_B));
	}

	@Test
	void surfBikeAndLitMaps() throws Exception {
		this.rules.onPlayerState(json("{\"mode\":\"idle\",\"bike\":true,\"surf\":false,\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}"));
		assertTrue(this.rules.bike());
		this.rules.onPlayerState(json("{\"mode\":\"idle\",\"bike\":false,\"surf\":true,\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}"));
		assertTrue(this.rules.surf() && !this.rules.bike());
		assertFalse(active().contains(WATER), "water opens through the surf flag, not the active set");

		this.rules.onSnapshot(json("{\"player\":{\"map\":\"CAVE\",\"x\":1,\"y\":1,\"facing\":\"up\"},\"toggles\":{},\"blocks\":[],"
				+ "\"lit\":[\"CAVE\"],\"state\":{\"mode\":\"idle\",\"bike\":false,\"surf\":false,\"slope\":false,\"hidden\":false,"
				+ "\"dark\":true,\"lit\":true}}"));
		assertEquals(Set.of("CAVE"), this.rules.lit());
		this.rules.onLit(json("{\"map\":\"CAVE\",\"lit\":false}"));
		assertTrue(this.rules.lit().isEmpty(), "reset when leaving it");
		this.rules.onPlayerState(json("{\"mode\":\"idle\",\"bike\":false,\"surf\":false,\"slope\":false,\"hidden\":false,\"dark\":true,\"lit\":true}"));
		assertEquals(Set.of("CAVE"), this.rules.lit(), "Flash on the current dark map");
		this.rules.onPlayerState(json("{\"mode\":\"idle\",\"bike\":false,\"surf\":false,\"slope\":false,\"hidden\":false,\"dark\":true,\"lit\":false}"));
		assertTrue(this.rules.lit().isEmpty());
	}

	@Test
	void malformedReportsChangeNothing() throws Exception {
		Set<String> before = active();
		this.rules.onToggles(json("{\"map\":\"town\",\"obj\":\"TOWN_BALL\",\"visible\":false}"));
		this.rules.onToggles(json("{\"map\":\"TOWN\",\"obj\":\"TOWN_BALL\",\"visible\":\"no\"}"));
		this.rules.onBlocks(json("{\"map\":\"TOWN\",\"changes\":[{\"bx\":5,\"by\":7,\"block\":300}]}"));
		this.rules.onPlayerMap("TOWN");
		this.rules.onBlocks(json("{\"map\":\"TOWN\",\"changes\":\"none\"}"));
		assertEquals(before, active());
	}

	@Test
	void withoutAWorldNothingIsClosed() {
		this.rules.setIndex(null);
		assertTrue(active().isEmpty() && drawn().isEmpty());
	}
}
