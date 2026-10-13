package dev.kantobridge.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.RegionTable;
import dev.kantobridge.world.WorldIndex;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * The server's regions (docs/specs/MULTI_GAME_PLAN.md §4.6, MG3: the plan's {@code Regions.of(server)}): the world's
 * {@link dev.kantobridge.world.Regions} (its table and records) with the WorldIndex published for each region, and
 * "which region is this player in". {@link WorldIndexSync} builds one on every publish; a legacy world (no
 * {@code regions.json}) is one implicit region, so every service can ask the same questions of every world (§4.14).
 *
 * <p>Services resolve a region from a relayed message's optional {@code region}, else from the player's position, else
 * the legacy region ({@link #regionOf(ServerPlayer, String)}). Server thread only; ids and counts in logs.
 */
public final class Regions {
	private final dev.kantobridge.world.Regions regions;
	private final Map<String, WorldIndex> indices;

	/**
	 * @param regions the world's regions
	 * @param indices the published WorldIndex per region id (a region whose index is unreadable has none)
	 */
	Regions(dev.kantobridge.world.Regions regions, Map<String, WorldIndex> indices) {
		this.regions = Objects.requireNonNull(regions, "regions");
		this.indices = Collections.unmodifiableMap(indices);
	}

	/** The server's regions, or null when the world has no published index (not a Kanto world). */
	public static @Nullable Regions of(@Nullable MinecraftServer server) {
		return server == null ? null : WorldIndexSync.regions(server);
	}

	/**
	 * Tags a region-specific S→C message with its {@code region}, in a region world only: a legacy world's wire stays
	 * exactly what it was (§4.14). Returns {@code data}.
	 */
	public static JsonObject tag(JsonObject data, @Nullable MinecraftServer server, @Nullable String region) {
		if (region != null && WorldIndexSync.isRegionWorld(server)) {
			data.addProperty("region", region);
		}
		return data;
	}

	/** True when the world has at least one published region (a Kanto world of any shape). */
	public static boolean exists(@Nullable MinecraftServer server) {
		return of(server) != null;
	}

	public dev.kantobridge.world.Regions regions() {
		return this.regions;
	}

	public RegionTable table() {
		return this.regions.table();
	}

	/** The region ids in table (slot) order. */
	public List<String> ids() {
		return this.regions.ids();
	}

	public int size() {
		return this.regions.size();
	}

	/** True for a world without {@code regions.json}: one implicit region, today's wire format (§4.14). */
	public boolean isLegacyWorld() {
		return this.regions.isLegacyWorld();
	}

	public @Nullable RegionRecord byId(String id) {
		return this.regions.byId(id);
	}

	/** The region block column (x, z) lies in (its bounds, then its areas), or null between regions. */
	public @Nullable RegionRecord at(int x, int z) {
		return this.regions.at(x, z);
	}

	/** The legacy region: slot 0, else the only one, else null. */
	public @Nullable RegionRecord legacy() {
		return this.regions.legacy();
	}

	/** The published WorldIndex of a region, or null (unknown region, or its index was unreadable). */
	public @Nullable WorldIndex indexOf(@Nullable String regionId) {
		return regionId == null ? null : this.indices.get(regionId);
	}

	/** The published indices by region id, in table order. */
	public Map<String, WorldIndex> indices() {
		return this.indices;
	}

	/** The region the player stands in, else the legacy region (a player between regions belongs to it), else null. */
	public @Nullable RegionRecord regionAt(ServerPlayer player) {
		RegionRecord at = this.at(Mth.floor(player.getX()), Mth.floor(player.getZ()));
		return at != null ? at : this.legacy();
	}

	/** {@link #regionAt}'s index. */
	public @Nullable WorldIndex indexAt(ServerPlayer player) {
		RegionRecord region = this.regionAt(player);
		return region == null ? null : this.indexOf(region.id());
	}

	/**
	 * The region a message is about: the named one when the message carries a {@code region} (null when that id is not
	 * a region of this world: the caller answers {@code error{code:"bad_message"}}), else {@link #regionAt}.
	 */
	public @Nullable RegionRecord regionOf(ServerPlayer player, @Nullable String region) {
		return region != null ? this.byId(region) : this.regionAt(player);
	}

	/**
	 * {@link #regionOf(ServerPlayer, String)} reading the message's optional {@code region} itself: absent means the
	 * player's region; a value that isn't a region id of this world (or isn't a string) gives null.
	 */
	public @Nullable RegionRecord regionOf(ServerPlayer player, JsonObject data) {
		if (!data.has("region")) {
			return this.regionAt(player);
		}
		JsonElement value = data.get("region");
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			return null;
		}
		String id = value.getAsString();
		return DataReader.REGION_ID.matcher(id).matches() ? this.byId(id) : null;
	}

	/** The index a message is about: {@link #regionOf(ServerPlayer, String)}'s, or null. */
	public @Nullable WorldIndex indexFor(ServerPlayer player, @Nullable String region) {
		RegionRecord record = this.regionOf(player, region);
		return record == null ? null : this.indexOf(record.id());
	}

	// ---- shorthand for services ----

	/** The index of the region {@code player} stands in (else the legacy region's), or null without regions. */
	public static @Nullable WorldIndex currentIndexAt(ServerPlayer player) {
		Regions regions = of(player.server);
		return regions == null ? null : regions.indexAt(player);
	}

	/**
	 * The index a relayed message with an optional {@code region} is about, or null when there are no regions or the
	 * named region is unknown.
	 */
	public static @Nullable WorldIndex currentIndexFor(ServerPlayer player, @Nullable String region) {
		Regions regions = of(player.server);
		return regions == null ? null : regions.indexFor(player, region);
	}

	/** The region id a relayed message is about (its {@code region}, else the player's), or null without regions. */
	public static @Nullable String currentRegionId(ServerPlayer player, @Nullable String region) {
		Regions regions = of(player.server);
		RegionRecord record = regions == null ? null : regions.regionOf(player, region);
		return record == null ? null : record.id();
	}

	/** The legacy region's id for this server ({@code kanto} when the world has none published). */
	public static String legacyId(@Nullable MinecraftServer server) {
		Regions regions = of(server);
		RegionRecord legacy = regions == null ? null : regions.legacy();
		return legacy == null ? RegionRecord.LEGACY_ID : legacy.id();
	}

	/** True when {@code region} (null, or an id) means the legacy region of this server's world. */
	public static boolean isLegacy(@Nullable MinecraftServer server, @Nullable String region) {
		return region == null || region.equals(legacyId(server));
	}
}
