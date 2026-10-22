// Item package - accessories for Curios slots, wearable by players and units
package com.poseidon.sem_classsystem.item;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.DownedState;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

// Base class for our Curios accessories (Spotting Scope, Sergeant's Insignia,
// IFAK Pouch). Carries zero Curios imports so these items work even on installs
// without Curios:
//  - Spotting Scope: right-click in hand to laser-range and ping a distant target
//  - IFAK Pouch: right-click in hand to self-treat, or right-click an ally/downed unit
public class SemcsCurioItem extends Item {

    public SemcsCurioItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isValidRepairItem(ItemStack toRepair, ItemStack repair) {
        if (toRepair.is(SemcsRegistries.IFAK_POUCH.get())) {
            return repair.is(Items.RED_WOOL)
                    || repair.is(Items.WHITE_WOOL)
                    || repair.is(Items.GOLDEN_APPLE);
        }
        return super.isValidRepairItem(toRepair, repair);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        if (!stack.is(SemcsRegistries.IFAK_POUCH.get())) {
            return super.interactLivingEntity(stack, player, target, hand);
        }
        boolean downedUnit = target instanceof PmcUnitEntity pmc && DownedState.isDowned(pmc);
        if (!downedUnit && target.getHealth() >= target.getMaxHealth() - 0.1F) {
            return InteractionResult.PASS;
        }
        if (!player.level().isClientSide) {
            if (downedUnit && target instanceof PmcUnitEntity pmc) {
                DownedState.revive(pmc, 0.50D);
            } else {
                target.heal(8.0F);
            }
            target.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
            target.removeEffect(MobEffects.POISON);
            target.removeEffect(MobEffects.WITHER);
            target.playSound(SoundEvents.ARMOR_EQUIP_LEATHER, 0.9F, 1.1F);
            if (target.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.HEART,
                        target.getX(), target.getEyeY() + 0.2D, target.getZ(),
                        5, 0.25D, 0.2D, 0.25D, 0.01D);
            }
            stack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(hand));
            player.getCooldowns().addCooldown(this, 40);
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // 1. IFAK Pouch held in hand -> emergency self-bandage when hurt
        if (stack.is(SemcsRegistries.IFAK_POUCH.get())) {
            if (player.getHealth() >= player.getMaxHealth() - 0.1F) {
                return InteractionResultHolder.pass(stack);
            }
            if (!level.isClientSide) {
                player.heal(8.0F);
                player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
                player.removeEffect(MobEffects.POISON);
                player.removeEffect(MobEffects.WITHER);
                player.playSound(SoundEvents.ARMOR_EQUIP_LEATHER, 0.9F, 1.1F);
                if (level instanceof ServerLevel sl) {
                    sl.sendParticles(ParticleTypes.HEART,
                            player.getX(), player.getEyeY() + 0.2D, player.getZ(),
                            4, 0.25D, 0.2D, 0.25D, 0.01D);
                }
                stack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(hand));
                player.getCooldowns().addCooldown(this, 60);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        // 2. Spotting Scope held in hand -> laser rangefinder + target ping (up to 96m)
        if (stack.is(SemcsRegistries.SPOTTING_SCOPE.get())) {
            if (!level.isClientSide) {
                double maxDist = 96.0D;
                Vec3 eye = player.getEyePosition();
                Vec3 look = player.getViewVector(1.0F);
                Vec3 end = eye.add(look.scale(maxDist));
                AABB box = player.getBoundingBox().expandTowards(look.scale(maxDist)).inflate(1.5D);
                EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                        player, eye, end, box,
                        e -> e instanceof LivingEntity && e.isPickable() && !e.isSpectator(),
                        maxDist * maxDist);
                if (hit != null && hit.getEntity() instanceof LivingEntity target) {
                    int dist = (int) Math.round(player.distanceTo(target));
                    int hp = (int) Math.ceil(target.getHealth());
                    int maxHp = (int) Math.ceil(target.getMaxHealth());
                    WeaponClass cls = WeaponClassSupport.get(target);
                    String role = cls != null ? " [" + ClassSystemConfig.classNickname(cls) + "]" : "";
                    target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 160, 0, false, false));
                    player.displayClientMessage(
                            Component.literal("⌖ ")
                                    .withStyle(ChatFormatting.AQUA)
                                    .append(target.getDisplayName().copy().withStyle(ChatFormatting.WHITE))
                                    .append(Component.literal(role + " — " + dist + "m | " + hp + "/" + maxHp + " HP")
                                            .withStyle(ChatFormatting.GRAY)),
                            true);
                    player.playSound(SoundEvents.SPYGLASS_USE, 0.8F, 1.2F);
                    player.getCooldowns().addCooldown(this, 25);
                } else {
                    player.displayClientMessage(
                            Component.literal("⌖ No target in range (96m)").withStyle(ChatFormatting.DARK_GRAY),
                            true);
                    player.playSound(SoundEvents.SPYGLASS_USE, 0.6F, 0.9F);
                }
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        return super.use(level, player, hand);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(getDescriptionId(stack) + ".desc")
                .withStyle(ChatFormatting.GRAY));
        if (stack.isDamageableItem()) {
            int left = stack.getMaxDamage() - stack.getDamageValue();
            tooltip.add(Component.translatable("item.sem_classsystem.ifak_pouch.doses",
                            left, stack.getMaxDamage())
                    .withStyle(ChatFormatting.GOLD));
        }
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
