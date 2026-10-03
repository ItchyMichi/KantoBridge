package dev.kantobridge.mixin.client;

import dev.kantobridge.client.packs.GeneratedPack;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.BuiltInPackSource;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Offers the locally generated resource pack ({@code <gameDir>/kantobridge/resourcepack/}) as a required built-in
 * client pack. Pack repositories have no add-source API, so this follows Fabric API's own pattern for its client
 * packs: append to the client's built-in source when it lists its packs (P5 research §3.2–3.3). Sources are asked
 * again on every reload, so a pack written later appears on the next resource reload.
 */
@Mixin(BuiltInPackSource.class)
abstract class BuiltInPackSourceMixin implements GeneratedPack.SourceMarker {
	@Inject(method = "loadPacks", at = @At("RETURN"))
	private void kantobridge$offerGeneratedPack(Consumer<Pack> consumer, CallbackInfo ci) {
		if ((Object) this instanceof ClientPackSource) {
			GeneratedPack.offer(FabricLoader.getInstance().getGameDir(), consumer);
		}
	}
}
