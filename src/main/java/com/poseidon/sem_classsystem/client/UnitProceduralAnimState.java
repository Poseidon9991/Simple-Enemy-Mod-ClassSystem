package com.poseidon.sem_classsystem.client;

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
    private float useItemBlend = 0.0F;
    private float proneBlend = 0.0F;
    private float recoilImpulse = 0.0F;
    private float recoilSign = 1.0F;
    private float reloadBlend = 0.0F;
    private float reloadTicks = 0.0F;
    private float boltBlend = 0.0F;
    private float boltTicks = 0.0F;

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
            }
            this.crouchBlend = entity.isShiftKeyDown() ? 1.0F : 0.0F;
            this.walkBlend = Mth.clamp(entity.walkAnimation.speed() * 2.2F, 0.0F, 1.0F);
            this.useItemBlend = (entity.isUsingItem() && !entity.getOffhandItem().isEmpty()) ? 1.0F : 0.0F;
            this.proneBlend = entity.getPose() == net.minecraft.world.entity.Pose.SWIMMING ? 1.0F : 0.0F;
        } else {
            dt = Mth.clamp(ageInTicks - this.lastAgeInTicks, 0.0F, 1.5F);
        }
        this.lastAgeInTicks = ageInTicks;

        // Smooth prone transition (~5 ticks = 0.25s)
        float targetProne = (entity.isAlive() && entity.getPose() == net.minecraft.world.entity.Pose.SWIMMING) ? 1.0F : 0.0F;
        this.proneBlend = approach(this.proneBlend, targetProne, dt * 0.20F);

        // Smooth crouch / kneel transition (~4 ticks = 0.20s) - eliminates 1-frame
        // snapping when VdfNightWatchGoal or RangedGunAttackGoal toggles sneak.
        float targetCrouch = (entity.isAlive() && entity.isShiftKeyDown() && targetProne < 0.5F) ? 1.0F : 0.0F;
        this.crouchBlend = approach(this.crouchBlend, targetCrouch, dt * 0.25F);

        // Smooth locomotion blend from client walkAnimation speed + position delta
        float pt = Mth.frac(ageInTicks);
        float limbSpeed = entity.walkAnimation.speed(pt);
        double dx = entity.getX() - entity.xo;
        double dz = entity.getZ() - entity.zo;
        float posSpeed = (float) Math.sqrt(dx * dx + dz * dz) * 4.5F;
        float targetWalk = Mth.clamp(Math.max(limbSpeed * 2.4F, posSpeed), 0.0F, 1.0F);
        float walkRate = targetWalk > this.walkBlend ? dt * 0.32F : dt * 0.22F;
        this.walkBlend = approach(this.walkBlend, targetWalk, walkRate);

        // Smooth offhand food / medkit use blend (~3.5 ticks)
        float targetUse = (entity.isAlive() && entity.isUsingItem() && !entity.getOffhandItem().isEmpty()) ? 1.0F : 0.0F;
        this.useItemBlend = approach(this.useItemBlend, targetUse, dt * 0.28F);

        boolean inCombat = (entity instanceof AbstractUnit unit && unit.isInCombat())
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
        WeaponClass cls = WeaponClassSupport.getEffective(entity);
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
