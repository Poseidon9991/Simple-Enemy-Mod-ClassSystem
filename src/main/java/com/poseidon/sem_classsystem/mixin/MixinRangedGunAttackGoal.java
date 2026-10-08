// Mixin package - patches SEM's gun combat goal for per-class combat tuning
package com.poseidon.sem_classsystem.mixin;

// Our helper: faction x class -> spread multiplier
import com.poseidon.sem_classsystem.faction.ClassLoadouts;
import com.poseidon.sem_classsystem.faction.UnitClassAttributes;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
// TaCZ interfaces: read which gun id the held ItemStack is
import com.tacz.guns.api.item.IGun;
import net.minecraft.resources.ResourceLocation;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    // Guard: which class the fields were last tuned for - re-running for the
    // same class does nothing, but a class CHANGE must retune.
    @Unique
    private WeaponClass semcs$tunedClass = null;

    // The constructor's original burst/cooldown values, captured once.
    // Tuning always computes original x multiplier - never multiplies an
    // already-scaled field (that would corrupt values on a re-class).
    @Unique
    private int semcs$origMinBurst;
    @Unique
    private int semcs$origMaxBurst;
    @Unique
    private int semcs$origMinCooldown;
    @Unique
    private int semcs$origMaxCooldown;
    @Unique
    private boolean semcs$origCaptured = false;

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

    // Applies burst-length and cooldown multipliers to the shadowed fields,
    // always computed from the captured ORIGINAL constructor values so a
    // re-class retunes correctly instead of scaling the scaled numbers.
    @Unique
    private void semcs$applyTuning() {
        // First call: snapshot SEM's constructor values before we touch them
        if (!this.semcs$origCaptured) {
            this.semcs$origMinBurst = this.MIN_BURST_SHOTS;
            this.semcs$origMaxBurst = this.MAX_BURST_SHOTS;
            this.semcs$origMinCooldown = this.MIN_BURST_COOLDOWN_TICKS;
            this.semcs$origMaxCooldown = this.MAX_BURST_COOLDOWN_TICKS;
            this.semcs$origCaptured = true;
        }

        // Which class is the shooter? null -> leave SEM defaults untouched.
        WeaponClass cls = WeaponClassSupport.get(this.mob);
        if (cls == null || cls == this.semcs$tunedClass) {
            return;   // no class yet, or already tuned for this class
        }
        this.semcs$tunedClass = cls;

        // --- burst length (min 1 shot, max >= min) ---
        double burst = ClassSystemConfig.classBurst(cls);
        this.MIN_BURST_SHOTS = Math.max(1, (int) Math.round(this.semcs$origMinBurst * burst));
        this.MAX_BURST_SHOTS = Math.max(this.MIN_BURST_SHOTS,
                (int) Math.round(this.semcs$origMaxBurst * burst));

        // --- cadence between bursts (the "fire rate" knob) ---
        double cooldown = ClassSystemConfig.classCooldown(cls);
        this.MIN_BURST_COOLDOWN_TICKS = Math.max(1,
                (int) Math.round(this.semcs$origMinCooldown * cooldown));
        this.MAX_BURST_COOLDOWN_TICKS = Math.max(this.MIN_BURST_COOLDOWN_TICKS,
                (int) Math.round(this.semcs$origMaxCooldown * cooldown));
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

    // ---- class weapon lock -------------------------------------------------
    // RETURN-inject on canUse(): after SEM decided the goal may run, we veto
    // it when the held gun belongs to a DIFFERENT weapon class. This is the
    // second layer of the lock - even if a wrong-class gun reaches the hand
    // (commands, NBT edits, another mod), the unit simply will not fire it.
    // remap stays default (true): canUse is a vanilla Goal method, the refmap
    // translates it to the obfuscated name in production.
    @Inject(method = "canUse", at = @At("RETURN"), cancellable = true)
    private void semcs$enforceClassWeapon(CallbackInfoReturnable<Boolean> cir) {
        // SEM already said no -> nothing to do
        if (!cir.getReturnValue()) {
            return;
        }
        // Retune burst/cooldown if the unit's class changed since the ctor
        // (e.g. the /semcs class command re-classed a living unit)
        semcs$applyTuning();
        // No class -> no restriction
        WeaponClass cls = WeaponClassSupport.get(this.mob);
        if (cls == null) {
            return;
        }
        // Identify the gun in the main hand through the TaCZ API
        IGun iGun = IGun.getIGunOrNull(this.mob.getMainHandItem());
        ResourceLocation gunId = iGun != null ? iGun.getGunId(this.mob.getMainHandItem()) : null;
        WeaponClass gunClass = gunId == null ? null : ClassLoadouts.inferClass(gunId);
        // Known gun of another class -> goal cannot run (unit holds fire)
        if (gunClass != null && gunClass != cls) {
            cir.setReturnValue(false);
        }
    }
}
