// Mixin package - widens suppression for Suppressor-class shooters
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.event.server.PlayerSuppressionEventHandler;
import net.nekoyuni.SimpleEnemyMod.network.ModNetworking;
import net.nekoyuni.SimpleEnemyMod.network.packets.PacketSuppression;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SEM's PlayerSuppressionEventHandler sends PacketSuppression to players
// within a hardcoded 7-block radius of a bullet impact - and ONLY when the
// shooter is an RU or US unit (PMC and BHQ never suppress anyone).
// TAIL-inject re-runs the same falloff math at a bigger radius whenever a
// Suppressor-class unit fires: 14-block zone, ~40% stronger peak. Works for
// every faction - a BHQ Suppressor rattles you just like an RU one.
// remap=false: onAmmoHitBlock and everything it touches are mod classes.
@Mixin(PlayerSuppressionEventHandler.class)
public abstract class MixinBulletImpactSuppression {

    @Inject(method = "onAmmoHitBlock", at = @At("TAIL"), remap = false)
    private static void semcs$heavySuppression(AmmoHitBlockEvent event, CallbackInfo ci) {
        Entity owner = event.getAmmo().getOwner();
        if (!(owner instanceof LivingEntity living)
                || WeaponClassSupport.getEffective(living) != WeaponClass.HEAVY_MG) {
            return;
        }
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        Vec3 pos = event.getHitResult().getLocation();
        double radius = 14.0;                 // SEM uses 7.0 flat
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class,
                new AABB(pos, pos).inflate(radius))) {
            double d2 = player.distanceToSqr(pos);
            if (d2 < radius * radius) {
                // same falloff curve as SEM, scaled 1.4x - closer = harder hit
                float amount = (float) (1.0 - d2 / (radius * radius)) * 0.3f * 1.4f;
                ModNetworking.sendToPlayer(new PacketSuppression(amount), player);
            }
        }
    }
}
