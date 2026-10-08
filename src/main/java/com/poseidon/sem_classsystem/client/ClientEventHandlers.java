// Client package - client-only event wiring (renderer registration)
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// value = Dist.CLIENT keeps this class off dedicated servers entirely -
// Forge only loads subscribers whose dist matches the running side.
// (Loading a renderer class on a server would NoClassDefFoundError.)
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEventHandlers {

    private ClientEventHandlers() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // BhqUnitRenderer renders PmcUnitEntity (the generic bound), and
        // EntityType<BhqUnitEntity> satisfies EntityType<? extends PmcUnitEntity>
        event.registerEntityRenderer(SemcsRegistries.BHQ_UNIT.get(), BhqUnitRenderer::new);
    }
}
