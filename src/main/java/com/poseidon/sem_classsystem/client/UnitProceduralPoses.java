// Client package - renderers only exist on the client (never server code!)
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.faction.ClassLoadouts;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.tacz.guns.api.item.IGun;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

// Static pose library shared by every unit rig: MixinUnitModelCrouch layers
// these onto SEM's Pmc/RU/US/BHQ models at setupAnim TAIL, and VdfUnitModel
// calls the same entry points so the militia poses exactly like a PMC -
// one set of tuning constants, one place to retune.
//
// Contents (all math ported 1:1 from the SEM model path):
//   - combat stance blend (MixinCombatStateSmooth's replacement for
//     CombatStateLayer): low-ready patrol pose <-> raised clip/aim pose
//   - locomotion polish: distance-driven stride, natural knee lift, idle
//     breathing, rush lean, scale-strip of SEM's squash keyframes
//   - gun procedural: per-shot recoil kick, 3-stage tactical reload,
//     bolt-action / pump cycling
//   - use-item (eat/bandage) arm pose, prone prop-up, class crouch stances
public final class UnitProceduralPoses {

    private UnitProceduralPoses() {
    }

    private static void resetScales(ModelPart unit, ModelPart head, ModelPart body,
                                    ModelPart rightArm, ModelPart leftArm,
                                    ModelPart rightLeg, ModelPart leftLeg) {
        unit.xScale = 1.0F; unit.yScale = 1.0F; unit.zScale = 1.0F;
        head.xScale = 1.0F; head.yScale = 1.0F; head.zScale = 1.0F;
        body.xScale = 1.0F; body.yScale = 1.0F; body.zScale = 1.0F;
        rightArm.xScale = 1.0F; rightArm.yScale = 1.0F; rightArm.zScale = 1.0F;
        leftArm.xScale = 1.0F; leftArm.yScale = 1.0F; leftArm.zScale = 1.0F;
        rightLeg.xScale = 1.0F; rightLeg.yScale = 1.0F; rightLeg.zScale = 1.0F;
        leftLeg.xScale = 1.0F; leftLeg.yScale = 1.0F; leftLeg.zScale = 1.0F;
    }

    // -------------------------------------------------------------------
    // Combat stance: smoothly blends between the low-ready patrol hold and
    // whatever raised pose the clips / weapon-pose layer produced.
    // combatBlend 0 -> patrol constants, 1 -> untouched (full aim).
    // Runs BEFORE AdvancedWeaponPoseLayer, same as SEM's layer order.
    // -------------------------------------------------------------------
    public static void applyCombatStance(ModelPart rightArm, ModelPart leftArm, float combatBlend) {
        if (combatBlend >= 0.999F) {
            return;
        }
        rightArm.xRot = Mth.lerp(combatBlend, -0.95F, rightArm.xRot);
        rightArm.yRot = Mth.lerp(combatBlend, 0.00F, rightArm.yRot);
        rightArm.zRot = Mth.lerp(combatBlend, 0.00F, rightArm.zRot);

        leftArm.xRot = Mth.lerp(combatBlend, -0.25F, leftArm.xRot);
        leftArm.yRot = Mth.lerp(combatBlend, 0.50F, leftArm.yRot);
        leftArm.zRot = Mth.lerp(combatBlend, 0.50F, leftArm.zRot);
    }

    // -------------------------------------------------------------------
    // Dynamic distance-locked locomotion: replaces SEM's fixed 0.9s wall-clock
    // UNIT_WALK hip-sliding & 32.5-deg torso whip with smooth distance-driven
    // stride, strafe/backpedal leg direction, turn banking, airborne tuck,
    // natural knee lift, idle breathing, and rush lean.
    // -------------------------------------------------------------------
    public static void applyLocomotionPolish(ModelPart unit, ModelPart head, ModelPart body,
                                             ModelPart rightArm, ModelPart leftArm,
                                             ModelPart rightLeg, ModelPart leftLeg,
                                             float limbSwing, float limbSwingAmount, float ageInTicks,
                                             UnitProceduralAnimState state) {
        float phase = state.walkPhase() > 0.001F ? state.walkPhase() : (limbSwing * 0.6662F);
        float effLimbAmt = Math.max(limbSwingAmount, state.smoothStrideAmplitude());
        applyLocomotionPolish(unit, head, body, rightArm, leftArm, rightLeg, leftLeg,
                phase / 0.6662F, effLimbAmt, ageInTicks,
                state.smoothWalkBlend(), state.smoothRunBlend(), state.smoothCombatBlend(),
                state.moveForward(), state.moveSide(), state.turnRate(), state.smoothAirBlend());
    }

    public static void applyLocomotionPolish(ModelPart unit, ModelPart head, ModelPart body,
                                             ModelPart rightArm, ModelPart leftArm,
                                             ModelPart rightLeg, ModelPart leftLeg,
                                             float limbSwing, float limbSwingAmount, float ageInTicks,
                                             float walkBlend, float combatBlend) {
        applyLocomotionPolish(unit, head, body, rightArm, leftArm, rightLeg, leftLeg,
                limbSwing, limbSwingAmount, ageInTicks, walkBlend, 0.0F, combatBlend,
                1.0F, 0.0F, 0.0F, 0.0F);
    }

