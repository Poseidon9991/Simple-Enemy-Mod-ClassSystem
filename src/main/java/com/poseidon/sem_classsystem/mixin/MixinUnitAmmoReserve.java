// Mixin package - guarantees every class unit spawns with enough reserve ammo and built-in armor
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.BhqUnitEntity;
import com.poseidon.sem_classsystem.entity.UnitAmmoService;
import com.poseidon.sem_classsystem.faction.ClassLoadouts;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.nekoyuni.SimpleEnemyMod.data.UnitLoadout;
import net.nekoyuni.SimpleEnemyMod.entity.equipment.PmcUnitWeaponEquipper;
import net.nekoyuni.SimpleEnemyMod.entity.equipment.RuWeaponEquipper;
import net.nekoyuni.SimpleEnemyMod.entity.equipment.UsWeaponEquipper;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({RuWeaponEquipper.class, UsWeaponEquipper.class, PmcUnitWeaponEquipper.class})
public abstract class MixinUnitAmmoReserve {

    @Unique
    private static final org.slf4j.Logger SEMCS_AMMO_LOG = com.mojang.logging.LogUtils.getLogger();

    private static final int STORAGE_START = 6;

    @Inject(method = "equipRandomGun", at = @At("TAIL"), remap = false)
    private static void semcs$ensureReserveAmmo(LivingEntity entity, RandomSource random,
                                                CallbackInfo ci) {
        // 1. Always equip built-in 3D faction/class armor first
        try {
            semcs$equipBuiltInArmor(entity, random);
        } catch (Throwable t) {
            SEMCS_AMMO_LOG.warn("[SEMCAmmo] armor pass failed for {}: {}",
                    entity.getType().getDescriptionId(), t.toString());
        }

        // 2. Ensure primary weapon ammo is moved out of offhand/armor slots and split
        //    into legal <= 60 stacks in storage (6..17) so FriendlyByteBuf.writeItem
        //    never overflows signed byte (>127 -> negative -> ItemStack.EMPTY)!
        try {
            UnitAmmoService.ensurePrimaryAmmo(entity);
        } catch (Throwable t) {
            SEMCS_AMMO_LOG.warn("[SEMCAmmo] primary-ammo pass failed for {}: {}",
                    entity.getType().getDescriptionId(), t.toString());
        }

        // 3. Equip backup sidearm pistol + <= 60 legal stack of pistol ammo + field rations
        try {
            semcs$equipBackupSidearm(entity, random);
        } catch (Throwable t) {
            SEMCS_AMMO_LOG.warn("[SEMCAmmo] sidearm pass failed for {}: {}",
                    entity.getType().getDescriptionId(), t.toString());
        }
    }

    @Unique
    private static void semcs$placeSplitAmmo(IItemHandlerModifiable modifiable, ItemStack template, int totalRounds) {
        UnitAmmoService.placeSplitAmmo(modifiable, template, totalRounds);
    }

