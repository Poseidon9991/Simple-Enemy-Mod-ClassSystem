// Network package - client->server: assign/remove/transfer a unit on a post
package com.poseidon.sem_classsystem.network;

import com.poseidon.sem_classsystem.block.GuardPostBlockEntity;
import com.poseidon.sem_classsystem.entity.ai.UnitChunkDutyGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

// All three post operations in one message. The server re-validates
// everything: the target block is a guard post, the unit exists, is owned
// by the sender and is in the same dimension, and the destination post
// has a free slot for transfers.
public class PacketGuardPostAction {

    public static final int ACTION_ASSIGN = 0;    // entityId -> first free slot
    public static final int ACTION_REMOVE = 1;    // slot (1 primary, 2 reserve)
    public static final int ACTION_TRANSFER = 2;  // slot -> targetPost

    private final BlockPos pos;
    private final int action;
    private final int entityId;
    private final int slot;
    private final BlockPos targetPost;

    public PacketGuardPostAction(BlockPos pos, int action, int entityId,
                                 int slot, BlockPos targetPost) {
        this.pos = pos;
        this.action = action;
        this.entityId = entityId;
        this.slot = slot;
        this.targetPost = targetPost;
    }

    public static void encode(PacketGuardPostAction msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeByte(msg.action);
        buf.writeVarInt(msg.entityId);
        buf.writeVarInt(msg.slot);
        buf.writeBoolean(msg.targetPost != null);
        if (msg.targetPost != null) {
            buf.writeBlockPos(msg.targetPost);
        }
    }

    public static PacketGuardPostAction decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int action = buf.readByte();
        int entityId = buf.readVarInt();
        int slot = buf.readVarInt();
        BlockPos target = buf.readBoolean() ? buf.readBlockPos() : null;
        return new PacketGuardPostAction(pos, action, entityId, slot, target);
    }

    public static void handle(PacketGuardPostAction msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null || !(sender.level() instanceof ServerLevel level)
                    || !(level.getBlockEntity(msg.pos)
                    instanceof GuardPostBlockEntity post)) {
                return;
            }
            switch (msg.action) {
                case ACTION_ASSIGN -> assign(sender, level, post, msg);
                case ACTION_REMOVE -> remove(sender, level, post, msg.slot);
                case ACTION_TRANSFER -> transfer(sender, level, post, msg);
                default -> { }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void assign(ServerPlayer sender, ServerLevel level,
                               GuardPostBlockEntity post, PacketGuardPostAction msg) {
        if (!(level.getEntity(msg.entityId) instanceof PmcUnitEntity unit)
                || !unit.isOwnedBy(sender)) {
            return;
        }
        if (post.binds(unit.getUUID())) {
            return;   // already bound - idempotent
        }
        if (post.isFull()) {
            tell(sender, "This post already has two units. Remove one first.");
            return;
        }
        // Bound elsewhere? Implicit move - free the old post's slot first.
        BlockPos old = UnitChunkDutyGoal.boundPost(unit);
        if (old != null && !old.equals(msg.pos)
                && level.getBlockEntity(old) instanceof GuardPostBlockEntity oldPost) {
            int oldSlot = oldPost.unbind(unit.getUUID());
            if (oldSlot == 1) {
                oldPost.activateReserve(level);
            }
        }
        String name = unit.getCustomName() != null
                ? unit.getCustomName().getString() : "Unit";
        int slot = post.bind(unit.getUUID(), name);
        if (slot == 0) {
            return;
        }
        UnitChunkDutyGoal.bindPost(unit, msg.pos);
        if (slot == 1) {
            UnitChunkDutyGoal.assignPost(unit,
                    level.dimension().location().toString(), msg.pos);
            post.standDownReserve(level);
            tell(sender, name + " assigned as primary guard.");
        } else {
            tell(sender, name + " assigned as reserve.");
        }
    }

    private static void remove(ServerPlayer sender, ServerLevel level,
                               GuardPostBlockEntity post, int slot) {
        UUID uuid = slot == 1 ? post.primary() : slot == 2 ? post.reserve() : null;
        if (uuid == null) {
            return;
        }
        PmcUnitEntity unit = GuardPostBlockEntity.findUnit(level, uuid);
        if (unit != null && !unit.isOwnedBy(sender)) {
            return;   // can't strip another player's unit off a post
        }
        post.unbind(uuid);
        if (unit != null) {
            UnitChunkDutyGoal.unbindPost(unit);
            if (UnitChunkDutyGoal.dutyOf(unit) == UnitChunkDutyGoal.DUTY_POST) {
                UnitChunkDutyGoal.clear(unit);
            }
        }
        if (slot == 1) {
            post.activateReserve(level);   // reserve steps up
        }
    }

    private static void transfer(ServerPlayer sender, ServerLevel level,
                                 GuardPostBlockEntity post, PacketGuardPostAction msg) {
        if (msg.targetPost == null
                || !(level.getBlockEntity(msg.targetPost)
                instanceof GuardPostBlockEntity target)) {
            return;
        }
        UUID uuid = msg.slot == 1 ? post.primary() : msg.slot == 2 ? post.reserve() : null;
        if (uuid == null) {
            return;
        }
        PmcUnitEntity unit = GuardPostBlockEntity.findUnit(level, uuid);
        if (unit == null || !unit.isOwnedBy(sender)) {
            return;
        }
        if (target.isFull()) {
            tell(sender, "Target post is full.");
            return;
        }
        String name = msg.slot == 1 ? post.primaryName() : post.reserveName();
        int freed = post.unbind(uuid);
        if (freed == 0) {
            return;
        }
        if (freed == 1) {
            post.activateReserve(level);
        }
        int slot = target.bind(uuid, name);
        if (slot == 0) {
            return;
        }
        UnitChunkDutyGoal.bindPost(unit, msg.targetPost);
        if (slot == 1) {
            UnitChunkDutyGoal.assignPost(unit,
                    level.dimension().location().toString(), msg.targetPost);
            target.standDownReserve(level);
        }
        tell(sender, name + " transferred to post " + msg.targetPost.toShortString());
    }

    private static void tell(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text), true);
    }
}
