// Mixin package - patches SEM's AbstractUnit to carry a WeaponClass
package com.poseidon.sem_classsystem.mixin;

// Our helper modules & raid/hostile tracking
import com.poseidon.sem_classsystem.bridge.IClassifiedUnit;
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import com.poseidon.sem_classsystem.event.NightRaidEvents;
import com.poseidon.sem_classsystem.faction.UnitClassAttributes;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.faction.ClassLoadouts;
// NBT tag passed through spawn/load methods
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
// Needed to inspect the held gun for class inference on old saves
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
// TaCZ gun interface - lets us read which gun id an ItemStack is
import com.tacz.guns.api.item.IGun;
import net.minecraft.resources.ResourceLocation;
// SEM's base soldier class + config + concrete subclasses
import net.nekoyuni.SimpleEnemyMod.config.common.FactionsConfig;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.util.SoldierState;
// Mixin annotations:
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;      // marks this as a mixin
import org.spongepowered.asm.mixin.Unique;     // our own added field
import org.spongepowered.asm.mixin.injection.At;       // WHERE inside a method
import org.spongepowered.asm.mixin.injection.Inject;   // insert extra code
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// @Mixin(AbstractUnit.class) = merge into SEM's AbstractUnit at runtime.
// "implements IClassifiedUnit" = interface injection: after weaving, every
// SEM unit also answers our semcs$getWeaponClass()/semcs$setWeaponClass().
@Mixin(AbstractUnit.class)
public abstract class MixinAbstractUnit implements IClassifiedUnit {

    // @Unique = a field that only exists because of this mixin. SEM never had it.
    // Holds the rolled weapon class; null until assigned.
    @Unique
    private WeaponClass semcs$weaponClass = null;

    @Unique
    private int semcs$effCacheTick = -1;

    @Unique
    private ItemStack semcs$effCacheStack = ItemStack.EMPTY;

    @Unique
    private WeaponClass semcs$effCacheClass = null;

    // IClassifiedUnit implementation - the interface methods are just
    // accessors around our @Unique field.
    @Override
    public WeaponClass semcs$getWeaponClass() {
        return this.semcs$weaponClass;
    }

    @Override
    public void semcs$setWeaponClass(WeaponClass weaponClass) {
        this.semcs$weaponClass = weaponClass;
        this.semcs$effCacheTick = -1;
        this.semcs$effCacheStack = ItemStack.EMPTY;
    }

    @Override
    @Nullable
    public WeaponClass semcs$getCachedEffectiveClass() {
        AbstractUnit self = (AbstractUnit) (Object) this;
        int now = self.tickCount;
        ItemStack held = self.getMainHandItem();
        if (now == this.semcs$effCacheTick && held == this.semcs$effCacheStack) {
            return this.semcs$effCacheClass;
        }
        WeaponClass computed = WeaponClassSupport.computeEffective(self, held);
        this.semcs$effCacheTick = now;
        this.semcs$effCacheStack = held;
        this.semcs$effCacheClass = computed;
        return computed;
    }

    // ---- class assignment -------------------------------------------------
    // HEAD of finalizeSpawn = the very first thing when a unit spawns.
    // We roll the class HERE so that every later step (setupRoleGoals ->
    // gun goal constructor, equipRandomGun -> loadout pick) already sees it.
    @Inject(method = "finalizeSpawn", at = @At("HEAD"))
    private void semcs$assignClass(ServerLevelAccessor level, DifficultyInstance difficulty,
                                   MobSpawnType reason, @Nullable SpawnGroupData spawnData,
                                   @Nullable CompoundTag dataTag,
                                   CallbackInfoReturnable<SpawnGroupData> cir) {
        AbstractUnit self = (AbstractUnit) (Object) this;
        WeaponClassSupport.getOrAssign(self, self.getRandom());
    }

    // TAIL of finalizeSpawn = after SEM set attributes AND equipped the gun.
    // Our values overwrite SEM's globals; name tag gets set too.
    @Inject(method = "finalizeSpawn", at = @At("TAIL"))
    private void semcs$applyClassAttributes(ServerLevelAccessor level, DifficultyInstance difficulty,
                                            MobSpawnType reason, @Nullable SpawnGroupData spawnData,
                                            @Nullable CompoundTag dataTag,
                                            CallbackInfoReturnable<SpawnGroupData> cir) {
        AbstractUnit self = (AbstractUnit) (Object) this;
        // fullHeal=true: fresh spawn -> refill HP to the new max.
        UnitClassAttributes.apply(self, true);
        // "US Unit - Sweeper" floating name tag.
        WeaponClassSupport.applyDisplayName(self);
    }

