package com.poseidon.sem_classsystem.entity.ai;

import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

// Carried-ammo logistics for owned units - independent of [needs]. A TaCZ
// ammo box worn in a Curio slot (belt/back via data/curios/tags/items) is
// the unit's own mobile stockpile: when a carried gun has no matching
// rounds left in the pack, up to RESTOCK_ROUNDS are drawn straight out of
// the box as tacz:ammo stacks. No depot trip, no needs config - wearing
// the box IS the opt-in.
//
// Works in combat: the belt box is what a soldier is for. Runs on a
// 100-tick rescan like the other supply goals.
public class UnitAmmoBoxGoal extends Goal {

    private static final int RESCAN_TICKS = 100;
    private static final int RESTOCK_ROUNDS = 96;
    private static final ResourceLocation TACZ_AMMO_ITEM =
            ResourceLocation.parse("tacz:ammo");

    private final PmcUnitEntity unit;
    private int rescanTicks = 0;
    private IItemHandler carriedCurios;
    private Set<ResourceLocation> dryAmmoIds = Set.of();

    public UnitAmmoBoxGoal(PmcUnitEntity unit) {
        this.unit = unit;
    }

    @Override
    public boolean canUse() {
        if (this.rescanTicks > 0) {
            this.rescanTicks--;
            return false;
        }
        this.rescanTicks = RESCAN_TICKS;
        if (!this.unit.isAlive() || this.unit.getOwnerUUID() == null
                || this.unit.isSleeping() || this.unit.isUsingItem()) {
            return false;
        }
        this.dryAmmoIds = StockpileSearch.dryAmmoIds(this.unit);
        if (this.dryAmmoIds.isEmpty()) {
            return false;
        }
        this.carriedCurios = carriedCurios();
        if (this.carriedCurios == null || !boxHasWanted(this.carriedCurios)) {
            this.carriedCurios = null;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return false;   // instant draw inside start() - nothing to continue
    }

    @Override
    public void start() {
        int drawn = withdrawFromCarriedBoxes(this.carriedCurios, RESTOCK_ROUNDS);
        if (drawn > 0) {
            // Zipper/mag foley - same language as the other resupply pulls.
            this.unit.playSound(SoundEvents.ARMOR_EQUIP_IRON, 0.7F, 1.2F);
        }
        this.carriedCurios = null;
    }

    @Override
    public void stop() {
        this.carriedCurios = null;
        this.dryAmmoIds = Set.of();
        this.rescanTicks = RESCAN_TICKS;
    }

    // Curios is a soft dependency - reach for the worn-item handler
    // reflectively so the goal class-loads with zero warnings when the
    // mod is absent (same approach as UnitSelfHealGoal.scanCurios).
    private IItemHandler carriedCurios() {
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object lazy = api.getMethod("getCuriosInventory", LivingEntity.class)
                    .invoke(null, this.unit);
            if (!(lazy instanceof LazyOptional<?> lo) || !lo.isPresent()) {
                return null;
            }
            Object handler = lo.orElse(null);
            if (handler == null) {
                return null;
            }
            Object equipped = handler.getClass().getMethod("getEquippedCurios")
                    .invoke(handler);
            return equipped instanceof IItemHandler items ? items : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean boxHasWanted(IItemHandler curios) {
        for (int i = 0; i < curios.getSlots(); i++) {
            ItemStack stack = curios.getStackInSlot(i);
            if (stack.getItem() instanceof IAmmoBox box) {
                if (box.isAllTypeCreative(stack)) {
                    return true;
                }
                ResourceLocation id = box.getAmmoId(stack);
                if (id != null && this.dryAmmoIds.contains(id)
                        && (box.isCreative(stack) || box.getAmmoCount(stack) > 0)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Moves up to maxRounds of a needed ammo type out of worn ammo boxes
    // into the unit pack as tacz:ammo stacks (same shape stockpiles hand
    // out). Creative boxes never deplete; the all-type creative box feeds
    // whichever ammo id is wanted.
    private int withdrawFromCarriedBoxes(IItemHandler curios, int maxRounds) {
        Item ammoItem = ForgeRegistries.ITEMS.getValue(TACZ_AMMO_ITEM);
        if (ammoItem == null) {
            return 0;
        }
        IItemHandler pack = this.unit.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
        int moved = 0;
        for (int i = 0; i < curios.getSlots() && moved < maxRounds; i++) {
            ItemStack stack = curios.getStackInSlot(i);
            if (!(stack.getItem() instanceof IAmmoBox box)) {
                continue;
            }
            ResourceLocation wanted;
            if (box.isAllTypeCreative(stack)) {
                wanted = this.dryAmmoIds.iterator().next();
            } else {
                ResourceLocation id = box.getAmmoId(stack);
                wanted = id != null && this.dryAmmoIds.contains(id) ? id : null;
            }
            if (wanted == null) {
                continue;
            }
            int available = box.isCreative(stack) || box.isAllTypeCreative(stack)
                    ? maxRounds : box.getAmmoCount(stack);
            int take = Math.min(available, maxRounds - moved);
            if (take <= 0) {
                continue;
            }
            if (!box.isCreative(stack) && !box.isAllTypeCreative(stack)) {
                box.setAmmoCount(stack, available - take);
            }
            ItemStack drawn = new ItemStack(ammoItem, take);
            IAmmo ammo = IAmmo.getIAmmoOrNull(drawn);
            if (ammo == null) {
                break;
            }
            ammo.setAmmoId(drawn, wanted);
            ItemStack leftover = pack != null
                    ? ItemHandlerHelper.insertItemStacked(pack, drawn, false)
                    : drawn;
            if (!leftover.isEmpty()) {
                this.unit.spawnAtLocation(leftover);
            }
            moved += take;
        }
        return moved;
    }
}
