package com.poseidon.sem_classsystem.entity;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.ai.UnitWeaponFallbackGoal;
import com.poseidon.sem_classsystem.faction.ClassLoadouts;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.item.ArmorPlateItem;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.data.UnitLoadout;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.VerticalAwareTargetGoal;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.utils.UnitRole;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.registry.ModSounds;

import javax.annotation.Nullable;
import java.util.List;

// Hostile desert insurgent unit. Extends AbstractUnit so all tactical gun
// goals, weapon-class AI mixins, animations and procedural poses work natively.
//
// Strictly limited to 3 classes:
//   - RIFLE  (AK-47 Rifleman)
//   - HEAVY  (RPG-7 Demolisher + close-range pistol fallback)
//   - SNIPER (Marksman / Sniper Camper)
// Hostile to every non-Terrorist entity including players, soldiers and mobs.
public class TerroristUnitEntity extends AbstractUnit {

    public static final int VARIANT_COUNT = 6;
    private static final String FACTION_LOADOUT_KEY = "terrorist_units";
    private static final String NATURAL_DESPAWN_NBT = "semcs_natural";

    private boolean naturalDespawnable = false;

    public static final WeaponClass[] ALLOWED_CLASSES = new WeaponClass[]{
            WeaponClass.RIFLE,
            WeaponClass.HEAVY,
            WeaponClass.SNIPER
    };

    public TerroristUnitEntity(EntityType<? extends Monster> entityType, Level level) {
        super(entityType, level);
        this.setVariant(this.random.nextInt(VARIANT_COUNT));
    }

    public static boolean canSpawnNaturally(
            EntityType<TerroristUnitEntity> type,
            ServerLevelAccessor level,
            MobSpawnType reason,
            BlockPos pos,
            RandomSource random) {
        if (!ClassSystemConfig.terroristNaturalSpawnsEnabled()) {
            return false;
        }
        // Surface desert patrols only — keep them out of underground caves.
        if (!level.canSeeSky(pos)) {
            return false;
        }
        return Monster.checkMonsterSpawnRules(type, level, reason, pos, random);
    }

    public boolean isNaturalDespawnable() {
        return this.naturalDespawnable;
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(
            ServerLevelAccessor level,
            DifficultyInstance difficulty,
            MobSpawnType reason,
            @Nullable SpawnGroupData spawnData,
            @Nullable CompoundTag dataTag) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
        if (reason == MobSpawnType.NATURAL || reason == MobSpawnType.CHUNK_GENERATION) {
            // AbstractUnit.finalizeSpawn marks units persistent by default; let
            // ambient desert patrols despawn cleanly once players leave the area.
            this.naturalDespawnable = true;
        }
        return result;
    }

    @Override
    public boolean isPersistenceRequired() {
        if (this.naturalDespawnable && !this.hasCustomName()) {
            return false;
        }
        return super.isPersistenceRequired();
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return this.naturalDespawnable && !this.hasCustomName();
    }

    public static boolean isAllowedClass(@Nullable WeaponClass cls) {
        return cls == WeaponClass.RIFLE || cls == WeaponClass.HEAVY || cls == WeaponClass.SNIPER;
    }

    // Weighted roll matching real squad composition: mostly AK riflemen backed
    // by RPG demolishers and marksman snipers.
    public static WeaponClass rollAllowedClass(RandomSource random) {
        int roll = random.nextInt(100);
        if (roll < 55) {
            return WeaponClass.RIFLE;
        }
        if (roll < 80) {
            return WeaponClass.SNIPER;
        }
        return WeaponClass.HEAVY;
    }

    public int getVariant() {
        return this.entityData.get(DATA_VARIANT_ID);
    }

    public void setVariant(int variant) {
        this.entityData.set(DATA_VARIANT_ID, Math.floorMod(variant, VARIANT_COUNT));
    }

