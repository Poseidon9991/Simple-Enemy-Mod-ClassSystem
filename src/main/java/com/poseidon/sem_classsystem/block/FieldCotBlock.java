package com.poseidon.sem_classsystem.block;

import com.poseidon.sem_classsystem.needs.UnitNeeds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;

// Barracks Field Cot: dedicated sleep bunk for owned PMC/BHQ units.
// Never overrides player respawn points and has no villager POI, while
// supporting vanilla Entity.startSleeping() via Forge's isBed() hook
// and allowing players to toggle the cot's Day/Night shift on right-click.
public class FieldCotBlock extends HorizontalDirectionalBlock implements EntityBlock {

    private static final VoxelShape SHAPE = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 8.0D, 15.0D);

    public FieldCotBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FieldCotBlockEntity(pos, state);
    }

    @Override
    public boolean isBed(BlockState state, BlockGetter level, BlockPos pos, @Nullable Entity sleeper) {
        return sleeper instanceof PmcUnitEntity;
    }

    @Override
    public Direction getBedDirection(BlockState state, LevelReader level, BlockPos pos) {
        return state.getValue(FACING);
    }

    @Override
    public void setBedOccupied(BlockState state, Level level, BlockPos pos, LivingEntity sleeper, boolean occupied) {
        // Occupancy is tracked dynamically via UnitNeeds.bedPos on units.
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl
                && level.getBlockEntity(pos) instanceof FieldCotBlockEntity cot) {
            cot.cycleShift();
            int shift = cot.shift();
            long packed = pos.asLong();
            String assignedName = null;
            for (PmcUnitEntity unit : sl.getEntitiesOfClass(PmcUnitEntity.class, new AABB(pos).inflate(64.0D))) {
                if (sp.getUUID().equals(unit.getOwnerUUID()) && UnitNeeds.bedPos(unit) == packed) {
                    UnitNeeds.setShift(unit, shift);
                    assignedName = unit.getDisplayName().getString();
                }
            }
            if (assignedName != null) {
                sp.displayClientMessage(Component.translatable("message.sem_classsystem.field_cot_shift_unit",
                        UnitNeeds.shiftLabel(shift), assignedName).withStyle(ChatFormatting.AQUA), true);
            } else {
                sp.displayClientMessage(Component.translatable("message.sem_classsystem.field_cot_shift",
                        UnitNeeds.shiftLabel(shift)).withStyle(ChatFormatting.AQUA), true);
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}
