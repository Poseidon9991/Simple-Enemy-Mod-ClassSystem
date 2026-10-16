// Entity package - our own soldier types (BHQ starts here)
package com.poseidon.sem_classsystem.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

// BHQ (Cambodian Bodyguard Unit) - an elite PMC variant.
// Extends PmcUnitEntity so it inherits EVERYTHING for free: the 18-slot
// inventory + equipment GUI, orders system, recruit ownership, TaCZ reload
// logic, and all of our class-system mixins (they apply to AbstractUnit).
//
// What makes it different:
//   - own entity type + own skin (renderer picks our bhq textures)
//   - own faction profile in our config: [bhq_unit] has better stats than PMC
//   - own class gun table: data/*/class_loadouts/bhq_units.json
//   - recruited through OUR BHQ Recruit Table, not SEM's one
//
// SEM faction logic: the entity is added to SEM's simpleenemymod:pmc_units
// entity-type tag by our datapack, so PMCs treat it as an ally, it inherits
// the friendly role, and defenders-ignore-PMC-style rules still work.
public class BhqUnitEntity extends PmcUnitEntity {

    // 6 BHQ skins in assets/sem_classsystem/textures/entity/bhq_unit/:
    // 0-2: Standard BHQ (woodland camo: mask, skull, shades)
    // 3-5: GRK style (navy blue: mask, skull, beret)
    public static final int VARIANT_COUNT = 6;

    public BhqUnitEntity(EntityType<? extends Monster> entityType, Level level) {
        super(entityType, level);
        // Parent constructor rolled 0..5 for SEM's PMC skins - re-roll ours
        setVariant(this.random.nextInt(VARIANT_COUNT));
    }
}
