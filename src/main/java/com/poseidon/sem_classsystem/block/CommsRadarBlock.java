package com.poseidon.sem_classsystem.block;

import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import com.poseidon.sem_classsystem.world.PlayerChunkClaims;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

// Tactical Comms & Radar Dish:
// When placed inside claimed chunks, upgrades Night Raid early warning to show
// the exact inbound raid bearing + map pin, extends hostile unit map spotting
// around the dish to 256 blocks, and emits a Redstone signal on nearby threats.
public class CommsRadarBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final BooleanProperty ALERT = BooleanProperty.create("alert");

    public CommsRadarBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.getStateDefinition().any()
                .setValue(FACING, Direction.NORTH)
                .setValue(ALERT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ALERT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(ALERT, false);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CommsRadarBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return type == SemcsRegistries.COMMS_RADAR_BE.get()
                ? (lvl, pos, st, be) -> CommsRadarBlockEntity.serverTick(lvl, pos, st, (CommsRadarBlockEntity) be)
                : null;
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(ALERT) ? 15 : 0;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl
                && level.getBlockEntity(pos) instanceof CommsRadarBlockEntity radar) {
            String dim = sl.dimension().location().toString();
            boolean claimed = PlayerChunkClaims.get(sl.getServer().overworld())
                    .isClaimedBy(sp.getUUID(), dim, pos.getX() >> 4, pos.getZ() >> 4);
            if (!claimed) {
                sp.displayClientMessage(Component.translatable("message.sem_classsystem.comms_radar_unclaimed")
                        .withStyle(ChatFormatting.YELLOW), true);
            } else {
                sp.displayClientMessage(Component.translatable("message.sem_classsystem.comms_radar_online",
                        radar.nearbyHostiles()).withStyle(ChatFormatting.AQUA), true);
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}
