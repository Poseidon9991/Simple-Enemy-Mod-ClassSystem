package com.poseidon.sem_classsystem.item;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.List;

// Squad Rally Whistle - orders every unit the player owns within 64 blocks.
//
//   Right-click        : rally - all owned units drop their locks and fall
//                        in behind the player (FOLLOW_COMMANDER), or into the
//                        formation the whistle is currently set to.
//   Sneak + right-click: cycle the squad's march order - FOLLOW (loose
//                        wedge), WEDGE, COLUMN - and apply it immediately.
public class SquadWhistleItem extends Item {

    private static final double RALLY_RANGE = 64.0D;
    private static final String MODE_KEY = "semcs_whistle_mode";

    private static final OrderType[] MODES = {
            OrderType.FOLLOW_COMMANDER, OrderType.FORM_WEDGE, OrderType.FORM_COLUMN
    };

    public SquadWhistleItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        List<PmcUnitEntity> units = level.getEntitiesOfClass(PmcUnitEntity.class,
                player.getBoundingBox().inflate(RALLY_RANGE),
                u -> u.isAlive() && u.isOwnedBy(player));

        if (units.isEmpty()) {
            player.displayClientMessage(Component.literal("§eNo units in earshot."), true);
            return InteractionResultHolder.fail(stack);
        }

        OrderType order;
        if (player.isShiftKeyDown()) {
            order = cycleMode(player);
        } else {
            order = currentMode(player);
        }

        // Re-number the squad so formation offsets never collide
        units.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        int index = 0;
        for (PmcUnitEntity unit : units) {
            unit.setTarget(null);
            unit.releaseMovementLock();
            unit.setFormationIndex(index++);
            unit.setOrder(order);
            unit.resetCommanderGoalCooldown();
        }

        String label = switch (order) {
            case FORM_WEDGE -> "WEDGE";
            case FORM_COLUMN -> "COLUMN";
            default -> "RALLY ON ME";
        };
        player.displayClientMessage(
                Component.literal("§a[Whistle] " + label + " - " + units.size() + " units"), true);
        level.playSound(null, player.blockPosition(), SoundEvents.RAID_HORN.get(),
                SoundSource.PLAYERS, 0.7F, 1.6F);
        player.getCooldowns().addCooldown(this, 12);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static OrderType currentMode(Player player) {
        int idx = player.getPersistentData().getInt(MODE_KEY);
        return idx >= 0 && idx < MODES.length ? MODES[idx] : OrderType.FOLLOW_COMMANDER;
    }

    private static OrderType cycleMode(Player player) {
        int next = (player.getPersistentData().getInt(MODE_KEY) + 1) % MODES.length;
        player.getPersistentData().putInt(MODE_KEY, next);
        return MODES[next];
    }
}
