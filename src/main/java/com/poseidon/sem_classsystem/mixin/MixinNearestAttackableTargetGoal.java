// Mixin package - read-only accessor for NATG's private target class
package com.poseidon.sem_classsystem.mixin;

import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Lets UnitHostilityEvents tell "the goal that hunts players" apart from a
// mob's other NearestAttackableTargetGoals (villagers, golems, turtles...)
// without touching any of them - the class the goal was built for is the
// only reliable identity.
@Mixin(NearestAttackableTargetGoal.class)
public interface MixinNearestAttackableTargetGoal {

    @Accessor("targetType")
    Class<?> semcs$getTargetType();
}
