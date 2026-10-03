package dev.kantobridge.proto;

import java.util.regex.Pattern;

/** Shared string formats from protocol/defs.schema.json, for messages read field by field. */
public final class Ids {
	/** {@code id}: an opaque correlation id chosen by the sender ({@code battle}, {@code give}, {@code save}). */
	public static final Pattern ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
	/** {@code uid}: a Pokémon's Cobblemon UUID, lowercase. */
	public static final Pattern UID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
	/** {@code symbol}: an engine symbol id. */
	public static final Pattern SYMBOL = Pattern.compile("^[A-Za-z0-9_]{1,64}$");
	/** {@code npcId}: a live NPC id. */
	public static final Pattern NPC_ID = Pattern.compile("^[A-Za-z0-9_]{1,96}$");
	/** {@code itemId}: an item symbol. */
	public static final Pattern ITEM_ID = Pattern.compile("^[A-Z0-9_]{1,64}$");

	private Ids() {
	}
}
