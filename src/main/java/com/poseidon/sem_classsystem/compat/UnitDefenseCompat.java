// Compat package - relationship fixes between SEM units and other mobs
package com.poseidon.sem_classsystem.compat;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.faction.UnitClass;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// SEM units extend Monster, so iron golems, snow golems and guard-type mods
// (Guard Villagers etc.) attack ALL of them - including the player's own
// recruited PMC soldiers. This handler fixes that relationship.
//
// When a mob in the sem_classsystem:unit_defenders entity-type tag tries to
// target a protected faction's unit, we swap the target out for null - the
// defender simply never locks on. Zombies, pillagers and SEM-vs-SEM fights
// are untouched.
//
// The tag ships with minecraft:iron_golem + minecraft:snow_golem; a datapack
// can add any modded guard entity to it (e.g. "guardvillagers:guard").
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class UnitDefenseCompat {

    private UnitDefenseCompat() {
    }

    // Entity types that count as "village defenders" - datapack extendable
    public static final TagKey<EntityType<?>> UNIT_DEFENDERS = TagKey.create(
            Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(SemClassSystem.MODID, "unit_defenders"));

    // Fired whenever any entity sets its attack target.
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity newTarget = event.getNewTarget();

        // We only care when the prospective target is one of SEM's soldiers
        if (!(newTarget instanceof AbstractUnit)) {
            return;
        }

        Entity attacker = event.getEntity();
        // Enforce VDF two-way neutrality pact: VDF never locks onto protected
        // allies (villagers, golems, fellow VDF, PMC/BHQ) or unprovoked players,
        // and PMC/BHQ never lock onto VDF militia.
        if (attacker instanceof com.poseidon.sem_classsystem.entity.VdfUnitEntity vdf) {
            if (com.poseidon.sem_classsystem.entity.VdfUnitEntity.isProtectedAlly(newTarget)
                    || (newTarget instanceof Player && newTarget != vdf.getLastHurtByMob())) {
                event.setNewTarget(null);
            }
            return;
        }
        if (attacker instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity
                && newTarget instanceof com.poseidon.sem_classsystem.entity.VdfUnitEntity) {
            event.setNewTarget(null);
            return;
        }
        // Players choose their own targets - never filtered
        // SEM units fighting each other is core SEM behaviour - leave it
        if (attacker instanceof Player || attacker instanceof AbstractUnit) {
            return;
        }
        // Only tag members count as defenders (golems + whatever packs add)
        if (!attacker.getType().is(UNIT_DEFENDERS)) {
            return;
        }

        // Is this faction protected from defenders in the config?
        UnitClass faction = UnitClass.of(newTarget);
        if (faction != null && ClassSystemConfig.defendersIgnore(faction)) {
            // Replace the target with null - the defender never locks on.
            // (setNewTarget, not cancel: a genuine "clear target" call still
            // needs to go through.)
            event.setNewTarget(null);
        }
    }
}
