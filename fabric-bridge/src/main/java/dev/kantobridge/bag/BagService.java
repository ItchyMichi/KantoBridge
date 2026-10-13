package dev.kantobridge.bag;

import com.cobblemon.mod.common.battles.BattleRegistry;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.server.Ledger;
import dev.kantobridge.server.LedgerService;
import dev.kantobridge.server.Regions;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.RegionRecord;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;

/**
 * The hidden game's battle items in the Minecraft inventory (docs/PLAN.md P17, §4.4 {@code BagService}). The bag stays
 * authoritative in the hidden game; Cobblemon battles use the inventory.
 *
 * <ul>
 * <li><b>{@code bag.sync}</b> (C→S, the client relays gen1-bridge's whole mirrored bag, sent after every snapshot and
 * on every change): each mirrored item ({@link BagItems}) is added to or taken from the inventory until it matches, less
 * the uses still in flight (a sync can cross them); a sync that matches changes nothing. Unmirrored items are ignored.
 * During a battle the newest sync waits for its end.</li>
 * <li><b>{@code bag.use}</b> (S→C, relayed to gen1-bridge): whatever Minecraft consumed (a thrown ball, a potion used
 * from Cobblemon's party screen or in battle), checked every {@value #OBSERVE_EVERY_TICKS} ticks; during a battle only
 * once it is over, since Cobblemon gives back an item whose turn never came ({@link BagLedger}).</li>
 * </ul>
 *
 * Items are taken from the main inventory before the hotbar. Logs carry counts and codes only. Server thread only.
 *
 * <p><b>Marked stacks</b> (MG6): every stack the mirror creates carries a custom-data mark ({@value #MARK}); only marked
 * stacks are counted and only they are ever taken, so a ball or potion the player found in Minecraft is theirs for good
 * (marked and unmarked stacks never merge: their components differ). <b>Several games</b> (MULTI_GAME_PLAN §4.9): in a
 * region world the hidden bag no longer drives the mirror; {@code bag.sync} (now with {@code money} and the ledger
 * {@code stamp}) feeds the player's {@link LedgerService ledger}, the mirror shows the ledger's battle items, and a use
 * debits the ledger as well as going to the followed game. A legacy world keeps the per-game path exactly as it was.
 */
public final class BagService {
	static final int OBSERVE_EVERY_TICKS = 5;
	/** {@code bag.use.qty}'s maximum; larger uses go out in parts. */
	static final int MAX_USE = 999;
	private static final Set<String> FIELDS = Set.of("items", "region", "money", "stamp");
	/** The custom-data key on every stack the mirror creates. */
	static final String MARK = "kantobridge_bag";

	/** One {@code bag.sync}: the hidden bag by item symbol, the save's money (-1 when absent), the ledger stamp or null. */
	record Sync(Map<String, Integer> items, long money, @Nullable String stamp) {
	}

	/** One player's mirror. */
	private static final class State {
		final BagLedger ledger = new BagLedger();
		/** The newest sync that arrived during a battle. */
		@Nullable Map<String, Integer> pending;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();
	private static final Map<String, AtomicLong> WARNINGS = new ConcurrentHashMap<>();
	private static final AtomicLong SYNCS = new AtomicLong();
	private static final AtomicLong CHANGED = new AtomicLong();
	private static final AtomicLong USES = new AtomicLong();
	/** The mirrored Cobblemon items, resolved from the registry once. */
	private static @Nullable Map<String, Item> items;
	private static @Nullable Map<Item, String> byItem;

	private BagService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		RelayService.get().register(MsgTypes.BAG_SYNC, BagService::onSync);
		ServerTickEvents.END_SERVER_TICK.register(BagService::tick);
		ServerPlayerEvents.LEAVE.register(player -> STATES.remove(player.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> STATES.clear());
	}

	// ---- bag.sync ----

