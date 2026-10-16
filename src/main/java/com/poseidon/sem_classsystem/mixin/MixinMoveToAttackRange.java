// Mixin package - retunes the approach goal so each class fights at its own distance
package com.poseidon.sem_classsystem.mixin;

// Our helpers: read the unit's class + per-class tuning values
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.ai.UnitMeleeAttackGoal;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
// Vanilla Mob type of the goal's owner field
import net.minecraft.world.entity.Mob;
// SEM's approach goal - THE CLASS WE PATCH. It walks the unit toward the
// target until it is inside "attackRange", then hands over to the gun goal.
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.MoveToAttackRangeGoal;
import net.minecraft.world.entity.Entity;
// Mixin annotations:
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Makes the approach distance class-aware: a Sweeper keeps walking until it
// is in shotgun range, a Camper (sniper) never advances, and so on.
@Mixin(MoveToAttackRangeGoal.class)
public abstract class MixinMoveToAttackRange {

    // @Shadow mirrors SEM's private fields. remap=false: SEM-internal names,
    // identical in dev and production. @Mutable unlocks writing to finals.
    @Shadow(remap = false) @Final private Mob mob;
    @Shadow(remap = false) @Final @Mutable private double attackRange;
    @Shadow(remap = false) @Final @Mutable private double speed;

    // Which class the goal was last tuned for (re-class support)
    @Unique
    private WeaponClass semcs$tunedClass = null;
    // Jack retunes on gun swaps too - this remembers the held gun's type.
    @Unique
    private String semcs$tunedGunType = null;
    // Constructor originals - retunes always start from these, never from
    // already-scaled values
    @Unique
    private double semcs$origSpeed;
    @Unique
    private boolean semcs$origCaptured = false;

    // TAIL of the constructor: SEM just stored (detectionRange, attackRange,
    // speed) - we overwrite attackRange/speed with the unit's class values.
    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void semcs$tuneForClass(CallbackInfo ci) {
        semcs$applyTuning();
    }

    // canUse runs every evaluation - retune here too so a re-classed unit
    // (e.g. /semcs class) updates its preferred distance mid-fight.
    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void semcs$retuneOnUse(CallbackInfoReturnable<Boolean> cir) {
        if (UnitMeleeAttackGoal.isMeleeWeapon(this.mob.getMainHandItem())) {
            cir.setReturnValue(false);
            return;
        }
        semcs$applyTuning();
    }

    @Inject(method = "canContinueToUse", at = @At("HEAD"), cancellable = true)
    private void semcs$stopWhenMelee(CallbackInfoReturnable<Boolean> cir) {
        if (UnitMeleeAttackGoal.isMeleeWeapon(this.mob.getMainHandItem())) {
            cir.setReturnValue(false);
        }
    }

    // attackRange <- the class's preferred engage distance (absolute blocks)
    // speed      <- original approach speed x class multiplier
    @Unique
    private void semcs$applyTuning() {
        if (!this.semcs$origCaptured) {
            this.semcs$origSpeed = this.speed;
            this.semcs$origCaptured = true;
        }
        // Effective class: holding a subclass gun (pistol) swaps the unit's
        // movement tuning for the sidearm's - it keeps advancing mid-range
        // instead of planting/rushing for its real class.
        WeaponClass cls = WeaponClassSupport.getEffective(this.mob);
        if (cls == null) {
            return;
        }
        // Jack's stance follows the weapon in its hands, so a gun SWAP must
        // retune even though the class didn't change. Everyone else only
        // retunes when the effective class changes.
        String gunType = cls == WeaponClass.JACK
                ? WeaponClassSupport.heldGunType(this.mob) : null;
        if (cls == this.semcs$tunedClass
                && java.util.Objects.equals(gunType, this.semcs$tunedGunType)) {
            return;
        }
        this.semcs$tunedClass = cls;
        this.semcs$tunedGunType = gunType;

        // Preferred fighting distance in blocks - a Sweeper walks in close,
        // a Camper's value exceeds its shoot range so it holds position.
        // Jack reads it off the held gun's type: shotgun Jack closes in,
        // marksman Jack stands off.
        this.attackRange = cls == WeaponClass.JACK
                ? WeaponClassSupport.jackEngageDistance(gunType)
                : ClassSystemConfig.classEngageDistance(cls);
        this.speed = this.semcs$origSpeed * (cls == WeaponClass.JACK
                ? WeaponClassSupport.jackApproachSpeed(gunType)
                : ClassSystemConfig.classApproachSpeed(cls));
    }

    // ---- vertical FOV boost ------------------------------------------------
    // Same elevation tolerance as the fire goal: the approach band checks
    // raw 3D distance, so a target on a roof or ledge sat outside the band
    // and the unit never even moved toward it.
    @Redirect(method = {"canUse", "canContinueToUse", "tick"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Mob;distanceTo(Lnet/minecraft/world/entity/Entity;)F"))
    private float semcs$elevationTolerantDistance(Mob self, Entity target) {
        return (float) Math.sqrt(
                WeaponClassSupport.elevationTolerantDistanceSqr(self, target));
    }
}
