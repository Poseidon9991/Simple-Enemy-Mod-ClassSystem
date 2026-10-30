// Event package - hostile mobs treat SEM units as prey, not just players
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;                    // MODID for the subscriber annotation
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;              // militia identity check
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;           // SEM soldier base (PMC/BHQ/RU/US)
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Vanilla zombies proactively hunt players, villagers and golems only - SEM
// units are plain Monsters/PathfinderMobs and simply never appear in their
// target scan (they only fight back through HurtByTargetGoal). That is why a
// horde walks past a squad to reach the player.
//
// Fix: when a zombie joins the level, fold its priority-2 "nearest player"
// goal into a merged "nearest human" scan that also matches SEM units and VDF
// militia. Nearest-of-both wins: a closer soldier draws the zombie before a
// farther player, while the vanilla villager/golem goals stay untouched at
// their own lower priority.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class UnitHostilityEvents {

    private UnitHostilityEvents() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Zombie zombie)) {
            return;
        }
        // Zombie target layout: 1 = HurtByTargetGoal (retaliate), 2 =
        // NearestAttackableTargetGoal<Player>, 3 = villagers + golems,
        // 5 = turtle-egg stomping. Swap the priority-2 player goal for the
        // merged one - collect first, mutating the set mid-iteration is
        // a ConcurrentModificationException.
        java.util.List<Goal> playerGoals = new java.util.ArrayList<>();
        for (WrappedGoal wrapped : zombie.targetSelector.getAvailableGoals()) {
            if (wrapped.getPriority() == 2
                    && wrapped.getGoal() instanceof NearestAttackableTargetGoal) {
                playerGoals.add(wrapped.getGoal());
            }
        }
        for (Goal goal : playerGoals) {
            zombie.targetSelector.removeGoal(goal);
        }
        zombie.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                zombie, LivingEntity.class, 10, true, false,
                e -> e instanceof Player || e instanceof AbstractUnit
                        || e instanceof VdfUnitEntity));
    }
}
