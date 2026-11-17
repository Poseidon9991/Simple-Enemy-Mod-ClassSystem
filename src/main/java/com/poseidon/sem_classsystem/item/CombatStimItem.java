package com.poseidon.sem_classsystem.item;

import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import com.poseidon.sem_classsystem.needs.UnitNeeds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;

// Combat Stim / Auto-Injector:
//  - Single-use consumable syringe (stacks to 8) for rapid combat recovery.
//  - Right-click in hand: self-inject (+4 HP, Speed II, Absorption I, Regen I,
//    clears Slowness & Weakness).
//  - Right-click a friendly soldier (PMC / BHQ / VDF / Player): inject ally with
//    +6 HP, Speed II, Absorption I, Regen I, and for owned units immediately restores
//    +12 Energy, +25 Morale, and wakes sleeping troops for an emergency push.
public class CombatStimItem extends Item {

    private static final DustParticleOptions STIM_DUST =
            new DustParticleOptions(new Vector3f(0.20F, 0.92F, 0.98F), 1.1F);

    public CombatStimItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        boolean friendly = target instanceof VdfUnitEntity
                || target instanceof Player
                || (target instanceof PmcUnitEntity pmc && pmc.getOwnerUUID() != null);
        if (!friendly || !target.isAlive()) {
            return super.interactLivingEntity(stack, player, target, hand);
        }
        if (!player.level().isClientSide) {
            applyStim(target, 6.0F, 240);
            if (target instanceof PmcUnitEntity pmc) {
                UnitNeeds.stimInject(pmc);
            }
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            player.getCooldowns().addCooldown(this, 30);
            player.displayClientMessage(
                    Component.translatable("message.sem_classsystem.stim_ally", target.getDisplayName())
                            .withStyle(ChatFormatting.AQUA),
                    true);
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            applyStim(player, 4.0F, 200);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            player.getCooldowns().addCooldown(this, 40);
            player.displayClientMessage(
                    Component.translatable("message.sem_classsystem.stim_self")
                            .withStyle(ChatFormatting.AQUA),
                    true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static void applyStim(LivingEntity target, float healAmount, int speedTicks) {
        target.heal(healAmount);
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, speedTicks, 1));
        target.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 300, 0));
        target.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 120, 0));
        target.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        target.removeEffect(MobEffects.WEAKNESS);

        target.level().playSound(null, target.blockPosition(),
                SoundEvents.BOTTLE_EMPTY, SoundSource.PLAYERS, 0.8F, 1.45F);
        target.level().playSound(null, target.blockPosition(),
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.35F, 1.85F);
        if (target.level() instanceof ServerLevel sl) {
            sl.sendParticles(STIM_DUST,
                    target.getX(), target.getEyeY() - 0.3D, target.getZ(),
                    10, 0.25D, 0.35D, 0.25D, 0.02D);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.sem_classsystem.combat_stim.desc")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.sem_classsystem.combat_stim.hint")
                .withStyle(ChatFormatting.AQUA));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
