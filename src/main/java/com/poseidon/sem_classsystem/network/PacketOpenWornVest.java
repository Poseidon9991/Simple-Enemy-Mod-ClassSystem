package com.poseidon.sem_classsystem.network;

import com.poseidon.sem_classsystem.item.ArmorPlateItem;
import com.poseidon.sem_classsystem.item.BhqArmorItem;
import com.poseidon.sem_classsystem.menu.ArmorPlateMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Client -> Server packet: requests opening the ArmorPlateMenu for the player's
// currently equipped tactical vest (or helmet insert if no vest is worn).
public class PacketOpenWornVest {

    public PacketOpenWornVest() {
    }

    public static void encode(PacketOpenWornVest msg, FriendlyByteBuf buf) {
    }

    public static PacketOpenWornVest decode(FriendlyByteBuf buf) {
        return new PacketOpenWornVest();
    }

    public static void handle(PacketOpenWornVest msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || !player.isAlive()) {
                return;
            }

            // Check worn chestplate (tactical vest) first
            ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
            if (chest.getItem() instanceof BhqArmorItem && ArmorPlateItem.maxPlateSlots(chest) > 0) {
                ArmorPlateMenu.openForWorn(player, EquipmentSlot.CHEST);
                return;
            }

            // Fallback to worn helmet
            ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
            if (head.getItem() instanceof BhqArmorItem && ArmorPlateItem.maxPlateSlots(head) > 0) {
                ArmorPlateMenu.openForWorn(player, EquipmentSlot.HEAD);
                return;
            }

            player.displayClientMessage(Component.translatable("message.sem_classsystem.no_vest_equipped")
                    .withStyle(ChatFormatting.RED), true);
        });
        ctx.setPacketHandled(true);
    }
}