    @Override
    public boolean isAlliedTo(Entity entity) {
        if (entity instanceof TerroristUnitEntity) {
            return true;
        }
        return super.isAlliedTo(entity);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Variant", this.getVariant());
        if (this.naturalDespawnable) {
            tag.putBoolean(NATURAL_DESPAWN_NBT, true);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        this.naturalDespawnable = tag.getBoolean(NATURAL_DESPAWN_NBT);
        super.readAdditionalSaveData(tag);
        if (tag.contains("Variant")) {
            this.setVariant(tag.getInt("Variant"));
        }
        this.equipBackupSidearm();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide || !this.isAlive()) {
            return;
        }
        // Proactive line-of-sight sensing every 12 ticks (staggered by entity id)
        // so insurgents do not stare idly when players or rival troops enter FOV.
        if ((this.tickCount + this.getId()) % 12 != 0) {
            return;
        }
        LivingEntity cur = this.getTarget();
        boolean curIsHighPriority = (cur instanceof Player || cur instanceof AbstractUnit || cur instanceof VdfUnitEntity)
                && cur.isAlive()
                && (this.getSensing().hasLineOfSight(cur) || this.distanceToSqr(cur) <= 256.0D);
        if (curIsHighPriority) {
            return;
        }

        double range = Math.max(40.0D, this.getAttributeValue(Attributes.FOLLOW_RANGE));
        double rangeSq = range * range;
        LivingEntity bestTarget = null;
        double bestScoreSq = Double.MAX_VALUE;

        for (Player candidate : this.level().players()) {
            if (!candidate.isAlive() || candidate.isSpectator() || candidate.isCreative()) {
                continue;
            }
            double d2 = this.distanceToSqr(candidate);
            if (d2 < rangeSq && d2 < bestScoreSq && this.canAttack(candidate) && this.hasLineOfSight(candidate)) {
                bestScoreSq = d2;
                bestTarget = candidate;
            }
        }

        if (bestTarget == null) {
            double mobScan = Math.min(48.0D, range * 0.5D);
            AABB scanBox = this.getBoundingBox().inflate(mobScan, 20.0D, mobScan);
            List<LivingEntity> nearby = this.level().getEntitiesOfClass(LivingEntity.class, scanBox,
                    e -> e.isAlive()
                            && !(e instanceof TerroristUnitEntity)
                            && (e instanceof AbstractUnit || e instanceof VdfUnitEntity));
            for (LivingEntity candidate : nearby) {
                double d2 = this.distanceToSqr(candidate);
                if (d2 < bestScoreSq && this.canAttack(candidate) && this.hasLineOfSight(candidate)) {
                    bestScoreSq = d2;
                    bestTarget = candidate;
                }
            }
        }

        if (bestTarget != null) {
            this.setTarget(bestTarget);
            this.getEntityData().set(AbstractUnit.IS_IN_COMBAT, true);
            this.getLookControl().setLookAt(bestTarget, 30.0F, 30.0F);
        }
    }

