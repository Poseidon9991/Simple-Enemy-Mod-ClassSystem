package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.event.UnitMarkerTracker;
import com.poseidon.sem_classsystem.faction.UnitClass;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

// Medic class behaviour: the unit keeps fighting normally, but when a
// nearby ally is hurt it moves in and patches them up. Bandages/medkits in
// the medic's own inventory are consumed for a big heal (8 HP); with no
// supplies left it performs a slower free field dressing (3 HP).
//
// Eligible patients: same-faction units, SEM allies (PMC<->BHQ), and any
// unit sharing the medic's owner (a player's whole squad heals each other).
public class UnitMedicHealGoal extends Goal {

    private static final double SCAN_RANGE = 24.0D;
    private static final double HEAL_RANGE_SQR = 3.5D * 3.5D;
    private static final int HEAL_INTERVAL = 25;      // ticks between heal pulses
    private static final int RESCAN_TICKS = 15;

    private final PathfinderMob mob;
    private LivingEntity patient;
    private int healTicks = 0;
    private int scanCooldown = 0;

    public UnitMedicHealGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (WeaponClassSupport.get(this.mob) != WeaponClass.MEDIC) {
            return false;
        }
        if (this.scanCooldown > 0) {
            this.scanCooldown--;
            return false;
        }
        this.scanCooldown = RESCAN_TICKS;
        this.patient = findPatient();
        return this.patient != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.patient == null || !this.patient.isAlive()
                || this.mob.distanceToSqr(this.patient) >= SCAN_RANGE * SCAN_RANGE) {
            return false;
        }
        return this.patient.getHealth() < this.patient.getMaxHealth() - 0.5F;
    }

    @Override
    public void start() {
        this.healTicks = HEAL_INTERVAL / 2;
    }

    @Override
    public void stop() {
        this.patient = null;
    }

    @Override
    public void tick() {
        if (this.patient == null) {
            return;
        }
        this.mob.getLookControl().setLookAt(this.patient, 30.0F, 30.0F);

        if (this.mob.distanceToSqr(this.patient) > HEAL_RANGE_SQR) {
            if (this.mob.getNavigation().isDone()) {
                this.mob.getNavigation().moveTo(this.patient, 1.0D);
            }
            return;
        }
        this.mob.getNavigation().stop();

        if (--this.healTicks > 0) {
            return;
        }
        this.healTicks = HEAL_INTERVAL;
        performHeal(this.patient);
    }

    private void performHeal(LivingEntity patient) {
        boolean usedKit = consumeMedicalKit();
        patient.heal(usedKit ? 8.0F : 3.0F);
        if (patient.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.HEART,
                    patient.getX(), patient.getY() + patient.getBbHeight() + 0.3D, patient.getZ(),
                    usedKit ? 5 : 2, 0.3D, 0.3D, 0.3D, 0.02D);
        }
        patient.level().playSound(null, patient.blockPosition(),
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.NEUTRAL, 0.6F, 1.6F);
    }

    // Uses one bandage/medkit/potion from the medic's storage for a big heal;
    // food and buff items are left alone (the unit eats those itself).
    private boolean consumeMedicalKit() {
        var cap = this.mob.getCapability(ForgeCapabilities.ITEM_HANDLER);
        if (!cap.isPresent() || !(cap.orElse(null) instanceof IItemHandlerModifiable handler)) {
            return false;
        }
        for (int slot = 6; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty() && UnitMarkerTracker.isMedicalItem(stack)) {
                stack.shrink(1);
                handler.setStackInSlot(slot, stack);
                return true;
            }
        }
        return false;
    }

    @Nullable
    private LivingEntity findPatient() {
        UnitClass myFaction = UnitClass.of(this.mob);
        java.util.UUID owner = this.mob instanceof PmcUnitEntity pmc ? pmc.getOwnerUUID() : null;

        List<LivingEntity> allies = this.mob.level().getEntitiesOfClass(
                LivingEntity.class,
                this.mob.getBoundingBox().inflate(SCAN_RANGE),
                e -> {
                    if (e == this.mob || !e.isAlive()) {
                        return false;
                    }
                    if (e.getHealth() >= e.getMaxHealth() - 0.5F) {
                        return false;
                    }
                    if (owner != null && e instanceof PmcUnitEntity pmc && owner.equals(pmc.getOwnerUUID())) {
                        return true;
                    }
                    if (!(e instanceof AbstractUnit)) {
                        // Medics also patch up their owner player
                        return owner != null && e instanceof Player p && p.getUUID().equals(owner);
                    }
                    if (this.mob.isAlliedTo(e)) {
                        return true;
                    }
                    UnitClass other = UnitClass.of(e);
                    return other != null && other == myFaction;
                });

        LivingEntity worst = null;
        double worstPct = 0.85D;
        for (LivingEntity ally : allies) {
            double pct = ally.getHealth() / ally.getMaxHealth();
            if (pct < worstPct) {
                worstPct = pct;
                worst = ally;
            }
        }
        return worst;
    }
}
