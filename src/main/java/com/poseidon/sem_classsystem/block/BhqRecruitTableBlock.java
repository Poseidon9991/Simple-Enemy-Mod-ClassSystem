// Block package - our own interactive blocks
package com.poseidon.sem_classsystem.block;

import com.poseidon.sem_classsystem.bridge.IClassifiedUnit;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.nekoyuni.SimpleEnemyMod.config.common.MiscConfig;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

// BHQ Recruit Table - a clone of SEM's RecruitTableBlock that hires the
// better BHQ operator instead of a plain PMC. Same flow:
//   sneak + right-click = cycle your preferred class (shared preference
//                          with SEM's table - same persistent NBT key)
//   normal right-click  = pay the price, spawn a BHQ unit of that class
// Uses the same recruit item SEM is configured with (emerald by default)
// but its own, higher price - elite units cost more.
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

        // Sneak-click cycles the shared recruit-class preference (same as
        // SEM's table via our mixin - one pick controls both tables).
        if (player.isShiftKeyDown()) {
            WeaponClass next = WeaponClassSupport.cycleRecruitPreference(player);
            String label = next == null ? "Random" : next.defaultNickname();
            player.displayClientMessage(
                    Component.literal("Recruit class: " + label), true);
            return InteractionResult.SUCCESS;
        }

        Item requiredItem = MiscConfig.getRecruitItem();
        int requiredAmount = ClassSystemConfig.bhqRecruitPrice();

        ItemStack itemInHand = player.getItemInHand(hand);

        if (itemInHand.getItem() != requiredItem || itemInHand.getCount() < requiredAmount) {
            player.displayClientMessage(
                    Component.literal("You need " + requiredAmount + " " +
                            requiredItem.getDescription().getString() + " to recruit a BHQ Operator!!"),
                    true
            );
            return InteractionResult.FAIL;
        }

        PmcUnitEntity unit = SemcsRegistries.BHQ_UNIT.get().create(level);
        if (unit == null) {
            return InteractionResult.FAIL;
        }

        // Pre-set the chosen class BEFORE finalizeSpawn - our HEAD injector
        // keeps an already-set class, so the recruit equips, tunes and names
        // itself for it (identical mechanism to MixinRecruitTable).
        WeaponClass pref = WeaponClassSupport.getRecruitPreference(player);
        if (pref != null && unit instanceof IClassifiedUnit classified) {
            classified.semcs$setWeaponClass(pref);
        }

        itemInHand.shrink(requiredAmount);

        unit.moveTo(
                pos.getX() + 0.5,
                pos.getY() + 1.0,
                pos.getZ() + 0.5,
                0.0F,
                0.0F
        );

        unit.finalizeSpawn(
                (ServerLevelAccessor) level,
                level.getCurrentDifficultyAt(pos),
                MobSpawnType.TRIGGERED,
                null,
                null
        );

        unit.setOwner(player.getUUID());
        level.addFreshEntity(unit);

        playContractSuccessSound(level, pos);

        return InteractionResult.SUCCESS;
    }

    // Same contract-completion flourish SEM plays: white ash, page turn,
    // then a delayed villager-yes + happy particles.
    private void playContractSuccessSound(Level level, BlockPos pos) {

        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        if (level.getServer() == null) {
            return;
        }

        serverLevel.sendParticles(
                ParticleTypes.WHITE_ASH,
                pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
                10,
                0.2, 0.2, 0.2,
                0.05
        );

        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1.0F, 1.0F);

        level.getServer().tell(new net.minecraft.server.TickTask(
                level.getServer().getTickCount() + 10, () -> {

            level.playSound(null, pos, SoundEvents.VILLAGER_YES, SoundSource.BLOCKS, 1.0F, 1.0F);

            serverLevel.sendParticles(
                    ParticleTypes.HAPPY_VILLAGER,
                    pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5,
                    15,
                    0.4, 0.4, 0.4,
                    0.1
            );

        }));
    }
}
