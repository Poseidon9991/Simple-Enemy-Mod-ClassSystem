// Registry package - every Forge object our mod owns is registered here
package com.poseidon.sem_classsystem.registry;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.block.BhqRecruitTableBlock;
import com.poseidon.sem_classsystem.entity.BhqUnitEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.utils.UnitRole;
import net.nekoyuni.SimpleEnemyMod.item.RoleSpawnEggItem;

// DeferredRegisters for our entity, block and items - same pattern SEM uses
// in ModEntities/ModBlocks/ModItems, but everything lives under our modid.
public final class SemcsRegistries {

    private SemcsRegistries() {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, SemClassSystem.MODID);

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, SemClassSystem.MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, SemClassSystem.MODID);

    // ---- BHQ unit ---------------------------------------------------------
    // Same physical box + tracking range SEM uses for pmcunit so hitboxes,
    // pathing and rendering behave identically.
    public static final RegistryObject<EntityType<BhqUnitEntity>> BHQ_UNIT =
            ENTITY_TYPES.register("bhqunit",
                    () -> EntityType.Builder.of(BhqUnitEntity::new, MobCategory.MONSTER)
                            .sized(0.6F, 1.95F)
                            .clientTrackingRange(256)
                            .build("bhqunit"));

    // ---- BHQ recruit table ------------------------------------------------
    public static final RegistryObject<Block> BHQ_RECRUIT_TABLE = BLOCKS.register(
            "bhq_recruit_table",
            () -> new BhqRecruitTableBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_BLACK)
                            .strength(1.0F)
                            .sound(SoundType.METAL)
            )
    );

    public static final RegistryObject<Item> BHQ_RECRUIT_TABLE_ITEM = ITEMS.register(
            "bhq_recruit_table",
            () -> new BlockItem(BHQ_RECRUIT_TABLE.get(), new Item.Properties())
    );

    // Spawn egg uses SEM's RoleSpawnEggItem so eggs spawn with the same
    // FRIENDLY_DEFAULT role SEM's own PMC egg assigns.
    // Colors: tactical black shell + Cambodian-flag red spots.
    public static final RegistryObject<Item> BHQ_UNIT_SPAWN_EGG = ITEMS.register(
            "bhq_unit_spawn_egg",
            () -> new RoleSpawnEggItem(
                    BHQ_UNIT::get,
                    0x141414,   // primary - black armour
                    0xBA1E28,   // secondary - cambodian red
                    new Item.Properties().stacksTo(64),
                    UnitRole.FRIENDLY_DEFAULT
            )
    );

    // Called from the mod constructor: hook all three registers onto the bus.
    public static void registerAll() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITY_TYPES.register(bus);
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }

    // Forge asks every entity type for its attribute table at startup.
    // BhqUnitEntity inherits AbstractUnit.createAttributes(); the per-faction
    // numbers come from our [bhq_unit] config at spawn/load time anyway.
    @Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {

        private ModEvents() {
        }

        @SubscribeEvent
        public static void registerAttributes(EntityAttributeCreationEvent event) {
            event.put(BHQ_UNIT.get(), BhqUnitEntity.createAttributes().build());
        }

        // Put our items in vanilla creative tabs so players can find them
        // (SEM's own tab is registered for its modid and can't be extended
        // safely, so functional blocks + spawn eggs is the clean spot).
        @SubscribeEvent
        public static void addCreative(BuildCreativeModeTabContentsEvent event) {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                event.accept(BHQ_RECRUIT_TABLE_ITEM.get());
            }
            if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
                event.accept(BHQ_UNIT_SPAWN_EGG.get());
            }
        }
    }
}
