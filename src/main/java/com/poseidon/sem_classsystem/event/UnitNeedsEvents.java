// Event package - drives the unit needs system (hunger/energy/morale)
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.needs.UnitNeeds;
import com.poseidon.sem_classsystem.world.UnitEventLog;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.util.SoldierState;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.UUID;

// Tick + combat hooks for [needs]. All gated on UnitNeeds.appliesTo, which
// already checks the config toggle and ownership - with needs disabled
// these handlers return immediately and units behave like plain SEM.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID)
public final class UnitNeedsEvents {

    // Throttle the drain bookkeeping - the math is elapsed-time based so
    // resolution doesn't change, we just do it less often.
    private static final int DRAIN_INTERVAL = 40;
    private static final double ALLY_MORALE_RADIUS_SQR = 16.0 * 16.0;

    private UnitNeedsEvents() {
    }

    @SubscribeEvent
    public static void onTick(LivingEvent.LivingTickEvent event) {
        Entity e = event.getEntity();
        if (e.level().isClientSide || !(e instanceof PmcUnitEntity unit)
                || e.tickCount % DRAIN_INTERVAL != 0) {
            return;
        }
        UnitNeeds.tick(unit);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        Entity dead = event.getEntity();
        if (dead.level().isClientSide) {
            return;
        }
        // Killer morale bump: one of OUR owned units scored the kill.
        Entity killer = event.getSource().getEntity();
        if (killer instanceof PmcUnitEntity pmc) {
            UnitNeeds.onKill(pmc);
        }
        // A owned unit dying rattles the rest of the owner's squad nearby,
        // notifies the owner (or the offline report), and frees the name.
        if (dead instanceof PmcUnitEntity deadUnit) {
            UUID owner = deadUnit.getOwnerUUID();
            if (owner == null || !(dead.level() instanceof ServerLevel sl)) {
                return;
            }
            String name = deadUnit.getDisplayName().getString();
            boolean starved = event.getSource().is(DamageTypes.STARVE);
            String line = starved
                    ? name + " starved to death"
                    : name + " was killed in action";
            // Permanent KIA board entry - position + day for the intel app.
            UnitEventLog.get(sl.getServer().overworld()).addCasualty(owner,
                    line + " @ " + deadUnit.blockPosition().toShortString()
                            + " d" + (sl.getDayTime() / 24000L));
            ServerPlayer ownerPlayer = sl.getServer().getPlayerList().getPlayer(owner);
            if (ownerPlayer != null) {
                ownerPlayer.sendSystemMessage(Component.literal("[SEM] " + line
                        + " at " + deadUnit.blockPosition().toShortString())
                        .withStyle(starved ? ChatFormatting.RED : ChatFormatting.GRAY));
            } else {
                // Offline: file it for the login squad report.
                UnitEventLog.get(sl.getServer().overworld()).add(owner, line);
            }
            if (starved) {
                UnitNeeds.releaseBed(deadUnit);
            }
            if (UnitNeeds.appliesTo(deadUnit)) {
                for (PmcUnitEntity ally : dead.level().getEntitiesOfClass(
                        PmcUnitEntity.class, dead.getBoundingBox().inflate(16.0),
                        e -> e != dead && e.isAlive() && owner.equals(e.getOwnerUUID()))) {
                    UnitNeeds.onAllyDeath(ally);
                }
            }
        }
    }

    // Offline report: replay squad events that happened while the player
    // was away - deaths logged by onDeath, raids logged by NightRaidEvents.
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !ClassSystemConfig.offlineReport()) {
            return;
        }
        ServerLevel overworld = player.getServer().overworld();
        List<String> lines = UnitEventLog.get(overworld).drain(player.getUUID());
        if (lines.isEmpty()) {
            return;
        }
        player.sendSystemMessage(Component.literal(
                "=== Squad report (while you were away) ===")
                .withStyle(ChatFormatting.GOLD));
        for (String line : lines) {
            player.sendSystemMessage(Component.literal("  " + line)
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof PmcUnitEntity unit)
                || unit.level().isClientSide) {
            return;
        }
        // Getting shot wakes a sleeper - beds are for peacetime.
        UnitNeeds.wake(unit);
        // Shaken unit (low morale) can break and dive for cover instead of
        // standing its ground.
        if (UnitNeeds.shouldBreak(unit)) {
            unit.setSoldierState(SoldierState.SEEK_COVER);
        }
    }
}
