// Client-side mixin - poses class-specific stances when a unit is sneaking/crouching.
// Registered in the "client" section of the mixin config: model classes
// only exist on the client, so this must never load on a server.
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.client.UnitProceduralAnimState;
import com.poseidon.sem_classsystem.client.UnitProceduralPoses;
import com.poseidon.sem_classsystem.entity.ai.UnitMeleeAttackGoal;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.tacz.guns.api.item.nbt.GunItemDataAccessor;
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

// All three SEM unit models share the same field names (verified identical),
// and BHQ reuses PmcUnitModel - so one multi-target mixin covers every unit.
//
// The server sets the shared sneak flag on the entity (RangedGunAttackGoal
// mixin); here we read it and apply a CLASS-SPECIFIC firing pose.
//
// All pose math lives in UnitProceduralPoses - the same static library
// VdfUnitModel drives the militia rig with, so a retune lands everywhere.
//
// Pose catalogue (applied AFTER SEM's procedural aim layers so arms still
// track the target while the legs do the work):
//
//  CAMPER / SNIPER  - deep one-knee marksman kneel: right knee forward,
//                     left shin flat, strong forward body lean, left arm
//                     braces forward as a rifle rest.
//  SUPPRESSOR       - wide bipod crouch: knees out, body heavily pitched,
//                     left arm extends forward to "grip the bipod legs".
//  DEMOLISHER       - upright wide-leg blast stance: small rear lean,
//                     legs spread wider (carrying a tube). No kneel -
//                     you don't go prone with an RPG.
//  BREACHER / SWEEPER / RIFLEMAN / JACK / DEFAULT
//                   - tactical half-kneel: moderate torso lean, weight
//                     shifted, CQB-style low silhouette.
@Mixin({PmcUnitModel.class, RUunitModel.class, USunitModel.class})
public abstract class MixinUnitModelCrouch {

    // ModelPart fields exist in all three targets with identical names.
    // remap=false: SEM-internal names, same in dev and production.
    @Shadow(remap = false) @Final private ModelPart unit;
    @Shadow(remap = false) @Final private ModelPart head;
    @Shadow(remap = false) @Final private ModelPart body;
    @Shadow(remap = false) @Final private ModelPart rightArm;
    @Shadow(remap = false) @Final private ModelPart leftArm;
    @Shadow(remap = false) @Final private ModelPart rightLeg;
    @Shadow(remap = false) @Final private ModelPart leftLeg;

    // TAIL of setupAnim: SEM resets every part's pose, runs the keyframe
    // animations and all procedural aim layers - we then layer our class
    // pose on top so it composes with idle/aim instead of fighting it.
    @Inject(method = "setupAnim", at = @At("TAIL"))
    private void semcs$applyClassKneelPose(Entity entity, float limbSwing, float limbSwingAmount,
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
        if (UnitMeleeAttackGoal.isMeleeWeapon(main)) {
            return;
        }

        UnitProceduralAnimState animState = UnitProceduralAnimState.get(living);
        animState.update(living, ageInTicks);

        // 1. Dynamic distance-locked locomotion (replaces SEM's fixed-speed piston leg slide)
        UnitProceduralPoses.applyLocomotionPolish(this.unit, this.head, this.body,
                this.rightArm, this.leftArm, this.rightLeg, this.leftLeg,
                limbSwing, limbSwingAmount, ageInTicks,
                animState.smoothWalkBlend(), animState.smoothCombatBlend());

        // 2. Gun procedural animations: recoil kick, tactical reload, bolt-action cycling
        if (main.getItem() instanceof GunItemDataAccessor) {
            UnitProceduralPoses.applyGunProcedural(living, animState,
                    this.body, this.head, this.rightArm, this.leftArm);
        }

        // 3. Smoothly blended eating food / applying bandage or medkit animation
        UnitProceduralPoses.applyUseItem(animState, ageInTicks,
                this.head, this.rightArm, this.leftArm);

        // 4. Prone firing stance (smoothly blended when Pose.SWIMMING is active).
        // MixinUnitRenderProne tilts the whole PoseStack forward by -82 deg onto
        // the belly; here we counter-pitch the head and arms up so the unit
        // props on its elbows and aims straight forward instead of into the floor.
        float prone = animState.smoothProneBlend();
        if (prone > 0.001F) {
            UnitProceduralPoses.applyPronePose(prone, this.head,
                    this.rightArm, this.leftArm, this.rightLeg, this.leftLeg);
            if (prone > 0.5F) {
                return;
            }
        }

        // 5. Smoothly blended class crouch/kneel stances (no 1-frame snap)
        float crouch = animState.smoothCrouchBlend() * (1.0F - prone);
        if (crouch <= 0.001F) {
            return;
        }

        // Resolve the effective class from the entity (including sidearm pistols).
        WeaponClass cls = WeaponClassSupport.getEffective(living);

        if (cls == null) cls = WeaponClass.RIFLE; // safe default

        UnitProceduralPoses.applyCrouchStance(crouch, cls, this.body, this.head,
                this.rightArm, this.leftArm, this.rightLeg, this.leftLeg);
    }
}
