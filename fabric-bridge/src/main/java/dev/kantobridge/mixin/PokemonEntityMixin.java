package dev.kantobridge.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.kantobridge.entity.DeferredRemovals;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cobblemon discards a player's own sent-out Pokémon (not one tethered to a pasture) in {@code stopSeenByPlayer} when
 * that player stops seeing it: they leave, respawn or change dimension, or move out of its range. Minecraft calls that
 * from inside {@code ChunkMap}'s loops over its tracked entities, so the discard took the Pokémon out of the map being
 * iterated, and the server crashed when a player left with a Pokémon out (in or right after a battle: its recall plays
 * for 1.5 s). The same discard now happens at the end of the server tick ({@link DeferredRemovals}). Such a Pokémon is
 * never saved with the world (Cobblemon saves only tethered ones), so a discard a stopping server never gets to is
 * harmless.
 */
@Mixin(PokemonEntity.class)
abstract class PokemonEntityMixin {
	@Inject(method = "stopSeenByPlayer", at = @At("HEAD"), cancellable = true)
	private void kantobridge$discardOutsideTracking(ServerPlayer player, CallbackInfo ci) {
		PokemonEntity self = (PokemonEntity) (Object) this;
		if (player.getUUID().equals(self.getOwnerUUID()) && self.getTethering() == null) {
			ci.cancel();
			if (self.getRemovalReason() != Entity.RemovalReason.DISCARDED) {
				DeferredRemovals.discardLater(self);
			}
		}
	}
}
