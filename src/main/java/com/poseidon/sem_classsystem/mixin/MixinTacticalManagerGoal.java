// Mixin package - retunes SEM's tactical brain so each class plays its role
package com.poseidon.sem_classsystem.mixin;

// Our helpers: read the unit's effective class + per-class tactical tuning
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.util.SoldierState;
// SEM's per-unit tactical manager - THE CLASS WE PATCH. It decides when the
// unit seeks cover, flanks around the target, or commits to a forced rush.
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.TacticalManagerGoal;
// Mixin annotations:
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SEM's TacticalManagerGoal stores its behaviour knobs as per-instance finals
// loaded from AIDifficultySettings (patience timeout, flank duration, rush
// speed, retreat health, the close/mid range band edge). We shadow those
// fields, capture the constructor's values once, and re-scale them per class
// every tick - so a Camper waits out the target while a Sweeper flanks fast
// and charges hard.
//
// The second hook is the forced-rush gate: when a flank times out SEM sets
// isForcedRush and sends the unit charging. Classes with tacticalForcedRush
// = false (Camper, Suppressor) never charge - we clear the flag and re-arm a
// fresh flank arc instead, so they keep repositioning like a sniper should.
@Mixin(TacticalManagerGoal.class)
public abstract class MixinTacticalManagerGoal {

    // @Shadow mirrors SEM's private fields. remap=false: SEM-internal names,
    // identical in dev and production. @Mutable unlocks writing to finals.
    @Shadow(remap = false) @Final private AbstractUnit unit;
    @Shadow(remap = false) @Final @Mutable private long PATIENCE_TIMEOUT;
    @Shadow(remap = false) @Final @Mutable private long FLANKING_DURATION;
    @Shadow(remap = false) @Final @Mutable private double RUSH_SPEED;
    @Shadow(remap = false) @Final @Mutable private float RETREAT_HEALTH_PCT;
    @Shadow(remap = false) @Final @Mutable private double ENGAGE_DIST_MID;
    @Shadow(remap = false) private boolean isForcedRush;

    // Which class the manager was last tuned for (re-class / weapon-swap support)
    @Unique
    private WeaponClass semcs$tunedClass = null;
    // Constructor originals - retunes always start from these, never from
    // already-scaled values
    @Unique
    private long semcs$origPatience;
    @Unique
    private long semcs$origFlankDuration;
    @Unique
    private double semcs$origRushSpeed;
    @Unique
    private float semcs$origRetreatHealth;
    @Unique
    private double semcs$origCloseBand;
    @Unique
    private boolean semcs$origCaptured = false;

    // tick() runs every evaluation - retune here so a weapon swap (pistol
    // subclass) or a /semcs class change updates tactics mid-fight, then run
    // the forced-rush gate before SEM's own logic consumes the flag.
    @Inject(method = "tick", at = @At("HEAD"))
    private void semcs$tuneAndGateRush(CallbackInfo ci) {
        WeaponClass cls = semcs$applyTuning();
        if (cls == null) {
            return;
        }
        // Forced-rush gate: hold-position classes never charge on flank
        // timeout. Clear the flag SEM just set, restart the flank arc, and
        // hand movement back to the maneuver goal - the unit keeps circling
        // for a firing position instead of sprinting into melee range.
        if (this.isForcedRush && !ClassSystemConfig.classForcedRush(cls)) {
            this.isForcedRush = false;
            this.unit.releaseMovementLock();
            if (!this.unit.isFlankingActive()) {
                this.unit.setFlankingActive(true);
            }
            this.unit.setFlankingStartTick(this.unit.level().getGameTime());
            this.unit.setSoldierState(SoldierState.TACTICAL_MOV);
        }
    }

    // Re-scales the shadowed knobs from the captured ORIGINALS (a class swap
    // retunes correctly instead of stacking multipliers). Returns the class
    // this tick resolved to, or null for unclassified units.
    @Unique
    private WeaponClass semcs$applyTuning() {
        if (!this.semcs$origCaptured) {
            this.semcs$origPatience = this.PATIENCE_TIMEOUT;
            this.semcs$origFlankDuration = this.FLANKING_DURATION;
            this.semcs$origRushSpeed = this.RUSH_SPEED;
            this.semcs$origRetreatHealth = this.RETREAT_HEALTH_PCT;
            this.semcs$origCloseBand = this.ENGAGE_DIST_MID;
            this.semcs$origCaptured = true;
        }
        // Effective class: a pistol in hand swaps the unit's tactical
        // personality for the sidearm's, same as the combat mixins.
        WeaponClass cls = WeaponClassSupport.getEffective(this.unit);
        if (cls == null || cls == this.semcs$tunedClass) {
            return cls;
        }
        this.semcs$tunedClass = cls;

        this.PATIENCE_TIMEOUT = Math.max(1L,
                Math.round(this.semcs$origPatience * ClassSystemConfig.classPatienceMultiplier(cls)));
        this.FLANKING_DURATION = Math.max(1L,
                Math.round(this.semcs$origFlankDuration * ClassSystemConfig.classFlankDurationMultiplier(cls)));
        this.RUSH_SPEED = this.semcs$origRushSpeed * ClassSystemConfig.classRushSpeedMultiplier(cls);
        this.RETREAT_HEALTH_PCT = (float) (this.semcs$origRetreatHealth
                * ClassSystemConfig.classRetreatHealthMultiplier(cls));
        this.ENGAGE_DIST_MID = this.semcs$origCloseBand * ClassSystemConfig.classCloseRangeMultiplier(cls);
        return cls;
    }
}
