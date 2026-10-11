package com.poseidon.sem_classsystem.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * No-op placeholder — the prone stance feature was removed, but older copies of
 * sem_classsystem.mixins.json still reference this class name. Keeping it
 * prevents a missing-mixin crash if that config version is ever restored.
 */
@Mixin(LivingEntity.class)
public abstract class MixinUnitProneDimensions {
}
