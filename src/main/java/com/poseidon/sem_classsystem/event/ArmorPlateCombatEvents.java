package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.item.ArmorPlateItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.List;
import java.util.Locale;

// Intercepts incoming attack & TaCZ bullet damage on any entity (Player or Unit)
// wearing a BhqArmorItem vest or helmet with slotted ArmorPlateItem(s).
// Applies the plate's damage reduction (especially strong against TaCZ bullets)
// and degrades the plate's durability on hit.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID)
public final class ArmorPlateCombatEvents {

    private ArmorPlateCombatEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) {
            return;
        }
        float amount = event.getAmount();
        if (amount <= 0.05F) {
            return;
        }
        ItemStack chest = victim.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack head = victim.getItemBySlot(EquipmentSlot.HEAD);
        boolean hasChestPlates = ArmorPlateItem.hasPlates(chest);
        boolean hasHeadPlates = ArmorPlateItem.hasPlates(head);
        if (!hasChestPlates && !hasHeadPlates) {
            return;
        }

        DamageSource source = event.getSource();
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                || source.is(DamageTypeTags.IS_FALL)
                || source.is(DamageTypeTags.IS_DROWNING)
                || source.is(DamageTypeTags.IS_FREEZING)
                || source.is(DamageTypeTags.IS_FIRE)) {
            return;
        }

        boolean bullet = isTaczOrProjectileDamage(source);

        float multiplier = 1.0F;
        boolean plateHit = false;

        // 1. Chest Vest Plate(s): primary trauma absorption
        if (hasChestPlates) {
            List<ItemStack> plates = ArmorPlateItem.getPlates(chest);
            int bestIdx = -1;
            int bestTier = 0;
            int activeCount = 0;
            for (int i = 0; i < plates.size(); i++) {
                ItemStack p = plates.get(i);
                if (!p.isEmpty() && p.getItem() instanceof ArmorPlateItem ap) {
                    activeCount++;
                    if (ap.tier() > bestTier) {
                        bestTier = ap.tier();
                        bestIdx = i;
                    }
                }
            }
            if (bestIdx >= 0) {
                ItemStack active = plates.get(bestIdx);
                ArmorPlateItem ap = (ArmorPlateItem) active.getItem();
                float red = bullet ? ap.bulletReduction() : ap.generalReduction();
                // Dual-plate synergy when both Front + Back vest slots carry plates
                if (activeCount >= 2) {
                    red = Math.min(0.92F, red + 0.05F);
                }
                multiplier *= (1.0F - red);
                plateHit = true;

                int wear = Math.max(1, Math.round(amount * (bullet ? 1.15F : 0.85F)));
                boolean broke = damagePlate(active, wear);
                if (broke) {
                    plates.set(bestIdx, ItemStack.EMPTY);
                    onPlateShattered(victim, ap);
                }
                ArmorPlateItem.setPlates(chest, plates);
                syncUnitArmorSlot(victim, 4, chest);
            }
        }

        // 2. Helmet Ballistic Insert: supplementary trauma & headshot protection
        if (hasHeadPlates) {
            List<ItemStack> hPlates = ArmorPlateItem.getPlates(head);
            if (!hPlates.isEmpty() && !hPlates.get(0).isEmpty()
                    && hPlates.get(0).getItem() instanceof ArmorPlateItem hap) {
                ItemStack hPlate = hPlates.get(0);
                float hRed = (bullet ? hap.bulletReduction() : hap.generalReduction()) * 0.25F;
                multiplier *= (1.0F - hRed);
                plateHit = true;

                int wear = Math.max(1, Math.round(amount * 0.45F));
                if (damagePlate(hPlate, wear)) {
                    hPlates.set(0, ItemStack.EMPTY);
                    onPlateShattered(victim, hap);
                }
                ArmorPlateItem.setPlates(head, hPlates);
                syncUnitArmorSlot(victim, 5, head);
            }
        }

        if (plateHit) {
            float finalDamage = Math.max(0.1F, amount * multiplier);
            event.setAmount(finalDamage);
            if (bullet) {
                victim.playSound(SoundEvents.SHIELD_BLOCK, 0.55F, 1.55F + victim.getRandom().nextFloat() * 0.2F);
            }
        }
    }

    private static boolean damagePlate(ItemStack plate, int wear) {
        int next = plate.getDamageValue() + wear;
        if (next >= plate.getMaxDamage()) {
            return true;
        }
        plate.setDamageValue(next);
        return false;
    }

    private static void onPlateShattered(LivingEntity victim, ArmorPlateItem broken) {
        victim.playSound(SoundEvents.ITEM_BREAK, 1.0F, 0.85F);
        if (victim instanceof Player player) {
            player.displayClientMessage(
                    Component.translatable("message.sem_classsystem.plate_shattered", broken.tier())
                            .withStyle(ChatFormatting.RED),
                    true);
        }
    }

    // Keeps PmcUnitEntity's ITEM_HANDLER slots (4 = chest, 5 = head) synchronized
    // with EquipmentSlot changes when a plate is modified, takes durability damage, or breaks.
    public static void syncUnitArmorSlot(LivingEntity entity, int handlerSlot, ItemStack updatedArmor) {
        if (!(entity instanceof PmcUnitEntity)) {
            return;
        }
        entity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(h -> {
            if (h instanceof IItemHandlerModifiable mod && handlerSlot < mod.getSlots()) {
                ItemStack inSlot = mod.getStackInSlot(handlerSlot);
                if (!inSlot.isEmpty() && inSlot.getItem() == updatedArmor.getItem()) {
                    mod.setStackInSlot(handlerSlot, updatedArmor.copy());
                }
            }
        });
    }

    public static boolean isTaczOrProjectileDamage(DamageSource source) {
        if (source.is(DamageTypeTags.IS_PROJECTILE)) {
            return true;
        }
        String msgId = source.getMsgId();
        if (msgId != null && (containsIgnoreCase(msgId, "tacz")
                || containsIgnoreCase(msgId, "bullet")
                || containsIgnoreCase(msgId, "gun")
                || containsIgnoreCase(msgId, "ammo"))) {
            return true;
        }
        Entity direct = source.getDirectEntity();
        if (direct != null) {
            String cls = direct.getClass().getName();
            if (containsIgnoreCase(cls, "tacz")
                    || containsIgnoreCase(cls, "bullet")
                    || containsIgnoreCase(cls, "kinetic")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        final int length = needle.length();
        final int max = haystack.length() - length;
        for (int i = 0; i <= max; i++) {
            if (haystack.regionMatches(true, i, needle, 0, length)) {
                return true;
            }
        }
        return false;
    }
}