    @Override
    public void setupRoleGoals() {
        this.goalSelector.removeAllGoals(g -> true);
        this.targetSelector.removeAllGoals(g -> true);

        if (this.role == null) {
            this.setRole(UnitRole.DEFAULT);
        }

        // Universal hostility: attacks players, every military/militia faction,
        // villagers, golems and monsters; only fellow TerroristUnitEntity are allies.
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, TerroristUnitEntity.class)
                .setAlertOthers(TerroristUnitEntity.class)
                .setUnseenMemoryTicks(600));

        this.targetSelector.addGoal(2, new VerticalAwareTargetGoal<>(
                this, Player.class, true, 40.0D)
                .setUnseenMemoryTicks(800));

        this.targetSelector.addGoal(2, new VerticalAwareTargetGoal<>(
                this, AbstractUnit.class, true, 40.0D,
                e -> !(e instanceof TerroristUnitEntity))
                .setUnseenMemoryTicks(800));

        this.targetSelector.addGoal(2, new VerticalAwareTargetGoal<>(
                this, VdfUnitEntity.class, true, 40.0D)
                .setUnseenMemoryTicks(800));

        this.targetSelector.addGoal(3, new VerticalAwareTargetGoal<>(
                this, IronGolem.class, true, 32.0D));

        this.targetSelector.addGoal(3, new VerticalAwareTargetGoal<>(
                this, AbstractVillager.class, true, 32.0D));

        this.targetSelector.addGoal(4, new VerticalAwareTargetGoal<>(
                this, Monster.class, true, 32.0D,
                e -> !(e instanceof TerroristUnitEntity)));

        // Inherits tactical combat movement, gun firing, melee, self-heal and pistol fallback.
        UnitRole.DEFAULT.getGoals().addGoals(this);
    }

    @Override
    public void equipRandomGun() {
        WeaponClass cls = WeaponClassSupport.getOrAssign(this, this.random);
        if (!isAllowedClass(cls)) {
            cls = rollAllowedClass(this.random);
            if (this instanceof com.poseidon.sem_classsystem.bridge.IClassifiedUnit classified) {
                classified.semcs$setWeaponClass(cls);
            }
        }

        UnitLoadout loadout = ClassLoadouts.pick(FACTION_LOADOUT_KEY, cls, this.random);
        ItemStack gun = buildGunFromLoadout(loadout, cls);
        if (gun.isEmpty()) {
            return;
        }

        IGun iGun = IGun.getIGunOrNull(gun);
        if (iGun != null) {
            iGun.setMaxDummyAmmoAmount(gun, Integer.MAX_VALUE);
            iGun.setDummyAmmoAmount(gun, 9999);
        }
        this.setItemSlot(EquipmentSlot.MAINHAND, gun);
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        this.getPersistentData().putInt("semcs_field_rations", 3);
        this.equipBackupSidearm();
        this.equipInsurgentArmor();
    }

    private void equipInsurgentArmor() {
        if (this.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) {
            ItemStack vest = new ItemStack(SemcsRegistries.TERRORIST_VEST.get());
            ItemStack plate = new ItemStack(this.random.nextFloat() < 0.35F
                    ? SemcsRegistries.ARMOR_PLATE_T2.get()
                    : SemcsRegistries.ARMOR_PLATE_T1.get());
            ArmorPlateItem.insertOrReplacePlate(vest, plate);
            this.setItemSlot(EquipmentSlot.CHEST, vest);
            this.setDropChance(EquipmentSlot.CHEST, 0.05F);
        }
        if (this.getItemBySlot(EquipmentSlot.LEGS).isEmpty()) {
            this.setItemSlot(EquipmentSlot.LEGS, new ItemStack(SemcsRegistries.TERRORIST_PANTS.get()));
            this.setDropChance(EquipmentSlot.LEGS, 0.05F);
        }
        if (this.getItemBySlot(EquipmentSlot.FEET).isEmpty()) {
            this.setItemSlot(EquipmentSlot.FEET, new ItemStack(SemcsRegistries.TERRORIST_BOOTS.get()));
            this.setDropChance(EquipmentSlot.FEET, 0.05F);
        }
        if (this.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && this.random.nextFloat() < 0.45F) {
            this.setItemSlot(EquipmentSlot.HEAD, new ItemStack(SemcsRegistries.TERRORIST_HELMET.get()));
            this.setDropChance(EquipmentSlot.HEAD, 0.05F);
        }
    }

    private ItemStack buildGunFromLoadout(@Nullable UnitLoadout loadout, WeaponClass cls) {
        if (loadout != null && loadout.gunId != null) {
            FireMode mode = "AUTO".equalsIgnoreCase(loadout.fireMode) ? FireMode.AUTO : FireMode.SEMI;
            ItemStack stack = GunItemBuilder.create()
                    .setId(loadout.gunId)
                    .setAmmoCount(loadout.ammoCount)
                    .setFireMode(mode)
                    .setCount(1)
                    .build();
            IGun iGun = IGun.getIGunOrNull(stack);
            if (iGun != null) {
                loadout.scopeId.ifPresent(id ->
                        iGun.installAttachment(stack, AttachmentItemBuilder.create().setId(id).build()));
                loadout.muzzleId.ifPresent(id ->
                        iGun.installAttachment(stack, AttachmentItemBuilder.create().setId(id).build()));
                loadout.gripId.ifPresent(id ->
                        iGun.installAttachment(stack, AttachmentItemBuilder.create().setId(id).build()));
                return stack;
            }
        }

        // Inline fallback guarantees insurgents always spawn armed even without datapacks.
        ResourceLocation fallbackId;
        int ammo;
        FireMode mode;
        ResourceLocation scope = null;
        if (cls == WeaponClass.HEAVY) {
            fallbackId = ResourceLocation.fromNamespaceAndPath("tacz", "rpg7");
            ammo = 1;
            mode = FireMode.SEMI;
        } else if (cls == WeaponClass.SNIPER) {
            fallbackId = ResourceLocation.fromNamespaceAndPath("tacz", "sks_tactical");
            ammo = 10;
            mode = FireMode.SEMI;
            scope = ResourceLocation.fromNamespaceAndPath("tacz", "scope_mk5hd");
        } else {
            fallbackId = ResourceLocation.fromNamespaceAndPath("tacz", "ak47");
            ammo = 30;
            mode = FireMode.AUTO;
        }
        ItemStack stack = GunItemBuilder.create()
                .setId(fallbackId)
                .setAmmoCount(ammo)
                .setFireMode(mode)
                .setCount(1)
                .build();
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun != null && scope != null) {
            iGun.installAttachment(stack, AttachmentItemBuilder.create().setId(scope).build());
        }
        return stack;
    }

    private void equipBackupSidearm() {
        CompoundTag pdata = this.getPersistentData();
        if (pdata.contains(UnitWeaponFallbackGoal.SIDEARM_NBT)
                || pdata.contains(UnitWeaponFallbackGoal.PRIMARY_NBT)) {
            return;
        }
        UnitLoadout pistolLoadout = ClassLoadouts.pick(FACTION_LOADOUT_KEY, WeaponClass.PISTOL, this.random);
        ItemStack pistol;
        if (pistolLoadout != null && pistolLoadout.gunId != null) {
            FireMode mode = "AUTO".equalsIgnoreCase(pistolLoadout.fireMode) ? FireMode.AUTO : FireMode.SEMI;
            pistol = GunItemBuilder.create()
                    .setId(pistolLoadout.gunId)
                    .setAmmoCount(pistolLoadout.ammoCount)
                    .setFireMode(mode)
                    .setCount(1)
                    .build();
            IGun pg = IGun.getIGunOrNull(pistol);
            if (pg != null) {
                pistolLoadout.scopeId.ifPresent(id ->
                        pg.installAttachment(pistol, AttachmentItemBuilder.create().setId(id).build()));
                pistolLoadout.muzzleId.ifPresent(id ->
                        pg.installAttachment(pistol, AttachmentItemBuilder.create().setId(id).build()));
                pistolLoadout.gripId.ifPresent(id ->
                        pg.installAttachment(pistol, AttachmentItemBuilder.create().setId(id).build()));
            }
        } else {
            pistol = GunItemBuilder.create()
                    .setId(ResourceLocation.fromNamespaceAndPath("tacz", "cz75"))
                    .setAmmoCount(16)
                    .setFireMode(FireMode.SEMI)
                    .setCount(1)
                    .build();
        }
        IGun pistolGun = IGun.getIGunOrNull(pistol);
        if (pistolGun == null) {
            return;
        }
        pistolGun.setMaxDummyAmmoAmount(pistol, Integer.MAX_VALUE);
        pistolGun.setDummyAmmoAmount(pistol, 9999);
        pdata.put(UnitWeaponFallbackGoal.SIDEARM_NBT, pistol.save(new CompoundTag()));
    }

    @Override
    protected SoundEvent getCustomHurtSound() {
        return ModSounds.SOUND_RU_UNIT_HURT.get();
    }

    @Override
    protected SoundEvent getCustomDeathSound() {
        return ModSounds.SOUND_RU_UNIT_DEATH.get();
    }

    @Override
    protected SoundEvent getCustomAlertSound() {
        return ModSounds.SOUND_RU_UNIT_ALERT.get();
    }

    @Override
    public float getSoundVolume() {
        return 0.9F;
    }
}
