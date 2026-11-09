// Client-side mixin - Better Combat compatible melee stances & 3-hit combo animations
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.client.UnitProceduralPoses;
import com.poseidon.sem_classsystem.entity.ai.UnitMeleeAttackGoal;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.client.pmc_unit.PmcUnitModel;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitModel;
import net.nekoyuni.SimpleEnemyMod.entity.client.us_unit.USunitModel;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Replaces SEM's baked rifle-holding torso twist & shoulder offsets with
// natural humanoid shoulder pivots and Better Combat 3-hit combo animations
// (Right-to-Left Cleave -> Left-to-Right Backhand -> Overhead/Thrust Finisher).
// All math lives in UnitProceduralPoses.applyMeleePose so VdfUnitModel shares it.
@Mixin({PmcUnitModel.class, RUunitModel.class, USunitModel.class})
public abstract class MixinUnitMeleeWeaponPose<T extends Entity> extends HierarchicalModel<T> {

    @Shadow(remap = false) @Final private ModelPart unit;
    @Shadow(remap = false) @Final private ModelPart head;
    @Shadow(remap = false) @Final private ModelPart body;
    @Shadow(remap = false) @Final private ModelPart rightArm;
    @Shadow(remap = false) @Final private ModelPart leftArm;
    @Shadow(remap = false) @Final private ModelPart rightLeg;
    @Shadow(remap = false) @Final private ModelPart leftLeg;

    @Inject(method = "setupAnim", at = @At("TAIL"))
    private void semcs$applyMeleePose(Entity entity, float limbSwing, float limbSwingAmount,
                                      float ageInTicks, float netHeadYaw, float headPitch,
                                      CallbackInfo ci) {
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
            return;
        }
        if (entity instanceof AbstractUnit unitEntity
                && (unitEntity.deathAnimationState.isStarted() || unitEntity.hurtAnimationState.isStarted())) {
            return;
        }

        ItemStack main = living.getMainHandItem();
        if (!UnitMeleeAttackGoal.isMeleeWeapon(main)) {
            return;
        }

        UnitProceduralPoses.applyMeleePose(living, limbSwing, limbSwingAmount, ageInTicks,
                this.attackTime, this.unit, this.head, this.body,
                this.rightArm, this.leftArm, this.rightLeg, this.leftLeg);
    }
}
