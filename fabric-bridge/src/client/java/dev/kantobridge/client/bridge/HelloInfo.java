package dev.kantobridge.client.bridge;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * What the client says about itself in {@code hello} (protocol/messages/hello.schema.json). Collected when a
 * connection opens, so the player is known even if the client initialised before the user was set.
 *
 * @param mod        this mod's version
 * @param minecraft  the game version, if known
 * @param playerUuid the logged-in player, if known
 * @param playerName the player's name; dropped (with the UUID) unless it is a valid Minecraft name
 */
public record HelloInfo(String mod, @Nullable String minecraft, @Nullable UUID playerUuid, @Nullable String playerName) {
	private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z.+_-]{1,32}$");
	private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

	/** The {@code hello} data for {@code role}; fields that wouldn't pass the schema are left out. */
	public JsonObject toData(int protocol, String role) {
		JsonObject data = new JsonObject();
		data.addProperty("protocol", protocol);
		data.addProperty("role", role);
		data.addProperty("mod", VERSION.matcher(this.mod).matches() ? this.mod : "unknown");
		if (this.minecraft != null && VERSION.matcher(this.minecraft).matches()) {
			data.addProperty("minecraft", this.minecraft);
		}
		if (this.playerUuid != null && this.playerName != null && PLAYER_NAME.matcher(this.playerName).matches()) {
			JsonObject player = new JsonObject();
			player.addProperty("uuid", this.playerUuid.toString().toLowerCase(Locale.ROOT));
			player.addProperty("name", this.playerName);
			data.add("player", player);
		}
		return data;
	}
}
