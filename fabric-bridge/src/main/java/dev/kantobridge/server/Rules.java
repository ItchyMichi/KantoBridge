package dev.kantobridge.server;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.story.StoryStates;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

/**
 * Minecraft's own rules for Kanto (docs/PLAN.md P2, P7, §4.4 {@code Rules}, Appendix D).
 *
 * <ul>
 * <li>On server start: Appendix D's game rules ({@link KantoGameRules}), Peaceful with the difficulty locked (also no
 * hunger: food never drops on Peaceful), Adventure as the default game type, clear weather and a fixed noon.</li>
 * <li>On join and respawn: Adventure (except players {@link BuildHold} keeps waiting as spectators; they get it when
 * let in), Gen 1 walking or biking speed, and no jumping. Sprinting is stopped on the client
 * ({@code LocalPlayerMixin}): the server can't stop a client from sprinting.</li>
 * <li>Players take no damage, except from sources that bypass invulnerability (the void, {@code /kill}).</li>
 * <li>Cobblemon's settings (Appendix C, {@link CobblemonSettings}): its configuration at server start, its game rules
 * with Minecraft's, and the starter prompt locked for each player once Cobblemon has synchronised their data.</li>
 * </ul>
 *
 * <p>The rules hold on every server running this mod (the GameTest server included): the mod exists to play Kanto.
 */
public final class Rules {
	/**
	 * Gen 1 walking speed as the movement attribute: 16 frames per 2-block cell, 0.375 blocks per tick. On level ground
	 * a held key moves {@code 0.98·speed / (1 - 0.6·0.91)} blocks per tick at steady speed (PLAN P2 rounds it to 0.174).
	 */
	public static final float WALK_SPEED = 0.1737F;
	/** Gen 1 bicycle speed: 8 frames per cell, 0.75 blocks per tick (PLAN P2: ≈ 0.347). */
	public static final float BIKE_SPEED = 0.3474F;
	/** Noon: the fixed time of day. */
	public static final long NOON = 6000L;
	private static final int CLEAR_WEATHER_TICKS = 24000 * 30;

	private Rules() {
	}

	/** Call once from common init, after {@link BuildHold#init}. */
	public static void init() {
		// Appendix C: Cobblemon's configuration before anyone joins, its game rules with the world's, the starter locked
		// for every player once Cobblemon has synchronised their data.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> CobblemonSettings.apply(Cobblemon.INSTANCE.getConfig()));
		CobblemonEvents.DATA_SYNCHRONIZED.subscribe(Priority.NORMAL, player -> {
			CobblemonSettings.lockStarter(player);
		});
		ServerLifecycleEvents.SERVER_STARTED.register(Rules::applyServer);
		ServerPlayerEvents.JOIN.register(Rules::applyPlayer);
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> applyPlayer(newPlayer));
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(Rules::allowDamage);
		BuildHold.onRelease(Rules::applyPlayer);
	}

	/** The server-wide part: game rules, difficulty, default game type, weather and time. Safe to repeat. */
	public static void applyServer(MinecraftServer server) {
		KantoGameRules.apply(server.getGameRules(), server);
		CobblemonSettings.applyGameRules(server);
		server.setDifficulty(Difficulty.PEACEFUL, true);
		server.setDifficultyLocked(true);
		server.setDefaultGameType(GameType.ADVENTURE);
		ServerLevel overworld = server.overworld();
		overworld.setWeatherParameters(CLEAR_WEATHER_TICKS, 0, false, false);
		overworld.setDayTime(NOON);
		KantoBridge.LOGGER.info("rules: {} game rules, peaceful (locked), adventure, clear noon",
				KantoGameRules.BOOLEANS.size() + KantoGameRules.INTEGERS.size());
	}

	/** One player's part: Adventure (unless held for a build) and Gen 1 movement. */
	public static void applyPlayer(ServerPlayer player) {
		if (!BuildHold.isHeld(player)) {
			player.setGameMode(GameType.ADVENTURE);
		}
		applyMovement(player);
	}

	/**
	 * Gen 1 speed for the player's {@code StoryState} (bike or not; running, FA8 item 7, at the bike's 8 frames a cell, as
	 * Emerald runs) and no jumping. The walking-speed ability is set to
	 * the same value as the attribute, so the field of view doesn't widen as it does under a speed effect.
	 */
	public static void applyMovement(ServerPlayer player) {
		StoryState state = StoryStates.peek(player);
		float speed = speedFor(state != null && (state.bike() || state.run()));
		player.getAbilities().setWalkingSpeed(speed);
		AttributeInstance movement = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (movement != null) {
			movement.setBaseValue(speed);
		}
		AttributeInstance jump = player.getAttribute(Attributes.JUMP_STRENGTH);
		if (jump != null) {
			jump.setBaseValue(0.0);
		}
		player.setSprinting(false);
		player.onUpdateAbilities();
	}

	public static float speedFor(boolean bike) {
		return bike ? BIKE_SPEED : WALK_SPEED;
	}

	/** The damage veto: players are never hurt, except by what bypasses invulnerability (falling out of the world). */
	static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		return !(entity instanceof Player) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
	}
}
