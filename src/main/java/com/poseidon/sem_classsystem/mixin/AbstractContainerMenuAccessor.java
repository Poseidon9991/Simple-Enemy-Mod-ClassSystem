package com.poseidon.sem_classsystem.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractContainerMenu.class)
public interface AbstractContainerMenuAccessor {

    @Invoker("addSlot")
    Slot semcs$addSlot(Slot slot);

    @Invoker("moveItemStackTo")
    boolean semcs$moveItemStackTo(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection);
}
