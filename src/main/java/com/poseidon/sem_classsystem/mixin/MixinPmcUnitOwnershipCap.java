// Mixin package - last line of the ownership cap: even if some path
// (another mod, a command, an addon) reaches setOwner directly, the unit
// refuses to join a squad that is already at [general] maxOwnedUnits.
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.event.UnitOwnershipCap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(PmcUnitEntity.class)
public abstract class MixinPmcUnitOwnershipCap {

    @Inject(method = "setOwner", at = @At("HEAD"), cancellable = true, remap = false)
    private void semcs$ownershipCap(UUID uuid, CallbackInfo ci) {
        PmcUnitEntity self = (PmcUnitEntity) (Object) this;
        if (uuid == null || uuid.equals(self.getOwnerUUID())
                || self.level().isClientSide) {
            return;   // unclaim / re-set same owner / client echo: always fine
        }
        MinecraftServer server = self.getServer();
        int cap = ClassSystemConfig.maxOwnedUnits();
        if (server == null || cap <= 0) {
            return;
        }
        if (UnitOwnershipCap.ownedCount(server, uuid) >= cap) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                player.displayClientMessage(Component.literal(
                        "Unit limit reached (" + cap
                                + "). Dismiss or lose units before recruiting more."),
                        true);
            }
            ci.cancel();
        }
    }
}
