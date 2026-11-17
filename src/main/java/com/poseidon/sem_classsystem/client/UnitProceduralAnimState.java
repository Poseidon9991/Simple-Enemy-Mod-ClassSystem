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
    private float airBlend = 0.0F;
    private int lastSampledTick = Integer.MIN_VALUE;
    private float sampledSpeed = 0.0F;
    private float sampledMoveForward = 1.0F;
    private float sampledMoveSide = 0.0F;
    private float sampledTurnRate = 0.0F;
    private float lastSampledYaw = Float.NaN;
    private float phaseDirection = 1.0F;
    private boolean walkPhaseInitialized = false;

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
            this.walkBlend = Mth.clamp(entity.walkAnimation.speed() * 1.5F, 0.0F, 1.0F);
            this.useItemBlend = (entity.isUsingItem() && !entity.getOffhandItem().isEmpty()) ? 1.0F : 0.0F;
            this.proneBlend = entity.getPose() == net.minecraft.world.entity.Pose.SWIMMING ? 1.0F : 0.0F;
            this.lastSampledTick = Integer.MIN_VALUE;
            this.lastSampledYaw = entity.yBodyRot;
            this.sampledTurnRate = 0.0F;
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

        // Sample tick-level movement and body rotation once per entity tick using
        // interpolated yaw so high-FPS render passes never produce sub-tick turn spikes
        // or jittering forward/strafe estimates.
        float pt = Mth.frac(ageInTicks);
        if (entity.tickCount != this.lastSampledTick) {
            int tickDelta = (this.lastSampledTick == Integer.MIN_VALUE || entity.tickCount < this.lastSampledTick)
                    ? 1 : Math.min(4, entity.tickCount - this.lastSampledTick);
            this.lastSampledTick = entity.tickCount;

            double dx = entity.getX() - entity.xo;
            double dz = entity.getZ() - entity.zo;
            float horizDist = (float) Math.sqrt(dx * dx + dz * dz);
            if (horizDist > 1.2F) {
                // Ignore sudden teleport jumps so legs do not violently wind up
                horizDist = 0.0F;
            }
            float limbTickSpeed = entity.walkAnimation.speed(1.0F);
            float instantSpeed = horizDist > 0.002F ? horizDist : (limbTickSpeed * 0.22F);
            float speedAlpha = 1.0F - (float) Math.pow(0.62D, tickDelta);
            this.sampledSpeed = Mth.lerp(speedAlpha, this.sampledSpeed, instantSpeed);

            float interpYaw = Mth.rotLerp(pt, entity.yBodyRotO, entity.yBodyRot);
            if (!Float.isNaN(this.lastSampledYaw)) {
                float rawTurn = Mth.clamp(Mth.wrapDegrees(interpYaw - this.lastSampledYaw) / tickDelta, -28.0F, 28.0F);
                this.sampledTurnRate = Mth.lerp(speedAlpha, this.sampledTurnRate, rawTurn);
            }
            this.lastSampledYaw = interpYaw;

            if (horizDist > 0.004F) {
                float yawRad = interpYaw * Mth.DEG_TO_RAD;
                float fx = -Mth.sin(yawRad);
                float fz = Mth.cos(yawRad);
                float invLen = 1.0F / horizDist;
                float tFwd = Mth.clamp((float) (dx * fx + dz * fz) * invLen, -1.0F, 1.0F);
                float tSide = Mth.clamp((float) (-dx * fz + dz * fx) * invLen, -1.0F, 1.0F);
                if (Math.abs(tSide) < 0.08F) {
                    tSide = 0.0F;
                }
                float dirAlpha = 1.0F - (float) Math.pow(0.66D, tickDelta);
                this.sampledMoveForward = Mth.lerp(dirAlpha, this.sampledMoveForward, tFwd);
                this.sampledMoveSide = Mth.lerp(dirAlpha, this.sampledMoveSide, tSide);
            } else if (this.walkBlend < 0.06F) {
                float relaxAlpha = 1.0F - (float) Math.pow(0.82D, tickDelta);
                this.sampledMoveForward = Mth.lerp(relaxAlpha, this.sampledMoveForward, 1.0F);
                this.sampledMoveSide = Mth.lerp(relaxAlpha, this.sampledMoveSide, 0.0F);
            }
        }

        // Exponentially smooth locomotion speed, direction, and turn rate per frame.
        float speed = this.sampledSpeed;
        float limbNow = entity.walkAnimation.speed(pt);
        boolean movingHoriz = speed > 0.004F || limbNow > 0.025F;

        // Walk blend: rises proportionally to actual ground speed rather than pinning to 1.0 on slow strolls
        float targetWalk = movingHoriz ? Mth.clamp((speed - 0.003F) / 0.12F, 0.25F, 1.0F) : 0.0F;
        float walkAlpha = 1.0F - (float) Math.exp(-dt * (targetWalk > this.walkBlend ? 0.38F : 0.24F));
        this.walkBlend = Mth.lerp(walkAlpha, this.walkBlend, targetWalk);
        if (targetWalk == 0.0F && this.walkBlend < 0.002F) {
            this.walkBlend = 0.0F;
        }

        // Run blend: strictly 0 during slow patrol & normal walk (< 0.17 b/t), ramps up on sprints
        boolean isSprinting = (entity.isSprinting() && speed > 0.10F)
                || (entity instanceof AbstractUnit u && u.isInCombat() && speed > 0.20F)
                || (entity instanceof VdfUnitEntity v && v.isInCombat() && speed > 0.20F);
        float targetRun = (movingHoriz && (speed > 0.17F || isSprinting))
                ? Mth.clamp(isSprinting ? Math.max(0.75F, (speed - 0.15F) / 0.10F) : (speed - 0.17F) / 0.10F, 0.0F, 1.0F)
                : 0.0F;
        float runAlpha = 1.0F - (float) Math.exp(-dt * (targetRun > this.runBlend ? 0.30F : 0.20F));
        this.runBlend = Mth.lerp(runAlpha, this.runBlend, targetRun);

        // Clip playback ratio for base UNIT_WALK definition
        float targetSpeedRatio = movingHoriz
                ? Mth.clamp(0.14F + speed * 3.2F, 0.16F, 1.30F)
                : 0.0F;
        float ratioAlpha = 1.0F - (float) Math.exp(-dt * 0.32F);
        this.speedRatio = Mth.lerp(ratioAlpha, this.speedRatio, targetSpeedRatio);

        // Stride amplitude: calm compact steps at stroll (<0.4), measured at walk (~0.5), full drive at sprint
        float walkStride = Mth.clamp(0.24F + speed * 2.1F, 0.24F, 0.56F);
        float targetStrideAmp = movingHoriz
                ? Mth.lerp(this.runBlend, walkStride, Mth.clamp(0.76F + speed * 0.9F, 0.80F, 1.05F))
                : 0.0F;
        float ampAlpha = 1.0F - (float) Math.exp(-dt * (targetStrideAmp > this.strideAmplitude ? 0.34F : 0.22F));
        this.strideAmplitude = Mth.lerp(ampAlpha, this.strideAmplitude, targetStrideAmp);

        float moveAlpha = 1.0F - (float) Math.exp(-dt * 0.34F);
        this.moveForward = Mth.lerp(moveAlpha, this.moveForward, this.sampledMoveForward);
        this.moveSide = Mth.lerp(moveAlpha, this.moveSide, this.sampledMoveSide);
        this.turnRate = Mth.lerp(moveAlpha, this.turnRate, this.sampledTurnRate);

        // Smooth cadence direction with hysteresis band [-0.25, -0.05] and exponential slewing
        // so forward/backpedal transitions slow cleanly through zero instead of 180-deg leg flips.
        float targetPhaseDir = this.moveForward < -0.25F ? -1.0F
                : (this.moveForward > -0.05F ? 1.0F : this.phaseDirection);
        float pdAlpha = 1.0F - (float) Math.exp(-dt * 0.24F);
        this.phaseDirection = Mth.lerp(pdAlpha, this.phaseDirection, targetPhaseDir);

        if (this.walkBlend > 0.02F) {
            float stepFreq = Mth.lerp(this.runBlend,
                    Mth.clamp(0.11F + speed * 1.45F, 0.11F, 0.38F),
                    Mth.clamp(0.36F + speed * 1.15F, 0.38F, 0.58F));
            this.walkPhase += dt * stepFreq * this.phaseDirection;
            this.walkPhaseInitialized = true;
        }

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

    public boolean hasWalkPhase() {
        return this.walkPhaseInitialized;
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
