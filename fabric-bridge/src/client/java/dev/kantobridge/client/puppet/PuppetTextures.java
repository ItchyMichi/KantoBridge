package dev.kantobridge.client.puppet;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.pack.ArtKeys;
import dev.kantobridge.client.packs.KbPackReload;
import dev.kantobridge.client.packs.LoadedPack;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.WorldIndex;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Which texture draws a puppet: its sprite's sheet in its map's palette from the player's own generated pack
 * ({@code assets/kantobridge/textures/npc/<art set>/<palette>/<sprite>.png}, docs/PROTOCOL.md "The generated resource
 * pack"; the art set is the loaded pack's),
 * looked up once per pair and kept until resources reload. A sheet the pack lacks (no pack yet, a map or sprite it
 * doesn't know) falls back to a neutral placeholder from the mod jar, never to Minecraft's missing-texture checkerboard.
 */
public final class PuppetTextures {
	/** A plain grey figure in the sheet layout; made for this mod, not game art. */
	public static final ResourceLocation PLACEHOLDER = ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID,
			"textures/entity/puppet_placeholder.png");

	/** By palette, then sprite: no key string is made per look-up (M10: looked up every frame per drawn puppet). */
	private final Map<String, Map<String, ResourceLocation>> cache = new HashMap<>();
	private long missing;

	/** The texture for a puppet on a world with {@code index} (null outside Kanto worlds). */
	public ResourceLocation texture(Puppet puppet, @Nullable WorldIndex index) {
		MapPlacement map = index == null ? null : index.map(puppet.map());
		if (map == null) {
			return PLACEHOLDER;
		}
		Map<String, ResourceLocation> sprites = this.cache.computeIfAbsent(map.palette(), palette -> new HashMap<>());
		ResourceLocation found = sprites.get(puppet.sprite());
		if (found == null) {
			found = this.lookup(map.palette(), puppet.sprite());
			sprites.put(puppet.sprite(), found);
		}
		return found;
	}

	/** Resources reloaded (a new generated pack): look everything up again. */
	public void clear() {
		this.cache.clear();
	}

	public long missing() {
		return this.missing;
	}

	private ResourceLocation lookup(String palette, String sprite) {
		// A texture pack's sheet first (V6), when it exists.
		String override = KbPackReload.current().spriteTexture(sprite);
		ResourceLocation packed = override == null ? null : ResourceLocation.tryParse(override);
		if (packed != null && Minecraft.getInstance().getResourceManager().getResource(packed).isPresent()) {
			return packed;
		}
		String artSet = LoadedPack.artSet();
		ResourceLocation location = artSet == null ? null : ResourceLocation.tryParse(ArtKeys.npcTexture(artSet, palette, sprite));
		if (location != null && Minecraft.getInstance().getResourceManager().getResource(location).isPresent()) {
			return location;
		}
		this.missing++;
		// Engine symbols only: safe to log.
		KantoBridge.LOGGER.warn("puppets: no sheet for {} in palette {}; drawing a placeholder", sprite, palette);
		return PLACEHOLDER;
	}
}
