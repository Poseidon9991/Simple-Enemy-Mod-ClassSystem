// Event package - drives the unit needs system (hunger/energy/morale)
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.needs.UnitNeeds;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.util.SoldierState;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
        // A owned unit dying rattles the rest of the owner's squad nearby.
        if (dead instanceof PmcUnitEntity deadUnit) {
            java.util.UUID owner = deadUnit.getOwnerUUID();
            if (owner == null || !UnitNeeds.appliesTo(deadUnit)) {
                return;
            }
            for (PmcUnitEntity ally : dead.level().getEntitiesOfClass(
                    PmcUnitEntity.class, dead.getBoundingBox().inflate(16.0),
                    e -> e != dead && e.isAlive() && owner.equals(e.getOwnerUUID()))) {
                UnitNeeds.onAllyDeath(ally);
            }
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