    public static void applyLocomotionPolish(ModelPart unit, ModelPart head, ModelPart body,
                                             ModelPart rightArm, ModelPart leftArm,
                                             ModelPart rightLeg, ModelPart leftLeg,
                                             float limbSwing, float limbSwingAmount, float ageInTicks,
                                             float walkBlend, float combatBlend,
                                             float moveFwd, float moveSide, float turnRate, float airBlend) {
        applyLocomotionPolish(unit, head, body, rightArm, leftArm, rightLeg, leftLeg,
                limbSwing, limbSwingAmount, ageInTicks, walkBlend, 0.0F, combatBlend,
                moveFwd, moveSide, turnRate, airBlend);
    }

    public static void applyLocomotionPolish(ModelPart unit, ModelPart head, ModelPart body,
                                             ModelPart rightArm, ModelPart leftArm,
                                             ModelPart rightLeg, ModelPart leftLeg,
                                             float limbSwing, float limbSwingAmount, float ageInTicks,
                                             float walkBlend, float runBlend, float combatBlend,
                                             float moveFwd, float moveSide, float turnRate, float airBlend) {
        // 1. Strip SEM's cartoon squash-and-stretch scale keyframes (1.10x/0.97y)
        resetScales(unit, head, body, rightArm, leftArm, rightLeg, leftLeg);

        // Stride dynamics: moveAmt reflects actual speed (gentle walk vs full athletic sprint)
        float moveAmt = Mth.clamp(limbSwingAmount * walkBlend, 0.0F, 1.0F);
        // Flip the stride phase when backpedalling so the lifted knee leads the
        // actual direction of travel instead of moonwalking.
        float dirSign = moveFwd < -0.15F ? -1.0F : 1.0F;
        float stride = Mth.cos(limbSwing * 0.6662F) * dirSign;
        float stepSin = Mth.sin(limbSwing * 0.6662F) * dirSign;
        float doubleStep = Mth.cos(limbSwing * 1.3324F);
        float breath = Mth.sin(ageInTicks * 0.09F);
        float idleSway = Mth.cos(ageInTicks * 0.045F) * (1.0F - moveAmt);
        float turnBank = Mth.clamp(turnRate * 0.0045F, -0.09F, 0.09F);

        // 2. Stabilize root & torso: slow walk has calm vertical step, running has athletic bounce
        float bounceScale = Mth.lerp(runBlend, 0.16F, 0.50F);
        unit.x = stepSin * 0.22F * moveAmt + idleSway * 0.08F;
        unit.y = -Math.abs(stepSin) * bounceScale * moveAmt;
        unit.z = 0.0F;
        unit.xRot = 0.0873F;
        unit.yRot = 0.0F;
        unit.zRot = stepSin * 0.022F * moveAmt + turnBank;

        // Upright tactical walk vs forward-pitched athletic sprint lean:
        // Slow walk keeps torso completely upright (fwdLean = 0); sprint drives chest forward.
        float baseBodyYRot = Mth.lerp(walkBlend * (1.0F - 0.55F * combatBlend), 0.6536F, 0.34F);
        float fwdLean = runBlend * (0.22F * Math.max(-0.4F, moveFwd));
        float sideLean = moveSide * 0.09F * moveAmt + turnBank * 0.7F;
        body.x = 0.0F;
        body.y = -6.26F + breath * 0.09F * (1.0F - moveAmt);
        body.z = 1.0616F;
        body.xRot = 0.0997F + fwdLean + breath * 0.018F * (1.0F - moveAmt);
        body.yRot = baseBodyYRot - stride * (0.05F + runBlend * 0.08F) * moveAmt - turnBank * 0.9F;
        body.zRot = 0.0238F + stepSin * 0.024F * moveAmt + sideLean + idleSway * 0.012F;

        head.x = 0.0F;
        head.y = -6.16F + breath * 0.04F * (1.0F - moveAmt);
        head.z = 1.0616F - Math.max(0.0F, fwdLean) * 2.2F;
        head.zRot -= sideLean * 0.35F;

        // 3. Distance-synchronized legs:
        //    Slow walk: gentle 20-30 degree swing, low knee lift
        //    Running: wide 60-70 degree swing, high driving knee lift
        float absSide = Math.abs(moveSide) * moveAmt;
        float fwdScale = 1.0F - 0.38F * absSide;
        float rSwing = stride * 1.15F * moveAmt * fwdScale;
        float lSwing = -stride * 1.15F * moveAmt * fwdScale;
        float liftScale = Mth.lerp(runBlend, 0.35F, 0.85F);
        float rLift = Math.max(0.0F, -rSwing) * liftScale + Math.max(0.0F, stepSin * moveSide) * 0.45F * absSide;
        float lLift = Math.max(0.0F, -lSwing) * liftScale + Math.max(0.0F, -stepSin * moveSide) * 0.45F * absSide;
        float strafeRoll = stride * moveSide * 0.24F * moveAmt;
        float strafeShiftX = stepSin * moveSide * 0.45F * moveAmt;

        rightLeg.x = -1.9F + strafeShiftX;
        rightLeg.y = 6.0F - rLift;
        rightLeg.z = Mth.lerp(walkBlend, 4.0F, 1.4F) + rSwing * 0.45F;
        rightLeg.xRot = Mth.lerp(walkBlend, 0.1309F, 0.02F) + rSwing;
        rightLeg.yRot = moveSide * 0.14F * moveAmt;
        rightLeg.zRot = strafeRoll;

        leftLeg.x = Mth.lerp(walkBlend, 2.15F, 1.95F) + strafeShiftX;
        leftLeg.y = Mth.lerp(walkBlend, 5.85F, 6.0F) - lLift;
        leftLeg.z = Mth.lerp(walkBlend, -1.0F, 0.6F) + lSwing * 0.45F;
        leftLeg.xRot = Mth.lerp(walkBlend, -0.1745F, -0.02F) + lSwing;
        leftLeg.yRot = moveSide * 0.14F * moveAmt;
        leftLeg.zRot = -strafeRoll * 0.85F;

        // 4. Anchored shoulders & natural arm cadence
        float patrolRelax = 1.0F - 0.68F * combatBlend;
        float armBobY = doubleStep * (0.12F + runBlend * 0.22F) * moveAmt + breath * 0.11F * (1.0F - moveAmt);
        float swayX = stepSin * (0.05F + runBlend * 0.06F) * moveAmt * patrolRelax;
        float swayY = stride * (0.03F + runBlend * 0.04F) * moveAmt * patrolRelax;
        rightArm.x = -3.0F;
        rightArm.y = -2.9089F + armBobY;
        rightArm.z = 3.4377F;
        rightArm.xRot += swayX;
        rightArm.yRot += swayY;

        leftArm.x = 2.5F;
        leftArm.y = -3.2588F + armBobY;
        leftArm.z = -3.4123F;
        leftArm.xRot += swayX * 0.85F;
        leftArm.yRot += swayY;

        // 5. Airborne tuck (jumping / dropping off a ledge)
        if (airBlend > 0.001F) {
            rightLeg.xRot = Mth.lerp(airBlend, rightLeg.xRot, 0.38F);
            leftLeg.xRot = Mth.lerp(airBlend, leftLeg.xRot, -0.28F);
            rightLeg.y -= 0.9F * airBlend;
            leftLeg.y -= 0.5F * airBlend;
            rightArm.xRot -= 0.12F * airBlend;
            leftArm.xRot -= 0.12F * airBlend;
        }
    }

