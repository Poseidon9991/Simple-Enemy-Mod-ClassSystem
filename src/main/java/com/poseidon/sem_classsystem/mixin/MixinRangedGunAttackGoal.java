// Mixin package - patches SEM's gun combat goal for per-class combat tuning
package com.poseidon.sem_classsystem.mixin;

// Our helper: faction x class -> spread multiplier
import com.poseidon.sem_classsystem.faction.UnitClassAttributes;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
// Vanilla RNG source the goal passes to the spread calculator
import net.minecraft.util.RandomSource;
// Vanilla Mob type of the shooter field inside the goal
import net.minecraft.world.entity.Mob;
// SEM's gun AI goal - the class we patch. It does burst fire + spread math.
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.RangedGunAttackGoal;
// SEM's static helper that adds randomness to the aim angle (the actual spread)
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.utils.AiGunSpreadHelper;
// Mixin annotations:
import org.spongepowered.asm.mixin.Final;     // the shadowed fields are final in SEM
import org.spongepowered.asm.mixin.Mixin;     // marks this as a mixin
import org.spongepowered.asm.mixin.Mutable;   // unlocks writing to final fields
import org.spongepowered.asm.mixin.Shadow;    // mirrors a private field of the target
import org.spongepowered.asm.mixin.Unique;    // a method/field only this mixin adds
import org.spongepowered.asm.mixin.injection.At;         // WHERE inside a method
import org.spongepowered.asm.mixin.injection.Inject;     // insert code
import org.spongepowered.asm.mixin.injection.Redirect;   // replace a method call
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Patch SEM's RangedGunAttackGoal - the AI goal that makes soldiers shoot.
@Mixin(RangedGunAttackGoal.class)
public abstract class MixinRangedGunAttackGoal {

    // @Shadow mirrors fields that exist in the target class. remap=false because
    // these are SEM's own fields - names are identical in dev and production.
    @Shadow(remap = false)
    @Final
    private Mob mob;

    // SEM's constructor-set burst/cooldown values. @Mutable removes "final" at
    // weave time so we can retune them after the constructor.
    @Shadow(remap = false) @Final @Mutable private int MIN_BURST_SHOTS;
    @Shadow(remap = false) @Final @Mutable private int MAX_BURST_SHOTS;
    @Shadow(remap = false) @Final @Mutable private int MIN_BURST_COOLDOWN_TICKS;
    @Shadow(remap = false) @Final @Mutable private int MAX_BURST_COOLDOWN_TICKS;

    // Guard: which class the fields were last tuned for - prevents
    // double-scaling if a second tuning pass ever runs.
    @Unique
    private WeaponClass semcs$tunedClass = null;

    // TAIL of the constructor: runs right after SEM stored the global
    // AiShootingConfig burst/cooldown values into the finals above -
    // we overwrite them with class-scaled values.
    // remap=false: "<init>" (constructor) is never obfuscated.
    //
    // NOTE: spread fields (BASE_SPREAD_DEGREES / SPREAD_INCREASE_PER_BLOCK)
    // are NOT tuned here - they are scaled inside the CalculateSpread redirect
    // below so each shot reads the live config values.
    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void semcs$tuneForClass(CallbackInfo ci) {
        semcs$applyTuning();
    }

    // Applies burst-length and cooldown multipliers to the shadowed fields.
    // Idempotent per class - re-running for the same class does nothing.
    @Unique
    private void semcs$applyTuning() {
        // Which class is the shooter? null -> leave SEM defaults untouched.
        WeaponClass cls = WeaponClassSupport.get(this.mob);
        if (cls == null || cls == this.semcs$tunedClass) {
            return;   // no class yet, or already tuned for this class
        }
        this.semcs$tunedClass = cls;

        // --- burst length (min 1 shot, max >= min) ---
        double burst = ClassSystemConfig.classBurst(cls);
        this.MIN_BURST_SHOTS = Math.max(1, (int) Math.round(this.MIN_BURST_SHOTS * burst));
        this.MAX_BURST_SHOTS = Math.max(this.MIN_BURST_SHOTS,
                (int) Math.round(this.MAX_BURST_SHOTS * burst));

        // --- cadence between bursts (the "fire rate" knob) ---
        double cooldown = ClassSystemConfig.classCooldown(cls);
        this.MIN_BURST_COOLDOWN_TICKS = Math.max(1,
                (int) Math.round(this.MIN_BURST_COOLDOWN_TICKS * cooldown));
        this.MAX_BURST_COOLDOWN_TICKS = Math.max(this.MIN_BURST_COOLDOWN_TICKS,
                (int) Math.round(this.MAX_BURST_COOLDOWN_TICKS * cooldown));
    }

    // @Redirect replaces a CALL to another method with a call to OUR method.
    // SEM's private handleBurstFiringState calls CalculateSpread twice
    // (pitch + yaw); one @Redirect catches both - same descriptor.
    // remap=false: both names are SEM-internal, identical in prod.
    //
    // The descriptor spells out JVM bytecode types:
    //   (FFFFLnet/minecraft/util/RandomSource;)F =
    //   args: float, float, float, float, RandomSource -> returns float
    @Redirect(method = "handleBurstFiringState", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/ai/goals/utils/AiGunSpreadHelper;CalculateSpread(FFFFLnet/minecraft/util/RandomSource;)F"))
    private float semcs$classScaledSpread(float idealAngle, float distance, float baseSpread,
                                          float spreadIncreasePerBlock, RandomSource random) {
        // Safety net: if the ctor ran before the class was assigned, retune
        // burst/cooldown fields right before the first real shot.
        semcs$applyTuning();

        // Resolve the shooter's weapon class (null -> neutral 1.0 multipliers)
        WeaponClass cls = WeaponClassSupport.get(this.mob);
        double classSpread = cls == null ? 1.0 : ClassSystemConfig.classSpread(cls);
        double classFalloff = cls == null ? 1.0 : ClassSystemConfig.classFalloff(cls);

        // Total spread = SEM's value x faction multiplier x class multiplier.
        // The distance falloff arg is scaled by the class falloff multiplier
        // (shotguns collapse at range, snipers barely degrade).
        return AiGunSpreadHelper.CalculateSpread(idealAngle, distance,
                baseSpread * (float) (UnitClassAttributes.spreadMultiplier(this.mob) * classSpread),
                (float) (spreadIncreasePerBlock * classFalloff),
                random);
    }
}
