// Event package - issues BHQ's own armor before tacz_sewv's NpcArmor can
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.entity.BhqUnitEntity;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;

// tacz_sewv dresses every AbstractUnit from its PMC list on join (BHQ is a
// PmcUnitEntity, so it got the PMC set: IOTV chest + a mich helmet that does
// not even exist in the deployed jar). We want BHQ in its own kit instead.
//
// This listener runs at HIGHEST - before NpcArmor's NORMAL handler - and
// equips our pieces straight through the unit's item handler, then stamps
// the same "sewv:armor_issued" persistent flag so NpcArmor skips the unit
// entirely. If our items are absent (SBW/sewv not loaded) or the slots are
// already filled, we do not touch the flag and NpcArmor falls through to
// its normal PMC behaviour - graceful degradation both ways.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BhqArmorIssuer {

    // Same persistent key NpcArmor uses - setting it suppresses its issue()
    private static final String ISSUED_FLAG = "sewv:armor_issued";
    // UnitInventoryHandler: slots 2-5 are feet/legs/chest/head; armor slot
    // indices are FEET=0..HEAD=3, so handler slot = 2 + slot.getIndex().
    private static final int FIRST_ARMOR_SLOT = 2;

    private BhqArmorIssuer() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof BhqUnitEntity unit)
                || unit.level().isClientSide
                || !itemsLoaded()) {
            return;
        }
        CompoundTag data = unit.getPersistentData();
        if (data.getBoolean(ISSUED_FLAG)) {
            return;
        }

        boolean equipped = tryWear(unit, EquipmentSlot.HEAD, SemcsRegistries.BHQ_HELMET)
                         | tryWear(unit, EquipmentSlot.CHEST, SemcsRegistries.BHQ_VEST);
        if (equipped) {
            // We dressed it - tell NpcArmor the unit is handled.
            data.putBoolean(ISSUED_FLAG, true);
        }
    }

    private static boolean itemsLoaded() {
        return ModList.get().isLoaded("superbwarfare")
                && SemcsRegistries.BHQ_HELMET != null
                && SemcsRegistries.BHQ_VEST != null;
    }

    // Insert into the handler slot if it is free; falls back to setItemSlot
    // so units without the capability still carry the gear.
    private static boolean tryWear(BhqUnitEntity unit, EquipmentSlot slot,
                                   RegistryObject<Item> itemRef) {
        if (itemRef == null || !unit.getItemBySlot(slot).isEmpty()) {
            return false;
        }
        ItemStack stack = new ItemStack(itemRef.get());
        boolean[] placed = {false};
        unit.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
            int target = FIRST_ARMOR_SLOT + slot.getIndex();
            if (target < handler.getSlots()
                    && handler.getStackInSlot(target).isEmpty()
                    && handler.insertItem(target, stack, false).getCount() < stack.getCount()) {
                placed[0] = true;
            }
        });
        if (!placed[0]) {
            unit.setItemSlot(slot, stack);
            placed[0] = true;
        }
        return placed[0];
    }
}
