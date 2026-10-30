// Entity package - our own soldier types (VDF militia lives here)
package com.poseidon.sem_classsystem.entity;

import net.minecraft.core.BlockPos;                                   // village center anchor
import net.minecraft.nbt.CompoundTag;                                  // village-center + variant NBT persistence
import net.minecraft.network.syncher.EntityDataAccessor;               // variant synched-data key
import net.minecraft.network.syncher.EntityDataSerializers;            // INT serializer
import net.minecraft.network.syncher.SynchedEntityData;                // entity data registry
import net.minecraft.resources.ResourceLocation;                       // militia gun ids
import net.minecraft.server.level.ServerLevel;                         // POI scan on spawn/load
import net.minecraft.sounds.SoundEvent;                                // sound return type
import net.minecraft.sounds.SoundEvents;                               // villager hums
import net.minecraft.world.DifficultyInstance;                         // finalizeSpawn signature arg
import net.minecraft.world.entity.AnimationState;                      // client-side animation states
import net.minecraft.world.entity.Entity;                              // alliance & friendly-fire checks
import net.minecraft.world.entity.EntityType;                          // entity type parameter
import net.minecraft.world.entity.EquipmentSlot;                       // armor + hand slots
import net.minecraft.world.entity.LivingEntity;                        // target predicate entity type
import net.minecraft.world.entity.Mob;                                 // base attributes builder
import net.minecraft.world.entity.MobSpawnType;                        // finalizeSpawn signature arg
import net.minecraft.world.entity.SpawnGroupData;                      // finalizeSpawn signature + return
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;     // createAttributes builder
import net.minecraft.world.entity.ai.attributes.Attributes;            // MAX_HEALTH / MOVEMENT_SPEED / ...
import net.minecraft.world.entity.ai.goal.FloatGoal;                   // don't drown
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;            // idle gaze at nearby players
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;        // idle head turns
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;     // retaliate when hit
import net.minecraft.world.entity.ai.village.poi.PoiManager;           // village bell/bed lookup
import net.minecraft.world.entity.ai.village.poi.PoiTypes;             // MEETING / HOME POIs
import net.minecraft.world.entity.PathfinderMob;                       // our base - navigable mob, no Enemy flag
import net.minecraft.world.entity.animal.IronGolem;                    // protected village defender
import net.minecraft.world.entity.monster.Monster;                     // hostile-mob target class
import net.minecraft.world.entity.npc.AbstractVillager;                // protected villagers
import net.minecraft.world.entity.player.Player;                       // player neutrality gate
import net.minecraft.world.entity.raid.Raider;                         // raid mobs (pillagers, vindicators, ravagers)
import net.minecraft.world.item.ItemStack;                             // loadout stacks
import net.minecraft.world.item.Items;                                 // IRON_SWORD
import net.minecraft.world.level.Level;                                // entity world reference
import net.minecraft.world.level.ServerLevelAccessor;                  // finalizeSpawn signature arg
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.VerticalAwareTargetGoal;  // SEM's 3D-aware nearest-target goal
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;           // SEM soldier exclusion in target lists
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;          // PMC + BHQ neutrality pact
import net.nekoyuni.SimpleEnemyMod.entity.unit.util.UnitFactionTags;   // ru_units/us_units tag checks
import com.poseidon.sem_classsystem.config.ClassSystemConfig;          // [vdf] garrison + armament options
import com.poseidon.sem_classsystem.faction.UnitClassAttributes;       // configured stat application
import com.poseidon.sem_classsystem.registry.SemcsRegistries;          // vdf_* militia armor set
import com.poseidon.sem_classsystem.faction.WeaponClass;              // militia loadout roll categories
import com.tacz.guns.api.item.IGun;                                    // held-gun checks
import com.tacz.guns.api.item.builder.GunItemBuilder;                  // militia gun construction
import com.tacz.guns.api.item.gun.FireMode;                            // SEMI/AUTO
import org.jetbrains.annotations.Nullable;                             // Nullable marker for the anchor

