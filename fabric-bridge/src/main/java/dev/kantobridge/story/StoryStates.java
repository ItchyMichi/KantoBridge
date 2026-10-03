package dev.kantobridge.story;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.world.StoryCellType;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * The {@link StoryState} attachment on players (Fabric data attachments, both sides). It is not persisted: the
 * client sends a full {@code story.state} on every join, rebuilt from the hidden game. It survives respawn, which
 * creates a new player object.
 */
public final class StoryStates {
	public static final AttachmentType<StoryState> TYPE = AttachmentRegistry.create(
			ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "story_state"),
			builder -> builder.initializer(StoryState::new).copyOnDeath());

	private StoryStates() {
	}

	/** Registers the attachment type (class initialisation); call once from common init. */
	public static void init() {
		KantoBridge.LOGGER.debug("story state attachment registered");
	}

	/** The player's story state, created empty on first use. */
	public static StoryState of(Player player) {
		return ((AttachmentTarget) player).getAttachedOrCreate(TYPE);
	}

	/** The entity's story state if it has one; never creates it. */
	public static @Nullable StoryState peek(Entity entity) {
		return ((AttachmentTarget) entity).getAttached(TYPE);
	}

	/**
	 * The player whose story state governs an entity's collisions: the entity itself when it is a player, or the
	 * player steering it (a Cobblemon ride). Null for everything else.
	 */
	public static @Nullable Player governingPlayer(Entity entity) {
		if (entity instanceof Player player) {
			return player;
		}
		return entity.getControllingPassenger() instanceof Player rider ? rider : null;
	}

	/**
	 * Whether a story cell closes for {@code entity} (docs/PLAN.md §4.2): closed with no entity (pathfinding and
	 * other empty contexts), open for entities no player governs, and otherwise the governing player's
	 * {@link StoryState#blocks} rule.
	 */
	public static boolean blocks(@Nullable Entity entity, String key, StoryCellType type) {
		if (entity == null) {
			return true;
		}
		Player player = governingPlayer(entity);
		if (player == null) {
			return false;
		}
		StoryState state = peek(player);
		return state != null ? state.blocks(key, type) : StoryState.blocksWithoutState(type);
	}
}
