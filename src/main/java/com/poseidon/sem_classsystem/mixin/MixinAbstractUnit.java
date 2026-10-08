// Mixin package - classes here patch OTHER mods' code at runtime (never copied)
package com.poseidon.sem_classsystem.mixin;

// Our helper that writes per-faction values onto a unit's attributes
import com.poseidon.sem_classsystem.faction.UnitClassAttributes;
// NBT tag passed through the spawn/load methods
import net.minecraft.nbt.CompoundTag;
// World difficulty (part of vanilla's finalizeSpawn signature)
import net.minecraft.world.DifficultyInstance;
// Why the mob spawned: NATURAL, COMMAND, SPAWN_EGG, etc.
import net.minecraft.world.entity.MobSpawnType;
// Extra spawn data bundle vanilla hands around during spawning
import net.minecraft.world.entity.SpawnGroupData;
// Server-side level wrapper used during spawn placement
import net.minecraft.world.level.ServerLevelAccessor;
// SEM's base soldier class - THE CLASS WE PATCH (every SEM unit extends this)
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
// JetBrains nullability annotation used in the method signature
import org.jetbrains.annotations.Nullable;
// Mixin annotations:
import org.spongepowered.asm.mixin.Mixin;                      // marks this as a mixin
import org.spongepowered.asm.mixin.injection.At;               // WHERE inside the target method
import org.spongepowered.asm.mixin.injection.Inject;           // inject extra code
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;            // for void methods
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;  // for methods returning a value

// @Mixin(AbstractUnit.class) = "merge this class into SEM's AbstractUnit at runtime".
// Our methods get woven into AbstractUnit's bytecode; SEM's own code is untouched.
@Mixin(AbstractUnit.class)
public abstract class MixinAbstractUnit {

    // finalizeSpawn = vanilla method SEM overrides to set health/speed/range
    // and equip guns. Runs every time a unit spawns naturally/by egg/by command.
    //
    // @Inject(method="finalizeSpawn", at=@At("TAIL")) means:
    //   "insert my code at the very END (TAIL) of finalizeSpawn" - AFTER SEM set
    //   the shared global attributes, so our per-faction values overwrite them.
    //
    // remap defaults to true: MixinGradle's generated refmap translates
    // "finalizeSpawn" into the obfuscated name (m_6518_) used in production jars.
    //
    // The parameters MUST mirror the target method's own signature, with the
    // CallbackInfoReturnable appended at the end (SpawnGroupData = its return type).
    @Inject(method = "finalizeSpawn", at = @At("TAIL"))
    private void semcs$applyClassAttributes(ServerLevelAccessor level, DifficultyInstance difficulty,
                                            MobSpawnType reason, @Nullable SpawnGroupData spawnData,
                                            @Nullable CompoundTag dataTag,
                                            CallbackInfoReturnable<SpawnGroupData> cir) {
        // (AbstractUnit)(Object)this = "this mixin IS the unit" - the double cast
        // is required because Java doesn't know our mixin class becomes AbstractUnit.
        // fullHeal=true: fresh spawn -> refill HP to the new max.
        UnitClassAttributes.apply((AbstractUnit) (Object) this, true);
    }

    // readAdditionalSaveData = vanilla method that loads an entity from NBT when
    // the world loads. SEM overrides it to restore orders, gear, etc.
    //
    // We TAIL-inject here too so config changes apply to units that already exist
    // in saved worlds (their attributes would otherwise stay frozen from NBT).
    //
    // Void method -> CallbackInfo (not CallbackInfoReturnable).
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void semcs$reapplyClassAttributes(CompoundTag tag, CallbackInfo ci) {
        // fullHeal=false: a loaded unit keeps its saved HP; we only fix the max.
        UnitClassAttributes.apply((AbstractUnit) (Object) this, false);
    }
}
