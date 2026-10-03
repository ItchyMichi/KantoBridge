package dev.kantobridge.story;

import dev.kantobridge.KantoBridge;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/**
 * Whether a player surfs, for the other players' clients (M10; PROGRESS's open issue "other players' surfing"): the
 * surf flag lives in each player's {@link StoryState}, which isn't synced, so without this a surfer seems to stand on
 * the water to everyone else. The server sets it from the player's relayed {@code story.state}; Fabric syncs it like the
 * {@link Activity}, to every client tracking the player but the player's own (which draws its own surf sprite from its
 * local state). Not saved: the client relays a full state on every join.
 */
public final class Surfing {
	public static final AttachmentType<Boolean> TYPE = AttachmentRegistry.create(
			ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "surfing"),
			builder -> builder.copyOnDeath().syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.allButTarget()));

	private Surfing() {
	}

	/** Registers the attachment type (class initialisation); call once from common init. */
	public static void init() {
		// TYPE is created when this class initialises.
	}

	/** True while the player surfs (on a client: as last synced for another player). */
	public static boolean of(Player player) {
		Boolean surfing = ((AttachmentTarget) player).getAttached(TYPE);
		return surfing != null && surfing;
	}

	/** Sets the flag (server side; a change syncs to the other players' clients). Unchanged costs nothing. */
	public static void set(Player player, boolean surfing) {
		if (of(player) != surfing) {
			((AttachmentTarget) player).setAttached(TYPE, surfing);
		}
	}
}
