package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.api.item.IGun;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.Map;
import java.util.WeakHashMap;

// Per-entity client-side procedural animation state for SEM units.
// Tracks smooth low/high-ready transitions, per-shot recoil impulses,
// tactical reload progress, and bolt-action cycling at render frame rate.
public final class UnitProceduralAnimState {

    private static final Map<LivingEntity, UnitProceduralAnimState> STATES = new WeakHashMap<>();

    public static synchronized UnitProceduralAnimState get(LivingEntity entity) {
        return STATES.computeIfAbsent(entity, k -> new UnitProceduralAnimState());
    }

    private float lastAgeInTicks = Float.NaN;
    private float combatBlend = 0.0F;
    private float crouchBlend = 0.0F;
    private float walkBlend = 0.0F;
    private float runBlend = 0.0F;
    private float speedRatio = 0.0F;
    private float walkPhase = 0.0F;
    private float strideAmplitude = 0.0F;
    private float useItemBlend = 0.0F;
    private float proneBlend = 0.0F;
    private float recoilImpulse = 0.0F;
    private float recoilSign = 1.0F;
    private float reloadBlend = 0.0F;
    private float reloadTicks = 0.0F;
    private float boltBlend = 0.0F;
    private float boltTicks = 0.0F;

    // Locomotion direction relative to the body (units fight while strafing
    // and backpedalling with the body locked on the target), body turn rate
    // and airborne blend - feed the strafe-aware legs, directional lean and
    // turn bank in UnitProceduralPoses.applyLocomotionPolish.
    private float moveForward = 1.0F;                                     // +1 forward, -1 backpedal
    private float moveSide = 0.0F;                                        // +1 right, -1 left
    private float turnRate = 0.0F;                                        // deg/tick, smoothed
    private float lastBodyYaw = Float.NaN;
    private float airBlend = 0.0F;

    // Better Combat style smooth melee swing timer & 3-hit combo index
    private boolean wasSwinging = false;
    private float meleeSwingTicks = 0.0F;
    private float meleeSwingDuration = 11.0F;
    private int meleeComboIndex = -1;                                     // -1 so the first swing lands on combo 0

    private ResourceLocation lastGunId = null;
    private int lastAmmo = -1;
    private long lastShootCoolDown = 0L;

