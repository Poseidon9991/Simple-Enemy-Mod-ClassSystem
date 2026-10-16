package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.needs.UnitNeeds;
import com.poseidon.sem_classsystem.world.PlayerChunkClaims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Hunger handler for owned units ([needs].enabled). A hungry unit first eats
// real food it carries (same inventory/bag/Curios pipeline as self-heal); if
// it carries nothing edible it walks to the nearest container inside the
// OWNER'S claimed chunks and pulls a ration out of the stockpile.
//
// Runs only while out of combat - units under fire don't stop for lunch.
// Priority 3: below combat/self-heal (1) and duty (2) so a fighting or
// posted unit finishes its job before breaking for chow... except it must
// yield to nothing hungrier than the mission.
public class UnitEatGoal extends Goal {

    private static final int EAT_AT = 14;            // hunger level that triggers eating
    private static final int USE_TICKS = 28;
    private static final int COOLDOWN_TICKS = 60;
    private static final int RESCAN_TICKS = 40;
    private static final double REACH_SQR = 2.6 * 2.6;

    private final PmcUnitEntity unit;
    private int useTicks = 0;
    private int cooldown = 0;
    private int rescanTicks = 0;
    private ItemStack visualStack = ItemStack.EMPTY;
    private java.util.function.Supplier<ItemStack> consumeAction;
    private BlockPos stockpile;                      // container being walked to
    private boolean swappedOffhand = false;

