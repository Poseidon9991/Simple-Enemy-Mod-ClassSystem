// Event package - militia bullets can never hurt the people they protect
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;                    // MODID for the subscriber annotation
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;              // militia identity check
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;            // TaCZ's cancellable pre-hit event
import net.minecraft.world.entity.Entity;                              // hurt entity type
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

    // 4-Piece VDF "Village Warden" Set Bonus: when a wearer of the full VDF
    // militia armor set is attacked by (or attacks) a hostile entity, nearby
    // idle VDF militia within 28 blocks rally to engage that threat.
    @SubscribeEvent
    public static void onWardenCombat(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        net.minecraft.world.entity.LivingEntity victim = event.getEntity();
        Entity sourceEntity = event.getSource().getEntity();
        if (!(sourceEntity instanceof net.minecraft.world.entity.LivingEntity attacker)) {
            return;
        }
        net.minecraft.world.entity.LivingEntity warden = null;
        net.minecraft.world.entity.LivingEntity threat = null;
        if (VdfUnitEntity.isWearingFullVdfSet(victim) && !VdfUnitEntity.isProtectedAlly(attacker)) {
            warden = victim;
            threat = attacker;
        } else if (VdfUnitEntity.isWearingFullVdfSet(attacker) && !VdfUnitEntity.isProtectedAlly(victim)) {
            warden = attacker;
            threat = victim;
        }
        if (warden == null || threat == null || threat instanceof net.minecraft.world.entity.player.Player) {
            return;
        }
        final net.minecraft.world.entity.LivingEntity targetThreat = threat;
        for (VdfUnitEntity vdf : warden.level().getEntitiesOfClass(
                VdfUnitEntity.class, warden.getBoundingBox().inflate(28.0D), VdfUnitEntity::isAlive)) {
            if (vdf.getTarget() == null) {
                vdf.setTarget(targetThreat);
            }
        }
    }
}
