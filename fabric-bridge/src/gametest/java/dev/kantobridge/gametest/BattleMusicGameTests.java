package dev.kantobridge.gametest;

import com.cobblemon.mod.common.CobblemonEntities;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.BattleBuilder;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.kantobridge.battle.BattleMusic;
import dev.kantobridge.battle.BattleService;
import dev.kantobridge.battle.EncounterService;
import dev.kantobridge.pokemon.Gen1Mons;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Gen 1's battle music instead of Cobblemon's (docs/PLAN.md P22): every battle the hidden game asks for (a trainer, a
 * static encounter) starts with no Cobblemon theme for its player, so only the hidden game's music plays; a natural
 * wild battle is silenced in a Kanto world (one with a WorldIndex) and keeps Cobblemon's theme elsewhere. Made-up
 * battles only.
 */
public class BattleMusicGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_battle_music";
	/** A batch of its own: it publishes a WorldIndex for a moment. */
	private static final String WORLD_BATCH = "kantobridge_battle_music_world";
	private static final String HASH = "0".repeat(64);

	private static @Nullable PlayerBattleActor actor(@Nullable PokemonBattle battle, ServerPlayer player) {
		return battle != null && battle.getActor(player) instanceof PlayerBattleActor actor ? actor : null;
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 300)
	public void theHiddenGamesBattlesPlayNoCobblemonTheme(GameTestHelper helper) {
		ServerPlayer trainer = MockPlayers.join(helper);
		ServerPlayer wild = MockPlayers.join(helper);
		RelayTap.Recorder trainerRelay = RelayTap.open(trainer);
		RelayTap.Recorder wildRelay = RelayTap.open(wild);
		try {
			long silenced = BattleMusic.silenced();
			BattleGameTests.partner(trainer);
			BattleGameTests.startBattle(helper, trainer, "bm-1", "trainer", BattleGameTests.TRAINER);
			PlayerBattleActor fighter = actor(BattleService.battleOf(trainer), trainer);
			helper.assertTrue(fighter != null && fighter.getBattleTheme() == null, "a trainer battle kept Cobblemon's theme");
			EncounterGameTests.start(helper, wild, "bm-2", "static", 143, 30, true, false);
			PlayerBattleActor catcher = actor(EncounterService.battleOf(wild), wild);
			helper.assertTrue(catcher != null && catcher.getBattleTheme() == null, "a static encounter kept Cobblemon's theme");
			helper.assertTrue(BattleMusic.silenced() >= silenced + 2, "the silenced battles weren't counted");
		} catch (RuntimeException e) {
			trainerRelay.close();
			wildRelay.close();
			MockPlayers.leaveNow(helper, trainer);
			MockPlayers.leaveNow(helper, wild);
			throw e;
		}
		MockPlayers.leaveAfterBattle(helper, helper.startSequence(), () -> {
			trainerRelay.close();
			wildRelay.close();
		}, trainer, wild);
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = WORLD_BATCH, timeoutTicks = 300)
	public void naturalWildBattlesAreSilencedOnlyInKanto(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer outsider = MockPlayers.join(helper);
		ServerPlayer local = MockPlayers.join(helper);
		PokemonEntity[] wilds = new PokemonEntity[2];
		try {
			// Outside Kanto (no WorldIndex): Cobblemon's own wild theme.
			BattleGameTests.partner(outsider);
			wilds[0] = spawn(helper, 2);
			PlayerBattleActor actor = actor(start(outsider, wilds[0]), outsider);
			helper.assertTrue(actor != null && actor.getBattleTheme() != null, "outside a Kanto world Cobblemon's theme must stay");
			// In a Kanto world: silent.
			WorldIndexSync.publish(server, new WorldIndex("red", HASH, List.of(new MapPlacement("TEST_FIELD", 0, 64, 0, 4, 4, 0,
					MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null)), HASH, StoryCellTable.empty(), List.of()), false);
			BattleGameTests.partner(local);
			wilds[1] = spawn(helper, 5);
			PlayerBattleActor kanto = actor(start(local, wilds[1]), local);
			helper.assertTrue(kanto != null && kanto.getBattleTheme() == null, "a wild battle in a Kanto world kept Cobblemon's theme");
		} catch (RuntimeException e) {
			WorldIndexSync.withdraw(server);
			MockPlayers.leaveNow(helper, outsider);
			MockPlayers.leaveNow(helper, local);
			discard(wilds);
			throw e;
		} finally {
			// Batches run one after another; nothing may linger.
			WorldIndexSync.withdraw(server);
		}
		MockPlayers.leaveAfterBattle(helper, helper.startSequence(), () -> discard(wilds), outsider, local);
	}

	private static PokemonEntity spawn(GameTestHelper helper, int x) {
		PokemonEntity entity;
		try {
			entity = new PokemonEntity(helper.getLevel(), Gen1Mons.wild(19, 5), CobblemonEntities.POKEMON);
		} catch (Gen1Mons.Missing e) {
			throw new GameTestAssertException("Cobblemon lacks " + e.code());
		}
		BlockPos at = helper.absolutePos(new BlockPos(x, 1, 2));
		entity.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
		entity.setNoAi(true);
		helper.assertTrue(helper.getLevel().addFreshEntity(entity), "the wild Pokémon didn't enter the level");
		return entity;
	}

	private static PokemonBattle start(ServerPlayer player, PokemonEntity entity) {
		BattleStartResult result = BattleBuilder.INSTANCE.pve(player, entity);
		if (!(result instanceof SuccessfulBattleStart started)) {
			throw new GameTestAssertException("Cobblemon didn't start the wild battle");
		}
		return started.getBattle();
	}

	private static void discard(PokemonEntity[] entities) {
		for (PokemonEntity entity : entities) {
			if (entity != null && !entity.isRemoved()) {
				entity.discard();
			}
		}
	}
}
