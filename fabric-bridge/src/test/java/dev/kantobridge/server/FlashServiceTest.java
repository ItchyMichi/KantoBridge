package dev.kantobridge.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.McBootstrap;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.junit.jupiter.api.Test;

/** Flash's Night Vision: only on a dark map the player's hidden game lit, and only our own effect is ever taken. */
class FlashServiceTest {
	private static final String HASH = "0".repeat(64);

	private static WorldIndex world() {
		return new WorldIndex("red", HASH, List.of(
				new MapPlacement("TUNNEL", 0, 42, 0, 10, 10, 1, MapPlacement.Kind.INTERIOR, "PAL", "kantobridge:kanto_cave", true, "TOWN"),
				new MapPlacement("TOWN", 0, 64, 0, 10, 10, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null)),
				HASH, StoryCellTable.empty(), List.of());
	}

	@Test
	void nightVisionOnlyOnALitDarkMap() {
		WorldIndex index = world();
		StoryState state = new StoryState();
		assertFalse(FlashService.wants(index, state, 5.5, 42.0, 5.5), "dark, not lit");
		state.setLit(Set.of("TUNNEL"));
		assertTrue(FlashService.wants(index, state, 5.5, 42.0, 5.5), "dark and lit");
		assertFalse(FlashService.wants(index, state, 5.5, 64.0, 5.5), "the town above isn't dark");
		state.setLit(Set.of("TUNNEL", "TOWN"));
		assertFalse(FlashService.wants(index, state, 5.5, 64.0, 5.5), "a lit map that isn't dark gets nothing");
		assertFalse(FlashService.wants(index, state, 50.5, 42.0, 5.5), "off every map");
		assertFalse(FlashService.wants(null, state, 5.5, 42.0, 5.5), "no Kanto world");
		assertFalse(FlashService.wants(index, null, 5.5, 42.0, 5.5), "no story state yet");
	}

	@Test
	void nightVisionStaysOnUpAShaftBetweenLitDarkFloors() {
		// TUNNEL's cell (2, 2) over B1F's: a ladder from B1F's feet (20) up through TUNNEL's floor (41)
		WorldIndex.Shaft shaft = new WorldIndex.Shaft(WorldIndex.Shaft.LADDER, new CellPos("TUNNEL", 2, 2), new CellPos("TUNNEL_B1F", 2, 2),
				true, 4, 4, 20, 41, Dir.UP);
		WorldIndex index = new WorldIndex("red", HASH, List.of(
				new MapPlacement("TUNNEL", 0, 42, 0, 10, 10, 1, MapPlacement.Kind.INTERIOR, "PAL", "kantobridge:kanto_cave", true, "TOWN"),
				new MapPlacement("TUNNEL_B1F", 0, 20, 0, 10, 10, 2, MapPlacement.Kind.INTERIOR, "PAL", "kantobridge:kanto_cave", true, "TOWN"),
				new MapPlacement("TOWN", 0, 64, 0, 10, 10, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null)),
				Map.of(), HASH, StoryCellTable.empty(), List.of(), List.of(), null, List.of(shaft));
		StoryState state = new StoryState();
		state.setLit(Set.of("TUNNEL", "TUNNEL_B1F"));
		assertTrue(index.toCell(4, 30, 4).isEmpty(), "half way up, in no cell");
		assertTrue(FlashService.wants(index, state, 4.5, 30.0, 4.5), "in the shaft");
		assertTrue(FlashService.wants(index, state, 4.5, 20.0, 4.5), "on the bottom cell");
		assertFalse(FlashService.wants(index, state, 8.5, 30.0, 8.5), "beside it, in the rock");
		state.setLit(Set.of());
		assertFalse(FlashService.wants(index, state, 4.5, 30.0, 4.5), "not lit");
	}

	@Test
	void onlyOurEffectIsOurs() {
		McBootstrap.ensure();
		assertTrue(FlashService.ours(FlashService.nightVision()));
		assertFalse(FlashService.ours(new MobEffectInstance(MobEffects.NIGHT_VISION, 3600)), "a potion's");
		assertFalse(FlashService.ours(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, true, true)),
				"one with particles");
	}
}
