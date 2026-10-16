package com.poseidon.sem_classsystem.compat;

import com.poseidon.sem_classsystem.SemClassSystem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.Locale;
import java.util.Map;

// Bridges Curios & Artifacts item effects (including Charm/Totem of Undying
// death protection) onto SEM units (PMC, BHQ, RU, US).
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CuriosUnitEffectsHandler {

    private CuriosUnitEffectsHandler() {
    }

    // 1. Intercept lethal damage BEFORE death / downed triggers so a Totem /
    //    Charm of Undying in Curios (or offhand/storage) saves the unit!
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onUnitLethalDamage(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof AbstractUnit unit) || unit.level().isClientSide) {
            return;
        }
        if (event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }
        if (unit.getHealth() - event.getAmount() > 0.0F) {
            return;
        }
        if (tryConsumeTotemOfUndying(unit)) {
            event.setAmount(0.0F);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onUnitDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof AbstractUnit unit) || unit.level().isClientSide) {
            return;
        }
        if (event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }
        if (tryConsumeTotemOfUndying(unit)) {
            event.setCanceled(true);
        }
    }

    public static boolean isUndyingTotem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.is(Items.TOTEM_OF_UNDYING)) {
            return true;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("totem") || path.contains("undying");
    }

    private static boolean tryConsumeTotemOfUndying(AbstractUnit unit) {
        boolean consumed = false;

        // A. Check Curios inventory slots first
        if (ModList.get().isLoaded("curios")) {
            var opt = CuriosApi.getCuriosInventory(unit).resolve();
            if (opt.isPresent()) {
                for (Map.Entry<String, ICurioStacksHandler> entry : opt.get().getCurios().entrySet()) {
                    IDynamicStackHandler stacks = entry.getValue().getStacks();
                    for (int i = 0; i < stacks.getSlots(); i++) {
                        ItemStack s = stacks.getStackInSlot(i);
                        if (isUndyingTotem(s)) {
                            consumeOneTotem(unit, stacks, i, s);
                            consumed = true;
                            break;
                        }
                    }
                    if (consumed) break;
                }
            }
        }

        // B. Check Offhand / Mainhand
        if (!consumed) {
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.OFFHAND, EquipmentSlot.MAINHAND}) {
                ItemStack s = unit.getItemBySlot(slot);
                if (isUndyingTotem(s)) {
                    if (s.isDamageableItem()) {
                        s.hurtAndBreak(1, unit, e -> e.broadcastBreakEvent(slot));
                    } else {
                        s.shrink(1);
                    }
                    unit.setItemSlot(slot, s.isEmpty() ? ItemStack.EMPTY : s);
                    consumed = true;
                    break;
                }
            }
        }

        // C. Check unit ITEM_HANDLER storage slots (6..17)
        if (!consumed) {
            var cap = unit.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
            if (cap.isPresent() && cap.get() instanceof IItemHandlerModifiable mod) {
                for (int i = 0; i < mod.getSlots(); i++) {
                    ItemStack s = mod.getStackInSlot(i);
                    if (isUndyingTotem(s)) {
                        if (s.isDamageableItem()) {
                            s.hurtAndBreak(1, unit, e -> {});
                        } else {
                            s.shrink(1);
                        }
                        mod.setStackInSlot(i, s.isEmpty() ? ItemStack.EMPTY : s);
                        consumed = true;
                        break;
                    }
                }
            }
        }

        if (!consumed) {
            return false;
        }

        // Apply vanilla Totem of Undying resurrection effects + client totem pop animation/sound
        unit.setHealth(Math.max(6.0F, unit.getMaxHealth() * 0.35F));
        unit.removeAllEffects();
        unit.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
        unit.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));
        unit.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));
        unit.level().broadcastEntityEvent(unit, (byte) 35);
        return true;
    }

    private static void consumeOneTotem(AbstractUnit unit, IDynamicStackHandler stacks, int slot, ItemStack s) {
        if (s.isDamageableItem()) {
            s.hurtAndBreak(1, unit, e -> {});
        } else {
            s.shrink(1);
        }
        stacks.setStackInSlot(slot, s.isEmpty() ? ItemStack.EMPTY : s);
    }

    // 2. Periodic tick for passive Curios / Artifacts worn by units
    @SubscribeEvent
    public static void onUnitTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof AbstractUnit unit) || unit.level().isClientSide) {
            return;
        }
        if ((unit.tickCount + unit.getId()) % 20 != 0 || !ModList.get().isLoaded("curios")) {
            return;
        }
        CuriosApi.getCuriosInventory(unit).ifPresent(inv -> {
            for (ICurioStacksHandler handler : inv.getCurios().values()) {
                IDynamicStackHandler stacks = handler.getStacks();
                for (int i = 0; i < stacks.getSlots(); i++) {
                    ItemStack stack = stacks.getStackInSlot(i);
                    if (stack.isEmpty()) continue;
                    String path = itemPath(stack);
                    if (path.contains("night_vision")) {
                        unit.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 260, 0, false, false));
                    } else if (path.contains("scarf_of_invisibility") && !unit.isInCombat()) {
                        unit.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 40, 0, false, false));
                    } else if (path.contains("running_shoes") || path.contains(" roller") || path.contains("agility")) {
                        unit.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 40, 0, false, false));
                    } else if (path.contains("antidote")) {
                        unit.removeEffect(MobEffects.POISON);
                        unit.removeEffect(MobEffects.WITHER);
                        unit.removeEffect(MobEffects.DARKNESS);
                    }
                }
            }
        });
    }

    // 3. On-hurt and on-attack triggers for Artifacts worn by units
    @SubscribeEvent
    public static void onUnitHurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide || !ModList.get().isLoaded("curios")) {
            return;
        }
        // Defender is a unit wearing defensive Curios/Artifacts
        if (event.getEntity() instanceof AbstractUnit victim) {
            CuriosApi.getCuriosInventory(victim).ifPresent(inv -> {
                for (ICurioStacksHandler handler : inv.getCurios().values()) {
                    IDynamicStackHandler stacks = handler.getStacks();
                    for (int i = 0; i < stacks.getSlots(); i++) {
                        ItemStack stack = stacks.getStackInSlot(i);
                        if (stack.isEmpty()) continue;
                        String path = itemPath(stack);
                        if (path.contains("panic_necklace")) {
                            victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 100, 1));
                        } else if (path.contains("cross_necklace")) {
                            victim.invulnerableTime = Math.max(victim.invulnerableTime, 30);
                        } else if (path.contains("obsidian_skull")
                                && event.getSource().is(DamageTypeTags.IS_FIRE)) {
                            victim.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200, 0));
                            event.setAmount(event.getAmount() * 0.5F);
                        } else if (path.contains("flame_pendant")
                                && event.getSource().getEntity() instanceof LivingEntity attacker) {
                            attacker.setSecondsOnFire(4);
                        } else if (path.contains("thorn_pendant")
                                && event.getSource().getEntity() instanceof LivingEntity attacker) {
                            attacker.hurt(victim.damageSources().thorns(victim), 3.0F);
                        }
                    }
                }
            });
        }

        // Attacker is a unit wearing offensive Curios/Artifacts (Power Glove, Fire Gauntlet, Vampiric Glove)
        if (event.getSource().getEntity() instanceof AbstractUnit attacker) {
            CuriosApi.getCuriosInventory(attacker).ifPresent(inv -> {
                for (ICurioStacksHandler handler : inv.getCurios().values()) {
                    IDynamicStackHandler stacks = handler.getStacks();
                    for (int i = 0; i < stacks.getSlots(); i++) {
                        ItemStack stack = stacks.getStackInSlot(i);
                        if (stack.isEmpty()) continue;
                        String path = itemPath(stack);
                        if (path.contains("power_glove")) {
                            event.setAmount(event.getAmount() + 4.0F);
                        } else if (path.contains("fire_gauntlet")) {
                            event.getEntity().setSecondsOnFire(4);
                        } else if (path.contains("vampiric_glove") || path.contains("leech")) {
                            attacker.heal(Math.min(4.0F, event.getAmount() * 0.25F));
                        }
                    }
                }
            });
        }
    }

    private static String itemPath(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null ? id.getPath().toLowerCase(Locale.ROOT) : "";
    }
}
