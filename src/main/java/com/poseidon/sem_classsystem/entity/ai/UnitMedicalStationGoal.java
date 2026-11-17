package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.block.MedicalStationBlockEntity;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.needs.UnitNeeds;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.EnumSet;

// Out-of-combat Post-Fight Recovery & Medic/IFAK Restock:
// Owned units that are wounded, low on Medic charges, or carrying a damaged
// IFAK Pouch walk to a stocked Field Medical Station in the owner's claimed
// chunks to heal, boost morale, restock Medic charges, and recharge IFAK doses.
public class UnitMedicalStationGoal extends Goal {

    private static final int RESCAN_TICKS = 80;
    private static final double REACH_SQR = 2.8D * 2.8D;

    private final PmcUnitEntity unit;
    private int rescanTicks = 0;
    private BlockPos stationPos;

    public UnitMedicalStationGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.rescanTicks > 0) {
            this.rescanTicks--;
            return false;
        }
        this.rescanTicks = RESCAN_TICKS;
        if (this.unit.getOwnerUUID() == null || !this.unit.isAlive()
                || this.unit.isSleeping() || this.unit.isUsingItem()
                || this.unit.getTarget() != null) {
            return false;
        }
        if (!needsMedicalAttention()) {
            return false;
        }
        this.stationPos = StockpileSearch.findBlockEntity(this.unit, MedicalStationBlockEntity.class,
                MedicalStationBlockEntity::hasMedicalSupply);
        return this.stationPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.unit.isAlive() && this.unit.getTarget() == null
                && !this.unit.isSleeping() && this.stationPos != null;
    }

    @Override
    public void start() {
        if (this.stationPos != null) {
            this.unit.getNavigation().moveTo(
                    this.stationPos.getX() + 0.5, this.stationPos.getY(),
                    this.stationPos.getZ() + 0.5, 1.05D);
        }
    }

    @Override
    public void tick() {
        if (this.stationPos == null || !(this.unit.level() instanceof ServerLevel level)) {
            return;
        }
        if (this.unit.distanceToSqr(this.stationPos.getX() + 0.5,
                this.stationPos.getY() + 0.5, this.stationPos.getZ() + 0.5) <= REACH_SQR) {
            if (level.getBlockEntity(this.stationPos) instanceof MedicalStationBlockEntity station) {
                treatAtStation(level, station);
            }
            this.stationPos = null;
        } else if (this.unit.getNavigation().isDone()) {
            this.unit.getNavigation().moveTo(
                    this.stationPos.getX() + 0.5, this.stationPos.getY(),
                    this.stationPos.getZ() + 0.5, 1.05D);
        }
    }

    @Override
    public void stop() {
        this.stationPos = null;
        this.rescanTicks = RESCAN_TICKS;
    }

    private boolean needsMedicalAttention() {
        if (this.unit.getHealth() < this.unit.getMaxHealth() * 0.85F
                || this.unit.hasEffect(MobEffects.POISON)
                || this.unit.hasEffect(MobEffects.WITHER)) {
            return true;
        }
        if (WeaponClassSupport.get(this.unit) == WeaponClass.MEDIC
                && this.unit.getPersistentData().getInt("semcs_medic_charges") < 6) {
            return true;
        }
        return hasDamagedIfak();
    }

    private void treatAtStation(ServerLevel level, MedicalStationBlockEntity station) {
        if (!station.consumeMedicalSupply()) {
            return;
        }
        if (this.unit.getHealth() < this.unit.getMaxHealth()) {
            this.unit.heal(8.0F);
            this.unit.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0));
        }
        this.unit.removeEffect(MobEffects.POISON);
        this.unit.removeEffect(MobEffects.WITHER);
        if (ClassSystemConfig.needsEnabled()) {
            UnitNeeds.addMorale(this.unit, 8);
        }
        if (WeaponClassSupport.get(this.unit) == WeaponClass.MEDIC) {
            CompoundTag data = this.unit.getPersistentData();
            data.putInt("semcs_medic_charges", Math.max(12, data.getInt("semcs_medic_charges")));
        }
        rechargeCarriedIfaks();

        this.unit.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7F, 1.35F);
        level.sendParticles(ParticleTypes.HEART,
                this.unit.getX(), this.unit.getEyeY() + 0.3D, this.unit.getZ(),
                5, 0.25D, 0.2D, 0.25D, 0.02D);
    }

    private boolean hasDamagedIfak() {
        var cap = this.unit.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isPresent()) {
            for (int i = 0; i < cap.get().getSlots(); i++) {
                ItemStack s = cap.get().getStackInSlot(i);
                if (s.is(SemcsRegistries.IFAK_POUCH.get()) && s.isDamaged()) {
                    return true;
                }
            }
        }
        if (ModList.get().isLoaded("curios")) {
            return CuriosIfakHelper.hasDamagedIfak(this.unit);
        }
        return false;
    }

    private void rechargeCarriedIfaks() {
        var cap = this.unit.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isPresent() && cap.get() instanceof IItemHandlerModifiable mod) {
            for (int i = 0; i < mod.getSlots(); i++) {
                ItemStack s = mod.getStackInSlot(i);
                if (s.is(SemcsRegistries.IFAK_POUCH.get()) && s.isDamaged()) {
                    s.setDamageValue(0);
                    mod.setStackInSlot(i, s);
                }
            }
        }
        if (ModList.get().isLoaded("curios")) {
            CuriosIfakHelper.rechargeIfaks(this.unit);
        }
    }

    // Isolated behind ModList.isLoaded("curios") so Curios classes never load when absent.
    private static final class CuriosIfakHelper {
        static boolean hasDamagedIfak(PmcUnitEntity unit) {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(unit).resolve();
            if (opt.isEmpty()) {
                return false;
            }
            for (var handler : opt.get().getCurios().values()) {
                var stacks = handler.getStacks();
                for (int i = 0; i < stacks.getSlots(); i++) {
                    ItemStack s = stacks.getStackInSlot(i);
                    if (s.is(SemcsRegistries.IFAK_POUCH.get()) && s.isDamaged()) {
                        return true;
                    }
                }
            }
            return false;
        }

        static void rechargeIfaks(PmcUnitEntity unit) {
            var opt = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(unit).resolve();
            if (opt.isEmpty()) {
                return;
            }
            for (var handler : opt.get().getCurios().values()) {
                var stacks = handler.getStacks();
                for (int i = 0; i < stacks.getSlots(); i++) {
                    ItemStack s = stacks.getStackInSlot(i);
                    if (s.is(SemcsRegistries.IFAK_POUCH.get()) && s.isDamaged()) {
                        s.setDamageValue(0);
                        stacks.setStackInSlot(i, s);
                    }
                }
            }
        }
    }
}