    public void update(LivingEntity entity, float ageInTicks) {
        float dt;
        if (Float.isNaN(this.lastAgeInTicks) || ageInTicks < this.lastAgeInTicks) {
            dt = 0.05F;
            if (entity instanceof AbstractUnit unit) {
                this.combatBlend = unit.isInCombat() ? 1.0F : 0.0F;
            } else if (entity instanceof VdfUnitEntity vdf) {
                this.combatBlend = vdf.isInCombat() ? 1.0F : 0.0F;
            }
            this.crouchBlend = entity.isShiftKeyDown() ? 1.0F : 0.0F;
            this.walkBlend = Mth.clamp(entity.walkAnimation.speed() * 2.2F, 0.0F, 1.0F);
            this.useItemBlend = (entity.isUsingItem() && !entity.getOffhandItem().isEmpty()) ? 1.0F : 0.0F;
            this.proneBlend = entity.getPose() == net.minecraft.world.entity.Pose.SWIMMING ? 1.0F : 0.0F;
        } else {
            float rawDt = ageInTicks - this.lastAgeInTicks;
            if (Math.abs(rawDt) < 1.0E-4F) {
                return; // Already updated for this exact render frame
            }
            dt = Mth.clamp(rawDt, 0.0F, 1.5F);
        }
        this.lastAgeInTicks = ageInTicks;

        // Smooth prone transition (~5 ticks = 0.25s)
        float targetProne = (entity.isAlive() && entity.getPose() == net.minecraft.world.entity.Pose.SWIMMING) ? 1.0F : 0.0F;
        this.proneBlend = approach(this.proneBlend, targetProne, dt * 0.20F);

        // Smooth crouch / kneel transition (~4 ticks = 0.20s) - eliminates 1-frame
        // snapping when VdfNightWatchGoal or RangedGunAttackGoal toggles sneak.
        float targetCrouch = (entity.isAlive() && entity.isShiftKeyDown() && targetProne < 0.5F) ? 1.0F : 0.0F;
        this.crouchBlend = approach(this.crouchBlend, targetCrouch, dt * 0.25F);

        // Smooth locomotion blend from client walkAnimation speed + position delta.
        // Accurately differentiates slow walking/patrolling from running/sprinting so
        // slow strolls take gentle, measured strides while combat rushes drive forward.
        float pt = Mth.frac(ageInTicks);
        float limbSpeed = entity.walkAnimation.speed(pt);
        double dx = entity.getX() - entity.xo;
        double dz = entity.getZ() - entity.zo;
        float horizDist = (float) Math.sqrt(dx * dx + dz * dz);

        // Ground speed estimation (blocks/tick): slow walk is ~0.03-0.12 b/t, sprint is 0.22-0.35+ b/t
        float speed = Math.max(limbSpeed * 0.25F, horizDist);
        boolean movingHoriz = speed > 0.003F || limbSpeed > 0.015F;

        // Walk blend: smoothly rises when moving, returns to 0 when stationary
        float targetWalk = movingHoriz ? Mth.clamp(Math.max(0.40F, speed * 10.0F), 0.0F, 1.0F) : 0.0F;
        float walkRate = targetWalk > this.walkBlend ? dt * 0.35F : dt * 0.25F;
        this.walkBlend = approach(this.walkBlend, targetWalk, walkRate);

        // Run blend: 0 during slow walk/patrol (< 0.16 b/t), rises toward 1.0 during full sprint (> 0.26 b/t)
        boolean isSprinting = entity.isSprinting() || (entity instanceof AbstractUnit u && u.isInCombat() && speed > 0.22F);
        float targetRun = (movingHoriz && (speed > 0.16F || isSprinting))
                ? Mth.clamp(isSprinting ? 1.0F : (speed - 0.16F) / 0.10F, 0.0F, 1.0F)
                : 0.0F;
        float runRate = targetRun > this.runBlend ? dt * 0.30F : dt * 0.20F;
        this.runBlend = approach(this.runBlend, targetRun, runRate);

        // Locomotion speed ratio for base clip advancement:
        // Slow stroll (~0.04 b/t) -> ~0.25x speed
        // Normal walk (~0.14 b/t) -> ~0.70x speed
        // Fast sprint (~0.30+ b/t) -> ~1.25x speed
        float targetSpeedRatio = movingHoriz
                ? Mth.clamp(0.20F + speed * 3.5F, 0.20F, 1.35F)
                : 0.0F;
        this.speedRatio = approach(this.speedRatio, targetSpeedRatio, dt * 0.35F);

        // Stride amplitude:
        // Slow walk: 0.32F - 0.50F (gentle, relaxed, compact leg swing)
        // Normal walk: 0.55F - 0.70F
        // Sprint: 1.0F - 1.15F (wide athletic drive)
        float targetStrideAmp = movingHoriz
                ? Mth.lerp(this.runBlend, Mth.clamp(0.32F + speed * 1.8F, 0.32F, 0.65F), 1.15F)
                : 0.0F;
        float ampRate = targetStrideAmp > this.strideAmplitude ? dt * 0.35F : dt * 0.22F;
        this.strideAmplitude = approach(this.strideAmplitude, targetStrideAmp, ampRate);

        // Step frequency (cadence):
        // Scales directly with ground translation speed so legs never piston on ice!
        // At slow patrol: stepFreq ~ 0.10 rad/tick (takes ~2.5s per cycle)
        // At full sprint: stepFreq ~ 0.50 rad/tick (takes ~0.6s per cycle)
        if (this.walkBlend > 0.02F) {
            float stepFreq = Mth.clamp(0.08F + speed * 1.45F, 0.08F, 0.60F);
            this.walkPhase += dt * stepFreq;
        }

        // Movement direction in body space. yBodyRot 0 faces +Z; the body's
        // right-hand side is (-cos, -sin). Only re-aim while actually moving
        // so the legs don't snap back to "forward" in the last stride frames.
        if (horizDist > 0.004F) {
            float yawRad = entity.yBodyRot * Mth.DEG_TO_RAD;
            float fx = -Mth.sin(yawRad);
            float fz = Mth.cos(yawRad);
            float invLen = 1.0F / horizDist;
            float tFwd = (float) (dx * fx + dz * fz) * invLen;
            float tSide = (float) (-dx * fz + dz * fx) * invLen;
            float dirRate = Mth.clamp(dt * 0.30F, 0.0F, 1.0F);
            this.moveForward = Mth.lerp(dirRate, this.moveForward, tFwd);
            this.moveSide = Mth.lerp(dirRate, this.moveSide, tSide);
        } else if (this.walkBlend < 0.05F) {
            float relax = Mth.clamp(dt * 0.10F, 0.0F, 1.0F);
            this.moveForward = Mth.lerp(relax, this.moveForward, 1.0F);
            this.moveSide = Mth.lerp(relax, this.moveSide, 0.0F);
        }

        // Body turn rate (deg per tick), smoothed - drives a small bank /
        // torso lag so pivots read as weight instead of a rigid spin.
        float bodyYaw = entity.yBodyRot;
        if (!Float.isNaN(this.lastBodyYaw) && dt > 0.0001F) {
            float rate = Mth.clamp(Mth.wrapDegrees(bodyYaw - this.lastBodyYaw) / dt, -40.0F, 40.0F);
            this.turnRate = Mth.lerp(Mth.clamp(dt * 0.25F, 0.0F, 1.0F), this.turnRate, rate);
        }
        this.lastBodyYaw = bodyYaw;

        // Airborne (jump / drop off a ledge) - legs tuck, arms balance
        boolean airborne = entity.isAlive() && !entity.onGround() && !entity.isInWater()
                && !entity.isPassenger() && !entity.onClimbable()
                && Math.abs(entity.getY() - entity.yo) > 0.02D;
        this.airBlend = approach(this.airBlend, airborne ? 1.0F : 0.0F, dt * (airborne ? 0.18F : 0.30F));

        // Smooth offhand food / medkit use blend (~3.5 ticks)
        float targetUse = (entity.isAlive() && entity.isUsingItem() && !entity.getOffhandItem().isEmpty()) ? 1.0F : 0.0F;
        this.useItemBlend = approach(this.useItemBlend, targetUse, dt * 0.28F);

        boolean inCombat = (entity instanceof AbstractUnit unit && unit.isInCombat())
                || (entity instanceof VdfUnitEntity vdf && vdf.isInCombat())
                || (entity instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() != null);

        // 1. Smooth combat stance blend (~4.5 ticks = 0.225s transition)
        float targetCombat = inCombat ? 1.0F : 0.0F;
        this.combatBlend = approach(this.combatBlend, targetCombat, dt * 0.22F);

        // Smooth Better Combat melee swing progression. The swing window is
        // retuned to the held weapon's attack speed every update so a fast
        // knife's anim fits inside its 8-tick cadence while a heavy axe gets
        // a full committed swing - same interval math the server goal uses.
        boolean swingingNow = entity.swinging && entity.swingTime <= 2;
        if (swingingNow || this.meleeSwingTicks > 0.0F) {
            this.meleeSwingDuration = Mth.clamp(
                    meleeAttackIntervalTicks(entity) * 0.70F, 6.0F, 15.0F);
        }
        if (swingingNow && !this.wasSwinging) {
            this.meleeComboIndex = (this.meleeComboIndex + 1) % 3;
            this.meleeSwingTicks = 0.001F;
        }
        this.wasSwinging = swingingNow;
        if (this.meleeSwingTicks > 0.0F) {
            this.meleeSwingTicks += dt;
            if (this.meleeSwingTicks >= this.meleeSwingDuration) {
                this.meleeSwingTicks = 0.0F;
            }
        }

        // 2. Inspect held TaCZ gun for shots, reload, and bolting
        ItemStack stack = entity.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun == null) {
            this.lastGunId = null;
            this.lastAmmo = -1;
            this.recoilImpulse = 0.0F;
            this.reloadBlend = Math.max(0.0F, this.reloadBlend - dt * 0.25F);
            this.boltBlend = Math.max(0.0F, this.boltBlend - dt * 0.25F);
            return;
        }

