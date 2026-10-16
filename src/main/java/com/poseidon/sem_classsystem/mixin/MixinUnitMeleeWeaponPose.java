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
        if (entity instanceof AbstractUnit unit
                && (unit.deathAnimationState.isStarted() || unit.hurtAnimationState.isStarted())) {
            return;
        }

        ItemStack main = living.getMainHandItem();
        if (!UnitMeleeAttackGoal.isMeleeWeapon(main)) {
            return;
        }

        UnitProceduralAnimState animState = UnitProceduralAnimState.get(living);
        animState.update(living, ageInTicks);

        // 1. CRITICAL FIX: SEM's UnitModelDefinitions bakes a +37.5 deg rifle twist
        //    on 'body' (yRot = 0.6536) and rifle-cradle offsets on rightArm (z = +3.44)
        //    and leftArm (x = +2.5, z = -3.41). Reset both shoulders and torso to
        //    square humanoid pivots so the unit NEVER holds a sword like a rifle!
        this.body.xRot = 0.04F;
        this.body.yRot = 0.0F;
        this.body.zRot = 0.0F;

        this.rightArm.x = -5.0F;
        this.rightArm.y = -2.2F;
        this.rightArm.z = 0.0F;

        this.leftArm.x = 5.0F;
        this.leftArm.y = -2.2F;
        this.leftArm.z = 0.0F;

        // 2. Straight player-like legs: SEM bakes a staggered tactical stance
        //    into the leg pivots (rightLeg z=+4.0 / xRot=+0.13, leftLeg z=-1.0 /
        //    xRot=-0.17). Recentre both hips under the torso and swap the baked
        //    stagger for a vanilla alternating swing so a standing unit holds a
        //    sword exactly like a player does.
        float legMove = Math.min(1.0F, limbSwingAmount);
        float legSwing = Mth.cos(limbSwing * 0.6662F) * 1.4F * legMove;

        this.rightLeg.x = -1.9F;
        this.rightLeg.y = 6.0F;
        this.rightLeg.z = 1.0F;
        this.rightLeg.xRot = legSwing;
        this.rightLeg.yRot = 0.0F;
        this.rightLeg.zRot = 0.0F;

        this.leftLeg.x = 1.9F;
        this.leftLeg.y = 6.0F;
        this.leftLeg.z = 1.0F;
        this.leftLeg.xRot = -legSwing;
        this.leftLeg.yRot = 0.0F;
        this.leftLeg.zRot = 0.0F;

        boolean inCombat = (living instanceof AbstractUnit unit && unit.isInCombat())
                || (living instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() != null);
        boolean twoHanded = isTwoHandedWeapon(main);
        boolean spearOrThrust = isSpearOrThrustWeapon(main);

        // Smooth Better Combat swing progress (falls back to interpolated this.attackTime)
        float swing = animState.meleeSwingProgress();
        if (swing <= 0.0F && this.attackTime > 0.0F) {
            swing = this.attackTime;
        }
        int combo = animState.meleeComboIndex();

        if (swing > 0.001F) {
            if (spearOrThrust) {
                applyThrustComboSwing(swing);
            } else if (twoHanded) {
                applyTwoHandedComboSwing(swing, combo);
            } else {
                applyOneHandedComboSwing(swing, combo);
            }
            return;
        }

        // Locomotion & Combat Guard Stances
        float stride = Mth.cos(limbSwing * 0.6662F);
        float move = Math.min(1.0F, limbSwingAmount);

        if (twoHanded) {
            // Better Combat 2-handed diagonal low-guard across waist
            this.body.yRot = -0.18F;
            this.body.xRot = 0.08F + move * 0.14F;

            this.rightArm.x = -4.2F;
            this.rightArm.z = 0.8F;
            this.rightArm.xRot = -0.78F + stride * 0.22F * move;
            this.rightArm.yRot = -0.28F;
            this.rightArm.zRot = 0.12F;

            this.leftArm.x = 3.8F;
            this.leftArm.z = -1.4F;
            this.leftArm.xRot = -0.82F + stride * 0.22F * move;
            this.leftArm.yRot = 0.52F;
            this.leftArm.zRot = -0.10F;
        } else if (inCombat) {
            // Agile 1-handed combat guard: sword poised at right side, left hand balanced
            this.body.yRot = -0.16F;
            this.body.xRot = 0.10F + move * 0.16F;

            this.rightArm.xRot = -0.62F + stride * 0.35F * move;
            this.rightArm.yRot = -0.18F;
            this.rightArm.zRot = 0.16F;

            this.leftArm.xRot = -0.35F - stride * 0.45F * move;
            this.leftArm.yRot = 0.22F;
            this.leftArm.zRot = -0.18F;
        } else {
            // Relaxed patrol walk: arms swing naturally at sides like a player
            this.rightArm.xRot = -0.15F + stride * 0.55F * move;
            this.rightArm.yRot = 0.0F;
            this.rightArm.zRot = 0.06F;

            this.leftArm.xRot = -stride * 0.65F * move;
            this.leftArm.yRot = 0.0F;
            this.leftArm.zRot = -0.05F;
        }
    }

    // -------------------------------------------------------------------
    // Better Combat 3-Hit Combo for 1-Handed Weapons (Swords, Katanas, Knives, Machetes)
    //   Combo 0: Right-to-Left Diagonal Cleave
    //   Combo 1: Left-to-Right Backhand Reverse Slash
    //   Combo 2: Overhead Step-In Finisher
    // -------------------------------------------------------------------
    @Unique
    private void applyOneHandedComboSwing(float t, int combo) {
        // Smooth ease-in-out curve with fast strike phase (t = 0.20..0.70)
        float strike = smoothStrikeCurve(t);
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
        float strike = smoothStrikeCurve(t);
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
        float thrust = Mth.sin(t * (float) Math.PI);
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
        this.rightArm.z = Mth.sin(twist) * 4.2F;
        this.leftArm.z = -Mth.sin(twist) * 4.2F;
    }

    @Unique
    private static float smoothStrikeCurve(float t) {
        if (t < 0.22F) {
            return 0.0F;
        }
        if (t > 0.78F) {
            return 1.0F;
        }
        float u = (t - 0.22F) / 0.56F;
        return u * u * (3.0F - 2.0F * u);
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
