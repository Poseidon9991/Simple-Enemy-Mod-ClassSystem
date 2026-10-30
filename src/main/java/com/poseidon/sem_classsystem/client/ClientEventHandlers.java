// Client package - client-only event wiring (renderer registration)
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.client.particle.MuzzleFlashParticle;
import com.poseidon.sem_classsystem.client.particle.SparkParticle;
import com.poseidon.sem_classsystem.client.particle.ZzzParticle;
import com.poseidon.sem_classsystem.item.BhqArmorItem;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.registries.RegistryObject;
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompat;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompatClient;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;

// value = Dist.CLIENT keeps this class off dedicated servers entirely -
// Forge only loads subscribers whose dist matches the running side.
// (Loading a renderer class on a server would NoClassDefFoundError.)
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEventHandlers {

    private ClientEventHandlers() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(SemcsRegistries.RECRUIT_TABLE_MENU.get(), RecruitTableScreen::new);
            if (ModList.get().isLoaded("curios")) {
                SpottingScopeCurioRenderer.register();
            }
        });
        // Xaero's World Map is a soft dep: the isLoaded gate keeps
        // SemcsXaeroCompat (and its xaero.* imports) from ever class-loading
        // on installs without the map mod.
        if (ModList.get().isLoaded("xaeroworldmap")) {
            com.poseidon.sem_classsystem.client.xaero.SemcsXaeroCompat.register();
        }
        // Xaero's Minimap is a separate soft dep: same gate, its own compat
        // class, so installs with only one of the two map mods still work.
        if (ModList.get().isLoaded("xaerominimap")) {
            com.poseidon.sem_classsystem.client.xaero.SemcsMinimapCompat.register();
        }
    }

    // Client-side particle factories - SimpleParticleType data is empty,
    // the provider just binds each type to its sprite sheet + behaviour.
    @SubscribeEvent
    public static void onRegisterParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(SemcsRegistries.SPARK_PARTICLE.get(), SparkParticle.Provider::new);
        event.registerSpriteSet(SemcsRegistries.MUZZLE_FLASH_PARTICLE.get(), MuzzleFlashParticle.Provider::new);
        event.registerSpriteSet(SemcsRegistries.ZZZ_PARTICLE.get(), ZzzParticle.Provider::new);
    }

    @SubscribeEvent
    public static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(VdfUnitModel.LAYER_LOCATION, VdfUnitModel::createBodyLayer);
    }

    // SemcsArmorItemRenderer swaps GUI rendering to "<piece>_flat#inventory" so
    // slots draw crisp 2D sprites while ground/hand draws the 3D GeckoLib geo.
    // ModelBakery only auto-loads models for registered Item ids, so every
    // *_flat model must be registered explicitly here or ModelManager falls
    // back to the black-and-purple missing model in GUIs.
    @SubscribeEvent
    public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        for (RegistryObject<Item> entry : SemcsRegistries.ITEMS.getEntries()) {
            if (entry.get() instanceof BhqArmorItem armor) {
                event.register(new ModelResourceLocation(
                        SemClassSystem.MODID, armor.piece() + "_flat", "inventory"));
            }
        }
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // BhqUnitRenderer renders PmcUnitEntity (the generic bound), and
        // EntityType<BhqUnitEntity> satisfies EntityType<? extends PmcUnitEntity>
        event.registerEntityRenderer(SemcsRegistries.BHQ_UNIT.get(), BhqUnitRenderer::new);
        // VDF gets its own renderer - it extends PathfinderMob, not the
        // SEM unit hierarchy, so SEM's bound renderers don't apply.
        event.registerEntityRenderer(SemcsRegistries.VDF_UNIT.get(), VdfUnitRenderer::new);
    }

    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        if (GeckoCompat.LOADED) {
            LivingEntityRenderer<RUunitEntity, ?> ruRenderer = event.getRenderer(ModEntities.RUUNIT.get());
            if (ruRenderer != null) {
                HumanoidModel geckoDummy = new HumanoidModel(event.getEntityModels().bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
                ruRenderer.addLayer(GeckoCompatClient.createArmorLayer((LivingEntityRenderer) ruRenderer, geckoDummy));
            }
            LivingEntityRenderer<USunitEntity, ?> usRenderer = event.getRenderer(ModEntities.USUNIT.get());
            if (usRenderer != null) {
                HumanoidModel geckoDummy = new HumanoidModel(event.getEntityModels().bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
                usRenderer.addLayer(GeckoCompatClient.createArmorLayer((LivingEntityRenderer) usRenderer, geckoDummy));
            }
            // VDF armor is handled by its own VdfArmorLayer inside
            // VdfUnitRenderer - SEM's gecko layer is bound to AbstractUnit
            // which VDF no longer extends.
        }

        if (ModList.get().isLoaded("curios")) {
            attachCuriosLayer(event, ModEntities.PMCUNIT.get());
            attachCuriosLayer(event, SemcsRegistries.BHQ_UNIT.get());
            attachCuriosLayer(event, ModEntities.RUUNIT.get());
            attachCuriosLayer(event, ModEntities.USUNIT.get());
            attachCuriosLayer(event, SemcsRegistries.VDF_UNIT.get());
        }

    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends net.minecraft.world.entity.LivingEntity> void attachGeckoArmorLayer(
            EntityRenderersEvent.AddLayers event, net.minecraft.world.entity.EntityType<T> type) {
        LivingEntityRenderer<T, ?> renderer = event.getRenderer(type);
        if (renderer != null) {
            HumanoidModel geckoDummy = new HumanoidModel(event.getEntityModels().bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
            renderer.addLayer(GeckoCompatClient.createArmorLayer((LivingEntityRenderer) renderer, geckoDummy));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends net.minecraft.world.entity.LivingEntity> void attachCuriosLayer(
            EntityRenderersEvent.AddLayers event, net.minecraft.world.entity.EntityType<T> type) {
        LivingEntityRenderer<T, ?> renderer = event.getRenderer(type);
        if (renderer != null) {
            HumanoidModel dummy = new HumanoidModel(event.getEntityModels().bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
            renderer.addLayer(new UnitCuriosRenderLayer((LivingEntityRenderer) renderer, dummy));
        }
    }

    // Override ForgeSpawnEggItem's default primary/secondary tint multiplier
    // with 0xFFFFFFFF so our custom 32x32 faction+class spawn egg textures
    // render in true color without being darkened.
    @SubscribeEvent
    public static void onItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> 0xFFFFFFFF,
                SemcsRegistries.BHQ_UNIT_SPAWN_EGG.get(),
                SemcsRegistries.VDF_UNIT_SPAWN_EGG.get());
        for (RegistryObject<Item> egg : SemcsRegistries.CLASS_SPAWN_EGGS) {
            event.register((stack, tintIndex) -> 0xFFFFFFFF, egg.get());
        }
    }
}