	static void onSync(ServerPlayer player, Envelope message) {
		Sync sync;
		try {
			sync = read(message.data());
		} catch (DataException e) {
			KantoBridge.LOGGER.debug("bag: malformed bag.sync at {} ({})", e.path(), e.problem());
			warn("bad_message");
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		SYNCS.incrementAndGet();
		Map<String, Integer> bag = sync.items();
		if (LedgerService.enabled(player.server)) {
			// Several games: the report feeds the ledger; the mirror shows the ledger's battle items.
			Regions regions = Regions.of(player.server);
			RegionRecord region = regions == null ? null : regions.regionOf(player, message.data());
			if (region == null) {
				warn("bad_message");
				RelayService.get().sendError(player, "bad_message", message.seq());
				return;
			}
			LedgerService.onSync(player, region, sync.items(), sync.money(), sync.stamp());
			bag = LedgerService.mirrorTargets(player);
		}
		State state = STATES.computeIfAbsent(player.getUUID(), id -> new State());
		if (inBattle(player)) {
			state.pending = bag;
			return;
		}
		apply(player, state, bag, player.server.getTickCount());
	}

	/** {@code bag.sync}: item symbols to counts 0–9999, the optional {@code money} and {@code stamp} (MG6), nothing else. */
	static Sync read(JsonObject data) throws DataException {
		DataReader reader = DataReader.of(data, "").allowOnly(FIELDS);
		DataReader entries = reader.object("items");
		Map<String, Integer> bag = new LinkedHashMap<>();
		for (String item : entries.json().keySet()) {
			if (!Ids.ITEM_ID.matcher(item).matches()) {
				throw new DataException(entries.path(), "bad item id");
			}
			bag.put(item, DataReader.asInt(entries.json().get(item), entries.path() + "/item", 0, BagLedger.MAX));
		}
		long money = reader.has("money") ? reader.longValue("money", 0, Ledger.MAX_MONEY) : -1;
		String stamp = reader.optionalString("stamp", DataReader.HASH);
		return new Sync(bag, money, stamp);
	}

	private static void apply(ServerPlayer player, State state, Map<String, Integer> bag, long now) {
		// What the inventory shows consumed since the last sync goes first (in flight), so a sync never hides a use.
		report(player, state.ledger.observe(counts(player), false, now));
		change(player, state.ledger.sync(bag, counts(player)));
		state.ledger.settled(counts(player));
	}

	/** Adds and takes items as {@code changes} says (Gen 1 item → count to add, negative to take). */
	private static void change(ServerPlayer player, Map<String, Integer> changes) {
		Map<String, Item> mirrored = items();
		int added = 0;
		int removed = 0;
		for (Map.Entry<String, Integer> change : changes.entrySet()) {
			Item item = mirrored.get(change.getKey());
			if (item == null) {
				continue;
			}
			if (change.getValue() > 0) {
				int put = add(player.getInventory(), item, change.getValue());
				added += put;
				if (put < change.getValue()) {
					warn("inventory_full");
				}
			} else {
				removed += remove(player.getInventory(), item, -change.getValue());
			}
		}
		if (added > 0 || removed > 0) {
			player.containerMenu.broadcastChanges();
			player.inventoryMenu.slotsChanged(player.getInventory());
			CHANGED.incrementAndGet();
			KantoBridge.LOGGER.debug("bag: mirrored the bag ({} items added, {} taken)", added, removed);
		}
	}

	// ---- consumption ----

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % OBSERVE_EVERY_TICKS != 0 || STATES.isEmpty()) {
			return;
		}
		for (Map.Entry<UUID, State> entry : STATES.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			State state = entry.getValue();
			if (player == null) {
				continue;
			}
			boolean battle = inBattle(player);
			long now = server.getTickCount();
			if (!battle && state.pending != null) {
				Map<String, Integer> pending = state.pending;
				state.pending = null;
				apply(player, state, pending, now);
				continue;
			}
			report(player, state.ledger.observe(counts(player), battle, now));
			if (!battle) {
				Map<String, Integer> expired = state.ledger.expire(counts(player), now);
				if (!expired.isEmpty()) {
					// Uses no sync ever showed (lost with a link): the hidden game still has those items.
					warn("use_unacknowledged");
					change(player, expired);
					state.ledger.settled(counts(player));
				}
			}
		}
	}

	private static void report(ServerPlayer player, Map<String, Integer> used) {
		boolean ledger = LedgerService.enabled(player.server);
		for (Map.Entry<String, Integer> entry : used.entrySet()) {
			if (ledger) {
				// Several games: the player's own item was consumed; the followed game hears it through bag.use too
				LedgerService.use(player, entry.getKey(), entry.getValue());
			}
			int left = entry.getValue();
			while (left > 0) {
				int qty = Math.min(MAX_USE, left);
				JsonObject data = new JsonObject();
				data.addProperty("item", entry.getKey());
				data.addProperty("qty", qty);
				if (RelayService.get().send(player, MsgTypes.BAG_USE, data, null)) {
					USES.incrementAndGet();
				}
				left -= qty;
			}
		}
	}

	// ---- the inventory ----

