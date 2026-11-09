package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.event.UnitMarkerTracker;
import com.poseidon.sem_classsystem.faction.UnitClass;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
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

// Interactive Medic class behaviour:
// When any nearby ally (or owner player) takes damage, the Medic sprints over,
// kneels beside the patient, pulls out an IFAK trauma kit in offhand, and plays
// a two-handed field-treatment animation with bandage audio and particles before
// restoring 10 HP + Regeneration II.
//
// Supply sources (checked in order):
//  1. Medical items / IFAK pouch doses in the unit's inventory (slots 6+)
//  2. Built-in "semcs_medic_charges" NBT reserve (12 charges issued at spawn)
//  3. Rechargeable field-dressing fallback (8s cooldown when physical supplies
//     are depleted) so a Medic never stands idle ignoring wounded teammates.
public class UnitMedicHealGoal extends Goal {

    private static final double SCAN_RANGE = 28.0D;
    private static final double HEAL_RANGE_SQR = 3.2D * 3.2D;
    private static final int TREAT_DURATION_TICKS = 30;   // 1.5s interactive kneeling treatment
    private static final int RESCAN_TICKS = 10;
    private static final int FIELD_TRIAGE_COOLDOWN = 160; // 8s fallback cooldown when out of kits

    private final PathfinderMob mob;
    private LivingEntity patient;
    private int treatTicks = 0;
    private int scanCooldown = 0;
    private int repathTicks = 0;
    private int fallbackCooldown = 0;
    private boolean swappedEmptyOffhand = false;
    private boolean kneelingForTriage = false;

