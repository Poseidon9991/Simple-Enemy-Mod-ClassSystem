// Event package - per-player squad size cap + recruit interception
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.UUID;

// Anti-abuse rule for multiplayer: one player may own at most
// [general] maxOwnedUnits units (default 32, 0 = unlimited), counted
// across every dimension. Enforced at three layers:
//   1. RecruitTableMenu checks before payment (no lost emeralds).
//   2. This EntityInteract cancel - SEM's own right-click-to-recruit
//      path never runs, so it can't consume the recruit item either.
//   3. MixinPmcUnitOwnershipCap cancels setOwner outright - last line of
//      defence for any path that slips through (other mods, commands).
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID)
public final class UnitOwnershipCap {

    private UnitOwnershipCap() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide
                || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof PmcUnitEntity pmc)
                || pmc.getOwnerUUID() != null) {
            return;
        }
        // Unowned PMC/BHQ + player at cap: the only thing SEM could do with
        // this click is recruit, so swallow it whole.
        if (denyIfAtCap(player)) {
            event.setCanceled(true);
        }
    }

    // true = the player is at the cap and was told why. Shared by the
    // menu, the interact cancel and the mixin so the message never differs.
    public static boolean denyIfAtCap(ServerPlayer player) {
        int cap = ClassSystemConfig.maxOwnedUnits();
        if (cap <= 0) {
            return false;
        }
        if (ownedCount(player.getServer(), player.getUUID()) < cap) {
            return false;
        }
        player.displayClientMessage(Component.literal(
                "Unit limit reached (" + cap
                        + "). Dismiss or lose units before recruiting more."), true);
        return true;
    }

    // Live count across every dimension - no bookkeeping to drift out of
    // sync when units die. Rare call sites (recruit clicks), so the sweep
    // is cheap enough.
    public static int ownedCount(MinecraftServer server, UUID owner) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof PmcUnitEntity pmc
                        && pmc.isAlive() && owner.equals(pmc.getOwnerUUID())) {
                    count++;
                }
            }
        }
        return count;
    }
}