        ResourceLocation gunId = iGun.getGunId(stack);
        int ammo = iGun.getCurrentAmmoCount(stack);
        IGunOperator operator = IGunOperator.fromLivingEntity(entity);
        long shootCd = operator != null ? operator.getSynShootCoolDown() : 0L;

        boolean sameGun = gunId != null && gunId.equals(this.lastGunId);
        if (sameGun) {
            boolean ammoDropped = this.lastAmmo > 0 && ammo < this.lastAmmo && (this.lastAmmo - ammo) <= 4;
            boolean cdSpiked = shootCd > this.lastShootCoolDown + 1L && shootCd > 0L;
            if (ammoDropped || cdSpiked) {
                this.recoilImpulse = 1.0F;
                this.recoilSign = -this.recoilSign;
            }
        }
        this.lastGunId = gunId;
        this.lastAmmo = ammo;
        this.lastShootCoolDown = shootCd;

        // Exponential recoil recovery (snappier decay so full-auto doesn't over-kick)
        if (this.recoilImpulse > 0.001F && dt > 0.0F) {
            this.recoilImpulse *= (float) Math.exp(-dt * 0.78F);
            if (this.recoilImpulse < 0.005F) {
                this.recoilImpulse = 0.0F;
            }
        }

        // 3. Reload state
        boolean reloading = false;
        if (operator != null) {
            ReloadState rs = operator.getSynReloadState();
            if (rs != null && rs.getStateType() != null && rs.getStateType().isReloading()) {
                reloading = true;
            }
        }
        if (reloading) {
            this.reloadBlend = Math.min(1.0F, this.reloadBlend + dt * 0.25F);
            this.reloadTicks += dt;
        } else {
            this.reloadBlend = Math.max(0.0F, this.reloadBlend - dt * 0.25F);
            if (this.reloadBlend == 0.0F) {
                this.reloadTicks = 0.0F;
            }
        }