    // -------------------------------------------------------------------
    // Shared Better Combat melee pose pass (guards, 3-hit combos, lunge
    // thrusts and watch crouch) - called by both MixinUnitMeleeWeaponPose
    // (for PMC/RU/US/BHQ) and VdfUnitModel (for militia carrying knives/
    // machetes/axes).
    // -------------------------------------------------------------------
    public static void applyMeleePose(LivingEntity living, float limbSwing, float limbSwingAmount,
                                      float ageInTicks, float fallbackAttackTime,
                                      ModelPart unit, ModelPart head, ModelPart body,
                                      ModelPart rightArm, ModelPart leftArm,
                                      ModelPart rightLeg, ModelPart leftLeg) {
        ItemStack main = living.getMainHandItem();
        UnitProceduralAnimState animState = UnitProceduralAnimState.get(living);
        animState.update(living, ageInTicks);

        resetScales(unit, head, body, rightArm, leftArm, rightLeg, leftLeg);

        float walkBlend = animState.smoothWalkBlend();
        float combatBlend = animState.smoothCombatBlend();
        float crouchBlend = animState.smoothCrouchBlend();
        float moveFwd = animState.moveForward();
        float moveSide = animState.moveSide();
        float turnBank = Mth.clamp(animState.turnRate() * 0.0045F, -0.09F, 0.09F);
        float dirSign = moveFwd < -0.15F ? -1.0F : 1.0F;
        float effLimbAmt = Math.max(limbSwingAmount, animState.smoothStrideAmplitude());
        float legMove = Mth.clamp(Math.max(effLimbAmt * 1.15F, walkBlend * 0.58F) * walkBlend, 0.0F, 1.0F);
        float phase = animState.walkPhase() > 0.001F ? animState.walkPhase() : (limbSwing * 0.6662F);
        float stride = Mth.cos(phase) * dirSign;
        float stepSin = Mth.sin(phase) * dirSign;
        float breath = Mth.sin(ageInTicks * 0.09F);

        unit.x = stepSin * 0.20F * legMove;
        unit.y = -Math.abs(stepSin) * 0.44F * legMove;
        unit.z = 0.0F;
        unit.xRot = 0.04F;
        unit.yRot = 0.0F;
        unit.zRot = stepSin * 0.018F * legMove + turnBank;

        float pt = Mth.frac(ageInTicks);
        float bodyYaw = Mth.rotLerp(pt, living.yBodyRotO, living.yBodyRot);
        float headYaw = Mth.rotLerp(pt, living.yHeadRotO, living.yHeadRot);
        float smoothYaw = Mth.clamp(Mth.wrapDegrees(headYaw - bodyYaw), -45.0F, 45.0F);
        float smoothPitch = Mth.clamp(Mth.lerp(pt, living.xRotO, living.getXRot()), -30.0F, 45.0F);
        head.x = 0.0F;
        head.y = -6.16F;
        head.z = 1.0616F;
        head.xRot = smoothPitch * Mth.DEG_TO_RAD;
        head.yRot = smoothYaw * Mth.DEG_TO_RAD;
        head.zRot = 0.0F;

        body.x = 0.0F;
        body.y = -6.26F + breath * 0.07F * (1.0F - legMove);
        body.z = 1.0616F;
        body.xRot = 0.04F + breath * 0.012F * (1.0F - legMove);
        body.yRot = 0.0F;
        body.zRot = moveSide * 0.07F * legMove + turnBank * 0.6F;

        rightArm.x = -4.0F;
        rightArm.y = -4.15F + breath * 0.09F * (1.0F - legMove);
        rightArm.z = 1.06F;

        leftArm.x = 4.0F;
        leftArm.y = -4.15F + breath * 0.09F * (1.0F - legMove);
        leftArm.z = 1.06F;

        float legSwing = stride * 1.25F * legMove;
        float rLift = Math.max(0.0F, -legSwing) * 0.75F;
        float lLift = Math.max(0.0F, legSwing) * 0.75F;
        float strafeRoll = stride * moveSide * 0.22F * legMove;

        rightLeg.x = -1.95F + stepSin * moveSide * 0.35F * legMove;
        rightLeg.y = 6.0F - rLift;
        rightLeg.z = 1.06F + legSwing * 0.35F;
        rightLeg.xRot = legSwing;
        rightLeg.yRot = moveSide * 0.12F * legMove;
        rightLeg.zRot = strafeRoll;

        leftLeg.x = 1.95F + stepSin * moveSide * 0.35F * legMove;
        leftLeg.y = 6.0F - lLift;
        leftLeg.z = 1.06F - legSwing * 0.35F;
        leftLeg.xRot = -legSwing;
        leftLeg.yRot = moveSide * 0.12F * legMove;
        leftLeg.zRot = -strafeRoll * 0.85F;

        boolean twoHanded = isTwoHandedWeapon(main);
        boolean spearOrThrust = isSpearOrThrustWeapon(main);

        float swing = animState.meleeSwingProgress();
        if (swing <= 0.0F && fallbackAttackTime > 0.0F) {
            swing = fallbackAttackTime;
        }
        int combo = Math.max(0, animState.meleeComboIndex());

        if (swing > 0.001F) {
            // Subtle forward weight lunge into the strike
            float lunge = Mth.sin(swing * (float) Math.PI);
            rightLeg.xRot += 0.22F * lunge;
            rightLeg.z -= 0.7F * lunge;
            leftLeg.xRot -= 0.18F * lunge;
            leftLeg.z += 0.5F * lunge;
            unit.y += 0.35F * lunge;

            if (spearOrThrust) {
                applyThrustComboSwing(swing, body, rightArm, leftArm);
            } else if (twoHanded) {
                applyTwoHandedComboSwing(swing, combo, body, rightArm, leftArm);
            } else {
                applyOneHandedComboSwing(swing, combo, body, rightArm, leftArm);
            }
            if (crouchBlend > 0.001F) {
                applyMeleeWatchCrouch(crouchBlend, body, head, rightArm, leftArm, rightLeg, leftLeg);
            }
            return;
        }

        if (twoHanded) {
            body.yRot = -0.18F - stride * 0.08F * legMove;
            body.xRot = 0.08F + legMove * 0.14F;

            rightArm.x = -3.4F;
            rightArm.z = 1.6F;
            rightArm.xRot = -0.78F + stride * 0.22F * legMove;
            rightArm.yRot = -0.28F;
            rightArm.zRot = 0.12F;

            leftArm.x = 3.2F;
            leftArm.z = -0.2F;
            leftArm.xRot = -0.82F + stride * 0.22F * legMove;
            leftArm.yRot = 0.52F;
            leftArm.zRot = -0.10F;
        } else {
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

            body.yRot = Mth.lerp(combatBlend, -stride * 0.09F * legMove, -0.16F - stride * 0.06F * legMove);
            body.xRot = Mth.lerp(combatBlend, 0.05F + legMove * 0.10F, 0.12F + legMove * 0.16F);

            rightArm.xRot = Mth.lerp(combatBlend, patrolRx, guardRx);
            rightArm.yRot = Mth.lerp(combatBlend, patrolRy, guardRy);
            rightArm.zRot = Mth.lerp(combatBlend, patrolRz, guardRz);

            leftArm.xRot = Mth.lerp(combatBlend, patrolLx, guardLx);
            leftArm.yRot = Mth.lerp(combatBlend, patrolLy, guardLy);
            leftArm.zRot = Mth.lerp(combatBlend, patrolLz, guardLz);
        }

        if (crouchBlend > 0.001F) {
            applyMeleeWatchCrouch(crouchBlend, body, head, rightArm, leftArm, rightLeg, leftLeg);
        }
    }

