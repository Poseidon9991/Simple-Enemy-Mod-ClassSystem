// Registry package - every Forge object our mod owns is registered here
package com.poseidon.sem_classsystem.registry;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.block.BhqRecruitTableBlock;
import com.poseidon.sem_classsystem.entity.BhqUnitEntity;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.item.BhqArmorItem;
import com.poseidon.sem_classsystem.item.ClassSpawnEggItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SemClassSystem.MODID);

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

    // ---- BHQ armor set -----------------------------------------------------
    // GeckoLib pieces - each owns its geo model + texture. Cap and helmet
    // are both helmet slot: soft cap vs ballistic shell.
    public static final RegistryObject<Item> BHQ_CAP = ITEMS.register("bhq_cap",
            () -> new BhqArmorItem("bhq_cap", ArmorItem.Type.HELMET));
    public static final RegistryObject<Item> BHQ_HELMET = ITEMS.register("bhq_helmet",
            () -> new BhqArmorItem("bhq_helmet", ArmorItem.Type.HELMET));
    public static final RegistryObject<Item> BHQ_VEST = ITEMS.register("bhq_vest",
            () -> new BhqArmorItem("bhq_vest", ArmorItem.Type.CHESTPLATE));
    public static final RegistryObject<Item> BHQ_LEGGINGS = ITEMS.register("bhq_leggings",
            () -> new BhqArmorItem("bhq_leggings", ArmorItem.Type.LEGGINGS));
    public static final RegistryObject<Item> BHQ_BOOTS = ITEMS.register("bhq_boots",
            () -> new BhqArmorItem("bhq_boots", ArmorItem.Type.BOOTS));

    // ---- 911 Special Forces set --------------------------------------------
    // Cambodia's counter-terror unit: red beret + tiger-stripe kit.
    public static final RegistryObject<Item> SF911_BERET = ITEMS.register("sf911_beret",
            () -> new BhqArmorItem("sf911_beret", ArmorItem.Type.HELMET));
    public static final RegistryObject<Item> SF911_VEST = ITEMS.register("sf911_vest",
            () -> new BhqArmorItem("sf911_vest", ArmorItem.Type.CHESTPLATE));
    public static final RegistryObject<Item> SF911_PANTS = ITEMS.register("sf911_pants",
            () -> new BhqArmorItem("sf911_pants", ArmorItem.Type.LEGGINGS));
    public static final RegistryObject<Item> SF911_BOOTS = ITEMS.register("sf911_boots",
            () -> new BhqArmorItem("sf911_boots", ArmorItem.Type.BOOTS));

    // ---- GRK (Gendarmerie Royale Kambodja) set ------------------------------
    public static final RegistryObject<Item> GRK_HELMET = ITEMS.register("grk_helmet",
            () -> new BhqArmorItem("grk_helmet", ArmorItem.Type.HELMET));
    public static final RegistryObject<Item> GRK_VEST = ITEMS.register("grk_vest",
            () -> new BhqArmorItem("grk_vest", ArmorItem.Type.CHESTPLATE));
    public static final RegistryObject<Item> GRK_PANTS = ITEMS.register("grk_pants",
            () -> new BhqArmorItem("grk_pants", ArmorItem.Type.LEGGINGS));
    public static final RegistryObject<Item> GRK_BOOTS = ITEMS.register("grk_boots",
            () -> new BhqArmorItem("grk_boots", ArmorItem.Type.BOOTS));

    // ---- RAF (Royal Armed Forces) set ---------------------------------------
    public static final RegistryObject<Item> RAF_HELMET = ITEMS.register("raf_helmet",
            () -> new BhqArmorItem("raf_helmet", ArmorItem.Type.HELMET));
    public static final RegistryObject<Item> RAF_VEST = ITEMS.register("raf_vest",
            () -> new BhqArmorItem("raf_vest", ArmorItem.Type.CHESTPLATE));
    public static final RegistryObject<Item> RAF_PANTS = ITEMS.register("raf_pants",
            () -> new BhqArmorItem("raf_pants", ArmorItem.Type.LEGGINGS));
    public static final RegistryObject<Item> RAF_BOOTS = ITEMS.register("raf_boots",
            () -> new BhqArmorItem("raf_boots", ArmorItem.Type.BOOTS));

    // ---- Camper (sniper) set -----------------------------------------------
    public static final RegistryObject<Item> CAMPER_HELMET = ITEMS.register("camper_helmet",
            () -> new BhqArmorItem("camper_helmet", ArmorItem.Type.HELMET));
    public static final RegistryObject<Item> CAMPER_VEST = ITEMS.register("camper_vest",
            () -> new BhqArmorItem("camper_vest", ArmorItem.Type.CHESTPLATE));
    public static final RegistryObject<Item> CAMPER_PANTS = ITEMS.register("camper_pants",
            () -> new BhqArmorItem("camper_pants", ArmorItem.Type.LEGGINGS));
    public static final RegistryObject<Item> CAMPER_BOOTS = ITEMS.register("camper_boots",
            () -> new BhqArmorItem("camper_boots", ArmorItem.Type.BOOTS));

    // ---- Suppressor (heavy MG) set -----------------------------------------
    public static final RegistryObject<Item> SUPPRESSOR_HELMET = ITEMS.register("suppressor_helmet",
            () -> new BhqArmorItem("suppressor_helmet", ArmorItem.Type.HELMET));
    public static final RegistryObject<Item> SUPPRESSOR_VEST = ITEMS.register("suppressor_vest",
            () -> new BhqArmorItem("suppressor_vest", ArmorItem.Type.CHESTPLATE));
    public static final RegistryObject<Item> SUPPRESSOR_PANTS = ITEMS.register("suppressor_pants",
            () -> new BhqArmorItem("suppressor_pants", ArmorItem.Type.LEGGINGS));
    public static final RegistryObject<Item> SUPPRESSOR_BOOTS = ITEMS.register("suppressor_boots",
            () -> new BhqArmorItem("suppressor_boots", ArmorItem.Type.BOOTS));

    // ---- Demolisher (ordnance) set -----------------------------------------
    public static final RegistryObject<Item> DEMOLISHER_HELMET = ITEMS.register("demolisher_helmet",
            () -> new BhqArmorItem("demolisher_helmet", ArmorItem.Type.HELMET));
    public static final RegistryObject<Item> DEMOLISHER_VEST = ITEMS.register("demolisher_vest",
            () -> new BhqArmorItem("demolisher_vest", ArmorItem.Type.CHESTPLATE));
    public static final RegistryObject<Item> DEMOLISHER_PANTS = ITEMS.register("demolisher_pants",
            () -> new BhqArmorItem("demolisher_pants", ArmorItem.Type.LEGGINGS));
    public static final RegistryObject<Item> DEMOLISHER_BOOTS = ITEMS.register("demolisher_boots",
            () -> new BhqArmorItem("demolisher_boots", ArmorItem.Type.BOOTS));

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
    // One egg per (faction x main weapon class): 4 factions x 7 classes.
    // Egg colour = faction base, spots = class accent. PISTOL is a subclass -
    // it is not spawned, it is equipped, so it gets no egg.
    public static final List<RegistryObject<Item>> CLASS_SPAWN_EGGS;

    private static final Map<WeaponClass, Integer> CLASS_SPOT_COLORS = new EnumMap<>(WeaponClass.class);

    // The class accent colour shared by spawn eggs, WAILA tooltip and the
    // inventory badge - single source so every UI agrees.
    public static int classAccent(WeaponClass cls) {
        return CLASS_SPOT_COLORS.getOrDefault(cls, 0xFFFFFF);
    }

    static {
        CLASS_SPOT_COLORS.put(WeaponClass.SMG, 0xD9A62E);      // amber - breach
        CLASS_SPOT_COLORS.put(WeaponClass.RIFLE, 0x8C8C8C);    // steel gray
        CLASS_SPOT_COLORS.put(WeaponClass.SHOTGUN, 0xB0402C);  // breaching red
        CLASS_SPOT_COLORS.put(WeaponClass.SNIPER, 0x3E6EA8);   // recon blue
        CLASS_SPOT_COLORS.put(WeaponClass.HEAVY_MG, 0x7B4FA6); // suppression purple
        CLASS_SPOT_COLORS.put(WeaponClass.HEAVY, 0xC97B1E);    // ordnance orange
        CLASS_SPOT_COLORS.put(WeaponClass.JACK, 0x5C8A4D);     // ranger green

        List<RegistryObject<Item>> eggs = new ArrayList<>();
        eggs.addAll(registerClassEggs("ru", ModEntities.RUUNIT, 0x4B5320));   // olive drab
        eggs.addAll(registerClassEggs("us", ModEntities.USUNIT, 0x9C8B6B));   // desert tan
        eggs.addAll(registerClassEggs("pmc", ModEntities.PMCUNIT, 0x3B3B3F)); // charcoal
        eggs.addAll(registerClassEggs("bhq", BHQ_UNIT, 0x141B2E));            // midnight navy
        CLASS_SPAWN_EGGS = Collections.unmodifiableList(eggs);
    }

    // Own creative tab - everything this mod adds, one place. Items also
    // stay in the vanilla tabs below (functional block + spawn eggs) so
    // they show up where players already look.
    public static final RegistryObject<CreativeModeTab> SEMCS_TAB = CREATIVE_TABS.register(
            "semcs",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.sem_classsystem"))
                    .icon(() -> new ItemStack(BHQ_RECRUIT_TABLE_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(BHQ_RECRUIT_TABLE_ITEM.get());
                        output.accept(BHQ_CAP.get());
                        output.accept(BHQ_HELMET.get());
                        output.accept(BHQ_VEST.get());
                        output.accept(BHQ_LEGGINGS.get());
                        output.accept(BHQ_BOOTS.get());
                        output.accept(SF911_BERET.get());
                        output.accept(SF911_VEST.get());
                        output.accept(SF911_PANTS.get());
                        output.accept(SF911_BOOTS.get());
                        output.accept(GRK_HELMET.get());
                        output.accept(GRK_VEST.get());
                        output.accept(GRK_PANTS.get());
                        output.accept(GRK_BOOTS.get());
                        output.accept(RAF_HELMET.get());
                        output.accept(RAF_VEST.get());
                        output.accept(RAF_PANTS.get());
                        output.accept(RAF_BOOTS.get());
                        output.accept(CAMPER_HELMET.get());
                        output.accept(CAMPER_VEST.get());
                        output.accept(CAMPER_PANTS.get());
                        output.accept(CAMPER_BOOTS.get());
                        output.accept(SUPPRESSOR_HELMET.get());
                        output.accept(SUPPRESSOR_VEST.get());
                        output.accept(SUPPRESSOR_PANTS.get());
                        output.accept(SUPPRESSOR_BOOTS.get());
                        output.accept(DEMOLISHER_HELMET.get());
                        output.accept(DEMOLISHER_VEST.get());
                        output.accept(DEMOLISHER_PANTS.get());
                        output.accept(DEMOLISHER_BOOTS.get());
                        output.accept(BHQ_UNIT_SPAWN_EGG.get());
                        for (RegistryObject<Item> egg : CLASS_SPAWN_EGGS) {
                            output.accept(egg.get());
                        }
                    })
                    .build());

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

    // Called from the mod constructor: hook all three registers onto the bus.
    public static void registerAll() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITY_TYPES.register(bus);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        CREATIVE_TABS.register(bus);
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
        }
    }
}
