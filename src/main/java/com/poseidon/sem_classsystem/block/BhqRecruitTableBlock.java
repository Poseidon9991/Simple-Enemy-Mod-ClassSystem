// Block package - our own interactive blocks
package com.poseidon.sem_classsystem.block;

import com.poseidon.sem_classsystem.menu.RecruitTableMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

// BHQ Recruit Table - a clone of SEM's RecruitTableBlock that hires the
// better BHQ operator instead of a plain PMC. Right-click opens the
// RecruitTableMenu in BHQ mode; class preference and hiring all happen
// through the menu's buttons (0..8 select, 10..18 select-and-recruit).
// The menu shares SEM's persistent recruit preference - one pick controls
// both tables. It uses the same recruit item SEM is configured with
// (emerald by default) but its own, higher price - elite units cost more.
public class BhqRecruitTableBlock extends HorizontalDirectionalBlock {

    public BhqRecruitTableBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(
                this.getStateDefinition().any().setValue(FACING, Direction.NORTH)
        );
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {

        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            RecruitTableMenu.open(serverPlayer, pos, true);
            return InteractionResult.CONSUME;
        }

        return InteractionResult.PASS;
    }
}
