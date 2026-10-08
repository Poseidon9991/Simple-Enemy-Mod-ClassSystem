package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.faction.UnitClassAttributes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.RangedGunAttackGoal;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.utils.AiGunSpreadHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RangedGunAttackGoal.class)
public abstract class MixinRangedGunAttackGoal {

    @Shadow(remap = false)
    @Final
    private Mob mob;

    @Redirect(method = "handleBurstFiringState", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/ai/goals/utils/AiGunSpreadHelper;CalculateSpread(FFFFLnet/minecraft/util/RandomSource;)F"))
    private float semcs$classScaledSpread(float idealAngle, float distance, float baseSpread,
                                          float spreadIncreasePerBlock, RandomSource random) {
        return AiGunSpreadHelper.CalculateSpread(idealAngle, distance,
                baseSpread * (float) UnitClassAttributes.spreadMultiplier(this.mob),
                spreadIncreasePerBlock, random);
    }
}
