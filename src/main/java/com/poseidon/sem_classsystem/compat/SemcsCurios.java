package com.poseidon.sem_classsystem.compat;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.DownedState;
import com.poseidon.sem_classsystem.needs.UnitNeeds;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.UUID;

// Binds our accessory Items (item.SemcsCurioItem) to Curios behaviour.
// Only referenced behind ModList.get().isLoaded("curios").
public final class SemcsCurios {

    private SemcsCurios() {
    }

    public static void register() {
        CuriosApi.registerCurio(SemcsRegistries.SPOTTING_SCOPE.get(), new SpottingScope());
        CuriosApi.registerCurio(SemcsRegistries.SERGEANTS_INSIGNIA.get(), new Insignia());
        CuriosApi.registerCurio(SemcsRegistries.IFAK_POUCH.get(), new Ifak());
    }

    public static boolean hasSpottingScope(LivingEntity entity) {
        var opt = CuriosApi.getCuriosInventory(entity).resolve();
        return opt.isPresent()
                && opt.get().findFirstCurio(SemcsRegistries.SPOTTING_SCOPE.get()).isPresent();
    }

    // 1. Spotting Scope (head / belt / curio):
    //    - +35% detection range (FOLLOW_RANGE)
    //    - -18% bullet spread on units (via UnitClassAttributes.spreadMultiplier)
    //    - Every 4s in combat, marks the wearer's current target with Glowing
    //      so the whole squad and commander can track it through foliage/darkness.
    private static final class SpottingScope implements ICurioItem {

        @Override
        public Multimap<Attribute, AttributeModifier> getAttributeModifiers(
                SlotContext slotContext, UUID uuid, ItemStack stack) {
            Multimap<Attribute, AttributeModifier> map = HashMultimap.create();
            map.put(Attributes.FOLLOW_RANGE, new AttributeModifier(uuid,
                    "Spotting scope detection bonus", 0.35D,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));
            return map;
        }

        @Override
        public void curioTick(SlotContext slotContext, ItemStack stack) {
            LivingEntity wearer = slotContext.entity();
            if (wearer.level().isClientSide
                    || (wearer.tickCount + wearer.getId()) % 80 != 0) {
                return;
            }
            LivingEntity target = wearer instanceof Mob mob ? mob.getTarget() : wearer.getLastHurtMob();
            if (target != null && target.isAlive() && wearer.distanceToSqr(target) <= 96.0D * 96.0D) {
                target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100, 0, false, false));
            }
        }
    }

    // 2. Sergeant's Insignia (charm / necklace / curio):
    //    - Grants the wearer +2.0 Armor Toughness and +10% Knockback Resistance
    //    - Command Aura (24m, every 5s): inspires nearby allied units with
    //      Resistance I in combat, plus hunger/energy/morale sustain when [needs] is on.
    private static final class Insignia implements ICurioItem {

        private static final int PERIOD_TICKS = 100;   // every 5s
        private static final double RADIUS = 24.0D;

        @Override
        public Multimap<Attribute, AttributeModifier> getAttributeModifiers(
                SlotContext slotContext, UUID uuid, ItemStack stack) {
            Multimap<Attribute, AttributeModifier> map = HashMultimap.create();
            map.put(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(uuid,
                    "Sergeant insignia toughness", 2.0D,
                    AttributeModifier.Operation.ADDITION));
            map.put(Attributes.KNOCKBACK_RESISTANCE, new AttributeModifier(uuid,
                    "Sergeant insignia steadiness", 0.10D,
                    AttributeModifier.Operation.ADDITION));
            return map;
        }

        @Override
        public void curioTick(SlotContext slotContext, ItemStack stack) {
            LivingEntity wearer = slotContext.entity();
            if (wearer.level().isClientSide
                    || (wearer.level().getGameTime() + wearer.getId()) % PERIOD_TICKS != 0) {
                return;
            }
            UUID ownerId = wearer instanceof Player p ? p.getUUID()
                    : (wearer instanceof PmcUnitEntity pmc ? pmc.getOwnerUUID() : null);
            if (ownerId == null) {
                return;
            }
            AABB area = wearer.getBoundingBox().inflate(RADIUS);
            for (PmcUnitEntity unit : wearer.level().getEntitiesOfClass(PmcUnitEntity.class,
                    area, u -> ownerId.equals(u.getOwnerUUID()) && u.isAlive())) {
                if (unit.getTarget() != null && unit.getTarget().isAlive()) {
                    unit.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 140, 0, false, false));
                }
                if (ClassSystemConfig.needsEnabled()) {
                    UnitNeeds.sustain(unit);
                    UnitNeeds.addMorale(unit, 2);
                }
            }
        }
    }

    // 3. IFAK Pouch (belt / back / curio):
    //    - 8 doses (repairable with wool or golden apples)
    //    - Auto-stabilizes a Downed unit after 3s (self-revive at 50% HP)
    //    - Auto-heals 4 hearts + Regeneration II (5s) + cleanses Poison/Wither
    //      when health drops below 40% (8s cooldown between doses).
    private static final class Ifak implements ICurioItem {

        private static final float HEAL = 8.0F;
        private static final float TRIGGER = 0.40F;
        private static final int COOLDOWN_TICKS = 160;   // 8s between doses

        @Override
        public void curioTick(SlotContext slotContext, ItemStack stack) {
            LivingEntity wearer = slotContext.entity();
            if (wearer.level().isClientSide) {
                return;
            }
            CompoundTag tag = stack.getOrCreateTag();
            long now = wearer.level().getGameTime();
            long last = tag.getLong("semcs_ifak_last");
            if (last != 0L && now - last < COOLDOWN_TICKS) {
                return;
            }

            // A. If an owned unit is Downed while wearing an IFAK, self-revive!
            if (wearer instanceof PmcUnitEntity pmc && DownedState.isDowned(pmc)) {
                long downedAt = tag.getLong("semcs_ifak_down_tick");
                if (downedAt == 0L || now - downedAt > 200L) {
                    tag.putLong("semcs_ifak_down_tick", now);
                    return;
                }
                if (now - downedAt >= 60L) {
                    tag.remove("semcs_ifak_down_tick");
                    tag.putLong("semcs_ifak_last", now);
                    DownedState.revive(pmc, 0.50D);
                    applyTraumaDose(pmc, stack);
                }
                return;
            }

            // B. Standard emergency trigger below 40% HP
            if (wearer.getHealth() >= wearer.getMaxHealth() * TRIGGER) {
                return;
            }
            tag.putLong("semcs_ifak_last", now);
            wearer.heal(HEAL);
            applyTraumaDose(wearer, stack);
        }

        private static void applyTraumaDose(LivingEntity wearer, ItemStack stack) {
            wearer.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
            wearer.removeEffect(MobEffects.POISON);
            wearer.removeEffect(MobEffects.WITHER);
            wearer.playSound(SoundEvents.ARMOR_EQUIP_LEATHER, 0.9F, 1.15F);
            if (wearer.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.HEART,
                        wearer.getX(), wearer.getEyeY() + 0.2D, wearer.getZ(),
                        4, 0.25D, 0.2D, 0.25D, 0.01D);
            }
            stack.hurtAndBreak(1, wearer, e -> {
            });
        }
    }
}
