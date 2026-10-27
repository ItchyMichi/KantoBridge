package dev.kantobridge.client.world;

import dev.kantobridge.proto.MsgTypes;
import java.util.Set;

/**
 * The message types whose schemas carry an optional {@code region} (docs/PROTOCOL.md "Regions (MG3)": the relayed
 * types): what the client tags with its followed region's id in a region world ({@link ClientRegions#tagFor}). The set
 * is checked against {@code protocol/messages/*.schema.json} by a test, since Java bundles only the catalog, not the
 * schemas. Types the client never sends ({@code world.index}, {@code world.shapes} and their offers) are listed too: the
 * tag only ever lands on the client-to-server path ({@code world.shapes.get} and {@code world.index.get} name their
 * region themselves).
 */
public final class RegionTags {
	public static final Set<String> TAGGED = Set.of(MsgTypes.WORLD_INDEX, MsgTypes.WORLD_INDEX_OFFER, MsgTypes.WORLD_INDEX_GET,
			MsgTypes.WORLD_SHAPES, MsgTypes.WORLD_SHAPES_OFFER, MsgTypes.WORLD_SHAPES_GET, MsgTypes.TEXTURE_STATUS, MsgTypes.STORY_STATE,
			MsgTypes.SESSION_BIND, MsgTypes.SESSION_BOUND, MsgTypes.SESSION_WAITING, MsgTypes.SAVE_BEGIN, MsgTypes.SAVE_LOADED,
			MsgTypes.SAVE_MISMATCH, MsgTypes.WARP_REQUEST, MsgTypes.ROUTE_GIVE, MsgTypes.ROUTE_BATTLE, MsgTypes.ROUTE_BLACKOUT,
			MsgTypes.ROUTE_RESULT, MsgTypes.ROUTE_PROGRESS, MsgTypes.ROUTE_SIGHT, MsgTypes.ROUTE_FADE, MsgTypes.ROUTE_LEAVE, MsgTypes.PARTY_GIVE, MsgTypes.PARTY_HEAL,
			MsgTypes.PARTY_ITEM, MsgTypes.PARTY_RENAME, MsgTypes.PARTY_SYNC, MsgTypes.PARTY_TAKE, MsgTypes.DAYCARE_DEPOSIT,
			MsgTypes.DAYCARE_WITHDRAW, MsgTypes.BAG_SYNC, MsgTypes.BAG_USE, MsgTypes.BATTLE_NATIVE, MsgTypes.BATTLE_RESULT, MsgTypes.BATTLE_START,
			MsgTypes.PC_OPEN, MsgTypes.PC_CLOSED, MsgTypes.GAME_STATUS, MsgTypes.PARTY_ORDER, MsgTypes.PARTY_CHANGE, MsgTypes.PARTY_HOLD,
			MsgTypes.PARTY_LEARN, MsgTypes.PARTY_FORGET);

	private RegionTags() {
	}

	/** True when messages of {@code type} carry a {@code region}. */
	public static boolean tagged(String type) {
		return TAGGED.contains(type);
	}
}