    private static void applyMeleeWatchCrouch(float c, ModelPart body, ModelPart head,
                                              ModelPart rightArm, ModelPart leftArm,
                                              ModelPart rightLeg, ModelPart leftLeg) {
        body.xRot += 0.36F * c;
        body.y += 1.8F * c;
        body.z += 0.5F * c;

        head.y += 2.8F * c;
        head.z += 0.9F * c;

        rightLeg.xRot += 0.24F * c;
        rightLeg.y -= 0.3F * c;
        leftLeg.xRot -= 0.32F * c;
        leftLeg.y += 0.9F * c;

        rightArm.y += 2.4F * c;
        rightArm.z += 0.8F * c;
        rightArm.xRot -= 0.18F * c;
        leftArm.y += 2.4F * c;
        leftArm.z += 0.8F * c;
    }

    private static void applyOneHandedComboSwing(float t, int combo, ModelPart body,
                                                 ModelPart rightArm, ModelPart leftArm) {
        float strike = combo >= 2 ? strikeCurve(t, true) : strikeCurve(t, false);
        float arc = Mth.sin(t * (float) Math.PI);

        if (combo == 0) {
            float twist = Mth.lerp(strike, -0.38F, 0.46F) * arc;
            body.yRot = twist;
            body.xRot = 0.08F + 0.16F * arc;
            applyShoulderTwistOffsets(twist, rightArm, leftArm);

            rightArm.xRot = Mth.lerp(strike, -1.75F, -0.45F);
            rightArm.yRot = Mth.lerp(strike, -0.55F, 0.65F);
            rightArm.zRot = Mth.lerp(strike, 0.35F, -0.48F);

            leftArm.xRot = -0.25F + 0.45F * arc;
            leftArm.yRot = -0.25F * arc;
            leftArm.zRot = -0.28F * arc;
        } else if (combo == 1) {
            float twist = Mth.lerp(strike, 0.42F, -0.44F) * arc;
            body.yRot = twist;
            body.xRot = 0.08F + 0.14F * arc;
            applyShoulderTwistOffsets(twist, rightArm, leftArm);

            rightArm.xRot = Mth.lerp(strike, -1.15F, -0.75F);
            rightArm.yRot = Mth.lerp(strike, 0.72F, -0.68F);
            rightArm.zRot = Mth.lerp(strike, -0.42F, 0.42F);

            leftArm.xRot = 0.20F + 0.35F * arc;
            leftArm.zRot = -0.22F;
        } else {
            float twist = Mth.lerp(strike, -0.22F, 0.28F) * arc;
            body.yRot = twist;
            body.xRot = Mth.lerp(strike, -0.10F, 0.30F) * arc;
            applyShoulderTwistOffsets(twist, rightArm, leftArm);

            rightArm.xRot = Mth.lerp(strike, -2.35F, -0.25F);
            rightArm.yRot = Mth.lerp(strike, -0.15F, 0.20F);
            rightArm.zRot = 0.10F * (1.0F - strike);

            leftArm.xRot = 0.35F * arc;
            leftArm.zRot = -0.25F * arc;
        }
    }

