package dev.kantobridge.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Which of a player's hidden games is followed and which are parked (docs/specs/MULTI_GAME_PLAN.md §4.5, §4.6; MG3's
 * record, MG4's {@code region.state}): the region whose link carries movement, screens and story, and the regions whose
 * links are connected and held still. Persistent per player ({@code kantobridge:presence}), copied on death; a player who
 * never said is taken to follow the legacy region.
 *
 * @param followed the followed link's region, or null when none is followed
 * @param parked   the regions with a parked link, in the client's order, no repeats
 */
public record RegionPresence(@Nullable String followed, List<String> parked) {
	public static final int MAX_PARKED = 16;
	private static final Set<String> FIELDS = Set.of("followed", "parked");

	public static final Codec<RegionPresence> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.optionalFieldOf("followed").forGetter(presence -> Optional.ofNullable(presence.followed())),
			Codec.STRING.listOf().optionalFieldOf("parked", List.of()).forGetter(RegionPresence::parked)
	).apply(instance, (followed, parked) -> new RegionPresence(followed.orElse(null), parked)));

	public static final AttachmentType<RegionPresence> TYPE = AttachmentRegistry.create(
			ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "presence"), builder -> builder.persistent(CODEC).copyOnDeath());

	public RegionPresence {
		parked = List.copyOf(new LinkedHashSet<>(parked));
	}

	/** A presence following {@code region} with nothing parked. */
	public static RegionPresence following(String region) {
		return new RegionPresence(region, List.of());
	}

	/** Registers the attachment type (class initialisation); call once from common init. */
	public static void init() {
		// TYPE is created when this class initialises.
	}

	/** The player's stored presence, or null when they never said. */
	public static @Nullable RegionPresence stored(Player player) {
		return ((AttachmentTarget) player).getAttached(TYPE);
	}

	static void set(Player player, RegionPresence presence) {
		((AttachmentTarget) player).setAttached(TYPE, presence);
	}

	/** Reads a {@code region.state} strictly: well-formed ids, no repeats, at most {@value #MAX_PARKED} parked. */
	public static RegionPresence read(JsonObject data) throws DataException {
		DataReader reader = DataReader.of(data, "").allowOnly(FIELDS);
		String followed = reader.optionalString("followed", DataReader.REGION_ID);
		List<String> parked = new ArrayList<>();
		for (JsonElement element : reader.array("parked")) {
			String id = DataReader.asString(element, reader.path("parked"), DataReader.REGION_ID);
			if (parked.contains(id)) {
				throw new DataException(reader.path("parked"), "repeated region");
			}
			parked.add(id);
		}
		if (parked.size() > MAX_PARKED) {
			throw new DataException(reader.path("parked"), "too many");
		}
		return new RegionPresence(followed, parked);
	}

	/** The {@code region.state} data for this presence. */
	public JsonObject toJson() {
		JsonObject data = new JsonObject();
		if (this.followed != null) {
			data.addProperty("followed", this.followed);
		}
		JsonArray parked = new JsonArray();
		for (String region : this.parked) {
			parked.add(region);
		}
		data.add("parked", parked);
		return data;
	}

	/** True when a link for {@code region} is connected (followed or parked). */
	public boolean has(String region) {
		return region.equals(this.followed) || this.parked.contains(region);
	}

	/** Every region with a link: the followed one first. */
	public List<String> regions() {
		List<String> out = new ArrayList<>();
		if (this.followed != null) {
			out.add(this.followed);
		}
		for (String region : this.parked) {
			if (!region.equals(this.followed)) {
				out.add(region);
			}
		}
		return out;
	}
}
