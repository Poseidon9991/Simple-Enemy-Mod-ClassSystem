// Mixin package - patches SEM's AbstractUnit to carry a WeaponClass
package com.poseidon.sem_classsystem.mixin;

// The interface this mixin adds to AbstractUnit
import com.poseidon.sem_classsystem.bridge.IClassifiedUnit;
// Per-faction attribute writer (phase 1)
import com.poseidon.sem_classsystem.faction.UnitClassAttributes;
// Class roll / NBT / name-tag helpers (phase 2)
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.faction.ClassLoadouts;
// NBT tag passed through spawn/load methods
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
// Needed to inspect the held gun for class inference on old saves
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ServerLevelAccessor;
// TaCZ gun interface - lets us read which gun id an ItemStack is
import com.tacz.guns.api.item.IGun;
import net.minecraft.resources.ResourceLocation;
// SEM's base soldier class - THE CLASS WE PATCH
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
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

    // IClassifiedUnit implementation - the interface methods are just
    // accessors around our @Unique field.
    @Override
    public WeaponClass semcs$getWeaponClass() {
        return this.semcs$weaponClass;
    }

    @Override
    public void semcs$setWeaponClass(WeaponClass weaponClass) {
        this.semcs$weaponClass = weaponClass;
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
        if (!self.level().isClientSide) {
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
}
