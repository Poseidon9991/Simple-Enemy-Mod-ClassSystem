package com.poseidon.sem_classsystem.block;

import com.poseidon.sem_classsystem.entity.ai.UnitChunkDutyGoal;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import javax.annotation.Nullable;
import java.util.UUID;

// The guard post's memory: two slots. PRIMARY is the active guard - it
// carries DUTY_POST and stands on the block. RESERVE is bound but off-duty
// until the primary dies or gets ordered away, at which point it is
// promoted (it marches to the post). When the primary comes back via the
// map's "Return to post" command the reserve stands down to free fire.
// Names are snapshotted at bind time so the post UI can still label a
// unit whose chunk is unloaded or that has died.
public class GuardPostBlockEntity extends BlockEntity {

    private static final String TAG_PRIMARY = "primary";
    private static final String TAG_RESERVE = "reserve";
    private static final String TAG_PRIMARY_NAME = "primaryName";
    private static final String TAG_RESERVE_NAME = "reserveName";

    private UUID primary;
    private UUID reserve;
    private String primaryName = "";
    private String reserveName = "";

    public GuardPostBlockEntity(BlockPos pos, BlockState state) {
        super(SemcsRegistries.GUARD_POST_BE.get(), pos, state);
    }

    @Nullable
    public UUID primary() {
        return this.primary;
    }

    @Nullable
    public UUID reserve() {
        return this.reserve;
    }

    public String primaryName() {
        return this.primaryName;
    }

    public String reserveName() {
        return this.reserveName;
    }

    public boolean binds(UUID unit) {
        return unit.equals(this.primary) || unit.equals(this.reserve);
    }

    public boolean isFull() {
        return this.primary != null && this.reserve != null;
    }

    public boolean isEmpty() {
        return this.primary == null && this.reserve == null;
    }

    // First free slot wins: primary, else reserve. Returns the slot taken
    // (1 primary, 2 reserve) or 0 when the post is full.
    public int bind(UUID unit, String name) {
        if (binds(unit)) {
            return unit.equals(this.primary) ? 1 : 2;
        }
        if (this.primary == null) {
            this.primary = unit;
            this.primaryName = name;
            setChanged();
            return 1;
        }
        if (this.reserve == null) {
            this.reserve = unit;
            this.reserveName = name;
            setChanged();
            return 2;
        }
        return 0;
    }

    // Frees whichever slot the unit held. Returns the slot freed (0 = not bound).
    public int unbind(UUID unit) {
        if (unit.equals(this.primary)) {
            this.primary = null;
            this.primaryName = "";
            setChanged();
            return 1;
        }
        if (unit.equals(this.reserve)) {
            this.reserve = null;
            this.reserveName = "";
            setChanged();
            return 2;
        }
        return 0;
    }

    // The reserve takes over the post: it gets the actual post duty. The
    // slot layout is unchanged - "primary" is a role, not a duty flag.
    public void activateReserve(ServerLevel level) {
        if (this.reserve == null) {
            return;
        }
        PmcUnitEntity reserve = findUnit(level, this.reserve);
        if (reserve != null) {
            UnitChunkDutyGoal.assignPost(reserve,
                    level.dimension().location().toString(), getBlockPos());
        }
    }

    // The standing-down side of the rule: when the primary is back on post,
    // a reserve that was covering is released to normal orders (free fire),
    // its post duty cleared by the setOrder mixin hook.
    public void standDownReserve(ServerLevel level) {
        if (this.reserve == null) {
            return;
        }
        PmcUnitEntity reserve = findUnit(level, this.reserve);
        if (reserve != null
                && UnitChunkDutyGoal.dutyOf(reserve) == UnitChunkDutyGoal.DUTY_POST) {
            UnitChunkDutyGoal.clear(reserve);
            reserve.setOrder(
                    net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType.FREE_FIRE);
        }
    }

    @Nullable
    public static PmcUnitEntity findUnit(ServerLevel level, UUID uuid) {
        return level.getEntity(uuid) instanceof PmcUnitEntity pmc ? pmc : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.primary != null) {
            tag.putUUID(TAG_PRIMARY, this.primary);
            tag.putString(TAG_PRIMARY_NAME, this.primaryName);
        }
        if (this.reserve != null) {
            tag.putUUID(TAG_RESERVE, this.reserve);
            tag.putString(TAG_RESERVE_NAME, this.reserveName);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.primary = tag.hasUUID(TAG_PRIMARY) ? tag.getUUID(TAG_PRIMARY) : null;
        this.reserve = tag.hasUUID(TAG_RESERVE) ? tag.getUUID(TAG_RESERVE) : null;
        this.primaryName = tag.getString(TAG_PRIMARY_NAME);
        this.reserveName = tag.getString(TAG_RESERVE_NAME);
    }
}