// VDF (Village Defense Force) - village militia, NOT a faction soldier.
//
// Extends PathfinderMob - deliberately independent of SEM's
// AbstractUnit/RUunitEntity hierarchy, and deliberately NOT Monster:
// Monster implements Enemy, which flagged militia on village golems'
// hostile scan (the event veto in VdfFriendlyFireEvents stays as a
// fallback) and made them despawn on Peaceful difficulty. PathfinderMob
// keeps everything the unit actually uses (navigation, MeleeAttackGoal,
// finalizeSpawn) without the hostile marker.
//
// Its animation states are driven locally from the vanilla movement
// tracker (Entity.walkAnimation is synced by the entity tracker), its
// combat package is two small goals (gun / melee), and its stats come
// from our own [vdf_unit] config section via UnitClassAttributes.
public class VdfUnitEntity extends PathfinderMob {

    // How many militia skins ship under textures/entity/vdf_unit/.
    public static final int VARIANT_COUNT = 4;

    // Synched skin index - saved into NBT via the persistent-data merge.
    private static final EntityDataAccessor<Integer> DATA_VARIANT =
            SynchedEntityData.defineId(VdfUnitEntity.class, EntityDataSerializers.INT);

    // Client-side animation states. Driven in tick() below, straight off
    // Entity.walkAnimation (the vanilla synced movement tracker) - so the
    // legs move whenever the server-side position actually moves, and idle
    // the moment it stops. No SEM locomotion condition in the loop.
    public final AnimationState walkAnimationState = new AnimationState();
    public final AnimationState idleAnimationState = new AnimationState();
    public final AnimationState hurtAnimationState = new AnimationState();
    public final AnimationState deathAnimationState = new AnimationState();

    // Village bell / meeting-point this militia defends - set by the
    // garrison spawner, persisted so patrol/raid goals survive chunk reloads.
    @Nullable
    private BlockPos villageCenter;

    // Militia small-arms pool - village-appropriate rifles/shotguns/SMGs
    // matching data/sem_classsystem/class_loadouts/vdf_units.json. Kept as
    // code (not datapack) because VDF is classless and never joins the
    // weapon-class system - the table is only here so the armament list
    // stays visible next to the other factions'.
    private static final ResourceLocation[] MILITIA_GUNS = {
            ResourceLocation.fromNamespaceAndPath("tacz", "ak47"),
            ResourceLocation.fromNamespaceAndPath("tacz", "type_81"),
            ResourceLocation.fromNamespaceAndPath("tacz", "sks_tactical"),
            ResourceLocation.fromNamespaceAndPath("tacz", "m870"),
            ResourceLocation.fromNamespaceAndPath("tacz", "spas_12"),
            ResourceLocation.fromNamespaceAndPath("tacz", "uzi"),
            ResourceLocation.fromNamespaceAndPath("tacz", "hk_mp5a5"),
    };
    private static final int[] MILITIA_AMMO = {30, 30, 10, 7, 8, 32, 30};
    private static final FireMode[] MILITIA_MODE = {
            FireMode.SEMI, FireMode.SEMI, FireMode.SEMI,
            FireMode.SEMI, FireMode.SEMI, FireMode.AUTO, FireMode.AUTO};

