package dev.kantobridge.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kantobridge.KantoBridge;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * WORLD_SESSIONS_PLAN §2.7, D10 (WS1): the Wild Routes visit a player was on when they left the world, persistent per
 * player ({@code kantobridge:zone_visit}), so a rejoin in the same zone continues it: the trainers beaten on it stay
 * quiet until the visit ends (the Wild Routes plan's Q7: they challenge again on the next visit), instead of the visit
 * starting over from the next tick's position. Written when the player disconnects inside a zone, cleared once read on
 * the next join (a join elsewhere ends the visit as leaving the box does). The plan put the zone into
 * {@code kantobridge:presence}; that record is replaced whole by every {@code region.state}, so the visit has its own.
 *
 * @param region the zone's region (MG3; null in a legacy world)
 * @param zone   the zone's id
 * @param beaten the trainers beaten on the visit
 */
public record ZoneVisitRecord(@Nullable String region, String zone, List<String> beaten) {
	public static final int MAX_BEATEN = 256;

	public static final Codec<ZoneVisitRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.optionalFieldOf("region").forGetter(v -> Optional.ofNullable(v.region())),
			Codec.STRING.fieldOf("zone").forGetter(ZoneVisitRecord::zone),
			Codec.STRING.listOf().optionalFieldOf("beaten", List.of()).forGetter(ZoneVisitRecord::beaten)
	).apply(instance, (region, zone, beaten) -> new ZoneVisitRecord(region.orElse(null), zone, beaten)));

	public static final AttachmentType<ZoneVisitRecord> TYPE = AttachmentRegistry.create(
			ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "zone_visit"), builder -> builder.persistent(CODEC));

	public ZoneVisitRecord {
		beaten = List.copyOf(new TreeSet<>(beaten).stream().limit(MAX_BEATEN).toList());
	}

	/** Registers the attachment type (class initialisation); call once from common init. */
	public static void init() {
		// TYPE is created when this class initialises.
	}

	public static @Nullable ZoneVisitRecord stored(Player player) {
		return ((AttachmentTarget) player).getAttached(TYPE);
	}

	static void set(Player player, @Nullable ZoneVisitRecord visit) {
		if (visit == null) {
			((AttachmentTarget) player).removeAttached(TYPE);
		} else {
			((AttachmentTarget) player).setAttached(TYPE, visit);
		}
	}
}