    // ---- persistence ------------------------------------------------------
    // HEAD of readAdditionalSaveData runs BEFORE SEM's body - SEM may call
    // equipRandomGun() inside, so the class must be restored first.
    @Inject(method = "readAdditionalSaveData", at = @At("HEAD"))
    private void semcs$loadClass(CompoundTag tag, CallbackInfo ci) {
        WeaponClassSupport.restoreFromTag((AbstractUnit) (Object) this, tag);
    }

    // TAIL: re-apply attributes so config edits affect loaded units, and
    // give units saved before this addon existed a class inferred from the
    // gun they are holding (keeps name consistent with the weapon).
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void semcs$reapplyClassAttributes(CompoundTag tag, CallbackInfo ci) {
        AbstractUnit self = (AbstractUnit) (Object) this;
        // If NBT had no class (unit saved before this mod) - guess from gun.
        // Skipped for addon subclasses (sewv medics etc.) - they keep custom
        // weapons that our class tables must not judge.
        if (this.semcs$weaponClass == null && WeaponClassSupport.isClassifiable(self)) {
            ItemStack held = self.getMainHandItem();
            IGun iGun = IGun.getIGunOrNull(held);
            ResourceLocation gunId = iGun != null ? iGun.getGunId(held) : null;
            if (gunId != null) {
                this.semcs$weaponClass = ClassLoadouts.inferClass(gunId);
            }
        }
        UnitClassAttributes.apply(self, false);
        // Re-stamp the name tag in case the class was just inferred
        WeaponClassSupport.applyDisplayName(self);

        // Repair ammo-less units: inventory NBT is already deserialized here,
        // so sweep stray/oversized stacks and top the unit back up to its
        // reserve (idempotent - only fills what is actually missing).
        if (!self.level().isClientSide
                && !(self instanceof com.poseidon.sem_classsystem.entity.TerroristUnitEntity)) {
            try {
                com.poseidon.sem_classsystem.entity.UnitAmmoService.ensurePrimaryAmmo(self);
            } catch (Throwable ignored) {
            }
        }
    }

    // Persist the class into the unit's NBT on save.
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void semcs$saveClass(CompoundTag tag, CallbackInfo ci) {
        WeaponClassSupport.saveToTag((AbstractUnit) (Object) this, tag);
    }