        // 4. Bolt action cycling
        boolean bolting = operator != null && operator.getSynIsBolting();
        if (bolting) {
            this.boltBlend = Math.min(1.0F, this.boltBlend + dt * 0.35F);
            this.boltTicks += dt;
        } else {
            this.boltBlend = Math.max(0.0F, this.boltBlend - dt * 0.30F);
            if (this.boltBlend == 0.0F) {
                this.boltTicks = 0.0F;
            }
        }
    }

    private static float approach(float current, float target, float step) {
        if (current < target) {
            return Math.min(target, current + step);
        }
        if (current > target) {
            return Math.max(target, current - step);
        }
        return current;
    }

    private static float smoothstep(float value) {
        float t = Mth.clamp(value, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    public float smoothCombatBlend() {
        return smoothstep(this.combatBlend);
    }

    public float smoothCrouchBlend() {
        return smoothstep(this.crouchBlend);
    }

    public float smoothWalkBlend() {
        return smoothstep(this.walkBlend);
    }

    public float smoothRunBlend() {
        return smoothstep(this.runBlend);
    }

    public float runBlend() {
        return this.runBlend;
    }

    public float locomotionSpeedRatio() {
        return this.speedRatio;
    }

    public float walkPhase() {
        return this.walkPhase;
    }

    public float smoothStrideAmplitude() {
        return smoothstep(this.strideAmplitude);
    }

    public float smoothUseItemBlend() {
        return smoothstep(this.useItemBlend);
    }

    public float smoothProneBlend() {
        return smoothstep(this.proneBlend);
    }

    public float recoilImpulse() {
        return this.recoilImpulse;
    }

    public float recoilSign() {
        return this.recoilSign;
    }

    public float smoothReloadBlend() {
        float t = Mth.clamp(this.reloadBlend, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    public float reloadTicks() {
        return this.reloadTicks;
    }

    public float smoothBoltBlend() {
        float t = Mth.clamp(this.boltBlend, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    public float boltTicks() {
        return this.boltTicks;
    }

    // Body-space travel direction: forward (+1) .. backpedal (-1)
    public float moveForward() {
        return Mth.clamp(this.moveForward, -1.0F, 1.0F);
    }

    // Body-space travel direction: strafe right (+1) .. strafe left (-1)
    public float moveSide() {
        return Mth.clamp(this.moveSide, -1.0F, 1.0F);
    }

    // Smoothed body yaw rate in degrees per tick (+ = turning toward +yaw)
    public float turnRate() {
        return this.turnRate;
    }

    public float smoothAirBlend() {
        return smoothstep(this.airBlend);
    }

    public float meleeSwingProgress() {
        if (this.meleeSwingTicks <= 0.0F) {
            return 0.0F;
        }
        return Mth.clamp(this.meleeSwingTicks / this.meleeSwingDuration, 0.0F, 1.0F);
    }

    public int meleeComboIndex() {
        return this.meleeComboIndex;
    }

    // Client mirror of UnitMeleeAttackGoal.computeSwingIntervalTicks(): derive
    // the real attack cadence (8-20 ticks) from the unit's ATTACK_SPEED value,
    // falling back to the item's own attribute modifier when no attribute exists.
    private static float meleeAttackIntervalTicks(LivingEntity entity) {
        double atkSpeed = 1.6D;
        AttributeInstance inst = entity.getAttribute(Attributes.ATTACK_SPEED);
        if (inst != null && inst.getValue() > 0.2D) {
            atkSpeed = inst.getValue();
        } else {
            var mods = entity.getMainHandItem().getAttributeModifiers(EquipmentSlot.MAINHAND)
                    .get(Attributes.ATTACK_SPEED);
            if (!mods.isEmpty()) {
                atkSpeed = Math.max(0.6D, 4.0D + mods.iterator().next().getAmount());
            }
        }
        return Math.max(8.0F, Math.min(20.0F,
                (float) Math.round(20.0D / Math.max(0.8D, atkSpeed))));
    }

    public static float classRecoilScale(LivingEntity entity) {
        // Classed units (SEM units + VDF militia) resolve through the class
        // system - assigned class with gun-subclass overlay; the inference
        // fallback stays for rigs carrying a gun with no class.
        WeaponClass cls = WeaponClassSupport.getEffective(entity);
        if (cls == null) {
            cls = UnitProceduralPoses.effectiveClass(entity);
        }
        if (cls == null) {
            return 0.75F;
        }
        return switch (cls) {
            case SNIPER, HEAVY -> 1.20F;
            case SHOTGUN -> 1.10F;
            case HEAVY_MG -> 0.80F;
            case SMG, PISTOL -> 0.50F;
            default -> 0.75F;
        };
    }
}
