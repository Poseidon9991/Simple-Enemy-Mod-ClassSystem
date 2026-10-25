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
        applyLocomotionPolish(limbSwing, limbSwingAmount, ageInTicks,
                animState.smoothWalkBlend(), animState.smoothCombatBlend());

        // 2. Gun procedural animations: recoil kick, tactical reload, bolt-action cycling
        if (main.getItem() instanceof GunItemDataAccessor) {
            applyGunProceduralAnimations(living, animState);
        }

        // 3. Smoothly blended eating food / applying bandage or medkit animation
        float useBlend = animState.smoothUseItemBlend();
        if (useBlend > 0.001F) {
            float chew = Mth.sin(ageInTicks * 0.85F);
            this.leftArm.xRot = Mth.lerp(useBlend, this.leftArm.xRot, -1.45F + chew * 0.14F);
            this.leftArm.yRot = Mth.lerp(useBlend, this.leftArm.yRot, 0.52F);
            this.leftArm.zRot = Mth.lerp(useBlend, this.leftArm.zRot, 0.18F);
            this.rightArm.xRot = Mth.lerp(0.55F * useBlend, this.rightArm.xRot, -0.85F);
            this.head.xRot += (0.18F + chew * 0.05F) * useBlend;
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

        // 5. Smoothly blended class crouch/kneel stances (no 1-frame snap)
        float crouch = animState.smoothCrouchBlend() * (1.0F - prone);
        if (crouch <= 0.001F) {
            return;
        }

        // Resolve the effective class from the entity (including sidearm pistols).
        WeaponClass cls = WeaponClassSupport.getEffective(living);

        if (cls == null) cls = WeaponClass.RIFLE; // safe default

        switch (cls) {
            case SNIPER -> applyMarksMankKneel(crouch);
            case HEAVY_MG -> applyBipodCrouch(crouch);
            case HEAVY -> applyDemolisherBlastStance(crouch);
            case SMG -> applyBreacherCqcCrouch(crouch);
            case SHOTGUN -> applySweeperStance(crouch);
            case PISTOL -> applyPistolStance(crouch);
            default -> applyTacticalHalfKneel(crouch);
        }
    }

    // -------------------------------------------------------------------
    // Pose: BREACHER (SMG) - aggressive tight CQB crouch
    //   Low hunch, compact silhouette for clearing doorways/corners.
    //   Right elbow tucked in, body pitched sharply forward.
    // -------------------------------------------------------------------
    private void applyBreacherCqcCrouch(float c) {
        this.body.xRot  += 0.50F * c;
        this.body.y     += 2.4F * c;
        this.body.z     += 0.6F * c;

        this.head.y     += 3.8F * c;
        this.head.z     += 1.4F * c;

        // Tight forward step, shins tucked
        this.rightLeg.xRot += 0.35F * c;
        this.rightLeg.y    -= 0.4F * c;
        this.leftLeg.xRot  -= 0.50F * c;
        this.leftLeg.y     += 1.4F * c;

        // Tucked arms: tight to chest, CQB ready position
        this.rightArm.y    += 3.4F * c;
        this.rightArm.z    += 1.2F * c;
        this.rightArm.xRot -= 0.10F * c;
        this.rightArm.zRot -= 0.15F * c; // elbow tucked inward

        this.leftArm.y     += 3.4F * c;
        this.leftArm.z     += 1.2F * c;
        this.leftArm.xRot  += 0.10F * c;
        this.leftArm.zRot  += 0.10F * c;
    }

    // -------------------------------------------------------------------
    // Pose: SWEEPER (SHOTGUN) - wide-base recoil absorption crouch
    //   Stance is spread wider to brace against heavy 12-gauge kick,
    //   torso angled slightly, forward knee bent deep.
    // -------------------------------------------------------------------
    private void applySweeperStance(float c) {
        this.body.xRot  += 0.46F * c;
        this.body.yRot  -= 0.08F * c; // slightly angled torso
        this.body.y     += 2.2F * c;

        this.head.y     += 3.6F * c;
        this.head.z     += 1.2F * c;

        // Wide stance for recoil stability
        this.rightLeg.xRot += 0.40F * c;
        this.rightLeg.zRot -= 0.15F * c;
        this.rightLeg.y    -= 0.3F * c;

        this.leftLeg.xRot  -= 0.45F * c;
        this.leftLeg.zRot  += 0.15F * c;
        this.leftLeg.y     += 1.2F * c;

        this.rightArm.y    += 3.2F * c;
        this.rightArm.z    += 1.2F * c;
        this.leftArm.y     += 3.2F * c;
        this.leftArm.z     += 1.2F * c;
    }

    // -------------------------------------------------------------------
    // Pose: SIDEARM (PISTOL) - tactical one-knee pistol stance
    //   Both arms extended forward in a locked Weaver/Isosceles grip,
    //   one knee fully grounded.
    // -------------------------------------------------------------------
    private void applyPistolStance(float c) {
        this.body.xRot  += 0.38F * c;
        this.body.y     += 2.2F * c;

        this.head.y     += 3.2F * c;
        this.head.z     += 1.0F * c;

        // Grounded right knee, left foot planted
        this.rightLeg.xRot -= 0.70F * c;
        this.rightLeg.y    += 2.0F * c;

        this.leftLeg.xRot  += 0.45F * c;
        this.leftLeg.y     -= 0.5F * c;

        // Both arms punch forward toward the target
        this.rightArm.y    += 3.0F * c;
        this.rightArm.z    += 1.5F * c;
        this.rightArm.xRot -= 0.20F * c;

        this.leftArm.y     += 3.0F * c;
        this.leftArm.z     += 1.5F * c;
        this.leftArm.xRot  -= 0.20F * c;
        this.leftArm.yRot  += 0.15F * c; // left hand supports right wrist
    }

    // -------------------------------------------------------------------
    // Pose: CAMPER / SNIPER - deep one-knee marksman kneel
    //   Right shin forward, left shin folded flat behind. The torso
    //   pitches more aggressively than the generic kneel so the sight
    //   line drops into the scope. Left arm dips lower to represent the
    //   elbow resting on the forward knee. The head barely moves because
    //   SEM's HeadTrackingLayer still owns the aim direction.
    // -------------------------------------------------------------------
    private void applyMarksMankKneel(float c) {
        // Strong forward body lean
        this.body.xRot  += 0.65F * c;
        this.body.y     += 2.8F * c;
        this.body.z     += 0.8F * c;

        // Head rides the torso lean
        this.head.y += 4.5F * c;
        this.head.z += 1.8F * c;

        // Right leg - knee swings well forward, shin planted
        this.rightLeg.xRot  += 0.50F * c;  // knee forward
        this.rightLeg.y     -= 1.0F * c;

        // Left leg - folds back, knee toward ground
        this.leftLeg.xRot   -= 1.20F * c;  // shin sweeps back
        this.leftLeg.y      += 2.5F * c;

        // Arms follow the body drop; right arm (trigger arm) up for scope
        this.rightArm.y  += 4.0F * c;
        this.rightArm.z  += 1.8F * c;
        this.rightArm.xRot -= 0.15F * c;   // elbow dips toward rifle stock

        // Left arm (support arm) drops deeper - elbow on knee
        this.leftArm.y  += 5.2F * c;
        this.leftArm.z  += 1.8F * c;
        this.leftArm.xRot += 0.20F * c;    // wrist angled into the forestock
    }

    // -------------------------------------------------------------------
    // Pose: SUPPRESSOR - wide bipod crouch
    //   Both knees out, body heavily pitched forward to simulate bracing
    //   a heavy machine gun on its bipod. Left arm extends forward along
    //   the body centerline as if gripping the bipod legs.
    // -------------------------------------------------------------------
    private void applyBipodCrouch(float c) {
        // Heavy forward lean with a slight sink
        this.body.xRot  += 0.55F * c;
        this.body.y     += 3.2F * c;
        this.body.z     += 0.5F * c;

        this.head.y += 4.8F * c;
        this.head.z += 1.4F * c;

        // Both legs spread outward (crouch width)
        this.rightLeg.xRot  += 0.40F * c;
        this.rightLeg.zRot  -= 0.20F * c;   // right knee flares out
        this.rightLeg.y     -= 0.5F * c;

        this.leftLeg.xRot   += 0.40F * c;
        this.leftLeg.zRot   += 0.20F * c;   // left knee flares out
        this.leftLeg.y      -= 0.5F * c;

        // Arms
        this.rightArm.y     += 4.5F * c;
        this.rightArm.z     += 1.4F * c;

        // Left arm reaches well forward to "grip the bipod"
        this.leftArm.y     += 4.5F * c;
        this.leftArm.z     += 2.4F * c;
        this.leftArm.xRot  -= 0.35F * c;   // extended forward reach
    }

    // -------------------------------------------------------------------
    // Pose: DEMOLISHER - upright wide-leg blast stance
    //   No deep kneel. Legs spread, slight rear lean (recoil preparation),
    //   right arm angled up to support the launcher tube.
    // -------------------------------------------------------------------
    private void applyDemolisherBlastStance(float c) {
        // Very slight rear-lean - bracing for back-blast
        this.body.xRot  += 0.18F * c;
        this.body.y     += 0.8F * c;

        this.head.y += 1.0F * c;

        // Legs planted wide - stable base for the tube
        this.rightLeg.zRot  -= 0.25F * c;
        this.rightLeg.xRot  += 0.12F * c;

        this.leftLeg.zRot   += 0.25F * c;
        this.leftLeg.xRot   += 0.12F * c;

        // Arms ride the small body shift
        this.rightArm.y     += 1.2F * c;
        this.leftArm.y      += 1.2F * c;

        // Right arm rises slightly to cradle the tube on the shoulder
        this.rightArm.xRot  -= 0.20F * c;
    }

    // -------------------------------------------------------------------
    // Pose: DEFAULT (SMG, Rifle, Shotgun, Jack, Pistol, etc.)
    //   Tactical half-kneel: moderate lean, CQB low silhouette.
    //   This replaces the old single generic kneel with a tighter,
    //   more deliberate forward hunch.
    // -------------------------------------------------------------------
    private void applyTacticalHalfKneel(float c) {
        // Moderate forward lean
        this.body.xRot  += 0.42F * c;
        this.body.y     += 2.0F * c;

        this.head.y     += 3.4F * c;
        this.head.z     += 1.1F * c;

        // Right leg - slight forward step
        this.rightLeg.xRot  += 0.25F * c;
        this.rightLeg.y     -= 0.3F * c;

        // Left leg - shifted back, partial crouch
        this.leftLeg.xRot   -= 0.35F * c;
        this.leftLeg.y      += 1.0F * c;

        // Arms follow naturally
        this.rightArm.y  += 3.0F * c;
        this.rightArm.z  += 1.1F * c;
        this.leftArm.y   += 3.0F * c;
        this.leftArm.z   += 1.1F * c;
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
    // Dynamic distance-locked locomotion: replaces SEM's fixed 0.9s wall-clock
    // UNIT_WALK hip-sliding & 32.5-deg torso whip with smooth distance-driven
    // stride, natural knee lift, idle breathing, and rush lean.
    // -------------------------------------------------------------------
    private void applyLocomotionPolish(float limbSwing, float limbSwingAmount, float ageInTicks,
                                       float walkBlend, float combatBlend) {
        // 1. Strip SEM's cartoon squash-and-stretch scale keyframes (1.10x/0.97y)
        for (ModelPart part : new ModelPart[]{
                this.unit, this.head, this.body,
                this.rightArm, this.leftArm, this.rightLeg, this.leftLeg}) {
            part.xScale = 1.0F;
            part.yScale = 1.0F;
            part.zScale = 1.0F;
        }

        float moveAmt = Mth.clamp(Math.max(limbSwingAmount * 1.15F, walkBlend * 0.45F) * walkBlend, 0.0F, 1.0F);
        float stride = Mth.cos(limbSwing * 0.6662F);
        float stepSin = Mth.sin(limbSwing * 0.6662F);
        float doubleStep = Mth.cos(limbSwing * 1.3324F);
        float breath = Mth.sin(ageInTicks * 0.09F);

        // 2. Stabilize root & torso against UNIT_WALK's fixed-timer -32.5 deg twist
        this.unit.x = 0.0F;
        this.unit.y = -Math.abs(stepSin) * 0.42F * moveAmt;
        this.unit.z = 0.0F;
        this.unit.xRot = 0.0873F;
        this.unit.yRot = 0.0F;
        this.unit.zRot = stepSin * 0.018F * moveAmt;

        // When patrolling out of combat or moving, slightly relax the 37.5-deg
        // bladed firing torso toward the direction of travel so walking looks natural.
        float baseBodyYRot = Mth.lerp(walkBlend * (1.0F - 0.55F * combatBlend), 0.6536F, 0.34F);
        float rushLean = Math.max(0.0F, moveAmt - 0.30F) * 0.24F;
        this.body.x = 0.0F;
        this.body.y = -6.26F + breath * 0.08F * (1.0F - moveAmt);
        this.body.z = 1.0616F;
        this.body.xRot = 0.0997F + rushLean + breath * 0.015F * (1.0F - moveAmt);
        this.body.yRot = baseBodyYRot - stride * 0.10F * moveAmt;
        this.body.zRot = 0.0238F + stepSin * 0.02F * moveAmt;

        this.head.x = 0.0F;
        this.head.y = -6.16F;
        this.head.z = 1.0616F - rushLean * 2.2F;

        // 3. Distance-synchronized legs: smoothly converge from SEM's wide
        //    stationary firing stagger (z = +4.0 / -1.0) into a clean walking
        //    hip line (z = +1.4 / +0.6) so slow VDF patrol strolls never piston-slide.
        float rSwing = stride * 1.15F * moveAmt;
        float lSwing = -stride * 1.15F * moveAmt;
        float rLift = Math.max(0.0F, -rSwing) * 0.75F;
        float lLift = Math.max(0.0F, -lSwing) * 0.75F;

        this.rightLeg.x = -1.9F;
        this.rightLeg.y = 6.0F - rLift;
        this.rightLeg.z = Mth.lerp(walkBlend, 4.0F, 1.4F) + rSwing * 0.45F;
        this.rightLeg.xRot = Mth.lerp(walkBlend, 0.1309F, 0.02F) + rSwing;
        this.rightLeg.yRot = 0.0F;
        this.rightLeg.zRot = 0.0F;

        this.leftLeg.x = Mth.lerp(walkBlend, 2.15F, 1.95F);
        this.leftLeg.y = Mth.lerp(walkBlend, 5.85F, 6.0F) - lLift;
        this.leftLeg.z = Mth.lerp(walkBlend, -1.0F, 0.6F) + lSwing * 0.45F;
        this.leftLeg.xRot = Mth.lerp(walkBlend, -0.1745F, -0.02F) + lSwing;
        this.leftLeg.yRot = 0.0F;
        this.leftLeg.zRot = 0.0F;

        // 4. Keep shoulders anchored to their tactical pivots (eliminates UNIT_WALK arm jitter)
        float armBobY = doubleStep * 0.28F * moveAmt + breath * 0.10F * (1.0F - moveAmt);
        float swayX = stepSin * 0.05F * moveAmt * (1.0F - 0.55F * combatBlend);
        this.rightArm.x = -3.0F;
        this.rightArm.y = -2.9089F + armBobY;
        this.rightArm.z = 3.4377F;
        this.rightArm.xRot += swayX;

        this.leftArm.x = 2.5F;
        this.leftArm.y = -3.2588F + armBobY;
        this.leftArm.z = -3.4123F;
        this.leftArm.xRot += swayX;
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