    private static void applyTwoHandedComboSwing(float t, int combo, ModelPart body,
                                                 ModelPart rightArm, ModelPart leftArm) {
        float strike = combo == 1 ? strikeCurve(t, false) : strikeCurve(t, true);
        float arc = Mth.sin(t * (float) Math.PI);

        if (combo == 1) {
            float twist = Mth.lerp(strike, -0.52F, 0.55F) * arc;
            body.yRot = twist;
            body.xRot = 0.16F * arc;
            applyShoulderTwistOffsets(twist, rightArm, leftArm);

            float armX = Mth.lerp(strike, -1.25F, -0.85F);
            float armY = Mth.lerp(strike, -0.55F, 0.60F);
            rightArm.xRot = armX;
            rightArm.yRot = armY;
            rightArm.zRot = 0.20F;

            leftArm.xRot = armX - 0.08F;
            leftArm.yRot = armY + 0.45F;
            leftArm.zRot = -0.15F;
        } else {
            body.xRot = Mth.lerp(strike, -0.16F, 0.34F) * arc;
            body.yRot = Mth.lerp(strike, -0.18F, 0.18F) * arc;

            float chopX = Mth.lerp(strike, -2.45F, -0.35F);
            rightArm.xRot = chopX;
            rightArm.yRot = -0.12F;
            rightArm.zRot = 0.08F;

            leftArm.xRot = chopX - 0.10F;
            leftArm.yRot = 0.28F;
            leftArm.zRot = -0.08F;
        }
    }

    private static void applyThrustComboSwing(float t, ModelPart body,
                                              ModelPart rightArm, ModelPart leftArm) {
        float thrust = strikeCurve(t, false);
        float twist = 0.35F * thrust;
        body.yRot = twist;
        body.xRot = 0.22F * thrust;
        applyShoulderTwistOffsets(twist, rightArm, leftArm);

        rightArm.xRot = -0.75F - 0.85F * thrust;
        rightArm.yRot = 0.10F * thrust;
        rightArm.z -= 2.2F * thrust;

        leftArm.xRot = -0.35F + 0.45F * thrust;
        leftArm.zRot = -0.25F * thrust;
    }

    private static void applyShoulderTwistOffsets(float twist, ModelPart rightArm, ModelPart leftArm) {
        rightArm.z = 1.06F + Mth.sin(twist) * 3.6F;
        leftArm.z = 1.06F - Mth.sin(twist) * 3.6F;
    }

    private static float strikeCurve(float t, boolean heavy) {
        float windupEnd = heavy ? 0.20F : 0.16F;
        float pullBack = heavy ? -0.20F : -0.14F;
        float overshoot = heavy ? 1.15F : 1.10F;
        if (t < windupEnd) {
            float u = t / windupEnd;
            return pullBack * (u * u);
        }
        float strikeEnd = heavy ? 0.62F : 0.55F;
        if (t < strikeEnd) {
            float u = (t - windupEnd) / (strikeEnd - windupEnd);
            return Mth.lerp(u * u * (3.0F - 2.0F * u), pullBack, overshoot);
        }
        float u = (t - strikeEnd) / (1.0F - strikeEnd);
        return Mth.lerp(1.0F - (1.0F - u) * (1.0F - u), overshoot, 0.10F);
    }

    private static boolean isTwoHandedWeapon(ItemStack stack) {
        if (stack.getItem() instanceof net.minecraft.world.item.AxeItem) {
            return true;
        }
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        String p = id.getPath().toLowerCase(java.util.Locale.ROOT);
        return p.contains("greatsword") || p.contains("claymore") || p.contains("zweihander")
                || p.contains("halberd") || p.contains("glaive") || p.contains("sledge")
                || p.contains("hammer") || p.contains("battleaxe") || p.contains("scythe")
                || p.contains("longsword") || p.contains("katana");
    }

    private static boolean isSpearOrThrustWeapon(ItemStack stack) {
        if (stack.getItem() instanceof net.minecraft.world.item.TridentItem) {
            return true;
        }
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        String p = id.getPath().toLowerCase(java.util.Locale.ROOT);
        return p.contains("spear") || p.contains("pike") || p.contains("lance") || p.contains("rapier");
    }

