// Mixin package - stocks freshly spawned units with medical supplies
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.data.ClassLoadoutManager;
import com.poseidon.sem_classsystem.entity.BhqUnitEntity;
import com.poseidon.sem_classsystem.faction.UnitClass;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.equipment.PmcUnitWeaponEquipper;
import net.nekoyuni.SimpleEnemyMod.entity.equipment.RuWeaponEquipper;
import net.nekoyuni.SimpleEnemyMod.entity.equipment.UsWeaponEquipper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The spawn-side of self-healing: after SEM's equipper hands the unit its
// gun, we drop "spawnHealItems" picks from the faction's heal_items pool
// (a sibling of the class loadout tables) into free storage slots.
// Players can then restock or upgrade the supply through the equipment
// GUI - this only guarantees nobody spawns empty.
@Mixin({RuWeaponEquipper.class, UsWeaponEquipper.class, PmcUnitWeaponEquipper.class})
public abstract class MixinUnitHealGrant {

    private static final int STORAGE_START = 6;

    // remap=false: equipRandomGun is a SEM-internal method.
    @Inject(method = "equipRandomGun", at = @At("TAIL"), remap = false)
    private static void semcs$grantHealItems(LivingEntity entity, RandomSource random,
                                             CallbackInfo ci) {
        int count = ClassSystemConfig.spawnHealItems();
        if (count <= 0 || entity.level().isClientSide) {
            return;
        }
        // Same table-key rule as the gun pools: BHQ rides the PMC equipper
        // but reads its own bhq_units file.
        String tableKey = entity instanceof BhqUnitEntity
                ? "bhq_units"
                : factionTableKey(entity);
        if (tableKey == null) {
            return;
        }
        IItemHandlerModifiable inv = entity.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .map(handler -> handler instanceof IItemHandlerModifiable modifiable
                        ? modifiable : null)
                .orElse(null);
        if (inv == null) {
            return;
        }
        for (int i = 0; i < count; i++) {
            ResourceLocation itemId = ClassLoadoutManager.pickHealItem(tableKey, random);
            if (itemId == null) {
                return;   // empty pool - nothing to grant
            }
            Item item = ForgeRegistries.ITEMS.getValue(itemId);
            if (item == null) {
                continue; // id resolved at load but vanished - skip quietly
            }
            for (int slot = STORAGE_START; slot < inv.getSlots(); slot++) {
                if (inv.getStackInSlot(slot).isEmpty()) {
                    inv.setStackInSlot(slot, new ItemStack(item));
                    break;
                }
            }
        }
    }

    // UnitClass -> class_loadouts file stem. null = not a classed faction.
    @javax.annotation.Nullable
    private static String factionTableKey(LivingEntity entity) {
        UnitClass faction = UnitClass.of(entity);
        if (faction == null) {
            return null;
        }
        return switch (faction) {
            case RU_UNIT -> "ru_units";
            case US_UNIT -> "us_units";
            case PMC_UNIT -> "pmc_units";
            case BHQ_UNIT -> "bhq_units";
        };
    }
}
