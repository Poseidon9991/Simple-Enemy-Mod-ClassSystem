// Event package - downed-not-dead hooks on the Forge game bus
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.DownedState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

// PlayerRevive-style flow for owned units: lethal damage collapses the unit
// instead of killing it; the bleedout clock then decides. Revive by
// right-clicking with a medical item (consumed) or sneak-clicking bare-handed
// for a weaker stabilize; medics triage automatically via UnitMedicHealGoal.
//
// Only OWNED PmcUnitEntity qualifies (PMC + BHQ) - RU/US field units die
// outright like always, and wild PMCs are fair game.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID)
public final class UnitDownedEvents {

    private UnitDownedEvents() {
    }

    // Lethal hit -> collapse instead of dying. Must run FIRST: the death
    // side-effect handlers (UnitNeedsEvents' KIA board + owner message +
    // squad morale hit, VeterancyEvents' callsign release) all listen at
    // NORMAL, and none of them opt into receiveCanceled - cancelling here,
    // before they fire, is what keeps a downed-revive off the casualty
    // board and keeps the unit's name out of the free pool.
    // A unit that is deliberately killed (bleedout timeout, /kill, void)
    // still dies: the isDowned check below early-returns on the real death.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof PmcUnitEntity unit)
                || unit.level().isClientSide) {
            return;
        }
        if (!ClassSystemConfig.downedEnabled()
                || unit.getOwnerUUID() == null
                || DownedState.isDowned(unit)) {
            return;
        }
        event.setCanceled(true);
        DownedState.down(unit);
        ServerPlayer owner = unit.getServer() == null ? null
                : unit.getServer().getPlayerList().getPlayer(unit.getOwnerUUID());
        if (owner != null) {
            owner.displayClientMessage(Component.literal(
                    nameOf(unit) + " is DOWN - stabilize within "
                            + ClassSystemConfig.downedBleedoutTicks() / 20 + "s "
                            + "(medical item or sneak-click)")
                    .withStyle(ChatFormatting.RED), false);
        }
        unit.level().playSound(null, unit.blockPosition(),
                SoundEvents.PLAYER_HURT, SoundSource.NEUTRAL, 1.0F, 0.7F);
    }

    // A downed unit can't be executed - the bleedout clock is the threat,
    // same as PlayerRevive's downed players.
    @SubscribeEvent
    public static void onAttack(LivingAttackEvent event) {
        if (event.getEntity() instanceof PmcUnitEntity unit
                && DownedState.isDowned(unit)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof PmcUnitEntity unit)
                || unit.level().isClientSide
                || !DownedState.isDowned(unit)) {
            return;
        }
        if (DownedState.expired(unit)) {
            unit.kill();   // bleedout -> real death, normal drops
            ServerPlayer owner = unit.getOwnerUUID() == null ? null
                    : unit.getServer().getPlayerList().getPlayer(unit.getOwnerUUID());
            if (owner != null) {
                owner.displayClientMessage(Component.literal(
                        nameOf(unit) + " bled out.").withStyle(ChatFormatting.DARK_RED), false);
            }
            return;
        }
        // Occasional distress puffs so a downed unit is visible mid-fight.
        if (unit.tickCount % 40 == 0 && unit.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.DAMAGE_INDICATOR,
                    unit.getX(), unit.getY() + 0.4D, unit.getZ(),
                    1, 0.2D, 0.1D, 0.2D, 0.02D);
        }
    }

    // Owner interact = revive attempt. Medical item -> proper revive at the
    // configured fraction; sneak + empty hand -> weak field stabilize.
    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof PmcUnitEntity unit)
                || !DownedState.isDowned(unit)
                || event.getLevel().isClientSide) {
            return;
        }
        Player player = event.getEntity();
        if (!unit.isOwnedBy(player)) {
            return;
        }
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        boolean medical = !held.isEmpty()
                && UnitMarkerTracker.isMedicalItem(held);
        if (!medical && !(held.isEmpty() && player.isShiftKeyDown())) {
            return;   // needs supplies or a bare-handed sneak stabilize
        }
        event.setCanceled(true);
        if (medical) {
            held.shrink(1);
        }
        DownedState.revive(unit, medical
                ? ClassSystemConfig.downedReviveFrac()
                : ClassSystemConfig.downedReviveFrac() * 0.5);
        if (unit.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HEART,
                    unit.getX(), unit.getY() + 0.6D, unit.getZ(),
                    4, 0.3D, 0.3D, 0.3D, 0.02D);
        }
        unit.level().playSound(null, unit.blockPosition(),
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.NEUTRAL, 0.7F, 1.4F);
        player.displayClientMessage(Component.literal(
                nameOf(unit) + " stabilized.")
                .withStyle(ChatFormatting.GREEN), true);
    }

    private static String nameOf(PmcUnitEntity unit) {
        Component custom = unit.getCustomName();
        return custom != null ? custom.getString() : "A unit";
    }
}