    public UnitMedicHealGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.fallbackCooldown > 0) {
            this.fallbackCooldown--;
        }
        if (WeaponClassSupport.get(this.mob) != WeaponClass.MEDIC
                || !this.mob.isAlive()
                || !canPerformTriage()) {
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
                || !this.mob.isAlive()
                || this.mob.distanceToSqr(this.patient) >= SCAN_RANGE * SCAN_RANGE
                || !canPerformTriage()) {
            return false;
        }
        return this.patient.getHealth() < this.patient.getMaxHealth() - 0.25F;
    }

    @Override
    public boolean isInterruptable() {
        return false;
    }

    @Override
    public void start() {
        this.treatTicks = 0;
        this.repathTicks = 0;
        if (this.patient != null) {
            this.mob.getNavigation().moveTo(this.patient, 1.30D);
        }
    }

    @Override
    public void stop() {
        endTriagePose();
        this.mob.getNavigation().stop();
        this.patient = null;
        this.treatTicks = 0;
    }

    @Override
    public void tick() {
        if (this.patient == null || !this.patient.isAlive()) {
            return;
        }
        this.mob.getLookControl().setLookAt(this.patient, 35.0F, 35.0F);

        double distSqr = this.mob.distanceToSqr(this.patient);
        if (distSqr > HEAL_RANGE_SQR) {
            // Still sprinting to patient
            endTriagePose();
            if (--this.repathTicks <= 0 || this.mob.getNavigation().isDone()) {
                this.repathTicks = 8;
                this.mob.getNavigation().moveTo(this.patient, 1.30D);
            }
            return;
        }

        // In treatment range: stop moving, kneel beside patient, hold IFAK in offhand,
        // and animate two-handed field dressing.
        this.mob.getNavigation().stop();
        beginTriagePose();

        this.treatTicks++;
        if (this.treatTicks % 6 == 1) {
            this.mob.swing(InteractionHand.MAIN_HAND);
            this.mob.playSound(SoundEvents.WOOL_PLACE, 0.75F, 1.05F + this.mob.getRandom().nextFloat() * 0.2F);
            if (this.mob.level() instanceof ServerLevel serverLevel) {
                double mx = (this.mob.getX() + this.patient.getX()) * 0.5D;
                double my = this.patient.getY() + this.patient.getBbHeight() * 0.55D;
                double mz = (this.mob.getZ() + this.patient.getZ()) * 0.5D;
                ItemStack icon = new ItemStack(SemcsRegistries.IFAK_POUCH.get());
                serverLevel.sendParticles(
                        new ItemParticleOption(ParticleTypes.ITEM, icon),
                        mx, my, mz, 3, 0.15D, 0.12D, 0.15D, 0.02D);
                serverLevel.sendParticles(
                        ParticleTypes.HAPPY_VILLAGER,
                        mx, my + 0.15D, mz, 1, 0.2D, 0.15D, 0.2D, 0.01D);
            }
        }

        if (this.treatTicks >= TREAT_DURATION_TICKS) {
            this.treatTicks = 0;
            performHeal(this.patient);
        }
    }

    private void beginTriagePose() {
        if (!this.kneelingForTriage) {
            this.kneelingForTriage = true;
            this.mob.setShiftKeyDown(true);
        }
        if (this.mob.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) {
            this.swappedEmptyOffhand = true;
            this.mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(SemcsRegistries.IFAK_POUCH.get()));
        }
        if (!this.mob.isUsingItem()) {
            this.mob.startUsingItem(InteractionHand.OFF_HAND);
        }
    }

    private void endTriagePose() {
        if (this.mob.isUsingItem()) {
            this.mob.stopUsingItem();
        }
        if (this.swappedEmptyOffhand) {
            this.mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            this.swappedEmptyOffhand = false;
        }
        if (this.kneelingForTriage) {
            this.mob.setShiftKeyDown(false);
            this.kneelingForTriage = false;
        }
    }

    private void performHeal(LivingEntity patient) {
        if (!consumeMedicalKit()) {
            return;
        }
        patient.heal(10.0F);
        patient.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
        patient.removeEffect(MobEffects.POISON);
        patient.removeEffect(MobEffects.WITHER);

        if (patient.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.HEART,
                    patient.getX(), patient.getY() + patient.getBbHeight() + 0.25D, patient.getZ(),
                    6, 0.3D, 0.25D, 0.3D, 0.02D);
        }
        patient.level().playSound(null, patient.blockPosition(),
                SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.NEUTRAL, 0.9F, 1.15F);
        patient.level().playSound(null, patient.blockPosition(),
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.NEUTRAL, 0.55F, 1.5F);
    }

    // Consumes one medical supply dose (inventory item/IFAK -> NBT charge -> field-triage cooldown).
    private boolean consumeMedicalKit() {
        var cap = this.mob.getCapability(ForgeCapabilities.ITEM_HANDLER);
        if (cap.isPresent() && cap.orElse(null) instanceof IItemHandlerModifiable handler) {
            for (int slot = 6; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty() && isUsableMedSupply(stack)) {
                    if (stack.isDamageableItem()) {
                        int nextDmg = stack.getDamageValue() + 1;
                        if (nextDmg >= stack.getMaxDamage()) {
                            handler.setStackInSlot(slot, ItemStack.EMPTY);
                        } else {
                            stack.setDamageValue(nextDmg);
                            handler.setStackInSlot(slot, stack);
                        }
                    } else {
                        stack.shrink(1);
                        handler.setStackInSlot(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                    }
                    return true;
                }
            }
        }
        CompoundTag data = this.mob.getPersistentData();
        int charges = data.getInt("semcs_medic_charges");
        if (charges > 0) {
            data.putInt("semcs_medic_charges", charges - 1);
            return true;
        }
        if (this.fallbackCooldown <= 0) {
            this.fallbackCooldown = FIELD_TRIAGE_COOLDOWN;
            return true;
        }
        return false;
    }

    private boolean canPerformTriage() {
        if (hasMedicalSupplies()) {
            return true;
        }
        if (this.mob.getPersistentData().getInt("semcs_medic_charges") > 0) {
            return true;
        }
        return this.fallbackCooldown <= 0;
    }

    private boolean hasMedicalSupplies() {
        var cap = this.mob.getCapability(ForgeCapabilities.ITEM_HANDLER);
        if (!cap.isPresent() || !(cap.orElse(null) instanceof IItemHandlerModifiable handler)) {
            return false;
        }
        for (int slot = 6; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty() && isUsableMedSupply(stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUsableMedSupply(ItemStack stack) {
        return stack.is(SemcsRegistries.IFAK_POUCH.get()) || UnitMarkerTracker.isMedicalItem(stack);
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
                    if (!(e instanceof AbstractUnit)
                            && !(e instanceof com.poseidon.sem_classsystem.entity.VdfUnitEntity)) {
                        // Medics also patch up their owner player
                        return owner != null && e instanceof Player p && p.getUUID().equals(owner);
                    }
                    if (e instanceof com.poseidon.sem_classsystem.entity.VdfUnitEntity) {
                        return true;
                    }
                    if (this.mob.isAlliedTo(e)) {
                        return true;
                    }
                    UnitClass other = UnitClass.of(e);
                    return other != null && other == myFaction;
                });

        LivingEntity worst = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity ally : allies) {
            double pct = ally.getHealth() / Math.max(1.0F, ally.getMaxHealth());
            double distPenalty = this.mob.distanceTo(ally) * 0.01D;
            double score = pct + distPenalty;
            if (score < bestScore) {
                bestScore = score;
                worst = ally;
            }
        }
        return worst;
    }
}