    // -------------------------------------------------------------------
    // Per-shot recoil kick, 3-stage reload animation, and bolt cycling
    // -------------------------------------------------------------------
    public static void applyGunProcedural(LivingEntity living, UnitProceduralAnimState state,
                                          ModelPart body, ModelPart head,
                                          ModelPart rightArm, ModelPart leftArm) {
        // 1. Per-shot recoil kick: crisp muzzle rise + shoulder pushback
        float recoil = state.recoilImpulse();
        if (recoil > 0.005F) {
            float scale = UnitProceduralAnimState.classRecoilScale(living);
            float kick = recoil * scale;
            float side = state.recoilSign() * 0.016F * kick;

            rightArm.xRot -= 0.068F * kick;
            rightArm.yRot += side;
            rightArm.z += 0.28F * kick;

            leftArm.xRot -= 0.056F * kick;
            leftArm.yRot += side;
            leftArm.z += 0.24F * kick;

            body.xRot -= 0.022F * kick;
            body.yRot += 0.014F * kick;
            head.xRot -= 0.014F * kick;
        }

        // 2. Tactical reload animation (cant weapon, left hand mag pouch -> magwell -> bolt)
        float rel = state.smoothReloadBlend();
        if (rel > 0.005F) {
            float phase = (state.reloadTicks() % 36.0F) / 36.0F;
            // Right arm cants the weapon inward and slightly down for inspection
            rightArm.xRot += 0.22F * rel;
            rightArm.yRot -= 0.18F * rel;
            rightArm.zRot -= 0.32F * rel;

            // Slight torso settle + head glances down toward receiver
            body.xRot += 0.06F * rel;
            body.yRot -= 0.08F * rel;
            head.xRot += 0.20F * rel;
            head.yRot -= 0.10F * rel;

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
            leftArm.xRot += lx * rel;
            leftArm.yRot += ly * rel;
            leftArm.zRot += lz * rel;
        }

        // 3. Bolt-action / pump cycling: pump shotguns cycle the left forend
        //    hand back-and-forth while bolt rifles work the right-hand bolt.
        float bolt = state.smoothBoltBlend();
        if (bolt > 0.005F) {
            float cycle = Mth.sin(Math.min(1.0F, state.boltTicks() / 12.0F) * (float) Math.PI);
            WeaponClass cls = effectiveClass(living);
            if (cls == WeaponClass.SHOTGUN) {
                leftArm.xRot += 0.28F * bolt * cycle;
                leftArm.yRot -= 0.10F * bolt * cycle;
                leftArm.z += 0.55F * bolt * cycle;
                rightArm.xRot += 0.08F * bolt * cycle;
                body.xRot += 0.03F * bolt * cycle;
            } else {
                rightArm.xRot += 0.26F * bolt * cycle;
                rightArm.yRot += 0.18F * bolt * cycle;
                rightArm.zRot -= 0.22F * bolt * cycle;
                head.yRot -= 0.05F * bolt * cycle;
            }
        }
    }

    // -------------------------------------------------------------------
    // Smoothly blended eating food / applying bandage or medkit animation
    // (offhand use - supports both self-heal and kneeling Medic triage).
    // -------------------------------------------------------------------
    public static void applyUseItem(UnitProceduralAnimState state, float ageInTicks,
                                    ModelPart head, ModelPart rightArm, ModelPart leftArm) {
        float useBlend = state.smoothUseItemBlend();
        if (useBlend <= 0.001F) {
            return;
        }
        float crouch = state.smoothCrouchBlend();
        if (crouch > 0.35F) {
            // Kneeling Medic triage: both arms reach forward toward the patient
            // and perform an active rhythmic bandaging / compress motion.
            float wrap = Mth.sin(ageInTicks * 0.75F);
            float cross = Mth.cos(ageInTicks * 0.75F);
            leftArm.xRot = Mth.lerp(useBlend, leftArm.xRot, -1.25F + wrap * 0.22F);
            leftArm.yRot = Mth.lerp(useBlend, leftArm.yRot, 0.38F + cross * 0.16F);
            leftArm.zRot = Mth.lerp(useBlend, leftArm.zRot, 0.15F);

            rightArm.xRot = Mth.lerp(useBlend, rightArm.xRot, -1.18F - wrap * 0.28F);
            rightArm.yRot = Mth.lerp(useBlend, rightArm.yRot, -0.34F - cross * 0.18F);
            rightArm.zRot = Mth.lerp(useBlend, rightArm.zRot, -0.14F);
            head.xRot += (0.28F + wrap * 0.06F) * useBlend;
            return;
        }
        float chew = Mth.sin(ageInTicks * 0.85F);
        leftArm.xRot = Mth.lerp(useBlend, leftArm.xRot, -1.45F + chew * 0.14F);
        leftArm.yRot = Mth.lerp(useBlend, leftArm.yRot, 0.52F);
        leftArm.zRot = Mth.lerp(useBlend, leftArm.zRot, 0.18F);
        rightArm.xRot = Mth.lerp(0.55F * useBlend, rightArm.xRot, -0.85F);
        head.xRot += (0.18F + chew * 0.05F) * useBlend;
    }

