package dev.kantobridge.mixin;

import com.cobblemon.mod.common.api.PrioritizedList;
import com.cobblemon.mod.common.api.storage.PokemonStoreManager;
import com.cobblemon.mod.common.api.storage.factory.PokemonStoreFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Cobblemon's store factories, which its API keeps private: the hidden game's saves must write the player's party and
 * PC at that moment ({@code SaveService}), but Cobblemon itself writes Pokémon stores only on its own timer, on logout
 * and at shutdown. Cobblemon's own names, so nothing is remapped.
 */
@Mixin(value = PokemonStoreManager.class, remap = false)
public interface PokemonStoreManagerAccessor {
	@Accessor(value = "factories", remap = false)
	PrioritizedList<PokemonStoreFactory> kantobridge$factories();
}