    public VdfUnitEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        setVariant(this.random.nextInt(VARIANT_COUNT));
        // Militia is a garrison, not wildlife - never let spawn rules
        // despawn a posted guard.
        this.setPersistenceRequired();
        this.setCanPickUpLoot(false);
        // Monster bases get xpReward 5 for free - keep the same bounty
        // now that the class sits on PathfinderMob.
        this.xpReward = 5;
    }

    // Vanilla attribute set - UnitClassAttributes.apply() overwrites the
    // bases with the [vdf_unit] config numbers on spawn/load, same as the
    // SEM factions.
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.35D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.ATTACK_DAMAGE, 4.0D)
                .add(Attributes.ARMOR, 0.0D);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_VARIANT, 0);
    }

    public int getVariant() {
        return this.entityData.get(DATA_VARIANT);
    }

    public void setVariant(int variant) {
        this.entityData.set(DATA_VARIANT, Math.floorMod(variant, VARIANT_COUNT));
    }

    // ---- client animation ---------------------------------------------------

    // Drive the four animation states straight off the synced movement
    // tracker. walkAnimation.speed() is exactly what vanilla feeds into
    // limbSwingAmount, so the walk clip plays iff the body is actually
    // translating - this is the root fix for the "sliding legs" bug.
    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            if (this.walkAnimation.speed() > 0.02F) {
                this.walkAnimationState.startIfStopped(this.tickCount);
                this.idleAnimationState.stop();
            } else {
                this.idleAnimationState.startIfStopped(this.tickCount);
                this.walkAnimationState.stop();
            }
            if (this.hurtTime > 0) {
                this.hurtAnimationState.startIfStopped(this.tickCount);
            }
            if (!this.isAlive() && !this.deathAnimationState.isStarted()) {
                this.deathAnimationState.start(this.tickCount);
            }
        }
    }

    // ---- village anchor -----------------------------------------------------

    @Nullable
    public BlockPos getVillageCenter() {                                // patrol/raid goals anchor here
        return this.villageCenter;
    }

    public void setVillageCenter(@Nullable BlockPos pos) {              // garrison spawner assigns the bell
        this.villageCenter = pos;
    }

    // ---- ally rules -----------------------------------------------------------

    // True when a living entity is wearing all 4 pieces of the VDF militia
    // armor set ("Village Warden" 4-piece bonus: VDF treats the wearer as a
    // sworn village ally and rallies to their defense).
    public static boolean isWearingFullVdfSet(@Nullable Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return false;
        }
        return living.getItemBySlot(EquipmentSlot.HEAD).is(SemcsRegistries.VDF_HELMET.get())
                && living.getItemBySlot(EquipmentSlot.CHEST).is(SemcsRegistries.VDF_VEST.get())
                && living.getItemBySlot(EquipmentSlot.LEGS).is(SemcsRegistries.VDF_PANTS.get())
                && living.getItemBySlot(EquipmentSlot.FEET).is(SemcsRegistries.VDF_BOOTS.get());
    }

    // Entities a VDF militia member is sworn never to harm or target:
    // villagers, golems, fellow VDF militia, neutral PMC/BHQ operators, and
    // anyone wearing the full 4-piece VDF Village Warden armor set.
    public static boolean isProtectedAlly(@Nullable Entity entity) {
        return entity instanceof VdfUnitEntity
                || entity instanceof AbstractVillager
                || entity instanceof IronGolem
                || entity instanceof PmcUnitEntity
                || isWearingFullVdfSet(entity);
    }

    @Override
    public boolean isAlliedTo(Entity other) {
        if (other != null && isProtectedAlly(other)) {
            return true;
        }
        return super.isAlliedTo(other);
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (isProtectedAlly(target)) {
            return false;
        }
        if (target instanceof Player && target != this.getLastHurtByMob()) {
            return false;
        }
        return super.canAttack(target);
    }

    // Single chokepoint for all target assignment (goals, bullet-impact
    // reactions, and target sharing).
    @Override
    public void setTarget(@Nullable LivingEntity target) {
        if (target != null) {
            if (isProtectedAlly(target)) {
                return;
            }
            if (target instanceof Player && target != this.getLastHurtByMob()) {
                return;
            }
        }
        super.setTarget(target);
    }

    // ---- AI -----------------------------------------------------------------

    @Override
    protected void registerGoals() {
        // --- combat core (our own two-goal package replaces SEM's role goals) ---
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new com.poseidon.sem_classsystem.entity.ai.VdfGunAttackGoal(this, 1.0D));
        this.goalSelector.addGoal(2, new com.poseidon.sem_classsystem.entity.ai.VdfMeleeAttackGoal(this, 1.15D, false));

        // --- militia duties ---
        this.goalSelector.addGoal(3, new com.poseidon.sem_classsystem.entity.ai.VdfRaidResponseGoal(this));
        this.goalSelector.addGoal(3, new com.poseidon.sem_classsystem.entity.ai.VdfInteractGoal(this));  // greeting preempts the stroll
        this.goalSelector.addGoal(3, new com.poseidon.sem_classsystem.entity.ai.VdfNightWatchGoal(this)); // dusk watch + rain shelter
        this.goalSelector.addGoal(4, new com.poseidon.sem_classsystem.entity.ai.VdfPatrolGoal(this));

        // --- idle presence ---
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 10.0F, 0.02F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        // Retaliate when hit; alert nearby militia so the garrison reacts as
        // one. The varargs are damage sources we NEVER retaliate against -
        // villagers/golems/militia are protected, and PmcUnitEntity covers the
        // PMC + BHQ neutrality pact (BhqUnitEntity extends it): a stray
        // friendly shot must not start a war with the player's own soldiers.
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this,
                        AbstractVillager.class,
                        IronGolem.class,
                        VdfUnitEntity.class,
                        PmcUnitEntity.class)
                .setAlertOthers(VdfUnitEntity.class)
                .setUnseenMemoryTicks(300));

        // Raid members first - they get their own goal so raid response can
        // outrank ambient monsters during an active raid. 32.0 matches the
        // verticalRange SEM passes on its own units.
        this.targetSelector.addGoal(2, new VerticalAwareTargetGoal<>(
                this, Raider.class, true, 32.0D));

        // Every other hostile mob in the village's sight bubble (zombies,
        // skeletons, spiders, vexes...) - raiders handled above. Both
        // exclusions are LOAD-BEARING: SEM units are Monsters (the tag goal
        // below handles them deliberately, not by accident) and the
        // VdfUnitEntity exclusion keeps the garrison from flagging any
        // Monster-typed straggler ally as a threat.
        this.targetSelector.addGoal(3, new VerticalAwareTargetGoal<>(
                this, Monster.class, true, 32.0D,
                e -> !(e instanceof Raider)
                        && !(e instanceof VdfUnitEntity)
                        && !(e instanceof AbstractUnit)
                        && !isProtectedAlly(e)));

        // SEM's foreign armies - tag checks, NOT instanceof, so VDF (which is
        // in no faction tag) never targets itself
        this.targetSelector.addGoal(3, new VerticalAwareTargetGoal<>(
                this, LivingEntity.class, true, 32.0D,
                e -> UnitFactionTags.isRuUnit(e) || UnitFactionTags.isUsUnit(e)));
    }

    // ---- armament -----------------------------------------------------------

    // Rolls the militia mix: gunner gets a village-appropriate firearm with
    // dummy-ammo reserve (unlimited reloads, SEM convention), melee militia
    // gets a blade. Guns come from the datapack table
    // data/*/class_loadouts/vdf_units.json - the same pool MixinWeaponEquippers
    // used to route VDF through - so pack authors can retune the armament.
    private void equipMilitiaWeapon() {
        if (this.random.nextFloat() < ClassSystemConfig.vdfGunnerChance()) {
            ItemStack gun = rollMilitiaGun();
            IGun iGun = IGun.getIGunOrNull(gun);
            if (iGun != null) {
                iGun.setMaxDummyAmmoAmount(gun, Integer.MAX_VALUE);     // unlimited reserve
                iGun.setDummyAmmoAmount(gun, 9999);
                this.setItemSlot(EquipmentSlot.MAINHAND, gun);
                this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);       // stays on the corpse
                return;
            }
        }
        // Melee militia - swords only (axes read as 2H greatswords)
        ItemStack melee = new ItemStack(Items.IRON_SWORD);
        this.setItemSlot(EquipmentSlot.MAINHAND, melee);
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    }

    // One gun stack for a militia gunner: datapack table first, inline
    // pool as fallback so a missing table never disarms a unit.
    private ItemStack rollMilitiaGun() {
        WeaponClass[] militiaPool = {
                WeaponClass.RIFLE, WeaponClass.RIFLE,
                WeaponClass.SHOTGUN, WeaponClass.SMG,
                WeaponClass.SNIPER, WeaponClass.PISTOL
        };
        net.nekoyuni.SimpleEnemyMod.data.UnitLoadout loadout =
                com.poseidon.sem_classsystem.faction.ClassLoadouts.pick(
                        "vdf_units", militiaPool[this.random.nextInt(militiaPool.length)], this.random);
        if (loadout != null && loadout.gunId != null) {
            GunItemBuilder builder = GunItemBuilder.create()
                    .setId(loadout.gunId)
                    .setAmmoCount(loadout.ammoCount)
                    .setCount(1);
            try {
                builder.setFireMode(FireMode.valueOf(loadout.fireMode));
            } catch (IllegalArgumentException ignored) {
            }
            return builder.build();
        }
        int pick = this.random.nextInt(MILITIA_GUNS.length);
        return GunItemBuilder.create()
                .setId(MILITIA_GUNS[pick])
                .setAmmoCount(MILITIA_AMMO[pick])
                .setFireMode(MILITIA_MODE[pick])
                .setCount(1)
                .build();
    }

    // Equips the VDF militia armor set with 0% vanilla drop chance so only
    // VdfDropEvents' configured player-kill roll awards pieces.
    public void equipMilitiaArmor() {
        this.setItemSlot(EquipmentSlot.HEAD, new ItemStack(SemcsRegistries.VDF_HELMET.get()));
        this.setItemSlot(EquipmentSlot.CHEST, new ItemStack(SemcsRegistries.VDF_VEST.get()));
        this.setItemSlot(EquipmentSlot.LEGS, new ItemStack(SemcsRegistries.VDF_PANTS.get()));
        this.setItemSlot(EquipmentSlot.FEET, new ItemStack(SemcsRegistries.VDF_BOOTS.get()));
        this.setDropChance(EquipmentSlot.HEAD, 0.0F);
        this.setDropChance(EquipmentSlot.CHEST, 0.0F);
        this.setDropChance(EquipmentSlot.LEGS, 0.0F);
        this.setDropChance(EquipmentSlot.FEET, 0.0F);
    }

    private void ensureVillageAnchor() {
        if (this.villageCenter != null || !(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos origin = this.blockPosition();
        this.villageCenter = serverLevel.getPoiManager()
                .findClosest(h -> h.is(PoiTypes.MEETING) || h.is(PoiTypes.HOME),
                        origin, 96, PoiManager.Occupancy.ANY)
                .orElse(origin);
    }

    // ---- spawn / persistence --------------------------------------------------

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level,
                                        DifficultyInstance difficulty,
                                        MobSpawnType spawnType,
                                        @Nullable SpawnGroupData groupData,
                                        @Nullable CompoundTag tag) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, spawnType, groupData, tag);
        // Configured [vdf_unit] stats - same application path as SEM units
        // get via the AbstractUnit mixin, just invoked directly here.
        UnitClassAttributes.apply(this, true);
        equipMilitiaArmor();
        equipMilitiaWeapon();
        ensureVillageAnchor();
        this.setPersistenceRequired();
        return data;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("vdfVariant", this.getVariant());
        if (this.villageCenter != null) {
            tag.putLong("vdfCenter", this.villageCenter.asLong());       // bell anchor survives restarts
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("vdfVariant")) {
            setVariant(tag.getInt("vdfVariant"));
        }
        this.villageCenter = tag.contains("vdfCenter")
                ? BlockPos.of(tag.getLong("vdfCenter"))
                : null;
        // Configured stats on load - keeps HP (clamps only when max dropped)
        UnitClassAttributes.apply(this, false);
        ensureVillageAnchor();
        if (this.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
            equipMilitiaArmor();
        }
    }

    // ---- voice ---------------------------------------------------------------

    // Villager hums instead of military voice lines - the whole point of the
    // unit is "armed villager", so it sounds like one.
    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.VILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) {
        return SoundEvents.VILLAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.VILLAGER_DEATH;
    }
}