	/** How many of each mirrored item the inventory holds in marked stacks (main, armour and off-hand slots). */
	static Map<String, Integer> counts(ServerPlayer player) {
		Map<Item, String> lookup = byItem();
		Map<String, Integer> out = new LinkedHashMap<>();
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			String gen1 = stack.isEmpty() || !marked(stack) ? null : lookup.get(stack.getItem());
			if (gen1 != null) {
				out.merge(gen1, stack.getCount(), Integer::sum);
			}
		}
		return out;
	}

	/** True for a stack the mirror created (the player's own finds carry no mark and are never counted or taken). */
	static boolean marked(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.contains(MARK);
	}

	/** Marks a stack as the mirror's. */
	static ItemStack mark(ItemStack stack) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(MARK, true));
		return stack;
	}

	/** Adds up to {@code count} of an item in marked stacks; returns how many fitted. */
	static int add(Inventory inventory, Item item, int count) {
		int added = 0;
		int max = new ItemStack(item).getMaxStackSize();
		while (added < count) {
			int part = Math.min(max, count - added);
			ItemStack stack = mark(new ItemStack(item, part));
			inventory.add(stack);
			int put = part - stack.getCount();
			added += put;
			if (put < part) {
				break;
			}
		}
		return added;
	}

	/** Takes up to {@code count} of an item from marked stacks, main inventory first, then the off-hand, the hotbar last; returns how many. */
	static int remove(Inventory inventory, Item item, int count) {
		int removed = 0;
		int hotbar = Inventory.getSelectionSize();
		int main = inventory.items.size();
		int[] order = new int[inventory.getContainerSize()];
		int n = 0;
		for (int slot = main - 1; slot >= hotbar; slot--) {
			order[n++] = slot;
		}
		int offhand = main + inventory.armor.size();
		for (int slot = offhand; slot < inventory.getContainerSize(); slot++) {
			order[n++] = slot;
		}
		for (int slot = hotbar - 1; slot >= 0; slot--) {
			order[n++] = slot;
		}
		for (int i = 0; i < n && removed < count; i++) {
			ItemStack stack = inventory.getItem(order[i]);
			if (!stack.isEmpty() && stack.is(item) && marked(stack)) {
				int take = Math.min(stack.getCount(), count - removed);
				stack.shrink(take);
				removed += take;
			}
		}
		if (removed > 0) {
			inventory.setChanged();
		}
		return removed;
	}

	private static boolean inBattle(ServerPlayer player) {
		return BattleRegistry.getBattleByParticipatingPlayer(player) != null;
	}

	/** The Cobblemon item of each mirrored Gen 1 item that the registry has; missing ones are warned about once. */
	public static synchronized Map<String, Item> items() {
		if (items == null) {
			Map<String, Item> resolved = new LinkedHashMap<>();
			Map<Item, String> reverse = new IdentityHashMap<>();
			for (Map.Entry<String, String> entry : BagItems.table().entrySet()) {
				Item item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(BagItems.NAMESPACE, entry.getValue()));
				if (item == Items.AIR) {
					warn("item_missing");
					continue;
				}
				resolved.put(entry.getKey(), item);
				reverse.put(item, entry.getKey());
			}
			items = resolved;
			byItem = reverse;
		}
		return items;
	}

	private static Map<Item, String> byItem() {
		items();
		return byItem;
	}

	private static void warn(String code) {
		long count = WARNINGS.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
		KantoBridge.LOGGER.warn("bag: {} ({} so far)", code, count);
	}

	// ---- views (tests, diagnostics) ----

	public static long warnings(String code) {
		AtomicLong count = WARNINGS.get(code);
		return count == null ? 0 : count.get();
	}

	public static Map<String, Long> warnings() {
		Map<String, Long> out = new TreeMap<>();
		WARNINGS.forEach((code, count) -> out.put(code, count.get()));
		return out;
	}

	/** Syncs received, syncs that changed the inventory, {@code bag.use} messages sent. */
	public static long[] stats() {
		return new long[] {SYNCS.get(), CHANGED.get(), USES.get()};
	}

	/** The ledger's reference count of a Gen 1 item for a player; -1 before the first sync. */
	public static int reference(ServerPlayer player, String gen1) {
		State state = STATES.get(player.getUUID());
		return state == null ? -1 : state.ledger.reference(gen1);
	}

	/** Uses of a Gen 1 item reported for a player and not yet shown applied by a sync. */
	public static int inFlight(ServerPlayer player, String gen1) {
		State state = STATES.get(player.getUUID());
		return state == null ? 0 : state.ledger.inFlight(gen1);
	}
}
