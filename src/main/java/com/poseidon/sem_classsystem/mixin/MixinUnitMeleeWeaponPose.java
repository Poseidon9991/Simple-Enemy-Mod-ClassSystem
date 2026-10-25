// Client-side mixin - Better Combat compatible melee stances & 3-hit combo animations
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.client.UnitProceduralAnimState;
import com.poseidon.sem_classsystem.entity.ai.UnitMeleeAttackGoal;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.client.pmc_unit.PmcUnitModel;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitModel;
import net.nekoyuni.SimpleEnemyMod.entity.client.us_unit.USunitModel;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

// Replaces SEM's baked rifle-holding torso twist & shoulder offsets with
// natural humanoid shoulder pivots and Better Combat 3-hit combo animations
// (Right-to-Left Cleave -> Left-to-Right Backhand -> Overhead/Thrust Finisher).
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

        UnitProceduralAnimState animState = UnitProceduralAnimState.get(living);
        animState.update(living, ageInTicks);

        // 0. Strip SEM's UNIT_WALK / UNIT_IDLE keyframe scale & position drift
        for (ModelPart part : new ModelPart[]{
                this.unit, this.head, this.body,
                this.rightArm, this.leftArm, this.rightLeg, this.leftLeg}) {
            part.xScale = 1.0F;
            part.yScale = 1.0F;
            part.zScale = 1.0F;
        }

        float walkBlend = animState.smoothWalkBlend();
        float combatBlend = animState.smoothCombatBlend();
        float crouchBlend = animState.smoothCrouchBlend();
        float legMove = Mth.clamp(Math.max(limbSwingAmount * 1.15F, walkBlend * 0.45F) * walkBlend, 0.0F, 1.0F);
        float stride = Mth.cos(limbSwing * 0.6662F);
        float stepSin = Mth.sin(limbSwing * 0.6662F);
        float breath = Mth.sin(ageInTicks * 0.09F);

        this.unit.x = 0.0F;
        this.unit.y = -Math.abs(stepSin) * 0.40F * legMove;
        this.unit.z = 0.0F;
        this.unit.xRot = 0.04F;
        this.unit.yRot = 0.0F;
        this.unit.zRot = stepSin * 0.015F * legMove;

        // Smooth frame-rate head tracking without baked rifle-aim head tilt
        float pt = Mth.frac(ageInTicks);
        float bodyYaw = Mth.rotLerp(pt, living.yBodyRotO, living.yBodyRot);
        float headYaw = Mth.rotLerp(pt, living.yHeadRotO, living.yHeadRot);
        float smoothYaw = Mth.clamp(Mth.wrapDegrees(headYaw - bodyYaw), -45.0F, 45.0F);
        float smoothPitch = Mth.clamp(Mth.lerp(pt, living.xRotO, living.getXRot()), -30.0F, 45.0F);
        this.head.x = 0.0F;
        this.head.y = -6.16F;
        this.head.z = 1.0616F;
        this.head.xRot = smoothPitch * Mth.DEG_TO_RAD;
        this.head.yRot = smoothYaw * Mth.DEG_TO_RAD;
        this.head.zRot = 0.0F;

        // 1. CRITICAL FIX: In UnitModelDefinitions, body top sits at y = -6.26, z = 1.0616,
        //    and rightArm/leftArm boxes start at y = -2.0 (rightArm x = [-3.01, +0.99],
        //    leftArm x = [-1.0, +3.0]). Placing shoulders at x = ±4.0, y = -4.15, z = 1.06
        //    keeps the shoulder tops at y = -6.15 (flush with the top of the torso!) and
        //    centered on the ribcage instead of dislocated 2px down and 1px forward.
        this.body.x = 0.0F;
        this.body.y = -6.26F + breath * 0.07F * (1.0F - legMove);
        this.body.z = 1.0616F;
        this.body.xRot = 0.04F + breath * 0.012F * (1.0F - legMove);
        this.body.yRot = 0.0F;
        this.body.zRot = 0.0F;

        this.rightArm.x = -4.0F;
        this.rightArm.y = -4.15F + breath * 0.09F * (1.0F - legMove);
        this.rightArm.z = 1.06F;

        this.leftArm.x = 4.0F;
        this.leftArm.y = -4.15F + breath * 0.09F * (1.0F - legMove);
        this.leftArm.z = 1.06F;

        // 2. Natural alternating leg stride with subtle knee lift
        float legSwing = stride * 1.25F * legMove;
        float rLift = Math.max(0.0F, -legSwing) * 0.70F;
        float lLift = Math.max(0.0F, legSwing) * 0.70F;

        this.rightLeg.x = -1.95F;
        this.rightLeg.y = 6.0F - rLift;
        this.rightLeg.z = 1.06F + legSwing * 0.35F;
        this.rightLeg.xRot = legSwing;
        this.rightLeg.yRot = 0.0F;
        this.rightLeg.zRot = 0.0F;

        this.leftLeg.x = 1.95F;
        this.leftLeg.y = 6.0F - lLift;
        this.leftLeg.z = 1.06F - legSwing * 0.35F;
        this.leftLeg.xRot = -legSwing;
        this.leftLeg.yRot = 0.0F;
        this.leftLeg.zRot = 0.0F;

        boolean twoHanded = isTwoHandedWeapon(main);
        boolean spearOrThrust = isSpearOrThrustWeapon(main);

        // Smooth Better Combat swing progress (falls back to interpolated this.attackTime)
        float swing = animState.meleeSwingProgress();
        if (swing <= 0.0F && this.attackTime > 0.0F) {
            swing = this.attackTime;
        }
        int combo = Math.max(0, animState.meleeComboIndex());

        if (swing > 0.001F) {
            if (spearOrThrust) {
                applyThrustComboSwing(swing);
            } else if (twoHanded) {
                applyTwoHandedComboSwing(swing, combo);
            } else {
                applyOneHandedComboSwing(swing, combo);
            }
            if (crouchBlend > 0.001F) {
                applyMeleeWatchCrouch(crouchBlend);
            }
            return;
        }

        // Locomotion & Smoothly Blended Combat Guard Stances
        if (twoHanded) {
            // Better Combat 2-handed diagonal low-guard across waist
            this.body.yRot = -0.18F - stride * 0.08F * legMove;
            this.body.xRot = 0.08F + legMove * 0.14F;

            this.rightArm.x = -3.4F;
            this.rightArm.z = 1.6F;
            this.rightArm.xRot = -0.78F + stride * 0.22F * legMove;
            this.rightArm.yRot = -0.28F;
            this.rightArm.zRot = 0.12F;

            this.leftArm.x = 3.2F;
            this.leftArm.z = -0.2F;
            this.leftArm.xRot = -0.82F + stride * 0.22F * legMove;
            this.leftArm.yRot = 0.52F;
            this.leftArm.zRot = -0.10F;
        } else {
            // Smoothly blend between relaxed patrol walk and agile 1-handed combat guard
            float patrolRx = -0.18F + stride * 0.52F * legMove;
            float patrolRy = -0.04F;
            float patrolRz = 0.06F;
            float patrolLx = -0.06F - stride * 0.58F * legMove;
            float patrolLy = 0.04F;
            float patrolLz = -0.05F;

            float guardRx = -0.68F + stride * 0.30F * legMove;
            float guardRy = -0.20F;
            float guardRz = 0.16F;
            float guardLx = -0.38F - stride * 0.38F * legMove;
            float guardLy = 0.22F;
            float guardLz = -0.16F;

            this.body.yRot = Mth.lerp(combatBlend, -stride * 0.09F * legMove, -0.16F - stride * 0.06F * legMove);
            this.body.xRot = Mth.lerp(combatBlend, 0.05F + legMove * 0.10F, 0.12F + legMove * 0.16F);

            this.rightArm.xRot = Mth.lerp(combatBlend, patrolRx, guardRx);
            this.rightArm.yRot = Mth.lerp(combatBlend, patrolRy, guardRy);
            this.rightArm.zRot = Mth.lerp(combatBlend, patrolRz, guardRz);

            this.leftArm.xRot = Mth.lerp(combatBlend, patrolLx, guardLx);
            this.leftArm.yRot = Mth.lerp(combatBlend, patrolLy, guardLy);
            this.leftArm.zRot = Mth.lerp(combatBlend, patrolLz, guardLz);
        }

        if (crouchBlend > 0.001F) {
            applyMeleeWatchCrouch(crouchBlend);
        }
    }

    @Unique
    private void applyMeleeWatchCrouch(float c) {
        this.body.xRot += 0.36F * c;
        this.body.y += 1.8F * c;
        this.body.z += 0.5F * c;

        this.head.y += 2.8F * c;
        this.head.z += 0.9F * c;

        this.rightLeg.xRot += 0.24F * c;
        this.rightLeg.y -= 0.3F * c;
        this.leftLeg.xRot -= 0.32F * c;
        this.leftLeg.y += 0.9F * c;

        this.rightArm.y += 2.4F * c;
        this.rightArm.z += 0.8F * c;
        this.rightArm.xRot -= 0.18F * c;
        this.leftArm.y += 2.4F * c;
        this.leftArm.z += 0.8F * c;
    }

    // -------------------------------------------------------------------
    // Better Combat 3-Hit Combo for 1-Handed Weapons (Swords, Katanas, Knives, Machetes)
    //   Combo 0: Right-to-Left Diagonal Cleave
    //   Combo 1: Left-to-Right Backhand Reverse Slash
    //   Combo 2: Overhead Step-In Finisher
    // -------------------------------------------------------------------
    @Unique
    private void applyOneHandedComboSwing(float t, int combo) {
        // Combo 2 (overhead finisher) gets the heavy curve: deeper windup,
        // slower strike, fuller follow-through than the quick slashes.
        float strike = combo >= 2 ? heavyStrikeCurve(t) : smoothStrikeCurve(t);
        float arc = Mth.sin(t * (float) Math.PI);

        if (combo == 0) {
            // Hit 1: Right-to-Left Diagonal Slash
            float twist = Mth.lerp(strike, -0.38F, 0.46F) * arc;
            this.body.yRot = twist;
            this.body.xRot = 0.08F + 0.16F * arc;
            applyShoulderTwistOffsets(twist);

            this.rightArm.xRot = Mth.lerp(strike, -1.75F, -0.45F);
            this.rightArm.yRot = Mth.lerp(strike, -0.55F, 0.65F);
            this.rightArm.zRot = Mth.lerp(strike, 0.35F, -0.48F);

            this.leftArm.xRot = -0.25F + 0.45F * arc;
            this.leftArm.yRot = -0.25F * arc;
            this.leftArm.zRot = -0.28F * arc;
        } else if (combo == 1) {
            // Hit 2: Left-to-Right Reverse Backhand Slash
            float twist = Mth.lerp(strike, 0.42F, -0.44F) * arc;
            this.body.yRot = twist;
            this.body.xRot = 0.08F + 0.14F * arc;
            applyShoulderTwistOffsets(twist);

            this.rightArm.xRot = Mth.lerp(strike, -1.15F, -0.75F);
            this.rightArm.yRot = Mth.lerp(strike, 0.72F, -0.68F);
            this.rightArm.zRot = Mth.lerp(strike, -0.42F, 0.42F);

            this.leftArm.xRot = 0.20F + 0.35F * arc;
            this.leftArm.zRot = -0.22F;
        } else {
            // Hit 3: Heavy Overhead Downward Cleave
            float twist = Mth.lerp(strike, -0.22F, 0.28F) * arc;
            this.body.yRot = twist;
            this.body.xRot = Mth.lerp(strike, -0.10F, 0.30F) * arc;
            applyShoulderTwistOffsets(twist);

            this.rightArm.xRot = Mth.lerp(strike, -2.35F, -0.25F);
            this.rightArm.yRot = Mth.lerp(strike, -0.15F, 0.20F);
            this.rightArm.zRot = 0.10F * (1.0F - strike);

            this.leftArm.xRot = 0.35F * arc;
            this.leftArm.zRot = -0.25F * arc;
        }
    }

    // -------------------------------------------------------------------
    // Better Combat 2-Handed Combo (Greatswords, Claymores, Axes, Sledgehammers)
    // -------------------------------------------------------------------
    @Unique
    private void applyTwoHandedComboSwing(float t, int combo) {
        // Horizontal sweep (combo 1) stays light; overhead cleaves are heavy.
        float strike = combo == 1 ? smoothStrikeCurve(t) : heavyStrikeCurve(t);
        float arc = Mth.sin(t * (float) Math.PI);

        if (combo == 1) {
            // Wide 2-handed horizontal sweep
            float twist = Mth.lerp(strike, -0.52F, 0.55F) * arc;
            this.body.yRot = twist;
            this.body.xRot = 0.16F * arc;
            applyShoulderTwistOffsets(twist);

            float armX = Mth.lerp(strike, -1.25F, -0.85F);
            float armY = Mth.lerp(strike, -0.55F, 0.60F);
            this.rightArm.xRot = armX;
            this.rightArm.yRot = armY;
            this.rightArm.zRot = 0.20F;

            this.leftArm.xRot = armX - 0.08F;
            this.leftArm.yRot = armY + 0.45F;
            this.leftArm.zRot = -0.15F;
        } else {
            // Overhead 2-handed cleave smash
            this.body.xRot = Mth.lerp(strike, -0.16F, 0.34F) * arc;
            this.body.yRot = Mth.lerp(strike, -0.18F, 0.18F) * arc;

            float chopX = Mth.lerp(strike, -2.45F, -0.35F);
            this.rightArm.xRot = chopX;
            this.rightArm.yRot = -0.12F;
            this.rightArm.zRot = 0.08F;

            this.leftArm.xRot = chopX - 0.10F;
            this.leftArm.yRot = 0.28F;
            this.leftArm.zRot = -0.08F;
        }
    }

    // -------------------------------------------------------------------
    // Better Combat Spear / Trident / Rapier Forward Lunge Thrust
    // -------------------------------------------------------------------
    @Unique
    private void applyThrustComboSwing(float t) {
        // Same 3-phase curve as the slashes: the negative anticipation pulls
        // the spear back before the lunge instead of a flat sine in/out.
        float thrust = smoothStrikeCurve(t);
        float twist = 0.35F * thrust;
        this.body.yRot = twist;
        this.body.xRot = 0.22F * thrust;
        applyShoulderTwistOffsets(twist);

        this.rightArm.xRot = -0.75F - 0.85F * thrust;
        this.rightArm.yRot = 0.10F * thrust;
        this.rightArm.z -= 2.2F * thrust;

        this.leftArm.xRot = -0.35F + 0.45F * thrust;
        this.leftArm.zRot = -0.25F * thrust;
    }

    @Unique
    private void applyShoulderTwistOffsets(float twist) {
        this.rightArm.z = 1.06F + Mth.sin(twist) * 3.6F;
        this.leftArm.z = 1.06F - Mth.sin(twist) * 3.6F;
    }

    // Three-phase attack curve: anticipation pull-back below zero, a fast snap
    // through the strike with slight overshoot, then an eased settle back
    // toward guard so the swing never ends frozen at full extension.
    @Unique
    private static float smoothStrikeCurve(float t) {
        return strikeCurve(t, false);
    }

    // Same curve with a deeper windup and a slower, heavier strike phase -
    // used for combo finishers and overhead two-handed smashes.
    @Unique
    private static float heavyStrikeCurve(float t) {
        return strikeCurve(t, true);
    }

    @Unique
    private static float strikeCurve(float t, boolean heavy) {
        float windupEnd = heavy ? 0.20F : 0.16F;                    // anticipation phase
        float pullBack = heavy ? -0.20F : -0.14F;                   // arm pulls past the cocked pose
        float overshoot = heavy ? 1.15F : 1.10F;                    // strike lands slightly past full extension
        if (t < windupEnd) {
            float u = t / windupEnd;
            return pullBack * (u * u);                            // ease-in the windup
        }
        float strikeEnd = heavy ? 0.62F : 0.55F;                  // snap through the strike
        if (t < strikeEnd) {
            float u = (t - windupEnd) / (strikeEnd - windupEnd);
            return Mth.lerp(u * u * (3.0F - 2.0F * u), pullBack, overshoot);
        }
        float u = (t - strikeEnd) / (1.0F - strikeEnd);           // settle back toward guard
        return Mth.lerp(1.0F - (1.0F - u) * (1.0F - u), overshoot, 0.10F);
    }

    @Unique
    private static boolean isTwoHandedWeapon(ItemStack stack) {
        if (stack.getItem() instanceof AxeItem) {
            return true;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        String p = id.getPath().toLowerCase(Locale.ROOT);
        return p.contains("greatsword") || p.contains("claymore") || p.contains("zweihander")
                || p.contains("halberd") || p.contains("glaive") || p.contains("sledge")
                || p.contains("hammer") || p.contains("battleaxe") || p.contains("scythe")
                || p.contains("longsword") || p.contains("katana");
    }

    @Unique
    private static boolean isSpearOrThrustWeapon(ItemStack stack) {
        if (stack.getItem() instanceof TridentItem) {
            return true;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        String p = id.getPath().toLowerCase(Locale.ROOT);
        return p.contains("spear") || p.contains("pike") || p.contains("lance") || p.contains("rapier");
    }
}
