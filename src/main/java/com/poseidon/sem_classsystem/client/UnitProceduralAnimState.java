package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.api.item.IGun;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
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
    private int meleeComboIndex = 0;

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
            this.proneBlend = entity.getPose() == net.minecraft.world.entity.Pose.SWIMMING ? 1.0F : 0.0F;
        } else {
            dt = Mth.clamp(ageInTicks - this.lastAgeInTicks, 0.0F, 1.5F);
        }
        this.lastAgeInTicks = ageInTicks;

        // Smooth prone transition (~5 ticks = 0.25s)
        float targetProne = (entity.isAlive() && entity.getPose() == net.minecraft.world.entity.Pose.SWIMMING) ? 1.0F : 0.0F;
        float proneStep = dt * 0.20F;
        if (this.proneBlend < targetProne) {
            this.proneBlend = Math.min(targetProne, this.proneBlend + proneStep);
        } else if (this.proneBlend > targetProne) {
            this.proneBlend = Math.max(targetProne, this.proneBlend - proneStep);
        }

        boolean inCombat = (entity instanceof AbstractUnit unit && unit.isInCombat())
                || (entity instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() != null);

        // 1. Smooth combat stance blend (~4.5 ticks = 0.225s transition)
        float targetCombat = inCombat ? 1.0F : 0.0F;
        float step = dt * 0.22F;
        if (this.combatBlend < targetCombat) {
            this.combatBlend = Math.min(targetCombat, this.combatBlend + step);
        } else if (this.combatBlend > targetCombat) {
            this.combatBlend = Math.max(targetCombat, this.combatBlend - step);
        }

        // Smooth Better Combat melee swing progression (11 ticks = 0.55s per slash)
        boolean swingingNow = entity.swinging && entity.swingTime <= 2;
        if (swingingNow && !this.wasSwinging) {
            if (this.meleeSwingTicks > 0.0F && this.meleeSwingTicks < this.meleeSwingDuration) {
                this.meleeComboIndex = (this.meleeComboIndex + 1) % 3;
            } else {
                this.meleeComboIndex = (this.meleeComboIndex + 1) % 3;
            }
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

    public float smoothCombatBlend() {
        float t = Mth.clamp(this.combatBlend, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    public float smoothProneBlend() {
        float t = Mth.clamp(this.proneBlend, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
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