    // ---- Night Raid relentless pursuit & reliable hostile player sensing ----
    // 1. Raid squads keep their lock on the raided player (ignoring ambient
    //    night zombies/skeletons) and march via intermediate waypoints from
    //    50+ blocks away instead of stalling outside vanilla path range.
    // 2. Hostile RU/US units check line-of-sight to nearby players directly
    //    even if a priority-2 Monster target goal had locked Goal.Flag.TARGET
    //    onto a distant or subterranean zombie.
    @Inject(method = "tick", at = @At("TAIL"))
    private void semcs$tickRaidAndHostileSense(CallbackInfo ci) {
        AbstractUnit self = (AbstractUnit) (Object) this;
        if (self.level().isClientSide || !self.isAlive()) {
            return;
        }
        if ((self.tickCount + self.getId()) % 10 != 0) {
            return;
        }

        // Part A: Night Raid Focus
        CompoundTag pdata = self.getPersistentData();
        if (pdata.contains(NightRaidEvents.RAID_UNTIL_KEY)) {
            if (self.level().getGameTime() > pdata.getLong(NightRaidEvents.RAID_UNTIL_KEY)
                    || !pdata.hasUUID(NightRaidEvents.RAID_TARGET_KEY)) {
                pdata.remove(NightRaidEvents.RAID_UNTIL_KEY);
                pdata.remove(NightRaidEvents.RAID_TARGET_KEY);
            } else {
                Player raidPlayer = self.level().getPlayerByUUID(pdata.getUUID(NightRaidEvents.RAID_TARGET_KEY));
                if (raidPlayer == null || !raidPlayer.isAlive()
                        || raidPlayer.isSpectator() || raidPlayer.isCreative()) {
                    pdata.remove(NightRaidEvents.RAID_UNTIL_KEY);
                    pdata.remove(NightRaidEvents.RAID_TARGET_KEY);
                } else {
                    LivingEntity curTarget = self.getTarget();
                    boolean engagingDefender = curTarget != null && curTarget.isAlive()
                            && (curTarget == raidPlayer
                                    || curTarget instanceof PmcUnitEntity
                                    || curTarget instanceof VdfUnitEntity
                                    || curTarget instanceof Player)
                            && (self.getSensing().hasLineOfSight(curTarget) || self.distanceToSqr(curTarget) <= 256.0D);
                    if (!engagingDefender) {
                        self.setTarget(raidPlayer);
                        self.getEntityData().set(AbstractUnit.IS_IN_COMBAT, true);
                    }
                    LivingEntity huntTarget = self.getTarget();
                    if (huntTarget != null && huntTarget.isAlive()) {
                        double distSq = self.distanceToSqr(huntTarget);
                        boolean sees = self.getSensing().hasLineOfSight(huntTarget);
                        if (distSq > 484.0D || !sees) {
                            if (self.isMovementLockedByManager() && distSq > 400.0D) {
                                self.releaseMovementLock();
                            }
                            SoldierState st = self.getSoldierState();
                            if ((st == SoldierState.SEEK_COVER || st == SoldierState.HOLD_COVER)
                                    && self.getHealth() > self.getMaxHealth() * 0.25F) {
                                self.setSoldierState(SoldierState.TACTICAL_MOV);
                            }
                            if ((self.tickCount + self.getId()) % 20 == 0 || self.getNavigation().isDone()) {
                                double dist = Math.sqrt(distSq);
                                if (dist > 26.0D) {
                                    double dx = (huntTarget.getX() - self.getX()) / dist;
                                    double dz = (huntTarget.getZ() - self.getZ()) / dist;
                                    double wx = self.getX() + dx * 24.0D;
                                    double wz = self.getZ() + dz * 24.0D;
                                    int wy = self.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                            (int) Math.floor(wx), (int) Math.floor(wz));
                                    self.getNavigation().moveTo(wx, wy, wz, 1.18D);
                                } else {
                                    self.getNavigation().moveTo(huntTarget, 1.15D);
                                }
                            }
                        }
                    }
                    return;
                }
            }
        }

        // Part B: Reliable Hostile Unit Player Detection (RU / US)
        boolean ruHostile = (self instanceof RUunitEntity) && !Boolean.TRUE.equals(FactionsConfig.RU_UNITS_FRIENDLY.get());
        boolean usHostile = (self instanceof USunitEntity) && !Boolean.TRUE.equals(FactionsConfig.US_UNITS_FRIENDLY.get());
        if (!ruHostile && !usHostile) {
            return;
        }
        LivingEntity cur = self.getTarget();
        boolean curIsPlayer = cur instanceof Player && cur.isAlive();
        if (curIsPlayer && (self.getSensing().hasLineOfSight(cur) || self.distanceToSqr(cur) <= 196.0D)) {
            return;
        }
        boolean curLowPriority = cur == null || !cur.isAlive()
                || (cur instanceof Monster && !(cur instanceof AbstractUnit))
                || (!self.getSensing().hasLineOfSight(cur) && self.distanceToSqr(cur) > 196.0D);
        if (!curLowPriority) {
            return;
        }

        WeaponClass cls = WeaponClassSupport.getEffective(self);
        double detectRange = Math.max(cls == WeaponClass.SNIPER ? 64.0D : 44.0D,
                self.getAttributeValue(Attributes.FOLLOW_RANGE));
        double maxRangeSq = detectRange * detectRange;

        Player bestPlayer = null;
        double bestDistSq = maxRangeSq;
        for (Player candidate : self.level().players()) {
            if (!candidate.isAlive() || candidate.isSpectator() || candidate.isCreative()) {
                continue;
            }
            double d2 = self.distanceToSqr(candidate);
            if (d2 >= bestDistSq || !self.canAttack(candidate) || self.isAlliedTo(candidate)) {
                continue;
            }
            if (self.hasLineOfSight(candidate)) {
                bestDistSq = d2;
                bestPlayer = candidate;
            }
        }
        if (bestPlayer != null) {
            self.setTarget(bestPlayer);
            self.getEntityData().set(AbstractUnit.IS_IN_COMBAT, true);
            self.getLookControl().setLookAt(bestPlayer, 30.0F, 30.0F);
        }
    }
}
