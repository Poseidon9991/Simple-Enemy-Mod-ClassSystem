// Client-side mixin - poses a crouch when a unit is sneaking.
// Registered in the "client" section of the mixin config: model classes
// only exist on the client, so this must never load on a server.
package com.poseidon.sem_classsystem.mixin;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.client.pmc_unit.PmcUnitModel;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitModel;
import net.nekoyuni.SimpleEnemyMod.entity.client.us_unit.USunitModel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// All three SEM unit models share the same field names (verified identical),
// and BHQ reuses PmcUnitModel - so one multi-target mixin covers every unit.
// The server sets the shared sneak flag on the entity (RangedGunAttackGoal
// mixin); here we read it and bend the model into a waist-lean crouch.
//
// We shift POSITIONS only and leave rotations alone: SEM's procedural aim
// writes head/arm rotations every frame from the look direction, so keeping
// them untouched means the gun and eyes stay glued to the target while the
// torso leans - exactly how a real firing crouch looks.
@Mixin({PmcUnitModel.class, RUunitModel.class, USunitModel.class})
public abstract class MixinUnitModelCrouch {

    // ModelPart fields exist in all three targets with identical names.
    // remap=false: SEM-internal names, same in dev and production.
    @Shadow(remap = false) @Final private ModelPart head;
    @Shadow(remap = false) @Final private ModelPart body;
    @Shadow(remap = false) @Final private ModelPart rightArm;
    @Shadow(remap = false) @Final private ModelPart leftArm;

    // TAIL of setupAnim: SEM resets every part's pose, runs the keyframe
    // animations and the procedural aim layers - we then add the crouch
    // offsets on top so the pose composes with idle/aim instead of fighting.
    @Inject(method = "setupAnim", at = @At("TAIL"))
    private void semcs$applyCrouchPose(Entity entity, float limbSwing, float limbSwingAmount,
                                       float ageInTicks, float netHeadYaw, float headPitch,
                                       CallbackInfo ci) {
        // Sneak flag off, or the entity is dying (death anim owns the pose)
        if (!(entity instanceof LivingEntity living)
                || !living.isShiftKeyDown()
                || !living.isAlive()) {
            return;
        }

        // Torso leans forward at the waist (vanilla-sneak style). The chest
        // is a sibling part - pitching it alone detaches it visually, so the
        // head and arms get shifted down/forward to ride the lean.
        this.body.xRot += 0.45F;
        this.body.y += 2.0F;
        this.head.y += 3.6F;
        this.head.z += 1.2F;
        this.rightArm.y += 3.2F;
        this.rightArm.z += 1.2F;
        this.leftArm.y += 3.2F;
        this.leftArm.z += 1.2F;
    }
}
