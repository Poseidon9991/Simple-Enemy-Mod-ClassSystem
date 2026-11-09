package com.poseidon.sem_classsystem.compat;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.needs.UnitNeeds;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import com.tacz.guns.api.item.IGun;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
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

    // 1. Spotting Scope (head):
    //    - +35% detection range (FOLLOW_RANGE) + +8% attack speed (target acquisition)
    //    - -18% bullet spread on units (via UnitClassAttributes.spreadMultiplier)
    //    - For Players: actively laser-ranges and highlights (Glowing) whatever entity
    //      the player looks at up to 96m, displays live range/HP telemetry on the HUD
    //      when crouching or holding a firearm, and grants low-light optic Night Vision
    //      while crouching in dark environments.
    //    - For Units: marks the wearer's current combat target with Glowing.
    private static final class SpottingScope implements ICurioItem {

        @Override
        public Multimap<Attribute, AttributeModifier> getAttributeModifiers(
                SlotContext slotContext, UUID uuid, ItemStack stack) {
            Multimap<Attribute, AttributeModifier> map = HashMultimap.create();
            map.put(Attributes.FOLLOW_RANGE, new AttributeModifier(uuid,
                    "Spotting scope detection bonus", 0.35D,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));
            map.put(Attributes.ATTACK_SPEED, new AttributeModifier(uuid,
                    "Spotting scope target acquisition", 0.08D,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));
            return map;
        }

        @Override
        public void curioTick(SlotContext slotContext, ItemStack stack) {
            LivingEntity wearer = slotContext.entity();
            if (wearer.level().isClientSide) {
                return;
            }

            if (wearer instanceof Player player) {
                if ((player.tickCount + player.getId()) % 12 != 0) {
                    return;
                }
                // Low-light optic enhancement while crouching in dark areas
                if (player.isShiftKeyDown()
                        && player.level().getMaxLocalRawBrightness(player.blockPosition()) <= 8) {
                    player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 260, 0, true, false, true));
                }

                // 96m look-vector laser rangefinder & target highlighter
                double maxDist = 96.0D;
                Vec3 eye = player.getEyePosition();
                Vec3 look = player.getViewVector(1.0F);
                Vec3 end = eye.add(look.scale(maxDist));
                AABB searchBox = player.getBoundingBox().expandTowards(look.scale(maxDist)).inflate(1.6D);
                EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                        player, eye, end, searchBox,
                        e -> e instanceof LivingEntity le && e != player && le.isAlive() && e.isPickable() && !e.isSpectator(),
                        maxDist * maxDist);

                LivingEntity lookedAt = hit != null && hit.getEntity() instanceof LivingEntity le ? le : null;
                if (lookedAt != null) {
                    lookedAt.addEffect(new MobEffectInstance(MobEffects.GLOWING, 120, 0, false, false));
                    boolean holdingGun = IGun.getIGunOrNull(player.getMainHandItem()) != null;
                    if (player.isShiftKeyDown() || holdingGun) {
                        int dist = (int) Math.round(player.distanceTo(lookedAt));
                        int hp = (int) Math.ceil(lookedAt.getHealth());
                        int maxHp = (int) Math.ceil(lookedAt.getMaxHealth());
                        WeaponClass cls = WeaponClassSupport.get(lookedAt);
                        String role = cls != null ? " [" + ClassSystemConfig.classNickname(cls) + "]" : "";
                        player.displayClientMessage(
                                Component.literal("⌖ ")
                                        .withStyle(ChatFormatting.AQUA)
                                        .append(lookedAt.getDisplayName().copy().withStyle(ChatFormatting.WHITE))
                                        .append(Component.literal(role + " — " + dist + "m | " + hp + "/" + maxHp + " HP")
                                                .withStyle(ChatFormatting.GRAY)),
                                true);
                    }
                    return;
                }

                // Fallback: keep last hurt target highlighted within 96m
                LivingEntity lastHurt = player.getLastHurtMob();
                if (lastHurt != null && lastHurt.isAlive() && player.distanceToSqr(lastHurt) <= maxDist * maxDist) {
                    lastHurt.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100, 0, false, false));
                }
                return;
            }

            if ((wearer.tickCount + wearer.getId()) % 60 != 0) {
                return;
            }
            LivingEntity target = wearer instanceof Mob mob ? mob.getTarget() : wearer.getLastHurtMob();
            if (target != null && target.isAlive() && wearer.distanceToSqr(target) <= 96.0D * 96.0D) {
                target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100, 0, false, false));
            }
        }
    }

    // 2. Sergeant's Insignia (charm):
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

    // 3. IFAK Pouch (belt):
    //    - 8 doses (repairable with wool or golden apples)
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

            // Standard emergency trigger below 40% HP
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
