// Event package - militia bullets can never hurt the people they protect
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;                    // MODID for the subscriber annotation
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;              // militia identity check
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;            // TaCZ's cancellable pre-hit event
import net.minecraft.world.entity.Entity;                              // hurt entity type
import net.minecraft.world.entity.animal.IronGolem;                    // village golem
import net.minecraft.world.entity.npc.AbstractVillager;                // villagers + wandering traders
import net.minecraftforge.eventbus.api.SubscribeEvent;                 // handler annotation
import net.minecraftforge.fml.common.Mod;                              // subscriber annotation

// Layer 2 of the villager-safety contract. Layer 1 (target selection) means a
// VDF never AIMS at villagers, golems, or its own kind - but TaCZ bullets are
// real projectiles, and a villager can still walk into the line of fire.
// EntityHurtByGunEvent.Pre is @Cancelable, so we veto the damage outright:
// a stray militia bullet passes through a villager harmlessly.
//
// Melee militia need no equivalent guard - a melee swing can only hit the
// current attack target, which targeting already filters.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VdfFriendlyFireEvents {

    private VdfFriendlyFireEvents() {
    }

    @SubscribeEvent
    public static void onGunHurtPre(EntityHurtByGunEvent.Pre event) {
        Entity attacker = event.getAttacker();
        Entity hurt = event.getHurtEntity();
        if (attacker instanceof VdfUnitEntity vdf) {
            if (VdfUnitEntity.isProtectedAlly(hurt)
                    || (hurt instanceof net.minecraft.world.entity.player.Player
                        && hurt != vdf.getLastHurtByMob())) {
                event.setCanceled(true);                   // militia bullet passes through allies/unprovoked players
            }
            return;
        }
        if (attacker instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity
                && hurt instanceof VdfUnitEntity) {
            event.setCanceled(true);                       // PMC/BHQ bullets never harm VDF militia in crossfire
        }
    }
}
