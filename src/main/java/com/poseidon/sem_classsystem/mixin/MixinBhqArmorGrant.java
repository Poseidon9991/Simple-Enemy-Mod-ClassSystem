// Mixin package - fresh BHQ operators wear their signature kit
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.equipment.PmcUnitWeaponEquipper;
import net.nekoyuni.SimpleEnemyMod.entity.equipment.RuWeaponEquipper;
import net.nekoyuni.SimpleEnemyMod.entity.equipment.UsWeaponEquipper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// BHQ rides SEM's PMC equipper, so a TAIL on all three equippers sees every
// spawn. Only BhqUnitEntity gets the set: cap, plate vest, combat pants,
// boots - full durability, zero NBT. Existing units are untouched; players
// can freely swap the pieces through the equipment GUI afterwards.
@Mixin({RuWeaponEquipper.class, UsWeaponEquipper.class, PmcUnitWeaponEquipper.class})
public abstract class MixinBhqArmorGrant {

    // remap=false: equipRandomGun is a SEM-internal method.
    @Inject(method = "equipRandomGun", at = @At("TAIL"), remap = false)
    private static void semcs$grantBhqArmor(LivingEntity entity, RandomSource random,
                                           CallbackInfo ci) {
        if (!(entity instanceof com.poseidon.sem_classsystem.entity.BhqUnitEntity)
                || entity.level().isClientSide) {
            return;
        }
        entity.setItemSlot(EquipmentSlot.HEAD,
                new ItemStack(SemcsRegistries.BHQ_CAP.get()));
        entity.setItemSlot(EquipmentSlot.CHEST,
                new ItemStack(SemcsRegistries.BHQ_VEST.get()));
        entity.setItemSlot(EquipmentSlot.LEGS,
                new ItemStack(SemcsRegistries.BHQ_LEGGINGS.get()));
        entity.setItemSlot(EquipmentSlot.FEET,
                new ItemStack(SemcsRegistries.BHQ_BOOTS.get()));
    }
}
