package dev.kantobridge.net;

import dev.kantobridge.KantoBridge;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The one relay payload between a player's client and the Minecraft server (docs/PLAN.md §3.3). It carries a
 * protocol envelope as JSON text, so the transport never depends on the message schemas.
 *
 * <p>Registered payloads skip vanilla's custom-payload size caps; the string codec's limit is the real one.
 */
public record KbJsonPayload(String json) implements CustomPacketPayload {
	/** Matches {@code limits.relayMaxBytes} in protocol/catalog.json; larger data is split by the application. */
	public static final int MAX_CHARS = 262_144;

	public static final Type<KbJsonPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "json"));
	public static final StreamCodec<ByteBuf, KbJsonPayload> STREAM_CODEC =
			ByteBufCodecs.stringUtf8(MAX_CHARS).map(KbJsonPayload::new, KbJsonPayload::json);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
