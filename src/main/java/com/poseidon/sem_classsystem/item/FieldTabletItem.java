package com.poseidon.sem_classsystem.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

// Field Tablet - a squad command PDA. Right-click boots the SEM-OS screen:
// app icons on a desktop, and the Squad app lists every owned unit's
// health, ammo supply (infinite when a creative ammo box is carried),
// class and food/medical status - all fed by the already-synced
// UnitMarker data, so the screen reads nothing client-authoritative.
public class FieldTabletItem extends Item {

    public FieldTabletItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.poseidon.sem_classsystem.client.ClientPacketHooks.openTablet());
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
