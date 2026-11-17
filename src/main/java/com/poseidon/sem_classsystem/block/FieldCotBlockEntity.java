package com.poseidon.sem_classsystem.block;

import com.poseidon.sem_classsystem.needs.UnitNeeds;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

// Barracks Field Cot state: stores shift preference (AUTO / DAY / NIGHT)
// so units sleeping on this cot automatically adopt the cot's watch roster.
public class FieldCotBlockEntity extends BlockEntity {

    private static final String TAG_SHIFT = "cotShift";
    private int shift = UnitNeeds.SHIFT_AUTO;

    public FieldCotBlockEntity(BlockPos pos, BlockState state) {
        super(SemcsRegistries.FIELD_COT_BE.get(), pos, state);
    }

    public int shift() {
        return this.shift;
    }

    public void cycleShift() {
        this.shift = (this.shift + 1) % 3;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt(TAG_SHIFT, this.shift);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.shift = Math.floorMod(tag.getInt(TAG_SHIFT), 3);
    }
}
