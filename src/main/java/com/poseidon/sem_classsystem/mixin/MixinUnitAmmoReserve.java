// Mixin package - guarantees every class unit spawns with enough reserve ammo and built-in armor
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.entity.BhqUnitEntity;
import com.poseidon.sem_classsystem.faction.ClassLoadouts;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
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

    private static final int STORAGE_START = 6;
    private static final int MIN_RESERVE_ROUNDS = 240;
    private static final int MAX_SAFE_STACK = 60;

    @Inject(method = "equipRandomGun", at = @At("TAIL"), remap = false)
    private static void semcs$ensureReserveAmmo(LivingEntity entity, RandomSource random,
                                                CallbackInfo ci) {
        // 1. Always equip built-in 3D faction/class armor first
        semcs$equipBuiltInArmor(entity, random);

        // 2. Ensure primary weapon ammo is moved out of offhand/armor slots and split
        //    into legal <= 60 stacks in storage (6..17) so FriendlyByteBuf.writeItem
        //    never overflows signed byte (>127 -> negative -> ItemStack.EMPTY)!
        ItemStack gun = entity.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(gun);
        if (iGun != null) {
            ResourceLocation gunId = iGun.getGunId(gun);
            ResourceLocation ammoId = gunId == null ? null : TimelessAPI.getCommonGunIndex(gunId)
                    .map(index -> index.getGunData().getAmmoId())
                    .orElse(null);
            if (ammoId != null) {
                entity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
                    if (!(handler instanceof IItemHandlerModifiable modifiable)) {
                        return;
                    }
                    // First relocate any stray ammo from slots 1..5 into storage
                    for (int slot = 1; slot < STORAGE_START && slot < modifiable.getSlots(); slot++) {
                        ItemStack s = modifiable.getStackInSlot(slot);
                        if (!s.isEmpty() && IAmmo.getIAmmoOrNull(s) != null) {
                            int cnt = s.getCount();
                            ItemStack tmpl = s.copy();
                            modifiable.setStackInSlot(slot, ItemStack.EMPTY);
                            if (slot == 1) {
                                entity.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                            }
                            semcs$placeSplitAmmo(modifiable, tmpl, cnt);
                        }
                    }

                    // Normalize any existing oversized stacks (> 60) and count total carried
                    int carried = 0;
                    for (int slot = STORAGE_START; slot < modifiable.getSlots(); slot++) {
                        ItemStack stack = modifiable.getStackInSlot(slot);
                        if (stack.isEmpty()) {
                            continue;
                        }
                        IAmmo ammo = IAmmo.getIAmmoOrNull(stack);
                        if (ammo != null && ammoId.equals(ammo.getAmmoId(stack))) {
                            if (stack.getCount() > MAX_SAFE_STACK) {
                                int extra = stack.getCount() - MAX_SAFE_STACK;
                                ItemStack tmpl = stack.copy();
                                stack.setCount(MAX_SAFE_STACK);
                                modifiable.setStackInSlot(slot, stack);
                                semcs$placeSplitAmmo(modifiable, tmpl, extra);
                            }
                            carried += modifiable.getStackInSlot(slot).getCount();
                        }
                    }

                    int deficit = MIN_RESERVE_ROUNDS - carried;
                    if (deficit > 0) {
                        ItemStack tmpl = AmmoItemBuilder.create().setId(ammoId).setCount(1).build();
                        semcs$placeSplitAmmo(modifiable, tmpl, deficit);
                    }
                });
            }
        }

        // 3. Equip backup sidearm pistol + <= 60 legal stack of pistol ammo + field rations
        semcs$equipBackupSidearm(entity, random);
    }

    @Unique
    private static void semcs$placeSplitAmmo(IItemHandlerModifiable modifiable, ItemStack template, int totalRounds) {
        int remaining = totalRounds;
        // First top up partially-filled matching stacks up to MAX_SAFE_STACK (60)
        IAmmo tmplAmmo = IAmmo.getIAmmoOrNull(template);
        ResourceLocation tmplId = tmplAmmo != null ? tmplAmmo.getAmmoId(template) : null;
        if (tmplId != null) {
            for (int slot = STORAGE_START; slot < modifiable.getSlots() && remaining > 0; slot++) {
                ItemStack cur = modifiable.getStackInSlot(slot);
                IAmmo curAmmo = IAmmo.getIAmmoOrNull(cur);
                if (!cur.isEmpty() && curAmmo != null && tmplId.equals(curAmmo.getAmmoId(cur))
                        && cur.getCount() < MAX_SAFE_STACK) {
                    int add = Math.min(MAX_SAFE_STACK - cur.getCount(), remaining);
                    cur.grow(add);
                    modifiable.setStackInSlot(slot, cur);
                    remaining -= add;
                }
            }
        }
        // Then fill empty storage slots in chunks of MAX_SAFE_STACK (60)
        for (int slot = STORAGE_START; slot < modifiable.getSlots() && remaining > 0; slot++) {
            if (modifiable.getStackInSlot(slot).isEmpty()) {
                int chunk = Math.min(MAX_SAFE_STACK, remaining);
                ItemStack stack = template.copy();
                stack.setCount(chunk);
                modifiable.setStackInSlot(slot, stack);
                remaining -= chunk;
            }
        }
    }

    @Unique
    private static void semcs$equipBuiltInArmor(LivingEntity entity, RandomSource random) {
        entity.getPersistentData().putBoolean("sewv:armor_issued", true);

        WeaponClass cls = WeaponClassSupport.get(entity);
        Item helmet;
        Item vest;
        Item pants;
        Item boots;

        if (cls == WeaponClass.SNIPER) {
            helmet = SemcsRegistries.CAMPER_HELMET.get();
            vest = SemcsRegistries.CAMPER_VEST.get();
            pants = SemcsRegistries.CAMPER_PANTS.get();
            boots = SemcsRegistries.CAMPER_BOOTS.get();
        } else if (cls == WeaponClass.HEAVY_MG) {
            helmet = SemcsRegistries.SUPPRESSOR_HELMET.get();
            vest = SemcsRegistries.SUPPRESSOR_VEST.get();
            pants = SemcsRegistries.SUPPRESSOR_PANTS.get();
            boots = SemcsRegistries.SUPPRESSOR_BOOTS.get();
        } else if (cls == WeaponClass.HEAVY) {
            helmet = SemcsRegistries.DEMOLISHER_HELMET.get();
            vest = SemcsRegistries.DEMOLISHER_VEST.get();
            pants = SemcsRegistries.DEMOLISHER_PANTS.get();
            boots = SemcsRegistries.DEMOLISHER_BOOTS.get();
        } else if (entity instanceof BhqUnitEntity) {
            if (cls == WeaponClass.SMG) {
                helmet = SemcsRegistries.SF911_BERET.get();
                vest = SemcsRegistries.SF911_VEST.get();
                pants = SemcsRegistries.SF911_PANTS.get();
                boots = SemcsRegistries.SF911_BOOTS.get();
            } else if (cls == WeaponClass.SHOTGUN) {
                helmet = SemcsRegistries.GRK_HELMET.get();
                vest = SemcsRegistries.GRK_VEST.get();
                pants = SemcsRegistries.GRK_PANTS.get();
                boots = SemcsRegistries.GRK_BOOTS.get();
            } else if (cls == WeaponClass.JACK) {
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
                mob.setDropChance(EquipmentSlot.FEET, 0.05F);
                mob.setDropChance(EquipmentSlot.LEGS, 0.05F);
                mob.setDropChance(EquipmentSlot.CHEST, 0.05F);
                mob.setDropChance(EquipmentSlot.HEAD, 0.05F);
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
                : (entity instanceof RUunitEntity ? "ru_units"
                : (entity instanceof USunitEntity ? "us_units" : "pmc_units"));
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
                        .map(idx -> idx.getGunData().getAmmoId())
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