    // -------------------------------------------------------------------
    // Prone firing stance: MixinUnitRenderProne tilts the whole PoseStack
    // forward -82 deg onto the belly; counter-pitch head and arms up so the
    // soldier props on its elbows and aims horizontally at the target, with
    // legs spread in a stable prone V-stance.
    // -------------------------------------------------------------------
    public static void applyPronePose(float p, ModelPart head,
                                      ModelPart rightArm, ModelPart leftArm,
                                      ModelPart rightLeg, ModelPart leftLeg) {
        // Lift head so eyes & helmet look forward along the ground
        head.xRot -= 1.32F * p;
        head.y += 1.2F * p;
        head.z -= 1.6F * p;

        // Prop both arms forward on elbows so the rifle points at the target
        rightArm.xRot -= 1.28F * p;
        rightArm.zRot += 0.14F * p;
        rightArm.y += 1.4F * p;
        rightArm.z -= 1.0F * p;

        leftArm.xRot -= 1.32F * p;
        leftArm.yRot += 0.22F * p;
        leftArm.zRot -= 0.16F * p;
        leftArm.y += 1.4F * p;
        leftArm.z -= 1.0F * p;

        // Flatten leg walk swing and spread legs into a prone V-brace
        rightLeg.xRot = Mth.lerp(p, rightLeg.xRot, 0.06F);
        leftLeg.xRot = Mth.lerp(p, leftLeg.xRot, -0.04F);
        rightLeg.zRot -= 0.18F * p;
        leftLeg.zRot += 0.18F * p;
    }

    // -------------------------------------------------------------------
    // Class-specific crouch/kneel stances. Caller passes the effective
    // weapon class (RIFLE is the default for classless rigs).
    // -------------------------------------------------------------------
    public static void applyCrouchStance(float c, WeaponClass cls,
                                         ModelPart body, ModelPart head,
                                         ModelPart rightArm, ModelPart leftArm,
                                         ModelPart rightLeg, ModelPart leftLeg) {
        switch (cls) {
            case SNIPER -> applyMarksmanKneel(c, body, head, rightArm, leftArm, rightLeg, leftLeg);
            case HEAVY_MG -> applyBipodCrouch(c, body, head, rightArm, leftArm, rightLeg, leftLeg);
            case HEAVY -> applyDemolisherBlastStance(c, body, head, rightArm, leftArm, rightLeg, leftLeg);
            case SMG -> applyBreacherCqcCrouch(c, body, head, rightArm, leftArm, rightLeg, leftLeg);
            case SHOTGUN -> applySweeperStance(c, body, head, rightArm, leftArm, rightLeg, leftLeg);
            case PISTOL -> applyPistolStance(c, body, head, rightArm, leftArm, rightLeg, leftLeg);
            default -> applyTacticalHalfKneel(c, body, head, rightArm, leftArm, rightLeg, leftLeg);
        }
    }

