package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.block.SupplyCrateBlockEntity;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.world.PlayerChunkClaims;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

// Shared stockpile search for the unit needs goals. A "stockpile" is any
// block entity inside the OWNER'S claimed chunks that exposes
// ForgeCapabilities.ITEM_HANDLER and holds a matching stack - chests,
// barrels, shulker crates, and our Ration Crate (which is preferred at
// equal distance so players can mark the official pantry).
//
// Only already-loaded chunks are touched: getChunk(..., FULL, false)
// returns null for anything not loaded, so searches never generate or
// force-load terrain.
final class StockpileSearch {

    private StockpileSearch() {
    }

    // Nearest claimed container holding at least one accepted stack.
    static BlockPos findContainer(PmcUnitEntity unit, Predicate<ItemStack> accepts) {
        if (!(unit.level() instanceof ServerLevel level)) {
            return null;
        }
        UUID owner = unit.getOwnerUUID();
        if (owner == null) {
            return null;
        }
        String dim = level.dimension().location().toString();
        Set<Long> claims = PlayerChunkClaims.get(level.getServer().overworld())
                .allIn(dim).get(owner);
        if (claims == null || claims.isEmpty()) {
            return null;
        }
        int radius = ClassSystemConfig.needsSearchRadius();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (long chunkLong : claims) {
            int cx = ChunkPos.getX(chunkLong);
            int cz = ChunkPos.getZ(chunkLong);
            // Cheap reject: chunk centre farther than the search radius.
            double cdx = (cx << 4) + 8 - unit.getX();
            double cdz = (cz << 4) + 8 - unit.getZ();
            if (cdx * cdx + cdz * cdz > (radius + 16.0) * (radius + 16.0)) {
                continue;
            }
            if (!(level.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) {
                continue;
            }
            for (Map.Entry<BlockPos, BlockEntity> be : chunk.getBlockEntities().entrySet()) {
                double dist = unit.distanceToSqr(
                        be.getKey().getX() + 0.5, be.getKey().getY() + 0.5,
                        be.getKey().getZ() + 0.5);
                if (dist > radius * radius) {
                    continue;
                }
                // Ration crates win ties - the "official" pantry.
                double score = be.getValue() instanceof SupplyCrateBlockEntity
                        ? dist * 0.5 : dist;
                if (score >= bestScore) {
                    continue;
                }
                if (containerHas(be.getValue(), accepts)) {
                    best = be.getKey().immutable();
                    bestScore = score;
                }
            }
        }
        return best;
    }

    static boolean containerHas(BlockEntity be, Predicate<ItemStack> accepts) {
        IItemHandler handler = handlerOf(be);
        if (handler == null) {
            return false;
        }
        for (int i = 0; i < handler.getSlots(); i++) {
            if (accepts.test(handler.getStackInSlot(i))) {
                return true;
            }
        }
        return false;
    }

    // Pull up to maxItems matching stacks out of the container and into the
    // unit's own ITEM_HANDLER; leftovers drop at the unit's feet so a full
    // inventory never silently deletes supplies. Returns true if anything
    // was moved.
    static boolean withdrawIntoUnit(PmcUnitEntity unit, BlockPos pos,
                                    Predicate<ItemStack> accepts, int maxItems) {
        if (!(unit.level() instanceof ServerLevel level)) {
            return false;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return false;
        }
        IItemHandler chest = handlerOf(be);
        if (chest == null) {
            return false;
        }
        IItemHandler pack = unit.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
        int moved = 0;
        for (int i = 0; i < chest.getSlots() && moved < maxItems; i++) {
            ItemStack stack = chest.getStackInSlot(i);
            if (stack.isEmpty() || !accepts.test(stack)) {
                continue;
            }
            int want = Math.min(stack.getCount(), maxItems - moved);
            ItemStack taken = chest.extractItem(i, want, false);
            if (taken.isEmpty()) {
                continue;
            }
            ItemStack leftover = pack != null
                    ? ItemHandlerHelper.insertItemStacked(pack, taken, false)
                    : taken;
            if (!leftover.isEmpty()) {
                unit.spawnAtLocation(leftover);
            }
            moved += taken.getCount();
        }
        return moved > 0;
    }

    private static IItemHandler handlerOf(BlockEntity be) {
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
    }
}
