// Registry package - every Forge object our mod owns is registered here
package com.poseidon.sem_classsystem.registry;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.block.BhqRecruitTableBlock;
import com.poseidon.sem_classsystem.entity.BhqUnitEntity;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.item.ClassSpawnEggItem;
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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.utils.UnitRole;
import net.nekoyuni.SimpleEnemyMod.item.RoleSpawnEggItem;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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

    // ---- per-class spawn eggs ----------------------------------------------
    // One egg per (faction x main weapon class): 4 factions x 5 classes.
    // Egg colour = faction base, spots = class accent. PISTOL is a subclass -
    // it is not spawned, it is equipped, so it gets no egg.
    public static final List<RegistryObject<Item>> CLASS_SPAWN_EGGS;

    private static final Map<WeaponClass, Integer> CLASS_SPOT_COLORS = new EnumMap<>(WeaponClass.class);

    static {
        CLASS_SPOT_COLORS.put(WeaponClass.SMG, 0xD9A62E);      // amber - breach
        CLASS_SPOT_COLORS.put(WeaponClass.RIFLE, 0x8C8C8C);    // steel gray
        CLASS_SPOT_COLORS.put(WeaponClass.SHOTGUN, 0xB0402C);  // breaching red
        CLASS_SPOT_COLORS.put(WeaponClass.SNIPER, 0x3E6EA8);   // recon blue
        CLASS_SPOT_COLORS.put(WeaponClass.HEAVY_MG, 0x7B4FA6); // suppression purple

        List<RegistryObject<Item>> eggs = new ArrayList<>();
        eggs.addAll(registerClassEggs("ru", ModEntities.RUUNIT, 0x4B5320));   // olive drab
        eggs.addAll(registerClassEggs("us", ModEntities.USUNIT, 0x9C8B6B));   // desert tan
        eggs.addAll(registerClassEggs("pmc", ModEntities.PMCUNIT, 0x3B3B3F)); // charcoal
        eggs.addAll(registerClassEggs("bhq", BHQ_UNIT, 0x141B2E));            // midnight navy
        CLASS_SPAWN_EGGS = Collections.unmodifiableList(eggs);
    }

    // Registers one egg per main weapon class for a faction. Item id looks
    // like "pmc_heavy_mg_spawn_egg".
    private static List<RegistryObject<Item>> registerClassEggs(
            String factionPrefix,
            Supplier<? extends EntityType<? extends net.minecraft.world.entity.Mob>> type,
            int baseColor) {
        List<RegistryObject<Item>> out = new ArrayList<>();
        for (WeaponClass cls : WeaponClass.mainClasses()) {
            out.add(ITEMS.register(
                    factionPrefix + "_" + cls.configKey() + "_spawn_egg",
                    () -> new ClassSpawnEggItem(type, baseColor, CLASS_SPOT_COLORS.get(cls), cls)));
        }
        return out;
    }

    // ---- BHQ armor (SBW bedrock-model gear, own design) --------------------
    // High-cut ballistic helmet + plate carrier, rendered through superbwarfare's
    // bedrock armor pipeline and drawn by tacz_sewv's BedrockArmorLayer (added
    // to the BHQ renderer in ClientEventHandlers). Registered only when both
    // mods are loaded - NpcArmor's PMC kit stays the fallback otherwise.
    public static final RegistryObject<Item> BHQ_HELMET;
    public static final RegistryObject<Item> BHQ_VEST;

    static {
        if (ModList.get().isLoaded("superbwarfare") && ModList.get().isLoaded("tacz_sewv")) {
            BHQ_HELMET = ITEMS.register("bhq_ballistic_helmet",
                    () -> new com.poseidon.sem_classsystem.item.BhqArmorItem(
                            net.minecraft.world.item.ArmorItem.Type.HELMET, "bhq_helmet"));
            BHQ_VEST = ITEMS.register("bhq_plate_carrier",
                    () -> new com.poseidon.sem_classsystem.item.BhqArmorItem(
                            net.minecraft.world.item.ArmorItem.Type.CHESTPLATE, "bhq_vest"));
        } else {
            BHQ_HELMET = null;
            BHQ_VEST = null;
        }
    }

    // Called from the mod constructor: hook all three registers onto the bus.
    public static void registerAll() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITY_TYPES.register(bus);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        // Client-only: registers our armor geos with simplebedrockmodel when SBW
        // is present (guarded internally too; referenced lazily so dedicated
        // servers never touch the client package).
        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.poseidon.sem_classsystem.client.SbwArmorModels.hookIfLoaded(bus);
        }
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
                for (RegistryObject<Item> egg : CLASS_SPAWN_EGGS) {
                    event.accept(egg.get());
                }
            }
            if (event.getTabKey() == CreativeModeTabs.COMBAT) {
                if (BHQ_HELMET != null) {
                    event.accept(BHQ_HELMET.get());
                }
                if (BHQ_VEST != null) {
                    event.accept(BHQ_VEST.get());
                }
            }
        }
    }
}
