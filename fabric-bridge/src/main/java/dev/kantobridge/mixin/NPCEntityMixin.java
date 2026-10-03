package dev.kantobridge.mixin;

import com.cobblemon.mod.common.entity.npc.NPCEntity;
import dev.kantobridge.battle.BattleProxies;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Battle proxies are never sent to any client (docs/PLAN.md §4.2). Vanilla pairs an entity with a player only when
 * {@code broadcastToPlayer} is true, and Cobblemon's own hide list is bypassed for operators, so it can't do this.
 */
@Mixin(NPCEntity.class)
abstract class NPCEntityMixin {
	@Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
	private void kantobridge$hideBattleProxy(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
		if (BattleProxies.isProxy((NPCEntity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
