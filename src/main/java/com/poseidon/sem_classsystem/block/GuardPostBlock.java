package com.poseidon.sem_classsystem.block;

import com.poseidon.sem_classsystem.entity.ai.UnitChunkDutyGoal;
import com.poseidon.sem_classsystem.network.PacketOpenGuardPost;
import com.poseidon.sem_classsystem.network.SemcsNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.PacketDistributor;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Guard Post - a squad billet marker. Right-click opens the post's UI
// (packet-driven screen, not a container): two slots, a primary and a
// reserve, each bound to one of the player's units. Breaking the post
// releases every unit still bound to it.
public class GuardPostBlock extends Block implements EntityBlock {

    // Nearby posts offered as transfer targets in the UI.
    private static final int NEARBY_SCAN_CHUNKS = 3;   // 7x7 chunk box

    public GuardPostBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GuardPostBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)
                || !(level instanceof ServerLevel serverLevel)
                || !(level.getBlockEntity(pos) instanceof GuardPostBlockEntity post)) {
            return InteractionResult.PASS;
        }
        SemcsNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                new PacketOpenGuardPost(pos, post,
                        nearbyPosts(serverLevel, pos)));
        return InteractionResult.CONSUME;
    }

    // Other guard posts with a free slot within the scan box - the UI's
    // transfer targets.
    private List<BlockPos> nearbyPosts(ServerLevel level, BlockPos self) {
        List<BlockPos> out = new ArrayList<>();
        int cx = self.getX() >> 4;
        int cz = self.getZ() >> 4;
        for (int dx = -NEARBY_SCAN_CHUNKS; dx <= NEARBY_SCAN_CHUNKS; dx++) {
            for (int dz = -NEARBY_SCAN_CHUNKS; dz <= NEARBY_SCAN_CHUNKS; dz++) {
                for (BlockEntity be : level.getChunkAt(
                        new BlockPos((cx + dx) << 4, self.getY(), (cz + dz) << 4))
                        .getBlockEntities().values()) {
                    if (be instanceof GuardPostBlockEntity other
                            && !be.getBlockPos().equals(self)
                            && !other.isFull()) {
                        out.add(be.getBlockPos());
                    }
                }
            }
        }
        return out;
    }

    // Block removed/broken: every bound unit is released (binding + duty).
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof GuardPostBlockEntity post) {
            releaseUnit(serverLevel, post.primary(), pos);
            releaseUnit(serverLevel, post.reserve(), pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    private static void releaseUnit(ServerLevel level, UUID uuid, BlockPos post) {
        if (uuid == null) {
            return;
        }
        PmcUnitEntity unit = GuardPostBlockEntity.findUnit(level, uuid);
        if (unit != null) {
            if (post.equals(UnitChunkDutyGoal.boundPost(unit))) {
                UnitChunkDutyGoal.unbindPost(unit);
                if (UnitChunkDutyGoal.dutyOf(unit) == UnitChunkDutyGoal.DUTY_POST) {
                    UnitChunkDutyGoal.clear(unit);
                }
            }
        }
    }
}
