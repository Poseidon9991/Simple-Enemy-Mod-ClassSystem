// Event package - Forge event subscribers that are always registered
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.veterancy.UnitVeterancy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

// Scores kills for SEM units. TaCZ gunshots report the shooter as the damage
// source entity, so source.getEntity() catches both gun and melee kills.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID)
public final class VeterancyEvents {

    private VeterancyEvents() {
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        // Free a dead unit's callsign so a future recruit can carry it again
        LivingEntity dead = event.getEntity();
        if (dead instanceof AbstractUnit && dead.getCustomName() != null
                && dead.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            com.poseidon.sem_classsystem.world.UnitNameRegistry.get(serverLevel)
                    .release(dead.getCustomName().getString());
        }

        Entity source = event.getSource().getEntity();
        // SEM units and VDF militia both earn veterancy - VDF is outside the
        // AbstractUnit hierarchy now so it needs the explicit instanceof.
        if (!(source instanceof AbstractUnit)
                && !(source instanceof com.poseidon.sem_classsystem.entity.VdfUnitEntity)) {
            return;
        }
        LivingEntity killer = (LivingEntity) source;
        int oldRank = UnitVeterancy.rank(killer);
        int newRank = UnitVeterancy.addKill(killer, event.getEntity());
        if (newRank > oldRank) {
            UnitVeterancy.applyRankBonuses(killer);
            killer.heal(killer.getMaxHealth() * 0.15F); // small "adrenaline" bump on promotion
        }
    }
}
