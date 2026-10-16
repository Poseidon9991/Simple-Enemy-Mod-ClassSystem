// Veterancy package - kill-count XP, rank tiers and permanent stat bonuses
package com.poseidon.sem_classsystem.veterancy;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.UUID;

// Tracks a unit's combat record in its persistent NBT ("semcs_xp").
// Scoring: killing another SEM unit or a player is worth 5 XP; killing a
// hostile mob (zombies, skeletons, illagers, any Enemy) is worth 1 XP.
// XP unlocks rank tiers which grant permanent attribute modifiers and a
// small accuracy bonus on top of the class spread tuning.
public final class UnitVeterancy {

    private UnitVeterancy() {
    }

    public static final String XP_KEY = "semcs_xp";

    // Rank ladder: index = rank tier, value = XP needed to reach it
    private static final int[] THRESHOLDS = {0, 15, 40, 80, 150, 250};
    private static final String[] RANK_NAMES =
            {"Recruit", "Private", "Corporal", "Sergeant", "Veteran", "Elite"};
    private static final String[] RANK_TITLES =
            {"Rct.", "Pvt.", "Cpl.", "Sgt.", "Vet.", "Elt."};

    // Fixed modifier UUIDs so re-applying a rank never stacks duplicates
    private static final UUID HEALTH_MODIFIER_ID =
            UUID.fromString("5b2f9a1e-3d4c-4a7b-9e2f-1c6d8a0b3f41");
    private static final UUID DAMAGE_MODIFIER_ID =
            UUID.fromString("7c3a0b2f-4e5d-5b8c-af30-2d7e9b1c4e52");

    public static int xp(LivingEntity unit) {
        return unit.getPersistentData().getInt(XP_KEY);
    }

    // Rank tier 0-5 from the unit's lifetime XP
    public static int rank(LivingEntity unit) {
        int xp = xp(unit);
        int rank = 0;
        for (int i = 0; i < THRESHOLDS.length; i++) {
            if (xp >= THRESHOLDS[i]) {
                rank = i;
            }
        }
        return rank;
    }

    public static String rankName(LivingEntity unit) {
        return RANK_NAMES[rank(unit)];
    }

    // Short prefix for nameplates / GUI titles ("Sgt. Jony")
    public static String rankTitle(LivingEntity unit) {
        return RANK_TITLES[rank(unit)];
    }

    // Score a kill for the shooter. Returns the XP that was awarded.
    public static int pointsFor(LivingEntity victim) {
        if (victim instanceof AbstractUnit || victim instanceof Player) {
            return 5;   // trained soldiers / players are worth the most
        }
        if (victim instanceof Enemy) {
            return 1;   // zombies, skeletons, illagers - cheap kills
        }
        return 0;
    }

    // Adds a kill's XP. Returns the new rank tier (for promotion feedback).
    public static int addKill(AbstractUnit killer, LivingEntity victim) {
        int gained = pointsFor(victim);
        if (gained > 0) {
            killer.getPersistentData().putInt(XP_KEY, xp(killer) + gained);
        }
        return rank(killer);
    }

    // Permanent rank bonuses: +2 max HP and +0.5 attack damage per tier.
    // Idempotent - removes the old modifier before re-adding.
    public static void applyRankBonuses(AbstractUnit unit) {
        int rank = rank(unit);
        AttributeInstance health = unit.getAttribute(Attributes.MAX_HEALTH);
        AttributeInstance damage = unit.getAttribute(Attributes.ATTACK_DAMAGE);

        if (health != null) {
            health.removeModifier(HEALTH_MODIFIER_ID);
            if (rank > 0) {
                health.addTransientModifier(new AttributeModifier(HEALTH_MODIFIER_ID,
                        "semcs_veterancy_health", rank * 2.0D, AttributeModifier.Operation.ADDITION));
                if (unit.getHealth() > health.getValue()) {
                    unit.setHealth((float) health.getValue());
                }
            }
        }
        if (damage != null) {
            damage.removeModifier(DAMAGE_MODIFIER_ID);
            if (rank > 0) {
                damage.addTransientModifier(new AttributeModifier(DAMAGE_MODIFIER_ID,
                        "semcs_veterancy_damage", rank * 0.5D, AttributeModifier.Operation.ADDITION));
            }
        }
    }

    // Accuracy bonus stacked onto the faction/class spread: -6% spread per rank
    // (max -30% at Elite). Multiplied onto the value UnitClassAttributes returns.
    public static double spreadScale(LivingEntity unit) {
        return Math.max(0.7D, 1.0D - rank(unit) * 0.06D);
    }
}
