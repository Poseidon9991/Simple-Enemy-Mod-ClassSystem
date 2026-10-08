package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.faction.UnitClassAttributes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractUnit.class)
public abstract class MixinAbstractUnit {

    @Inject(method = "finalizeSpawn", at = @At("TAIL"))
    private void semcs$applyClassAttributes(ServerLevelAccessor level, DifficultyInstance difficulty,
                                            MobSpawnType reason, @Nullable SpawnGroupData spawnData,
                                            @Nullable CompoundTag dataTag,
                                            CallbackInfoReturnable<SpawnGroupData> cir) {
        UnitClassAttributes.apply((AbstractUnit) (Object) this, true);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void semcs$reapplyClassAttributes(CompoundTag tag, CallbackInfo ci) {
        UnitClassAttributes.apply((AbstractUnit) (Object) this, false);
    }
}
