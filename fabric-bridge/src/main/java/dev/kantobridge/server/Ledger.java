package dev.kantobridge.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kantobridge.KantoBridge;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * A player's trainer ledger (docs/specs/MULTI_GAME_PLAN.md §4.9, D7, MG6): the one wallet and the one bag that are the
 * player's wherever they go, projected into whichever hidden game is followed and read back when it reports. Items are
 * canonical ids of the item catalogue ({@link dev.kantobridge.world.Items}); class {@code key} never enters it.
 *
 * <p>A persistent attachment on the player ({@code kantobridge:ledger}), copied on death, like the save pair. Numbers,
 * ids and hashes only.
 *
 * @param money   the wallet, 0..{@value #MAX_MONEY}
 * @param items   canonical id → quantity (only positive entries, each at most {@value #MAX_ITEM})
 * @param stamps  region id → the stamp of the last projection into that region's game ({@code bag.set.stamp}), so a
 *                reported bag can be matched to what was projected even after a restart
 * @param adopted the saves merged into the ledger once (their save pair ids, or a region's fresh-game key)
 */
public record Ledger(long money, Map<String, Integer> items, Map<String, String> stamps, Set<String> adopted) {
	/** The wallet's cap: Gen 1's {@code save.money} limit. */
	public static final long MAX_MONEY = 999_999;
	/** The most of one item the ledger counts (the protocol's bag quantities). */
	public static final int MAX_ITEM = 9999;
	/** A player with nothing yet. */
	public static final Ledger NONE = new Ledger(0, Map.of(), Map.of(), Set.of());

	public static final Codec<Ledger> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.LONG.optionalFieldOf("money", 0L).forGetter(Ledger::money),
			Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("items", Map.of()).forGetter(Ledger::items),
			Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("stamps", Map.of()).forGetter(Ledger::stamps),
			Codec.STRING.listOf().optionalFieldOf("adopted", List.of()).forGetter(ledger -> List.copyOf(ledger.adopted()))
	).apply(instance, (money, items, stamps, adopted) -> new Ledger(money, items, stamps, new TreeSet<>(adopted))));

	public static final AttachmentType<Ledger> TYPE = AttachmentRegistry.create(
			ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "ledger"), builder -> builder.persistent(CODEC).copyOnDeath());

	public Ledger {
		money = Math.max(0, Math.min(MAX_MONEY, money));
		Map<String, Integer> kept = new TreeMap<>();
		for (Map.Entry<String, Integer> entry : items.entrySet()) {
			int qty = entry.getValue() == null ? 0 : entry.getValue();
			if (qty > 0) {
				kept.put(entry.getKey(), Math.min(MAX_ITEM, qty));
			}
		}
		items = Collections.unmodifiableMap(kept);
		stamps = Collections.unmodifiableMap(new TreeMap<>(stamps));
		adopted = Collections.unmodifiableSet(new TreeSet<>(adopted));
	}

	/** Registers the attachment type (class initialisation); call once from common init. */
	public static void init() {
		// TYPE is created when this class initialises.
	}

	/** The player's ledger; {@link #NONE} before anything was adopted or earned. */
	public static Ledger of(Player player) {
		Ledger ledger = ((AttachmentTarget) player).getAttached(TYPE);
		return ledger == null ? NONE : ledger;
	}

	public static void set(Player player, Ledger ledger) {
		((AttachmentTarget) player).setAttached(TYPE, ledger);
	}

	// ---- reading ----

	/** How many of a canonical item the ledger holds. */
	public int count(String id) {
		return this.items.getOrDefault(id, 0);
	}

	/** The stamp of the last projection into a region's game, or null when it was never projected. */
	public @Nullable String stamp(String region) {
		return this.stamps.get(region);
	}

	/** True when a save (by its key) was merged into the ledger already. */
	public boolean isAdopted(String key) {
		return this.adopted.contains(key);
	}

	public boolean isEmpty() {
		return this.money == 0 && this.items.isEmpty();
	}

	// ---- changing (every method returns a new ledger) ----

	/** {@code qty} more of an item (a negative quantity takes; never below zero, never above the cap). */
	public Ledger add(String id, int qty) {
		Map<String, Integer> out = new TreeMap<>(this.items);
		out.merge(id, qty, Integer::sum);
		return new Ledger(this.money, out, this.stamps, this.adopted);
	}

	/** {@code qty} fewer of an item (never below zero). */
	public Ledger remove(String id, int qty) {
		return this.add(id, -qty);
	}

	/** Several changes at once (canonical id → delta). */
	public Ledger adjust(Map<String, Integer> deltas) {
		Map<String, Integer> out = new TreeMap<>(this.items);
		for (Map.Entry<String, Integer> delta : deltas.entrySet()) {
			out.merge(delta.getKey(), delta.getValue(), Integer::sum);
		}
		return new Ledger(this.money, out, this.stamps, this.adopted);
	}

	public Ledger withMoney(long money) {
		return new Ledger(money, this.items, this.stamps, this.adopted);
	}

	/** The wallet moved by {@code delta} (clamped to 0..{@value #MAX_MONEY}). */
	public Ledger addMoney(long delta) {
		return this.withMoney(this.money + delta);
	}

	/** The stamp of a region's latest projection remembered. */
	public Ledger withStamp(String region, String stamp) {
		Map<String, String> out = new TreeMap<>(this.stamps);
		out.put(region, stamp);
		return new Ledger(this.money, this.items, out, this.adopted);
	}

	/** A save marked as merged. */
	public Ledger adopt(String key) {
		Set<String> out = new TreeSet<>(this.adopted);
		out.add(key);
		return new Ledger(this.money, this.items, this.stamps, out);
	}
}
