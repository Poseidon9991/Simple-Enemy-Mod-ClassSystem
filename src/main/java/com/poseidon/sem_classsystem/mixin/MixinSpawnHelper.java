// Mixin package - keeps patrol/event squads out of tree canopies
package com.poseidon.sem_classsystem.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.nekoyuni.SimpleEnemyMod.spawn.utils.SpawnHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// SEM picks the spawn Y via MOTION_BLOCKING_NO_LEAVES, which ignores leaves
// entirely, so a random point under a tree canopy lands on the dirt INSIDE
// the leaf volume. isValidSpawn then only checks floor solidity + light -
// it never checks the body space - and the whole squad materializes
// wedged in foliage. Reject any candidate whose feet or head space has a
// collision shape (leaves, glass, solid blocks); air, tall grass and snow
// layers stay valid. Callers just skip the spot and retry elsewhere.
// remap=false: isValidSpawn is a mod method with no srg name.
@Mixin(SpawnHelper.class)
public abstract class MixinSpawnHelper {

    @Inject(method = "isValidSpawn", at = @At("RETURN"), cancellable = true, remap = false)
    private static void semcs$rejectCrowdedSpawns(ServerLevel level, BlockPos pos,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()
                && (semcs$collides(level, pos) || semcs$collides(level, pos.above()))) {
            cir.setReturnValue(false);
        }
    }

    @Unique
    private static boolean semcs$collides(ServerLevel level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }
}
