package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.faction.ClassLoadouts;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import javax.annotation.Nullable;

// Always-on weapon management goal:
// 1. Swaps to a loaded backup sidearm / alternate gun when the held gun is completely out of ammo.
// 2. Swaps back from a CQB backup sidearm to the unit's primary weapon once combat ends or the
//    target is comfortably outside sidearm return distance and a usable primary exists.
// 3. Provides shared static swap helpers used by MixinTacticalManagerGoal and VdfGunAttackGoal.
public class UnitWeaponFallbackGoal extends Goal {

    public static final String SIDEARM_NBT = "semcs_backup_sidearm";
    public static final String PRIMARY_NBT = "semcs_primary_gun";
    private static final String LEGACY_STASHED_NBT = "semcs_stashed_primary";
    private static final String NEXT_SWAP_NBT = "semcs_next_swap_tick";
    private static final int STORAGE_START = 6;

    private final PathfinderMob mob;
    private int scanCooldown = 0;

    public UnitWeaponFallbackGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(java.util.EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        return true;
    }

    @Override
    public void tick() {
        if (this.mob.level().isClientSide || !this.mob.isAlive() || this.mob.isUsingItem()) {
            return;
        }
        if (--this.scanCooldown > 0) {
            return;
        }
        this.scanCooldown = 10;

        migrateLegacyStash(this.mob);

        ItemStack held = this.mob.getMainHandItem();
        IGun heldGun = IGun.getIGunOrNull(held);
        boolean holdingMelee = UnitMeleeAttackGoal.isMeleeWeapon(held);
        if (heldGun == null && !holdingMelee) {
            return;
        }

        WeaponClass assigned = WeaponClassSupport.get(this.mob);
        long now = this.mob.level().getGameTime();
        long nextSwap = this.mob.getPersistentData().getLong(NEXT_SWAP_NBT);

        boolean holdingBackup = assigned != null && assigned != WeaponClass.PISTOL
                && isCqbBackupItem(held, assigned);

        boolean heldHasAmmo = heldGun != null && hasUsableAmmo(this.mob, held);

        // 1. Out-of-combat or long-range return from backup (pistol/melee) -> primary gun.
        if (holdingBackup && now >= nextSwap) {
            LivingEntity target = this.mob.getTarget();
            boolean inCombat = target != null && target.isAlive();
            if (!inCombat) {
                if (hasUsablePrimary(this.mob, assigned) || (heldGun != null && !heldHasAmmo)) {
                    if (swapToPrimary(this.mob, assigned, now, true)) {
                        return;
                    }
                }
            } else {
                double distSq = this.mob.distanceToSqr(target);
                double drawDist = ClassSystemConfig.classSidearmDrawDistance(assigned);
                double returnDist = Math.max(14.0D, Math.max(drawDist + 4.0D,
                        ClassSystemConfig.classSidearmReturnDistance(assigned)));
                if (distSq >= returnDist * returnDist && hasReadyPrimaryForDistance(this.mob, assigned, distSq, returnDist)) {
                    if (swapToPrimary(this.mob, assigned, now, false)) {
                        return;
                    }
                }
            }
        }

        // 2. Completely dry held weapon fallback (mag empty AND no reserve ammo anywhere).
        if (heldGun == null || heldHasAmmo) {
            return;
        }
        if (holdingBackup) {
            swapToPrimary(this.mob, assigned, now, true);
        } else {
            swapToBackupOrArmed(this.mob, assigned, held, now);
        }
    }

    public static void migrateLegacyStash(PathfinderMob mob) {
        CompoundTag data = mob.getPersistentData();
        if (data.contains(LEGACY_STASHED_NBT)) {
            if (!data.contains(PRIMARY_NBT)) {
                data.put(PRIMARY_NBT, data.getCompound(LEGACY_STASHED_NBT).copy());
            }
            data.remove(LEGACY_STASHED_NBT);
        }
    }