    public UnitEatGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        if (this.rescanTicks > 0) {
            this.rescanTicks--;
        }
        if (!UnitNeeds.appliesTo(this.unit) || !this.unit.isAlive()
                || this.unit.isSleeping() || this.unit.isUsingItem()
                || this.unit.getTarget() != null) {
            return false;
        }
        if (UnitNeeds.hunger(this.unit) > EAT_AT) {
            return false;
        }
        // Carried food? Eat on the spot.
        UnitSelfHealGoal.FoundConsumable found = UnitSelfHealGoal.findFood(this.unit);
        if (found != null) {
            this.visualStack = found.preview().copy();
            this.visualStack.setCount(1);
            this.consumeAction = found.consumeOne();
            this.stockpile = null;
            return true;
        }
        // Nothing on the body - head for a stockpile inside the claims.
        this.stockpile = findStockpile();
        return this.stockpile != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.unit.isAlive() || this.unit.getTarget() != null) {
            return false;
        }
        if (this.consumeAction != null) {
            return this.useTicks < USE_TICKS;
        }
        // Stockpile run: keep going while the container still holds food.
        return this.stockpile != null && UnitNeeds.hunger(this.unit) <= EAT_AT;
    }

    @Override
    public void start() {
        this.useTicks = 0;
        if (this.consumeAction != null) {
            // Show the meal in the offhand like the self-heal goal does.
            ItemStack off = this.unit.getItemBySlot(EquipmentSlot.OFFHAND);
            if (off.isEmpty()) {
                this.swappedOffhand = true;
                this.unit.setItemSlot(EquipmentSlot.OFFHAND, this.visualStack.copy());
            }
            this.unit.startUsingItem(InteractionHand.OFF_HAND);
        } else if (this.stockpile != null) {
            this.unit.getNavigation().moveTo(
                    this.stockpile.getX() + 0.5, this.stockpile.getY(),
                    this.stockpile.getZ() + 0.5, 1.0D);
        }
    }

    @Override
    public void tick() {
        if (this.consumeAction != null) {
            tickEating();
            return;
        }
        // Walking to a stockpile: arrived -> pull one food item and eat it.
        if (this.stockpile == null) {
            return;
        }
        if (this.unit.distanceToSqr(this.stockpile.getX() + 0.5,
                this.stockpile.getY() + 0.5, this.stockpile.getZ() + 0.5) <= REACH_SQR) {
            ItemStack ration = takeFood(this.unit.level() instanceof ServerLevel sl
                    ? sl : null, this.stockpile);
            if (ration.isEmpty()) {
                this.stockpile = null;   // cupboard's bare - give up
                return;
            }
            finishMeal(ration);
        } else if (this.unit.getNavigation().isDone()) {
            this.unit.getNavigation().moveTo(
                    this.stockpile.getX() + 0.5, this.stockpile.getY(),
                    this.stockpile.getZ() + 0.5, 1.0D);
        }
    }

    private void tickEating() {
        this.useTicks++;
        if (!this.unit.isUsingItem()) {
            this.unit.startUsingItem(InteractionHand.OFF_HAND);
        }
        if (this.useTicks % 4 == 0) {
            this.unit.playSound(SoundEvents.GENERIC_EAT, 0.7F,
                    0.9F + this.unit.getRandom().nextFloat() * 0.2F);
            if (this.unit.level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(
                        new ItemParticleOption(ParticleTypes.ITEM, this.visualStack),
                        this.unit.getX(), this.unit.getEyeY() - 0.25D, this.unit.getZ(),
                        3, 0.12D, 0.08D, 0.12D, 0.02D);
            }
        }
        if (this.useTicks >= USE_TICKS && this.consumeAction != null) {
            ItemStack eaten = this.consumeAction.get();
            if (!eaten.isEmpty()) {
                finishMeal(eaten);
            }
            this.consumeAction = null;
        }
    }

    // Shared "swallowed it" path for both carried food and stockpile pulls:
    // fills hunger, small morale bump, and the item's own eat effects still
    // run (golden apple absorption etc.).
    private void finishMeal(ItemStack eaten) {
        try {
            eaten.copy().finishUsingItem(this.unit.level(), this.unit);
        } catch (Throwable ignored) {
        }
        UnitNeeds.feed(this.unit, eaten);
        this.unit.playSound(SoundEvents.PLAYER_BURP, 0.6F,
                0.9F + this.unit.getRandom().nextFloat() * 0.2F);
        this.stop();
    }

    @Override
    public void stop() {
        this.unit.stopUsingItem();
        if (this.swappedOffhand) {
            this.unit.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            this.swappedOffhand = false;
        }
        this.visualStack = ItemStack.EMPTY;
        this.consumeAction = null;
        this.stockpile = null;
        this.cooldown = COOLDOWN_TICKS;
    }

    // ---- stockpile -----------------------------------------------------------

    // Nearest container with edible food inside the owner's claimed chunks.
    // Chests, barrels, shulker crates, guard-post storage - anything that
    // exposes ForgeCapabilities.ITEM_HANDLER counts as a pantry.
    private BlockPos findStockpile() {
        if (!(this.unit.level() instanceof ServerLevel level)) {
            return null;
        }
        UUID owner = this.unit.getOwnerUUID();
        if (owner == null) {
            return null;
        }
        if (this.rescanTicks > 0) {
            return null;
        }
        this.rescanTicks = RESCAN_TICKS;
        String dim = level.dimension().location().toString();
        Set<Long> claims = PlayerChunkClaims.get(level.getServer().overworld())
                .allIn(dim).get(owner);
        if (claims == null || claims.isEmpty()) {
            return null;
        }
        int radius = com.poseidon.sem_classsystem.config.ClassSystemConfig
                .needsSearchRadius();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (long chunkLong : claims) {
            int cx = ChunkPos.getX(chunkLong);
            int cz = ChunkPos.getZ(chunkLong);
            // Cheap reject: chunk centre farther than the search radius.
            double cdx = (cx << 4) + 8 - this.unit.getX();
            double cdz = (cz << 4) + 8 - this.unit.getZ();
            if (cdx * cdx + cdz * cdz > (radius + 16.0) * (radius + 16.0)) {
                continue;
            }
            var chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
            if (!(chunk instanceof net.minecraft.world.level.chunk.LevelChunk levelChunk)) {
                continue;
            }
            for (Map.Entry<BlockPos, BlockEntity> be : levelChunk.getBlockEntities().entrySet()) {
                double dist = this.unit.distanceToSqr(
                        be.getKey().getX() + 0.5, be.getKey().getY() + 0.5,
                        be.getKey().getZ() + 0.5);
                if (dist >= bestDist || dist > radius * radius) {
                    continue;
                }
                if (containerHasFood(be.getValue(), this.unit)) {
                    best = be.getKey().immutable();
                    bestDist = dist;
                }
            }
        }
        return best;
    }

    private static boolean containerHasFood(BlockEntity be, PmcUnitEntity unit) {
        var cap = be.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isEmpty()) {
            return false;
        }
        var handler = cap.get();
        for (int i = 0; i < handler.getSlots(); i++) {
            if (UnitSelfHealGoal.isFoodItem(handler.getStackInSlot(i), unit)) {
                return true;
            }
        }
        return false;
    }

    private ItemStack takeFood(ServerLevel level, BlockPos pos) {
        if (level == null) {
            return ItemStack.EMPTY;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return ItemStack.EMPTY;
        }
        var cap = be.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        if (cap.isEmpty()) {
            return ItemStack.EMPTY;
        }
        var handler = cap.get();
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (UnitSelfHealGoal.isFoodItem(stack, this.unit)) {
                return handler.extractItem(i, 1, false);
            }
        }
        return ItemStack.EMPTY;
    }
}
