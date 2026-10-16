// Client-side mixin - poses class-specific stances when a unit is sneaking/crouching.
// Registered in the "client" section of the mixin config: model classes
// only exist on the client, so this must never load on a server.
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.client.UnitProceduralAnimState;
import com.poseidon.sem_classsystem.entity.ai.UnitMeleeAttackGoal;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.tacz.guns.api.item.nbt.GunItemDataAccessor;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
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
        if (entity instanceof AbstractUnit unit
                && (unit.deathAnimationState.isStarted() || unit.hurtAnimationState.isStarted())) {
            return;
        }

        ItemStack main = living.getMainHandItem();
        if (UnitMeleeAttackGoal.isMeleeWeapon(main)) {
            return;
        }

        UnitProceduralAnimState animState = UnitProceduralAnimState.get(living);
        animState.update(living, ageInTicks);

        // 1. Dynamic locomotion polish (stride scaling, rush torso lean, weapon carry bob)
        applyLocomotionPolish(limbSwing, limbSwingAmount, animState.smoothCombatBlend());

        // 2. Gun procedural animations: recoil kick, tactical reload, bolt-action cycling
        if (main.getItem() instanceof GunItemDataAccessor) {
            applyGunProceduralAnimations(living, animState);
        }

        // 3. Eating food / applying bandage or medkit animation
        if (living.isUsingItem() && !living.getOffhandItem().isEmpty()) {
            float chew = Mth.sin(ageInTicks * 0.85F);
            this.leftArm.xRot = -1.45F + chew * 0.14F;
            this.leftArm.yRot = 0.52F;
            this.leftArm.zRot = 0.18F;
            this.rightArm.xRot = Mth.lerp(0.55F, this.rightArm.xRot, -0.85F);
            this.head.xRot += 0.18F + chew * 0.05F;
        }

        // 4. Prone firing stance (smoothly blended when Pose.SWIMMING is active).
        // MixinUnitRenderProne tilts the whole PoseStack forward by -82 deg onto
        // the belly; here we counter-pitch the head and arms up so the unit
        // props on its elbows and aims straight forward instead of into the floor.
        float prone = animState.smoothProneBlend();
        if (prone > 0.001F) {
            applyPronePose(prone);
            if (prone > 0.5F) {
                return;
            }
        }

        // Only apply crouch stances when the fire-goal has set the sneak flag.
        if (!living.isShiftKeyDown()) {
            return;
        }

        // Resolve the effective class from the entity (including sidearm pistols).
        WeaponClass cls = WeaponClassSupport.getEffective(living);

        if (cls == null) cls = WeaponClass.RIFLE; // safe default

        switch (cls) {
            case SNIPER -> applyMarksMankKneel();
            case HEAVY_MG -> applyBipodCrouch();
            case HEAVY -> applyDemolisherBlastStance();
            case SMG -> applyBreacherCqcCrouch();
            case SHOTGUN -> applySweeperStance();
            case PISTOL -> applyPistolStance();
            default -> applyTacticalHalfKneel();
        }
    }

    // -------------------------------------------------------------------
    // Pose: BREACHER (SMG) - aggressive tight CQB crouch
    //   Low hunch, compact silhouette for clearing doorways/corners.
    //   Right elbow tucked in, body pitched sharply forward.
    // -------------------------------------------------------------------
    private void applyBreacherCqcCrouch() {
        this.body.xRot  += 0.50F;
        this.body.y     += 2.4F;
        this.body.z     += 0.6F;

        this.head.y     += 3.8F;
        this.head.z     += 1.4F;

        // Tight forward step, shins tucked
        this.rightLeg.xRot += 0.35F;
        this.rightLeg.y    -= 0.4F;
        this.leftLeg.xRot  -= 0.50F;
        this.leftLeg.y     += 1.4F;

        // Tucked arms: tight to chest, CQB ready position
        this.rightArm.y    += 3.4F;
        this.rightArm.z    += 1.2F;
        this.rightArm.xRot -= 0.10F;
        this.rightArm.zRot -= 0.15F; // elbow tucked inward

        this.leftArm.y     += 3.4F;
        this.leftArm.z     += 1.2F;
        this.leftArm.xRot  += 0.10F;
        this.leftArm.zRot  += 0.10F;
    }

    // -------------------------------------------------------------------
    // Pose: SWEEPER (SHOTGUN) - wide-base recoil absorption crouch
    //   Stance is spread wider to brace against heavy 12-gauge kick,
    //   torso angled slightly, forward knee bent deep.
    // -------------------------------------------------------------------
    private void applySweeperStance() {
        this.body.xRot  += 0.46F;
        this.body.yRot  -= 0.08F; // slightly angled torso
        this.body.y     += 2.2F;

        this.head.y     += 3.6F;
        this.head.z     += 1.2F;

        // Wide stance for recoil stability
        this.rightLeg.xRot += 0.40F;
        this.rightLeg.zRot -= 0.15F;
        this.rightLeg.y    -= 0.3F;

        this.leftLeg.xRot  -= 0.45F;
        this.leftLeg.zRot  += 0.15F;
        this.leftLeg.y     += 1.2F;

        this.rightArm.y    += 3.2F;
        this.rightArm.z    += 1.2F;
        this.leftArm.y     += 3.2F;
        this.leftArm.z     += 1.2F;
    }

    // -------------------------------------------------------------------
    // Pose: SIDEARM (PISTOL) - tactical one-knee pistol stance
    //   Both arms extended forward in a locked Weaver/Isosceles grip,
    //   one knee fully grounded.
    // -------------------------------------------------------------------
    private void applyPistolStance() {
        this.body.xRot  += 0.38F;
        this.body.y     += 2.2F;

        this.head.y     += 3.2F;
        this.head.z     += 1.0F;

        // Grounded right knee, left foot planted
        this.rightLeg.xRot -= 0.70F;
        this.rightLeg.y    += 2.0F;

        this.leftLeg.xRot  += 0.45F;
        this.leftLeg.y     -= 0.5F;

        // Both arms punch forward toward the target
        this.rightArm.y    += 3.0F;
        this.rightArm.z    += 1.5F;
        this.rightArm.xRot -= 0.20F;

        this.leftArm.y     += 3.0F;
        this.leftArm.z     += 1.5F;
        this.leftArm.xRot  -= 0.20F;
        this.leftArm.yRot  += 0.15F; // left hand supports right wrist
    }

    // -------------------------------------------------------------------
    // Pose: CAMPER / SNIPER - deep one-knee marksman kneel
    //   Right shin forward, left shin folded flat behind. The torso
    //   pitches more aggressively than the generic kneel so the sight
    //   line drops into the scope. Left arm dips lower to represent the
    //   elbow resting on the forward knee. The head barely moves because
    //   SEM's HeadTrackingLayer still owns the aim direction.
    // -------------------------------------------------------------------
    private void applyMarksMankKneel() {
        // Strong forward body lean
        this.body.xRot  += 0.65F;
        this.body.y     += 2.8F;
        this.body.z     += 0.8F;

        // Head rides the torso lean
        this.head.y += 4.5F;
        this.head.z += 1.8F;

        // Right leg - knee swings well forward, shin planted
        this.rightLeg.xRot  += 0.50F;  // knee forward
        this.rightLeg.y     -= 1.0F;

        // Left leg - folds back, knee toward ground
        this.leftLeg.xRot   -= 1.20F;  // shin sweeps back
        this.leftLeg.y      += 2.5F;

        // Arms follow the body drop; right arm (trigger arm) up for scope
        this.rightArm.y  += 4.0F;
        this.rightArm.z  += 1.8F;
        this.rightArm.xRot -= 0.15F;   // elbow dips toward rifle stock

        // Left arm (support arm) drops deeper - elbow on knee
        this.leftArm.y  += 5.2F;
        this.leftArm.z  += 1.8F;
        this.leftArm.xRot += 0.20F;    // wrist angled into the forestock
    }

    // -------------------------------------------------------------------
    // Pose: SUPPRESSOR - wide bipod crouch
    //   Both knees out, body heavily pitched forward to simulate bracing
    //   a heavy machine gun on its bipod. Left arm extends forward along
    //   the body centerline as if gripping the bipod legs.
    // -------------------------------------------------------------------
    private void applyBipodCrouch() {
        // Heavy forward lean with a slight sink
        this.body.xRot  += 0.55F;
        this.body.y     += 3.2F;
        this.body.z     += 0.5F;

        this.head.y += 4.8F;
        this.head.z += 1.4F;

        // Both legs spread outward (crouch width)
        this.rightLeg.xRot  += 0.40F;
        this.rightLeg.zRot  -= 0.20F;   // right knee flares out
        this.rightLeg.y     -= 0.5F;

        this.leftLeg.xRot   += 0.40F;
        this.leftLeg.zRot   += 0.20F;   // left knee flares out
        this.leftLeg.y      -= 0.5F;

        // Arms
        this.rightArm.y     += 4.5F;
        this.rightArm.z     += 1.4F;

        // Left arm reaches well forward to "grip the bipod"
        this.leftArm.y     += 4.5F;
        this.leftArm.z     += 2.4F;
        this.leftArm.xRot  -= 0.35F;   // extended forward reach
    }

    // -------------------------------------------------------------------
    // Pose: DEMOLISHER - upright wide-leg blast stance
    //   No deep kneel. Legs spread, slight rear lean (recoil preparation),
    //   right arm angled up to support the launcher tube.
    // -------------------------------------------------------------------
    private void applyDemolisherBlastStance() {
        // Very slight rear-lean - bracing for back-blast
        this.body.xRot  += 0.18F;
        this.body.y     += 0.8F;

        this.head.y += 1.0F;

        // Legs planted wide - stable base for the tube
        this.rightLeg.zRot  -= 0.25F;
        this.rightLeg.xRot  += 0.12F;

        this.leftLeg.zRot   += 0.25F;
        this.leftLeg.xRot   += 0.12F;

        // Arms ride the small body shift
        this.rightArm.y     += 1.2F;
        this.leftArm.y      += 1.2F;

        // Right arm rises slightly to cradle the tube on the shoulder
        this.rightArm.xRot  -= 0.20F;
    }

    // -------------------------------------------------------------------
    // Pose: DEFAULT (SMG, Rifle, Shotgun, Jack, Pistol, etc.)
    //   Tactical half-kneel: moderate lean, CQB low silhouette.
    //   This replaces the old single generic kneel with a tighter,
    //   more deliberate forward hunch.
    // -------------------------------------------------------------------
    private void applyTacticalHalfKneel() {
        // Moderate forward lean
        this.body.xRot  += 0.42F;
        this.body.y     += 2.0F;

        this.head.y     += 3.4F;
        this.head.z     += 1.1F;

        // Right leg - slight forward step
        this.rightLeg.xRot  += 0.25F;
        this.rightLeg.y     -= 0.3F;

        // Left leg - shifted back, partial crouch
        this.leftLeg.xRot   -= 0.35F;
        this.leftLeg.y      += 1.0F;

        // Arms follow naturally
        this.rightArm.y  += 3.0F;
        this.rightArm.z  += 1.1F;
        this.leftArm.y   += 3.0F;
        this.leftArm.z   += 1.1F;
    }

    // -------------------------------------------------------------------
    // Pose: PRONE (Camper / Suppressor / Rifle at long range)
    //   MixinUnitRenderProne pitches the entire PoseStack forward -82 deg
    //   (-1.43 rad). Counter-pitch head and arms so the soldier props on
    //   elbows and aims horizontally at the target, with legs spread in a
    //   stable prone V-stance.
    // -------------------------------------------------------------------
    private void applyPronePose(float p) {
        // Lift head so eyes & helmet look forward along the ground
        this.head.xRot -= 1.32F * p;
        this.head.y    += 1.2F * p;
        this.head.z    -= 1.6F * p;

        // Prop both arms forward on elbows so the rifle points at the target
        this.rightArm.xRot -= 1.28F * p;
        this.rightArm.zRot += 0.14F * p;
        this.rightArm.y    += 1.4F * p;
        this.rightArm.z    -= 1.0F * p;

        this.leftArm.xRot  -= 1.32F * p;
        this.leftArm.yRot  += 0.22F * p;
        this.leftArm.zRot  -= 0.16F * p;
        this.leftArm.y     += 1.4F * p;
        this.leftArm.z     -= 1.0F * p;

        // Flatten leg walk swing and spread legs into a prone V-brace
        this.rightLeg.xRot = Mth.lerp(p, this.rightLeg.xRot, 0.06F);
        this.leftLeg.xRot  = Mth.lerp(p, this.leftLeg.xRot, -0.04F);
        this.rightLeg.zRot -= 0.18F * p;
        this.leftLeg.zRot  += 0.18F * p;
    }

    // -------------------------------------------------------------------
    // Dynamic locomotion: scales SEM's fixed walk stride by actual movement
    // speed, adds forward rush lean and subtle upper-body weapon bob.
    // -------------------------------------------------------------------
    private void applyLocomotionPolish(float limbSwing, float limbSwingAmount, float combatBlend) {
        float speedFactor = Mth.clamp(limbSwingAmount * 1.4F, 0.0F, 1.0F);
        // Dampen over-exaggerated leg swing when creeping slowly
        if (speedFactor < 0.85F) {
            float legScale = 0.35F + 0.65F * speedFactor;
            this.rightLeg.xRot = Mth.lerp(legScale, 0.1309F, this.rightLeg.xRot);
            this.leftLeg.xRot = Mth.lerp(legScale, -0.1745F, this.leftLeg.xRot);
        }

        if (limbSwingAmount > 0.08F) {
            float stride = Mth.sin(limbSwing * 0.6662F);
            float doubleStep = Mth.cos(limbSwing * 1.3324F);
            float moveAmt = Math.min(1.0F, limbSwingAmount);

            // Forward lean when moving quickly
            float rushLean = Math.max(0.0F, moveAmt - 0.35F) * 0.28F;
            this.body.xRot += rushLean;
            this.head.z -= rushLean * 2.5F;

            // Subtle weapon bob in sync with footsteps
            float bobY = doubleStep * 0.35F * moveAmt;
            float swayX = stride * 0.04F * moveAmt * (1.0F - 0.5F * combatBlend);
            this.rightArm.y += bobY;
            this.leftArm.y += bobY;
            this.rightArm.xRot += swayX;
            this.leftArm.xRot += swayX;
        }
    }

    // -------------------------------------------------------------------
    // Per-shot recoil kick, 3-stage reload animation, and bolt cycling
    // -------------------------------------------------------------------
    private void applyGunProceduralAnimations(LivingEntity living, UnitProceduralAnimState state) {
        // 1. Subtle per-shot recoil micro-kick (reduced so units stay steady)
        float recoil = state.recoilImpulse();
        if (recoil > 0.005F) {
            float scale = UnitProceduralAnimState.classRecoilScale(living);
            float kick = recoil * scale;
            float side = state.recoilSign() * 0.012F * kick;

            this.rightArm.xRot -= 0.045F * kick;
            this.rightArm.yRot += side;
            this.rightArm.z += 0.20F * kick;

            this.leftArm.xRot -= 0.038F * kick;
            this.leftArm.yRot += side;
            this.leftArm.z += 0.18F * kick;

            this.body.xRot -= 0.012F * kick;
            this.head.xRot -= 0.008F * kick;
        }

        // 2. Tactical reload animation (cant weapon, left hand mag pouch -> magwell -> bolt)
        float rel = state.smoothReloadBlend();
        if (rel > 0.005F) {
            float phase = (state.reloadTicks() % 36.0F) / 36.0F;
            // Right arm cants the weapon inward and slightly down for inspection
            this.rightArm.xRot += 0.22F * rel;
            this.rightArm.yRot -= 0.18F * rel;
            this.rightArm.zRot -= 0.32F * rel;

            // Head glances down toward receiver
            this.head.xRot += 0.20F * rel;
            this.head.yRot -= 0.10F * rel;

            // Left arm 3-stage cycle:
            //   0.00..0.35: drop hand to chest rig mag pouch
            //   0.35..0.75: bring fresh magazine up into magwell + tap
            //   0.75..1.00: reach over/forward to rack charging handle
            float lx, ly, lz;
            if (phase < 0.35F) {
                float p = Mth.sin((phase / 0.35F) * ((float) Math.PI * 0.5F));
                lx = 0.55F * p;
                ly = 0.35F * p;
                lz = -0.25F * p;
            } else if (phase < 0.75F) {
                float p = (phase - 0.35F) / 0.40F;
                float tap = Mth.sin(p * (float) Math.PI);
                lx = 0.55F * (1.0F - p) - 0.12F * tap;
                ly = 0.35F - 0.20F * p;
                lz = -0.25F * (1.0F - p) + 0.10F * tap;
            } else {
                float p = Mth.sin(((phase - 0.75F) / 0.25F) * (float) Math.PI);
                lx = 0.22F * p;
                ly = 0.25F * p;
                lz = 0.15F * p;
            }
            this.leftArm.xRot += lx * rel;
            this.leftArm.yRot += ly * rel;
            this.leftArm.zRot += lz * rel;
        }

        // 3. Bolt-action / pump cycling
        float bolt = state.smoothBoltBlend();
        if (bolt > 0.005F) {
            float cycle = Mth.sin(Math.min(1.0F, state.boltTicks() / 12.0F) * (float) Math.PI);
            this.rightArm.xRot += 0.24F * bolt * cycle;
            this.rightArm.yRot += 0.16F * bolt * cycle;
            this.rightArm.zRot -= 0.20F * bolt * cycle;
        }
    }
}
