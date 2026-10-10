// Client package - registers BHQ armor geo with SBW's bedrock model pipeline
package com.poseidon.sem_classsystem.client;

import com.github.mcmodderanchor.simplebedrockmodel.v1.client.model.BedrockArmorModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.event.RegisterBedrockModelEvent;
import com.github.mcmodderanchor.simplebedrockmodel.v1.resource.RawResourceLoaders;
import com.poseidon.sem_classsystem.SemClassSystem;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;

// superbwarfare discovers bedrock armor models through simplebedrockmodel's
// RegisterBedrockModelEvent: it is posted to every mod bus during init, so we
// subscribe manually and register our two BHQ pieces under
// sem_classsystem:armor/<name>, which resolves to
// assets/sem_classsystem/models/bedrock/armor/<name>.geo.json.
//
// NOT an @EventBusSubscriber on purpose: Forge class-loads auto-subscribers
// unconditionally at startup, which would resolve the simplebedrockmodel
// event class even when SBW is absent. hookIfLoaded only references the
// event type inside the isLoaded branch, so nothing resolves without it.
public final class SbwArmorModels {

    private SbwArmorModels() {
    }

    public static void hookIfLoaded(IEventBus modBus) {
        if (ModList.get().isLoaded("superbwarfare")) {
            modBus.addListener(SbwArmorModels::onRegisterBedrockModels);
        }
    }

    private static void onRegisterBedrockModels(RegisterBedrockModelEvent event) {
        registerArmor(event, "bhq_helmet");
        registerArmor(event, "bhq_vest");
    }

    private static void registerArmor(RegisterBedrockModelEvent event, String name) {
        // RL "armor/<name>" resolves to models/bedrock/armor/<name>.geo.json
        // (the loader appends .geo.json itself - same convention as SBW's
        // own armor/us_helmet_pasgt).
        event.register(
                ResourceLocation.fromNamespaceAndPath(SemClassSystem.MODID, "armor/" + name),
                RawResourceLoaders.COMMON_LOADER,
                BedrockArmorModel::new);
    }
}