    public static boolean isCqbBackupItem(ItemStack stack, @Nullable WeaponClass assigned) {
        if (stack.isEmpty()) {
            return false;
        }
        if (UnitMeleeAttackGoal.isMeleeWeapon(stack)) {
            return true;
        }
        if (assigned == WeaponClass.PISTOL) {
            return false;
        }
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun == null) {
            return false;
        }
        ResourceLocation gunId = iGun.getGunId(stack);
        WeaponClass gunCls = gunId != null ? ClassLoadouts.inferClass(gunId) : null;
        return gunCls == WeaponClass.PISTOL || (assigned != null && assigned != WeaponClass.SMG && gunCls == WeaponClass.SMG);
    }

    public static boolean hasUsableAmmo(LivingEntity mob, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        IGun g = IGun.getIGunOrNull(stack);
        if (g == null) {
            return false;
        }
        return g.getCurrentAmmoCount(stack) > 0
                || g.getDummyAmmoAmount(stack) > 0
                || g.hasInventoryAmmo(mob, stack, true);
    }

    public static boolean hasUsablePrimary(PathfinderMob mob, @Nullable WeaponClass assigned) {
        if (mob instanceof PmcUnitEntity) {
            var cap = mob.getCapability(ForgeCapabilities.ITEM_HANDLER);
            if (cap.isPresent() && cap.orElse(null) instanceof IItemHandlerModifiable modifiable) {
                for (int s = STORAGE_START; s < modifiable.getSlots(); s++) {
                    ItemStack st = modifiable.getStackInSlot(s);
                    if (!isCqbBackupItem(st, assigned) && hasUsableAmmo(mob, st)) {
                        return true;
                    }
                }
                ItemStack off = modifiable.getStackInSlot(1);
                return !isCqbBackupItem(off, assigned) && hasUsableAmmo(mob, off);
            }
            return false;
        }
        migrateLegacyStash(mob);
        CompoundTag data = mob.getPersistentData();
        if (!data.contains(PRIMARY_NBT)) {
            return false;
        }
        ItemStack st = ItemStack.of(data.getCompound(PRIMARY_NBT));
        return !isCqbBackupItem(st, assigned) && hasUsableAmmo(mob, st);
    }

    // Avoids ping-ponging back to an unloaded primary at mid-range if it swapped due to mid-fight reload:
    // require either a loaded magazine on the primary, OR distance >= max(returnDist, 20.0) blocks.
    public static boolean hasReadyPrimaryForDistance(PathfinderMob mob, @Nullable WeaponClass assigned,
                                                     double distSq, double returnDist) {
        ItemStack primary = peekPrimaryStack(mob, assigned);
        if (primary.isEmpty() || !hasUsableAmmo(mob, primary)) {
            return false;
        }
        IGun g = IGun.getIGunOrNull(primary);
        if (g != null && g.getCurrentAmmoCount(primary) > 0) {
            return distSq >= returnDist * returnDist;
        }
        double safeReloadDist = Math.max(returnDist, 20.0D);
        return distSq >= safeReloadDist * safeReloadDist;
    }

    public static boolean hasUsableBackup(PathfinderMob mob, @Nullable WeaponClass assigned) {
        if (mob instanceof PmcUnitEntity) {
            var cap = mob.getCapability(ForgeCapabilities.ITEM_HANDLER);
            if (cap.isPresent() && cap.orElse(null) instanceof IItemHandlerModifiable modifiable) {
                for (int s = STORAGE_START; s < modifiable.getSlots(); s++) {
                    ItemStack st = modifiable.getStackInSlot(s);
                    if (isCqbBackupItem(st, assigned) && (UnitMeleeAttackGoal.isMeleeWeapon(st) || hasUsableAmmo(mob, st))) {
                        return true;
                    }
                }
                ItemStack off = modifiable.getStackInSlot(1);
                return isCqbBackupItem(off, assigned) && (UnitMeleeAttackGoal.isMeleeWeapon(off) || hasUsableAmmo(mob, off));
            }
            return false;
        }
        CompoundTag data = mob.getPersistentData();
        if (!data.contains(SIDEARM_NBT)) {
            return false;
        }
        ItemStack st = ItemStack.of(data.getCompound(SIDEARM_NBT));
        return !st.isEmpty() && (UnitMeleeAttackGoal.isMeleeWeapon(st) || hasUsableAmmo(mob, st));
    }

    private static ItemStack peekPrimaryStack(PathfinderMob mob, @Nullable WeaponClass assigned) {
        if (mob instanceof PmcUnitEntity) {
            var cap = mob.getCapability(ForgeCapabilities.ITEM_HANDLER);
            if (cap.isPresent() && cap.orElse(null) instanceof IItemHandlerModifiable modifiable) {
                for (int s = STORAGE_START; s < modifiable.getSlots(); s++) {
                    ItemStack st = modifiable.getStackInSlot(s);
                    if (IGun.getIGunOrNull(st) != null && !isCqbBackupItem(st, assigned)) {
                        return st;
                    }
                }
                ItemStack off = modifiable.getStackInSlot(1);
                if (IGun.getIGunOrNull(off) != null && !isCqbBackupItem(off, assigned)) {
                    return off;
                }
            }
            return ItemStack.EMPTY;
        }
        CompoundTag data = mob.getPersistentData();
        return data.contains(PRIMARY_NBT) ? ItemStack.of(data.getCompound(PRIMARY_NBT)) : ItemStack.EMPTY;
    }

    public static boolean canSwapNow(PathfinderMob mob, long now) {
        return !mob.isUsingItem() && now >= mob.getPersistentData().getLong(NEXT_SWAP_NBT);
    }

    public static boolean swapToBackup(PathfinderMob mob, @Nullable WeaponClass assigned, long now) {
        return swapToBackupOrArmed(mob, assigned, mob.getMainHandItem(), now);
    }

    public static boolean swapToBackupOrArmed(PathfinderMob mob, @Nullable WeaponClass assigned,
                                              ItemStack currentMain, long now) {
        if (mob instanceof PmcUnitEntity) {
            var cap = mob.getCapability(ForgeCapabilities.ITEM_HANDLER);
            if (cap.isPresent() && cap.orElse(null) instanceof IItemHandlerModifiable handler) {
                int bestSlot = -1;
                int bestScore = -1;
                for (int slot = STORAGE_START; slot < handler.getSlots(); slot++) {
                    ItemStack stack = handler.getStackInSlot(slot);
                    int score = scoreSwapCandidate(mob, stack, assigned, true);
                    if (score > bestScore && score > 0) {
                        bestScore = score;
                        bestSlot = slot;
                    }
                }
                if (bestSlot < 0) {
                    int offScore = scoreSwapCandidate(mob, handler.getStackInSlot(1), assigned, true);
                    if (offScore > 0) {
                        bestSlot = 1;
                    }
                }
                if (bestSlot >= 0) {
                    ItemStack replacement = handler.getStackInSlot(bestSlot).copy();
                    handler.setStackInSlot(bestSlot, currentMain.copy());
                    handler.setStackInSlot(0, replacement);
                    mob.setItemSlot(EquipmentSlot.MAINHAND, replacement);
                    mob.getPersistentData().putLong(NEXT_SWAP_NBT, now + 60L);
                    return true;
                }
            }
            return false;
        }

        migrateLegacyStash(mob);
        CompoundTag data = mob.getPersistentData();
        if (data.contains(SIDEARM_NBT)) {
            ItemStack sidearm = ItemStack.of(data.getCompound(SIDEARM_NBT));
            if (!sidearm.isEmpty() && (UnitMeleeAttackGoal.isMeleeWeapon(sidearm) || hasUsableAmmo(mob, sidearm))) {
                data.put(PRIMARY_NBT, currentMain.copy().save(new CompoundTag()));
                data.remove(SIDEARM_NBT);
                mob.setItemInHand(InteractionHand.MAIN_HAND, sidearm);
                IGunOperator op = IGunOperator.fromLivingEntity(mob);
                if (op != null) {
                    op.draw(() -> mob.getMainHandItem());
                }
                data.putLong(NEXT_SWAP_NBT, now + 60L);
                return true;
            }
        }
        return false;
    }

    public static boolean swapToPrimary(PathfinderMob mob, @Nullable WeaponClass assigned,
                                        long now, boolean allowDryFallback) {
        if (mob instanceof PmcUnitEntity) {
            var cap = mob.getCapability(ForgeCapabilities.ITEM_HANDLER);
            if (cap.isPresent() && cap.orElse(null) instanceof IItemHandlerModifiable handler) {
                int bestSlot = -1;
                int bestScore = -1;
                for (int slot = STORAGE_START; slot < handler.getSlots(); slot++) {
                    ItemStack stack = handler.getStackInSlot(slot);
                    if (IGun.getIGunOrNull(stack) == null || isCqbBackupItem(stack, assigned)) {
                        continue;
                    }
                    int score = scoreSwapCandidate(mob, stack, assigned, false);
                    if (score > bestScore && (allowDryFallback || score > 0)) {
                        bestScore = score;
                        bestSlot = slot;
                    }
                }
                if (bestSlot < 0) {
                    ItemStack off = handler.getStackInSlot(1);
                    if (IGun.getIGunOrNull(off) != null && !isCqbBackupItem(off, assigned)) {
                        int offScore = scoreSwapCandidate(mob, off, assigned, false);
                        if (allowDryFallback || offScore > 0) {
                            bestSlot = 1;
                        }
                    }
                }
                if (bestSlot >= 0) {
                    ItemStack replacement = handler.getStackInSlot(bestSlot).copy();
                    handler.setStackInSlot(bestSlot, mob.getMainHandItem().copy());
                    handler.setStackInSlot(0, replacement);
                    mob.setItemSlot(EquipmentSlot.MAINHAND, replacement);
                    mob.getPersistentData().putLong(NEXT_SWAP_NBT, now + 60L);
                    maybeTriggerOutOfCombatReload(mob, replacement);
                    return true;
                }
            }
            return false;
        }

        migrateLegacyStash(mob);
        CompoundTag data = mob.getPersistentData();
        if (data.contains(PRIMARY_NBT)) {
            ItemStack primary = ItemStack.of(data.getCompound(PRIMARY_NBT));
            if (!primary.isEmpty() && (allowDryFallback || hasUsableAmmo(mob, primary))) {
                ItemStack prevBackup = mob.getMainHandItem().copy();
                data.put(SIDEARM_NBT, prevBackup.save(new CompoundTag()));
                data.remove(PRIMARY_NBT);
                mob.setItemInHand(InteractionHand.MAIN_HAND, primary);
                IGunOperator op = IGunOperator.fromLivingEntity(mob);
                if (op != null) {
                    op.draw(() -> mob.getMainHandItem());
                    maybeTriggerOutOfCombatReload(mob, primary);
                }
                data.putLong(NEXT_SWAP_NBT, now + 60L);
                return true;
            }
        }
        return false;
    }

    private static void maybeTriggerOutOfCombatReload(PathfinderMob mob, ItemStack swappedPrimary) {
        if (mob.getTarget() != null && mob.getTarget().isAlive()) {
            return;
        }
        IGun g = IGun.getIGunOrNull(swappedPrimary);
        if (g != null && g.getCurrentAmmoCount(swappedPrimary) <= 0
                && (g.getDummyAmmoAmount(swappedPrimary) > 0 || g.hasInventoryAmmo(mob, swappedPrimary, true))) {
            IGunOperator op = IGunOperator.fromLivingEntity(mob);
            if (op != null) {
                op.reload();
            }
        }
    }

    private static int scoreSwapCandidate(LivingEntity mob, ItemStack stack,
                                          @Nullable WeaponClass assigned, boolean preferBackup) {
        IGun g = IGun.getIGunOrNull(stack);
        if (g == null) {
            return (preferBackup && UnitMeleeAttackGoal.isMeleeWeapon(stack)) ? 1 : -1;
        }
        int ammoScore = g.getCurrentAmmoCount(stack)
                + (g.hasInventoryAmmo(mob, stack, true) ? 1000 : 0)
                + Math.min(g.getDummyAmmoAmount(stack), 999);
        if (ammoScore <= 0) {
            return 0;
        }
        boolean isBackup = isCqbBackupItem(stack, assigned);
        if (preferBackup) {
            return isBackup ? ammoScore + 5000 : ammoScore;
        }
        return !isBackup ? ammoScore + 5000 : ammoScore;
    }
}
