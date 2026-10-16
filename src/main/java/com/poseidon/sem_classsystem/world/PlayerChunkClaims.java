// World package - per-player chunk claims, persisted on the overworld
package com.poseidon.sem_classsystem.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Which player owns which chunk, per dimension. Claims are what unlock the
// map's "Guard chunk" / "Patrol chunk" orders for recruited units. Lives in
// overworld SavedData ("semcs_chunk_claims") so the whole record is one file.
public class PlayerChunkClaims extends SavedData {

    private static final String DATA_NAME = "semcs_chunk_claims";

    public enum ClaimResult {
        OK, ALREADY_MINE, TAKEN, FULL, NOT_ADJACENT
    }

    // dimLocation -> owner -> chunk longs
    private final Map<String, Map<UUID, Set<Long>>> claims = new HashMap<>();

    public static PlayerChunkClaims get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                PlayerChunkClaims::load, PlayerChunkClaims::new, DATA_NAME);
    }

    private static PlayerChunkClaims load(CompoundTag tag) {
        PlayerChunkClaims data = new PlayerChunkClaims();
        ListTag dims = tag.getList("dims", Tag.TAG_COMPOUND);
        for (Tag t : dims) {
            CompoundTag dimTag = (CompoundTag) t;
            String dim = dimTag.getString("name");
            Map<UUID, Set<Long>> owners = new HashMap<>();
            ListTag ownerList = dimTag.getList("owners", Tag.TAG_COMPOUND);
            for (Tag ot : ownerList) {
                CompoundTag ownerTag = (CompoundTag) ot;
                Set<Long> chunks = new HashSet<>();
                for (Tag ct : ownerTag.getList("chunks", Tag.TAG_LONG)) {
                    chunks.add(((LongTag) ct).getAsLong());
                }
                owners.put(ownerTag.getUUID("uuid"), chunks);
            }
            data.claims.put(dim, owners);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag dims = new ListTag();
        for (Map.Entry<String, Map<UUID, Set<Long>>> dimEntry : this.claims.entrySet()) {
            CompoundTag dimTag = new CompoundTag();
            dimTag.putString("name", dimEntry.getKey());
            ListTag ownerList = new ListTag();
            for (Map.Entry<UUID, Set<Long>> ownerEntry : dimEntry.getValue().entrySet()) {
                CompoundTag ownerTag = new CompoundTag();
                ownerTag.putUUID("uuid", ownerEntry.getKey());
                ListTag chunkList = new ListTag();
                for (Long pos : ownerEntry.getValue()) {
                    chunkList.add(LongTag.valueOf(pos));
                }
                ownerTag.put("chunks", chunkList);
                ownerList.add(ownerTag);
            }
            dimTag.put("owners", ownerList);
            dims.add(dimTag);
        }
        tag.put("dims", dims);
        return tag;
    }

    private Map<UUID, Set<Long>> dim(String dimension) {
        return this.claims.computeIfAbsent(dimension, d -> new HashMap<>());
    }

    // requireAdjacent: a new claim must edge-touch one of the player's own
    // claims in this dimension - the first claim there is always allowed.
    public ClaimResult claim(UUID owner, String dimension, int chunkX, int chunkZ,
                             int maxPerPlayer, boolean requireAdjacent) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        Map<UUID, Set<Long>> dimMap = dim(dimension);
        for (Map.Entry<UUID, Set<Long>> e : dimMap.entrySet()) {
            if (e.getValue().contains(key)) {
                return e.getKey().equals(owner) ? ClaimResult.ALREADY_MINE : ClaimResult.TAKEN;
            }
        }
        Set<Long> mine = dimMap.computeIfAbsent(owner, o -> new HashSet<>());
        if (mine.size() >= maxPerPlayer) {
            return ClaimResult.FULL;
        }
        if (requireAdjacent && !mine.isEmpty()
                && !mine.contains(ChunkPos.asLong(chunkX + 1, chunkZ))
                && !mine.contains(ChunkPos.asLong(chunkX - 1, chunkZ))
                && !mine.contains(ChunkPos.asLong(chunkX, chunkZ + 1))
                && !mine.contains(ChunkPos.asLong(chunkX, chunkZ - 1))) {
            return ClaimResult.NOT_ADJACENT;
        }
        mine.add(key);
        setDirty();
        return ClaimResult.OK;
    }

    public boolean unclaim(UUID owner, String dimension, int chunkX, int chunkZ) {
        Set<Long> mine = dim(dimension).get(owner);
        if (mine != null && mine.remove(ChunkPos.asLong(chunkX, chunkZ))) {
            setDirty();
            return true;
        }
        return false;
    }

    public boolean isClaimedBy(UUID owner, String dimension, int chunkX, int chunkZ) {
        Set<Long> mine = this.claims.getOrDefault(dimension, Map.of()).get(owner);
        return mine != null && mine.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    // The connected patch of the owner's claims containing (chunkX, chunkZ) -
    // orthogonal flood fill, chunk longs. Guard/patrol duty is assigned to the
    // whole patch, not just the clicked chunk. Empty when the chunk is not the
    // owner's.
    public long[] connectedRegion(UUID owner, String dimension, int chunkX, int chunkZ) {
        Set<Long> mine = this.claims.getOrDefault(dimension, Map.of()).get(owner);
        if (mine == null) {
            return new long[0];
        }
        long seed = ChunkPos.asLong(chunkX, chunkZ);
        if (!mine.contains(seed)) {
            return new long[0];
        }
        Set<Long> seen = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        seen.add(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            long c = queue.poll();
            int cx = ChunkPos.getX(c);
            int cz = ChunkPos.getZ(c);
            for (long n : new long[]{
                    ChunkPos.asLong(cx + 1, cz), ChunkPos.asLong(cx - 1, cz),
                    ChunkPos.asLong(cx, cz + 1), ChunkPos.asLong(cx, cz - 1)}) {
                if (mine.contains(n) && seen.add(n)) {
                    queue.add(n);
                }
            }
        }
        long[] out = new long[seen.size()];
        int i = 0;
        for (Long c : seen) {
            out[i++] = c;
        }
        return out;
    }

    public int claimCount(UUID owner, String dimension) {
        Set<Long> mine = this.claims.getOrDefault(dimension, Map.of()).get(owner);
        return mine == null ? 0 : mine.size();
    }

    // Every claim in a dimension: owner -> chunk longs (unmodifiable view not
    // needed - callers copy what they serialize).
    public Map<UUID, Set<Long>> allIn(String dimension) {
        return this.claims.getOrDefault(dimension, Map.of());
    }
}
