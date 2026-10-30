// Event package - hostile mobs treat SEM units as prey, not just players
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;                    // MODID for the subscriber annotation
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;              // militia identity check
import com.poseidon.sem_classsystem.mixin.MixinNearestAttackableTargetGoal;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;           // SEM soldier base (PMC/BHQ/RU/US)
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Vanilla hostile mobs proactively hunt players, villagers and golems only -
// SEM units are plain Monsters/PathfinderMobs and simply never appear in
// their target scan (they only fight back through HurtByTargetGoal). That is
// why a horde walks past a squad to reach the player.
//
// Fix: when a hostile mob joins the level, find its "hunt players"
// NearestAttackableTargetGoal (identified by the goal's own target class -
// villagers/golems/turtles keep theirs) and swap it for a merged
// nearest-human scan that also matches SEM units and VDF militia, kept at
// the same priority. Nearest-of-both wins: a closer soldier draws the mob
// before a farther player. Runs for every Enemy-tagged mob - zombies and
// variants, skeletons, spiders, creepers, slimes, witches, illagers and most
// modded hostiles get it for free. Mobs with bespoke player goals (phantom,
// ghast, enderman) are untouched, as are SEM's own units.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class UnitHostilityEvents {

    private UnitHostilityEvents() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (!(mob instanceof Enemy) || mob instanceof AbstractUnit || mob instanceof VdfUnitEntity) {
            return;
        }
        // Find every "hunt players" goal (collect first - mutating the goal
        // set mid-iteration is a ConcurrentModificationException) and replace
        // each with a merged players+units scan at the same priority.
        List<WrappedGoal> hits = new ArrayList<>();
        for (WrappedGoal wrapped : mob.targetSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof NearestAttackableTargetGoal<?> natg
                    && ((MixinNearestAttackableTargetGoal) natg).semcs$getTargetType() == Player.class) {
                hits.add(wrapped);
            }
        }
        for (WrappedGoal wrapped : hits) {
            mob.targetSelector.removeGoal(wrapped.getGoal());
            mob.targetSelector.addGoal(wrapped.getPriority(), new NearestAttackableTargetGoal<>(
                    mob, LivingEntity.class, 10, true, false,
                    e -> e instanceof Player || e instanceof AbstractUnit
                            || e instanceof VdfUnitEntity));
        }
    }
}
