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
    // stride, natural knee lift, idle breathing, and rush lean.
    // -------------------------------------------------------------------
    public static void applyLocomotionPolish(ModelPart unit, ModelPart head, ModelPart body,
                                             ModelPart rightArm, ModelPart leftArm,
                                             ModelPart rightLeg, ModelPart leftLeg,
                                             float limbSwing, float limbSwingAmount, float ageInTicks,
                                             float walkBlend, float combatBlend) {
        // 1. Strip SEM's cartoon squash-and-stretch scale keyframes (1.10x/0.97y)
        for (ModelPart part : new ModelPart[]{
                unit, head, body, rightArm, leftArm, rightLeg, leftLeg}) {
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
        unit.x = 0.0F;
        unit.y = -Math.abs(stepSin) * 0.42F * moveAmt;
        unit.z = 0.0F;
        unit.xRot = 0.0873F;
        unit.yRot = 0.0F;
        unit.zRot = stepSin * 0.018F * moveAmt;

        // When patrolling out of combat or moving, slightly relax the 37.5-deg
        // bladed firing torso toward the direction of travel so walking looks natural.
        float baseBodyYRot = Mth.lerp(walkBlend * (1.0F - 0.55F * combatBlend), 0.6536F, 0.34F);
        float rushLean = Math.max(0.0F, moveAmt - 0.30F) * 0.24F;
        body.x = 0.0F;
        body.y = -6.26F + breath * 0.08F * (1.0F - moveAmt);
        body.z = 1.0616F;
        body.xRot = 0.0997F + rushLean + breath * 0.015F * (1.0F - moveAmt);
        body.yRot = baseBodyYRot - stride * 0.10F * moveAmt;
        body.zRot = 0.0238F + stepSin * 0.02F * moveAmt;

        head.x = 0.0F;
        head.y = -6.16F;
        head.z = 1.0616F - rushLean * 2.2F;

        // 3. Distance-synchronized legs: smoothly converge from SEM's wide
        //    stationary firing stagger (z = +4.0 / -1.0) into a clean walking
        //    hip line (z = +1.4 / +0.6) so slow patrol strolls never piston-slide.
        float rSwing = stride * 1.15F * moveAmt;
        float lSwing = -stride * 1.15F * moveAmt;
        float rLift = Math.max(0.0F, -rSwing) * 0.75F;
        float lLift = Math.max(0.0F, -lSwing) * 0.75F;

        rightLeg.x = -1.9F;
        rightLeg.y = 6.0F - rLift;
        rightLeg.z = Mth.lerp(walkBlend, 4.0F, 1.4F) + rSwing * 0.45F;
        rightLeg.xRot = Mth.lerp(walkBlend, 0.1309F, 0.02F) + rSwing;
        rightLeg.yRot = 0.0F;
        rightLeg.zRot = 0.0F;

        leftLeg.x = Mth.lerp(walkBlend, 2.15F, 1.95F);
        leftLeg.y = Mth.lerp(walkBlend, 5.85F, 6.0F) - lLift;
        leftLeg.z = Mth.lerp(walkBlend, -1.0F, 0.6F) + lSwing * 0.45F;
        leftLeg.xRot = Mth.lerp(walkBlend, -0.1745F, -0.02F) + lSwing;
        leftLeg.yRot = 0.0F;
        leftLeg.zRot = 0.0F;

        // 4. Keep shoulders anchored to their tactical pivots (eliminates UNIT_WALK arm jitter)
        float armBobY = doubleStep * 0.28F * moveAmt + breath * 0.10F * (1.0F - moveAmt);
        float swayX = stepSin * 0.05F * moveAmt * (1.0F - 0.55F * combatBlend);
        rightArm.x = -3.0F;
        rightArm.y = -2.9089F + armBobY;
        rightArm.z = 3.4377F;
        rightArm.xRot += swayX;

        leftArm.x = 2.5F;
        leftArm.y = -3.2588F + armBobY;
        leftArm.z = -3.4123F;
        leftArm.xRot += swayX;
    }

    // -------------------------------------------------------------------
    // Per-shot recoil kick, 3-stage reload animation, and bolt cycling
    // -------------------------------------------------------------------
    public static void applyGunProcedural(LivingEntity living, UnitProceduralAnimState state,
                                          ModelPart body, ModelPart head,
                                          ModelPart rightArm, ModelPart leftArm) {
        // 1. Subtle per-shot recoil micro-kick (reduced so units stay steady)
        float recoil = state.recoilImpulse();
        if (recoil > 0.005F) {
            float scale = UnitProceduralAnimState.classRecoilScale(living);
            float kick = recoil * scale;
            float side = state.recoilSign() * 0.012F * kick;

            rightArm.xRot -= 0.045F * kick;
            rightArm.yRot += side;
            rightArm.z += 0.20F * kick;

            leftArm.xRot -= 0.038F * kick;
            leftArm.yRot += side;
            leftArm.z += 0.18F * kick;

            body.xRot -= 0.012F * kick;
            head.xRot -= 0.008F * kick;
        }

        // 2. Tactical reload animation (cant weapon, left hand mag pouch -> magwell -> bolt)
        float rel = state.smoothReloadBlend();
        if (rel > 0.005F) {
            float phase = (state.reloadTicks() % 36.0F) / 36.0F;
            // Right arm cants the weapon inward and slightly down for inspection
            rightArm.xRot += 0.22F * rel;
            rightArm.yRot -= 0.18F * rel;
            rightArm.zRot -= 0.32F * rel;

            // Head glances down toward receiver
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

        // 3. Bolt-action / pump cycling
        float bolt = state.smoothBoltBlend();
        if (bolt > 0.005F) {
            float cycle = Mth.sin(Math.min(1.0F, state.boltTicks() / 12.0F) * (float) Math.PI);
            rightArm.xRot += 0.24F * bolt * cycle;
            rightArm.yRot += 0.16F * bolt * cycle;
            rightArm.zRot -= 0.20F * bolt * cycle;
        }
    }

    // -------------------------------------------------------------------
    // Smoothly blended eating food / applying bandage or medkit animation
    // (offhand use - VDF never triggers it, SEM units do).
    // -------------------------------------------------------------------
    public static void applyUseItem(UnitProceduralAnimState state, float ageInTicks,
                                    ModelPart head, ModelPart rightArm, ModelPart leftArm) {
        float useBlend = state.smoothUseItemBlend();
        if (useBlend <= 0.001F) {
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
    // weapon class (RIFLE default for classless rigs like the VDF).
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

    // The weapon class a pose pass should read for ANY unit rig: SEM units
    // resolve through the class system (held gun can subclass the assigned
    // class); classless rigs like the VDF militia fall back to the held
    // gun's inferred class so a militia shotgun still poses as SHOTGUN.
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