    @Unique
    private static void semcs$equipBuiltInArmor(LivingEntity entity, RandomSource random) {
        WeaponClass cls = WeaponClassSupport.get(entity);
        // [loadout] uniformFactionArmor: true = faction sets on every unit;
        // false = legacy per-class gear (Camper/Suppressor/Demolisher/SF911/GRK/RAF)
        boolean uniform = ClassSystemConfig.uniformFactionArmor();
        Item helmet;
        Item vest;
        Item pants;
        Item boots;

        if (entity instanceof com.poseidon.sem_classsystem.entity.VdfUnitEntity) {
            helmet = SemcsRegistries.VDF_HELMET.get();
            vest = SemcsRegistries.VDF_VEST.get();
            pants = SemcsRegistries.VDF_PANTS.get();
            boots = SemcsRegistries.VDF_BOOTS.get();
        } else if (!uniform && cls == WeaponClass.SNIPER) {
            helmet = SemcsRegistries.CAMPER_HELMET.get();
            vest = SemcsRegistries.CAMPER_VEST.get();
            pants = SemcsRegistries.CAMPER_PANTS.get();
            boots = SemcsRegistries.CAMPER_BOOTS.get();
        } else if (!uniform && cls == WeaponClass.HEAVY_MG) {
            helmet = SemcsRegistries.SUPPRESSOR_HELMET.get();
            vest = SemcsRegistries.SUPPRESSOR_VEST.get();
            pants = SemcsRegistries.SUPPRESSOR_PANTS.get();
            boots = SemcsRegistries.SUPPRESSOR_BOOTS.get();
        } else if (!uniform && cls == WeaponClass.HEAVY) {
            helmet = SemcsRegistries.DEMOLISHER_HELMET.get();
            vest = SemcsRegistries.DEMOLISHER_VEST.get();
            pants = SemcsRegistries.DEMOLISHER_PANTS.get();
            boots = SemcsRegistries.DEMOLISHER_BOOTS.get();
        } else if (entity instanceof BhqUnitEntity) {
            if (!uniform && cls == WeaponClass.SMG) {
                helmet = SemcsRegistries.SF911_BERET.get();
                vest = SemcsRegistries.SF911_VEST.get();
                pants = SemcsRegistries.SF911_PANTS.get();
                boots = SemcsRegistries.SF911_BOOTS.get();
            } else if (!uniform && cls == WeaponClass.SHOTGUN) {
                helmet = SemcsRegistries.GRK_HELMET.get();
                vest = SemcsRegistries.GRK_VEST.get();
                pants = SemcsRegistries.GRK_PANTS.get();
                boots = SemcsRegistries.GRK_BOOTS.get();
            } else if (!uniform && cls == WeaponClass.JACK) {
                helmet = SemcsRegistries.RAF_HELMET.get();
                vest = SemcsRegistries.RAF_VEST.get();
                pants = SemcsRegistries.RAF_PANTS.get();
                boots = SemcsRegistries.RAF_BOOTS.get();
            } else {
                helmet = random.nextFloat() < 0.25F
                        ? SemcsRegistries.BHQ_CAP.get()
                        : SemcsRegistries.BHQ_HELMET.get();
                vest = SemcsRegistries.BHQ_VEST.get();
                pants = SemcsRegistries.BHQ_LEGGINGS.get();
                boots = SemcsRegistries.BHQ_BOOTS.get();
            }
        } else if (entity instanceof RUunitEntity) {
            helmet = SemcsRegistries.RU_HELMET.get();
            vest = SemcsRegistries.RU_VEST.get();
            pants = SemcsRegistries.RU_PANTS.get();
            boots = SemcsRegistries.RU_BOOTS.get();
        } else if (entity instanceof USunitEntity) {
            helmet = SemcsRegistries.US_HELMET.get();
            vest = SemcsRegistries.US_VEST.get();
            pants = SemcsRegistries.US_PANTS.get();
            boots = SemcsRegistries.US_BOOTS.get();
        } else {
            helmet = SemcsRegistries.PMC_HELMET.get();
            vest = SemcsRegistries.PMC_VEST.get();
            pants = SemcsRegistries.PMC_PANTS.get();
            boots = SemcsRegistries.PMC_BOOTS.get();
        }

        if (entity instanceof PmcUnitEntity) {
            entity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
                if (!(handler instanceof IItemHandlerModifiable modifiable)) {
                    return;
                }
                if (modifiable.getStackInSlot(2).isEmpty()) {
                    ItemStack s = new ItemStack(boots);
                    modifiable.setStackInSlot(2, s);
                    entity.setItemSlot(EquipmentSlot.FEET, s.copy());
                }
                if (modifiable.getStackInSlot(3).isEmpty()) {
                    ItemStack s = new ItemStack(pants);
                    modifiable.setStackInSlot(3, s);
                    entity.setItemSlot(EquipmentSlot.LEGS, s.copy());
                }
                if (modifiable.getStackInSlot(4).isEmpty()) {
                    ItemStack s = new ItemStack(vest);
                    modifiable.setStackInSlot(4, s);
                    entity.setItemSlot(EquipmentSlot.CHEST, s.copy());
                }
                if (modifiable.getStackInSlot(5).isEmpty()) {
                    ItemStack s = new ItemStack(helmet);
                    modifiable.setStackInSlot(5, s);
                    entity.setItemSlot(EquipmentSlot.HEAD, s.copy());
                }
            });
        } else {
            if (entity.getItemBySlot(EquipmentSlot.FEET).isEmpty()) {
                entity.setItemSlot(EquipmentSlot.FEET, new ItemStack(boots));
            }
            if (entity.getItemBySlot(EquipmentSlot.LEGS).isEmpty()) {
                entity.setItemSlot(EquipmentSlot.LEGS, new ItemStack(pants));
            }
            if (entity.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) {
                entity.setItemSlot(EquipmentSlot.CHEST, new ItemStack(vest));
            }
            if (entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                entity.setItemSlot(EquipmentSlot.HEAD, new ItemStack(helmet));
            }
            if (entity instanceof Mob mob) {
                float dropChance = entity instanceof com.poseidon.sem_classsystem.entity.VdfUnitEntity ? 0.0F : 0.05F;
                mob.setDropChance(EquipmentSlot.FEET, dropChance);
                mob.setDropChance(EquipmentSlot.LEGS, dropChance);
                mob.setDropChance(EquipmentSlot.CHEST, dropChance);
                mob.setDropChance(EquipmentSlot.HEAD, dropChance);
            }
        }
    }

    @Unique
    private static void semcs$equipBackupSidearm(LivingEntity entity, RandomSource random) {
        WeaponClass cls = WeaponClassSupport.get(entity);
        if (cls == WeaponClass.PISTOL) {
            return;
        }
        String tableKey = entity instanceof BhqUnitEntity ? "bhq_units"
                : (entity instanceof com.poseidon.sem_classsystem.entity.VdfUnitEntity ? "vdf_units"
                : (entity instanceof RUunitEntity ? "ru_units"
                : (entity instanceof USunitEntity ? "us_units" : "pmc_units")));
        UnitLoadout pistolLoadout = ClassLoadouts.pick(tableKey, WeaponClass.PISTOL, random);
        if (pistolLoadout == null) {
            return;
        }

        FireMode fireMode = "AUTO".equalsIgnoreCase(pistolLoadout.fireMode) ? FireMode.AUTO : FireMode.SEMI;
        ItemStack pistol = GunItemBuilder.create()
                .setId(pistolLoadout.gunId)
                .setAmmoCount(pistolLoadout.ammoCount)
                .setFireMode(fireMode)
                .setCount(1)
                .build();
        IGun pistolGun = IGun.getIGunOrNull(pistol);
        if (pistolGun == null) {
            return;
        }
        pistolLoadout.scopeId.ifPresent(id ->
                pistolGun.installAttachment(pistol, AttachmentItemBuilder.create().setId(id).build()));
        pistolLoadout.muzzleId.ifPresent(id ->
                pistolGun.installAttachment(pistol, AttachmentItemBuilder.create().setId(id).build()));
        pistolLoadout.gripId.ifPresent(id ->
                pistolGun.installAttachment(pistol, AttachmentItemBuilder.create().setId(id).build()));

        if (entity instanceof PmcUnitEntity) {
            entity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
                if (!(handler instanceof IItemHandlerModifiable modifiable)) {
                    return;
                }
                for (int slot = STORAGE_START; slot < modifiable.getSlots(); slot++) {
                    if (modifiable.getStackInSlot(slot).isEmpty()) {
                        modifiable.setStackInSlot(slot, pistol);
                        break;
                    }
                }
                ResourceLocation pistolAmmoId = TimelessAPI.getCommonGunIndex(pistolLoadout.gunId)
                        .map(idx -> idx.getGunData() == null ? null : idx.getGunData().getAmmoId())
                        .orElse(null);
                if (pistolAmmoId != null) {
                    ItemStack pAmmoTmpl = AmmoItemBuilder.create().setId(pistolAmmoId).setCount(1).build();
                    semcs$placeSplitAmmo(modifiable, pAmmoTmpl, 60);
                }
                for (int slot = STORAGE_START; slot < modifiable.getSlots(); slot++) {
                    if (modifiable.getStackInSlot(slot).isEmpty()) {
                        modifiable.setStackInSlot(slot, new ItemStack(Items.COOKED_BEEF, 3));
                        break;
                    }
                }
            });
        } else {
            pistolGun.setMaxDummyAmmoAmount(pistol, Integer.MAX_VALUE);
            pistolGun.setDummyAmmoAmount(pistol, 9999);
            entity.getPersistentData().put("semcs_backup_sidearm", pistol.save(new CompoundTag()));
            entity.getPersistentData().putInt("semcs_field_rations", 3);
        }
    }
}