    // The weapon class a pose pass should read for ANY unit rig: classed
    // units (SEM units + the VDF militia, which now implements
    // IClassifiedUnit natively) resolve through the class system - the
    // held gun can still subclass the assigned class (pistol sidearm).
    // The inference fallback stays for any rig carrying a gun with no
    // class at all.
    public static WeaponClass effectiveClass(LivingEntity living) {
        WeaponClass cls = WeaponClassSupport.getEffective(living);
        if (cls != null) {
            return cls;
        }
        ItemStack stack = living.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun == null) {
            return null;
        }
        ResourceLocation gunId = iGun.getGunId(stack);
        return gunId == null ? null : ClassLoadouts.inferClass(gunId);
    }

    // -------------------------------------------------------------------
    // Pose: BREACHER (SMG) - aggressive tight CQB crouch
    // -------------------------------------------------------------------
    private static void applyBreacherCqcCrouch(float c, ModelPart body, ModelPart head,
                                               ModelPart rightArm, ModelPart leftArm,
                                               ModelPart rightLeg, ModelPart leftLeg) {
        body.xRot += 0.50F * c;
        body.y += 2.4F * c;
        body.z += 0.6F * c;

        head.y += 3.8F * c;
        head.z += 1.4F * c;

        // Tight forward step, shins tucked
        rightLeg.xRot += 0.35F * c;
        rightLeg.y -= 0.4F * c;
        leftLeg.xRot -= 0.50F * c;
        leftLeg.y += 1.4F * c;

        // Tucked arms: tight to chest, CQB ready position
        rightArm.y += 3.4F * c;
        rightArm.z += 1.2F * c;
        rightArm.xRot -= 0.10F * c;
        rightArm.zRot -= 0.15F * c;

        leftArm.y += 3.4F * c;
        leftArm.z += 1.2F * c;
        leftArm.xRot += 0.10F * c;
        leftArm.zRot += 0.10F * c;
    }

    // -------------------------------------------------------------------
    // Pose: SWEEPER (SHOTGUN) - wide-base recoil absorption crouch
    // -------------------------------------------------------------------
    private static void applySweeperStance(float c, ModelPart body, ModelPart head,
                                           ModelPart rightArm, ModelPart leftArm,
                                           ModelPart rightLeg, ModelPart leftLeg) {
        body.xRot += 0.46F * c;
        body.yRot -= 0.08F * c;
        body.y += 2.2F * c;

        head.y += 3.6F * c;
        head.z += 1.2F * c;

        // Wide stance for recoil stability
        rightLeg.xRot += 0.40F * c;
        rightLeg.zRot -= 0.15F * c;
        rightLeg.y -= 0.3F * c;

        leftLeg.xRot -= 0.45F * c;
        leftLeg.zRot += 0.15F * c;
        leftLeg.y += 1.2F * c;

        rightArm.y += 3.2F * c;
        rightArm.z += 1.2F * c;
        leftArm.y += 3.2F * c;
        leftArm.z += 1.2F * c;
    }

    // -------------------------------------------------------------------
    // Pose: SIDEARM (PISTOL) - tactical one-knee pistol stance
    // -------------------------------------------------------------------
    private static void applyPistolStance(float c, ModelPart body, ModelPart head,
                                          ModelPart rightArm, ModelPart leftArm,
                                          ModelPart rightLeg, ModelPart leftLeg) {
        body.xRot += 0.38F * c;
        body.y += 2.2F * c;

        head.y += 3.2F * c;
        head.z += 1.0F * c;

        // Grounded right knee, left foot planted
        rightLeg.xRot -= 0.70F * c;
        rightLeg.y += 2.0F * c;

        leftLeg.xRot += 0.45F * c;
        leftLeg.y -= 0.5F * c;

        // Both arms punch forward toward the target
        rightArm.y += 3.0F * c;
        rightArm.z += 1.5F * c;
        rightArm.xRot -= 0.20F * c;

        leftArm.y += 3.0F * c;
        leftArm.z += 1.5F * c;
        leftArm.xRot -= 0.20F * c;
        leftArm.yRot += 0.15F * c;
    }

    // -------------------------------------------------------------------
    // Pose: CAMPER / SNIPER - deep one-knee marksman kneel
    // -------------------------------------------------------------------
    private static void applyMarksmanKneel(float c, ModelPart body, ModelPart head,
                                           ModelPart rightArm, ModelPart leftArm,
                                           ModelPart rightLeg, ModelPart leftLeg) {
        // Strong forward body lean
        body.xRot += 0.65F * c;
        body.y += 2.8F * c;
        body.z += 0.8F * c;

        // Head rides the torso lean
        head.y += 4.5F * c;
        head.z += 1.8F * c;

        // Right leg - knee swings well forward, shin planted
        rightLeg.xRot += 0.50F * c;
        rightLeg.y -= 1.0F * c;

        // Left leg - folds back, knee toward ground
        leftLeg.xRot -= 1.20F * c;
        leftLeg.y += 2.5F * c;

        // Arms follow the body drop; right arm (trigger arm) up for scope
        rightArm.y += 4.0F * c;
        rightArm.z += 1.8F * c;
        rightArm.xRot -= 0.15F * c;

        // Left arm (support arm) drops deeper - elbow on knee
        leftArm.y += 5.2F * c;
        leftArm.z += 1.8F * c;
        leftArm.xRot += 0.20F * c;
    }

    // -------------------------------------------------------------------
    // Pose: SUPPRESSOR - wide bipod crouch
    // -------------------------------------------------------------------
    private static void applyBipodCrouch(float c, ModelPart body, ModelPart head,
                                         ModelPart rightArm, ModelPart leftArm,
                                         ModelPart rightLeg, ModelPart leftLeg) {
        // Heavy forward lean with a slight sink
        body.xRot += 0.55F * c;
        body.y += 3.2F * c;
        body.z += 0.5F * c;

        head.y += 4.8F * c;
        head.z += 1.4F * c;

        // Both legs spread outward (crouch width)
        rightLeg.xRot += 0.40F * c;
        rightLeg.zRot -= 0.20F * c;
        rightLeg.y -= 0.5F * c;

        leftLeg.xRot += 0.40F * c;
        leftLeg.zRot += 0.20F * c;
        leftLeg.y -= 0.5F * c;

        // Arms
        rightArm.y += 4.5F * c;
        rightArm.z += 1.4F * c;

        // Left arm reaches well forward to "grip the bipod"
        leftArm.y += 4.5F * c;
        leftArm.z += 2.4F * c;
        leftArm.xRot -= 0.35F * c;
    }

    // -------------------------------------------------------------------
    // Pose: DEMOLISHER - upright wide-leg blast stance
    // -------------------------------------------------------------------
    private static void applyDemolisherBlastStance(float c, ModelPart body, ModelPart head,
                                                   ModelPart rightArm, ModelPart leftArm,
                                                   ModelPart rightLeg, ModelPart leftLeg) {
        // Very slight rear-lean - bracing for back-blast
        body.xRot += 0.18F * c;
        body.y += 0.8F * c;

        head.y += 1.0F * c;

        // Legs planted wide - stable base for the tube
        rightLeg.zRot -= 0.25F * c;
        rightLeg.xRot += 0.12F * c;

        leftLeg.zRot += 0.25F * c;
        leftLeg.xRot += 0.12F * c;

        // Arms ride the small body shift
        rightArm.y += 1.2F * c;
        leftArm.y += 1.2F * c;

        // Right arm rises slightly to cradle the tube on the shoulder
        rightArm.xRot -= 0.20F * c;
    }

    // -------------------------------------------------------------------
    // Pose: DEFAULT - tactical half-kneel: moderate lean, CQB low silhouette
    // -------------------------------------------------------------------
    private static void applyTacticalHalfKneel(float c, ModelPart body, ModelPart head,
                                               ModelPart rightArm, ModelPart leftArm,
                                               ModelPart rightLeg, ModelPart leftLeg) {
        // Moderate forward lean
        body.xRot += 0.42F * c;
        body.y += 2.0F * c;

        head.y += 3.4F * c;
        head.z += 1.1F * c;

        // Right leg - slight forward step
        rightLeg.xRot += 0.25F * c;
        rightLeg.y -= 0.3F * c;

        // Left leg - shifted back, partial crouch
        leftLeg.xRot -= 0.35F * c;
        leftLeg.y += 1.0F * c;

        // Arms follow naturally
        rightArm.y += 3.0F * c;
        rightArm.z += 1.1F * c;
        leftArm.y += 3.0F * c;
        leftArm.z += 1.1F * c;
    }
}
