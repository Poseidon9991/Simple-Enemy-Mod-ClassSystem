// Network package - this mod's own SimpleChannel
package com.poseidon.sem_classsystem.network;

import com.poseidon.sem_classsystem.SemClassSystem;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

// Registration lands in common setup (enqueueWork keeps it off the mod-load
// thread). SEM's own orders still ride SEM's channel - these packets are
// only the things SEM has no wire for: marker sync, chunk claims, duty.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SemcsNetwork {

    private static final String VERSION = "5";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(SemClassSystem.MODID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private SemcsNetwork() {
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            int id = 0;
            CHANNEL.registerMessage(id++, PacketSyncUnitMarkers.class,
                    PacketSyncUnitMarkers::encode, PacketSyncUnitMarkers::decode,
                    PacketSyncUnitMarkers::handle);
            CHANNEL.registerMessage(id++, PacketSyncClaims.class,
                    PacketSyncClaims::encode, PacketSyncClaims::decode,
                    PacketSyncClaims::handle);
            CHANNEL.registerMessage(id++, PacketClaimChunk.class,
                    PacketClaimChunk::encode, PacketClaimChunk::decode,
                    PacketClaimChunk::handle);
            CHANNEL.registerMessage(id++, PacketAssignDuty.class,
                    PacketAssignDuty::encode, PacketAssignDuty::decode,
                    PacketAssignDuty::handle);
            // Appended at the end - packet ids must stay stable for
            // clients/servers on the previous protocol.
            CHANNEL.registerMessage(id++, PacketAssignRoute.class,
                    PacketAssignRoute::encode, PacketAssignRoute::decode,
                    PacketAssignRoute::handle);
            CHANNEL.registerMessage(id++, PacketRaidPing.class,
                    PacketRaidPing::encode, PacketRaidPing::decode,
                    PacketRaidPing::handle);
            CHANNEL.registerMessage(id++, PacketOpenGuardPost.class,
                    PacketOpenGuardPost::encode, PacketOpenGuardPost::decode,
                    PacketOpenGuardPost::handle);
            CHANNEL.registerMessage(id++, PacketGuardPostAction.class,
                    PacketGuardPostAction::encode, PacketGuardPostAction::decode,
                    PacketGuardPostAction::handle);
            CHANNEL.registerMessage(id++, PacketReturnToPost.class,
                    PacketReturnToPost::encode, PacketReturnToPost::decode,
                    PacketReturnToPost::handle);
            CHANNEL.registerMessage(id++, PacketTeleportUnits.class,
                    PacketTeleportUnits::encode, PacketTeleportUnits::decode,
                    PacketTeleportUnits::handle);
            CHANNEL.registerMessage(id++, PacketSyncIntel.class,
                    PacketSyncIntel::encode, PacketSyncIntel::decode,
                    PacketSyncIntel::handle);
        });
    }
}
