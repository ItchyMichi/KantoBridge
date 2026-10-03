package dev.kantobridge.story;

import dev.kantobridge.KantoBridge;
import java.util.Locale;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * What a player is doing for the hidden game (protocol/messages/activity.schema.json), shown to other players as a
 * speech bubble in M9: in a battle, in a dialogue (one of the bridge's screens), in a cutscene, or nothing. Each client
 * reports its own player's; the server keeps it as an attachment on the player (not saved: the client reports again on
 * every join).
 */
public enum Activity {
	NONE, DIALOGUE, CUTSCENE, BATTLE;

	public static final AttachmentType<Activity> TYPE = AttachmentRegistry.create(
			ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "activity"), builder -> builder.copyOnDeath());

	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public static @Nullable Activity byId(@Nullable String id) {
		for (Activity activity : values()) {
			if (activity.id().equals(id)) {
				return activity;
			}
		}
		return null;
	}

	/** A player's reported activity; {@link #NONE} until their client reported one. */
	public static Activity of(Player player) {
		Activity activity = ((AttachmentTarget) player).getAttached(TYPE);
		return activity == null ? NONE : activity;
	}

	public static void set(Player player, Activity activity) {
		((AttachmentTarget) player).setAttached(TYPE, activity);
	}

	/** Registers the attachment type (class initialisation); call once from common init. */
	public static void init() {
		// TYPE is created when this class initialises.
	}
}
