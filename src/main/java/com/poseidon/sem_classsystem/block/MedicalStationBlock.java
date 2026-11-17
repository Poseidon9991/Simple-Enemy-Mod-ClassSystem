package com.poseidon.sem_classsystem.block;

import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

// Field Medical Station: stores medical kits, automatically recharges damaged
// IFAK Pouches, and serves as a post-fight triage & Medic restock hub.
public class MedicalStationBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public MedicalStationBlock(Properties properties) {
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

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MedicalStationBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return type == SemcsRegistries.MEDICAL_STATION_BE.get()
                ? (lvl, pos, st, be) -> MedicalStationBlockEntity.serverTick(lvl, pos, st, (MedicalStationBlockEntity) be)
                : null;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer sp)
                || !(level.getBlockEntity(pos) instanceof MedicalStationBlockEntity station)) {
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && held.is(SemcsRegistries.IFAK_POUCH.get()) && held.isDamaged()) {
            if (station.rechargeHeldIfak(held)) {
                level.playSound(null, pos, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 0.7F, 1.35F);
                if (level instanceof ServerLevel sl) {
                    sl.sendParticles(ParticleTypes.HEART, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
                            4, 0.25, 0.2, 0.25, 0.02);
                }
                sp.displayClientMessage(Component.translatable("message.sem_classsystem.ifak_recharged")
                        .withStyle(ChatFormatting.GREEN), true);
            } else {
                sp.displayClientMessage(Component.translatable("message.sem_classsystem.medical_station_empty")
                        .withStyle(ChatFormatting.RED), true);
            }
            return InteractionResult.CONSUME;
        }
        NetworkHooks.openScreen(sp, station, pos);
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof MedicalStationBlockEntity station) {
            Containers.dropContents(level, pos, station);
            level.updateNeighbourForOutputSignal(pos, this);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
