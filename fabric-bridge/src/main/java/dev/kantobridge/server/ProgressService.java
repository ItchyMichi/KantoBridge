package dev.kantobridge.server;

import com.mojang.serialization.Codec;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.RegionRecord;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Each player's story progress per region (docs/specs/MULTI_GAME_PLAN.md §4.7, MG3): the client forwards its hidden
 * game's {@code game.status} with its link's {@code region}; the server keeps the last report per region in the
 * persistent {@code kantobridge:progress} attachment ({@link ProgressRecord}), shows it on F7 (MG4) and reads it at a
 * pass to choose between resuming and starting the far game (MG5). A client speaks only for its own player; a malformed
 * report changes nothing and is answered with {@code error{bad_message}}; a report for a region this world doesn't have
 * is refused the same way. Logs carry region ids, playthrough words and counts only. Server thread only.
 */
public final class ProgressService {
	/** Region id → the last report. */
	public record Progress(Map<String, ProgressRecord> byRegion) {
		public static final Progress NONE = new Progress(Map.of());
		public static final Codec<Progress> CODEC = Codec.unboundedMap(Codec.STRING, ProgressRecord.CODEC).xmap(Progress::new, Progress::byRegion);

		public Progress {
			byRegion = Collections.unmodifiableMap(new TreeMap<>(byRegion));
		}

		public ProgressRecord get(String region) {
			return this.byRegion.getOrDefault(region, ProgressRecord.NONE);
		}

		public Progress with(String region, ProgressRecord record) {
			Map<String, ProgressRecord> out = new TreeMap<>(this.byRegion);
			out.put(region, record);
			return new Progress(out);
		}
	}

	public static final AttachmentType<Progress> TYPE = AttachmentRegistry.create(
			ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "progress"), builder -> builder.persistent(Progress.CODEC).copyOnDeath());

	private static final Map<String, AtomicLong> WARNINGS = new ConcurrentHashMap<>();
	private static final AtomicLong REPORTS = new AtomicLong();
	private static LongSupplier clock = () -> Instant.now().getEpochSecond();

	private ProgressService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		RelayService.get().register(MsgTypes.GAME_STATUS, ProgressService::onStatus);
	}

	static void onStatus(ServerPlayer player, Envelope message) {
		ProgressRecord.Status status;
		try {
			status = ProgressRecord.read(message.data(), clock.getAsLong());
		} catch (DataException e) {
			warn("bad_message");
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		Regions regions = Regions.of(player.server);
		RegionRecord region = regions == null ? null : regions.regionOf(player, status.region());
		if (region == null) {
			// not a Kanto world, or a region this world doesn't have
			warn(regions == null ? "no_regions" : "unknown_region");
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		set(player, region.id(), status.progress());
		REPORTS.incrementAndGet();
		KantoBridge.LOGGER.debug("progress: {} is {} ({} badges)", region.id(), status.progress().playthrough(), status.progress().badges());
	}

	/** Every region's progress the player reported ({@link Progress#NONE} before the first report). */
	public static Progress all(Player player) {
		Progress progress = ((AttachmentTarget) player).getAttached(TYPE);
		return progress == null ? Progress.NONE : progress;
	}

	/** The player's progress in a region ({@link ProgressRecord#NONE} when its game never reported). */
	public static ProgressRecord of(Player player, String region) {
		return all(player).get(region);
	}

	/** Records a report (the relay handler's and, for tests, anyone's). */
	public static void set(Player player, String region, ProgressRecord record) {
		((AttachmentTarget) player).setAttached(TYPE, all(player).with(region, record));
	}

	private static void warn(String code) {
		long count = WARNINGS.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
		KantoBridge.LOGGER.warn("progress: {} ({} so far)", code, count);
	}

	public static long warnings(String code) {
		AtomicLong count = WARNINGS.get(code);
		return count == null ? 0 : count.get();
	}

	/** Reports stored so far. */
	public static long reports() {
		return REPORTS.get();
	}

	/** Tests: the clock the reports are stamped with (Unix seconds). */
	static void setClock(@Nullable LongSupplier source) {
		clock = source == null ? () -> Instant.now().getEpochSecond() : source;
	}
}
