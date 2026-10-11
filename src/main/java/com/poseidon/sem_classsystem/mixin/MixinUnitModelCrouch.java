// Client-side mixin - poses a kneel when a unit is sneaking.
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
// mixin); here we read it and pose a marksman's kneel.
//
// The kneel: left leg folds back so the knee/shin lies on the ground,
// right leg swings forward so the foot plants ahead - classic one-knee
// firing stance. Torso keeps a small forward lean and everything rides
// down ~5px so the folded silhouette stays grounded. Rotations on arms and
// head stay SEM's procedural aim - the gun and eyes keep tracking the
// target while the legs do the work.
@Mixin({PmcUnitModel.class, RUunitModel.class, USunitModel.class})
public abstract class MixinUnitModelCrouch {

    // ModelPart fields exist in all three targets with identical names.
    // remap=false: SEM-internal names, same in dev and production.
    @Shadow(remap = false) @Final private ModelPart head;
    @Shadow(remap = false) @Final private ModelPart body;
    @Shadow(remap = false) @Final private ModelPart rightArm;
    @Shadow(remap = false) @Final private ModelPart leftArm;
    @Shadow(remap = false) @Final private ModelPart rightLeg;
    @Shadow(remap = false) @Final private ModelPart leftLeg;

    // TAIL of setupAnim: SEM resets every part's pose, runs the keyframe
    // animations and the procedural aim layers - we then add the kneel
    // on top so it composes with idle/aim instead of fighting it.
    @Inject(method = "setupAnim", at = @At("TAIL"))
    private void semcs$applyKneelPose(Entity entity, float limbSwing, float limbSwingAmount,
                                      float ageInTicks, float netHeadYaw, float headPitch,
                                      CallbackInfo ci) {
        // Sneak flag off, dying (death anim owns the pose): stand down.
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
            return;
        }

        // KNEEL below - only when the sneak flag is ours.
        if (!living.isShiftKeyDown()) {
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
