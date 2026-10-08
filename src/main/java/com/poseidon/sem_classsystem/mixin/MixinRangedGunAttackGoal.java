// Mixin package - this one patches SEM's gun combat goal to scale accuracy
package com.poseidon.sem_classsystem.mixin;

// Our helper: turns "which faction is this shooter" into a spread multiplier
import com.poseidon.sem_classsystem.faction.UnitClassAttributes;
// Vanilla RNG source the goal passes to the spread calculator
import net.minecraft.util.RandomSource;
// Vanilla Mob type of the shooter field inside the goal
import net.minecraft.world.entity.Mob;
// SEM's gun AI goal - the class we patch. It does burst fire + spread math.
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.RangedGunAttackGoal;
// SEM's static helper that adds randomness to the aim angle (the actual spread)
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.utils.AiGunSpreadHelper;
// Mixin annotations:
import org.spongepowered.asm.mixin.Final;    // the shadowed field is final in SEM
import org.spongepowered.asm.mixin.Mixin;    // marks this as a mixin
import org.spongepowered.asm.mixin.Shadow;   // lets us see a private field of the target
import org.spongepowered.asm.mixin.injection.At;        // WHERE inside the method
import org.spongepowered.asm.mixin.injection.Redirect;  // replace a method call

// Patch SEM's RangedGunAttackGoal - the AI goal that makes soldiers shoot guns.
@Mixin(RangedGunAttackGoal.class)
public abstract class MixinRangedGunAttackGoal {

    // @Shadow mirrors a field that already exists in the target class.
    // SEM declares "private final Mob mob" - after weaving, reading this.mob
    // reads the real field on the goal instance.
    // remap=false: "mob" is a field SEM added itself, not a vanilla-mapped name,
    // so there is no obfuscated name to translate to in production.
    @Shadow(remap = false)
    @Final
    private Mob mob;

    // @Redirect replaces a CALL to another method with a call to OUR method.
    //
    // SEM's private handleBurstFiringState calls
    //   AiGunSpreadHelper.CalculateSpread(angle, distance, baseSpread, perBlock, rand)
    // twice - once for pitch, once for yaw. One @Redirect catches BOTH calls
    // (they share the same method descriptor).
    //
    // remap=false again: handleBurstFiringState AND CalculateSpread are SEM's own
    // members - their names are identical in dev and production jars.
    //
    // The scary string "Lnet/nekoyuni/.../AiGunSpreadHelper;CalculateSpread(FFFFL...;)F"
    // is JVM bytecode notation: L<class>; = object type, F = float.
    // (FFFFLnet/minecraft/util/RandomSource;)F means
    //   args: float, float, float, float, RandomSource   -> returns float
    //
    // Our replacement receives the same arguments, multiplies baseSpread by the
    // shooter's faction multiplier, then calls the real method with the result.
    @Redirect(method = "handleBurstFiringState", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/ai/goals/utils/AiGunSpreadHelper;CalculateSpread(FFFFLnet/minecraft/util/RandomSource;)F"))
    private float semcs$classScaledSpread(float idealAngle, float distance, float baseSpread,
                                          float spreadIncreasePerBlock, RandomSource random) {
        // Ask our config what accuracy multiplier this shooter's faction has.
        // Non-SEM shooters / missing config return 1.0 -> unchanged behaviour.
        return AiGunSpreadHelper.CalculateSpread(idealAngle, distance,
                baseSpread * (float) UnitClassAttributes.spreadMultiplier(this.mob),
                spreadIncreasePerBlock, random);
    }
}
