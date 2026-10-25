// Entity package - our own soldier types (VDF militia lives here)
package com.poseidon.sem_classsystem.entity;

import net.minecraft.nbt.CompoundTag;                                   // village-center NBT persistence
import net.minecraft.world.DifficultyInstance;                        // finalizeSpawn signature arg
import net.minecraft.world.entity.EntityType;                         // entity type parameter
import net.minecraft.world.entity.EquipmentSlot;                      // hand slot for the melee loadout
import net.minecraft.world.entity.LivingEntity;                       // target predicate entity type
import net.minecraft.world.entity.MobSpawnType;                       // finalizeSpawn signature arg
import net.minecraft.world.entity.SpawnGroupData;                     // finalizeSpawn signature + return
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;    // retaliate when hit
import net.minecraft.world.entity.monster.Monster;                    // hostile-mob target class
import net.minecraft.world.entity.raid.Raider;                        // raid mobs (pillagers, vindicators, ravagers)
import net.minecraft.world.item.ItemStack;                            // melee loadout stack
import net.minecraft.world.item.Items;                                // IRON_SWORD / IRON_AXE
import net.minecraft.world.level.ServerLevelAccessor;                 // finalizeSpawn signature arg
import net.minecraft.world.level.Level;                               // entity world reference
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.VerticalAwareTargetGoal;  // SEM's 3D-aware nearest-target goal
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;           // all SEM units are Monsters - target exclusion
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.utils.UnitRole;    // SEM role enum (DEFAULT = combat core)
import net.nekoyuni.SimpleEnemyMod.entity.equipment.RuWeaponEquipper; // RU gun kit - unlimited ammo path
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;          // enemy-line unit base (no inventory, no owner)
import net.nekoyuni.SimpleEnemyMod.entity.unit.util.UnitFactionTags;  // ru_units/us_units tag checks
import net.minecraft.sounds.SoundEvent;                               // custom sound return type
import net.minecraft.sounds.SoundEvents;                              // villager hums
import net.minecraft.core.BlockPos;                                   // village center anchor
import net.minecraft.server.level.ServerLevel;                        // POI scan on spawn/load
import net.minecraft.world.entity.Entity;                             // alliance & friendly-fire checks
import net.minecraft.world.entity.ai.village.poi.PoiManager;          // village bell/bed lookup
import net.minecraft.world.entity.ai.village.poi.PoiTypes;            // MEETING / HOME POIs
import net.minecraft.world.entity.animal.IronGolem;                   // protected village defender
import net.minecraft.world.entity.npc.AbstractVillager;               // protected villagers
import net.minecraft.world.entity.player.Player;                      // player neutrality gate
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;         // PMC + BHQ neutrality pact
import com.poseidon.sem_classsystem.config.ClassSystemConfig;         // [vdf] garrison + armament options
import com.poseidon.sem_classsystem.registry.SemcsRegistries;         // vdf_* militia armor set
import com.tacz.guns.api.item.IGun;                                   // launcher safety check
import com.tacz.guns.api.item.builder.GunItemBuilder;                 // fallback militia rifle
import com.tacz.guns.api.item.gun.FireMode;                           // SEMI/AUTO
import net.minecraft.resources.ResourceLocation;                      // gun id check
import org.jetbrains.annotations.Nullable;                            // Nullable marker for the anchor

// VDF (Village Defense Force) - village militia, NOT a faction soldier.
// Extends RUunitEntity (SEM's enemy line) on purpose: RU units have no
// inventory GUI, no owner, no recruit path and unlimited gun ammo - all
// the traits we want for an autonomous village garrison.
//
// What differs from a plain RU unit:
//   - targeting: swaps RU's anti-PMC target list for monsters/raiders/RU+US
//   - stays out of every simpleenemymod:*_units tag (not a faction member)
//   - armament roll: ~60% gunner (RU kit), ~40% melee militia
//   - villager hums instead of military voice lines
//   - extra goals layered on in setupRoleGoals (patrol, raid, interact)
public class VdfUnitEntity extends RUunitEntity {

    // How many militia skins ship under textures/entity/vdf_unit/.
    public static final int VARIANT_COUNT = 4;

    // Village bell / meeting-point this militia defends - set by the
    // garrison spawner, persisted so patrol/raid goals survive chunk reloads.
    @Nullable
    private BlockPos villageCenter;

    public VdfUnitEntity(EntityType<? extends Monster> entityType, Level level) {
        super(entityType, level);
        // Parent ctor rolled 0..N for RU skins - re-roll over our own range
        setVariant(this.random.nextInt(VARIANT_COUNT));
    }

    // ---- village anchor -----------------------------------------------------

    @Nullable
    public BlockPos getVillageCenter() {                                // patrol/raid goals anchor here
        return this.villageCenter;
    }

    public void setVillageCenter(@Nullable BlockPos pos) {              // garrison spawner assigns the bell
        this.villageCenter = pos;
    }

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

    // Single chokepoint for all target assignment (goals, SEM's
    // BulletImpactEventHandler block-hit reactions, and radio target sharing).
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

    // ---- combat targeting ---------------------------------------------------

    // Replaces RU's target list entirely. Called by AbstractUnit.finalizeSpawn
    // and whenever SEM re-rolls the role - must stay idempotent and must not
    // touch instance state (virtual call can fire during parent construction).
    @Override
    public void setupRoleGoals() {
        this.targetSelector.removeAllGoals(goal -> true);               // wipe RU's anti-PMC targets
        this.goalSelector.removeAllGoals(goal -> true);                 // wipe inherited movement goals (re-added below)

        // Retaliate when hit; alert nearby militia so the garrison reacts as
        // one. The varargs are damage sources we NEVER retaliate against -
        // villagers/golems/militia are protected, and PmcUnitEntity covers the
        // PMC + BHQ neutrality pact (BhqUnitEntity extends it): a stray
        // friendly shot must not start a war with the player's own soldiers.
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this,
                        net.minecraft.world.entity.npc.AbstractVillager.class,
                        net.minecraft.world.entity.animal.IronGolem.class,
                        VdfUnitEntity.class,
                        net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity.class)
                .setAlertOthers(VdfUnitEntity.class)
                .setUnseenMemoryTicks(300));

        // Raid members first - they get their own goal so raid response can
        // outrank ambient monsters during an active raid. 32.0 matches the
        // verticalRange SEM passes on its own units.
        this.targetSelector.addGoal(2, new VerticalAwareTargetGoal<>(
                this, Raider.class, true, 32.0D));

        // Every other hostile mob in the village's sight bubble (zombies,
        // skeletons, spiders, vexes...) - raiders handled above. The
        // AbstractUnit exclusion is LOAD-BEARING: every SEM unit (including
        // this one) IS a Monster, so without it the militia would target
        // itself, its own kind, PMC and BHQ - violating the neutrality pact.
        this.targetSelector.addGoal(3, new VerticalAwareTargetGoal<>(
                this, Monster.class, true, 32.0D,
                e -> !(e instanceof Raider) && !(e instanceof AbstractUnit)));

        // SEM's foreign armies - tag checks, NOT instanceof, so VDF (which is
        // in no faction tag) never targets itself
        this.targetSelector.addGoal(3, new VerticalAwareTargetGoal<>(
                this, LivingEntity.class, true, 32.0D,
                e -> UnitFactionTags.isRuUnit(e) || UnitFactionTags.isUsUnit(e)));

        // SEM's shared combat package: RangedGunAttackGoal, SeekCoverGoal,
        // PeekFromCoverGoal, TacticalManeuverGoal, doors, float. Sourced from
        // the role so a future leader role still composes correctly.
        if (getRole() != null) {
            getRole().getGoals().addGoals(this);
        }

        // Militia duties under the combat core: raid rally outranks the
        // idle patrol but loses to SEM's own combat goals (priority 1-2).
        this.goalSelector.addGoal(3, new com.poseidon.sem_classsystem.entity.ai.VdfRaidResponseGoal(this));
        this.goalSelector.addGoal(4, new com.poseidon.sem_classsystem.entity.ai.VdfPatrolGoal(this));
        this.goalSelector.addGoal(3, new com.poseidon.sem_classsystem.entity.ai.VdfInteractGoal(this)); // outranks patrol so the greeting can preempt the stroll
        this.goalSelector.addGoal(3, new com.poseidon.sem_classsystem.entity.ai.VdfNightWatchGoal(this)); // dusk watch + rain shelter, below raid response
    }

    // ---- armament -----------------------------------------------------------

    // Called by AbstractUnit.finalizeSpawn (virtual). Rolls the militia mix:
    // gunner gets the full RU weapon kit (unlimited ammo, SEM reserve logic),
    // melee militia gets a blade and relies on the mixin-injected melee goal.
    @Override
    public void equipRandomGun() {
        equipMilitiaArmor();
        if (this.random.nextFloat() < ClassSystemConfig.vdfGunnerChance()) {
            RuWeaponEquipper.equipRandomGun(this, this.random);          // gunner militia - rifle/pistol
            sanitizeMilitiaGun();                                        // no explosive launchers in villages
            equipMilitiaArmor();                                         // re-assert VDF armor over RU default
        } else {
            ItemStack melee = new ItemStack(Items.IRON_SWORD);           // melee militia - swords only (axes read as 2H greatswords)
            this.setItemSlot(EquipmentSlot.MAINHAND, melee);
            // Keep the village drops rare: militia gear stays on the corpse
            this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        }
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

    // Replaces any explosive launcher (RPG/MGL) rolled by the RU equipper with
    // a standard militia rifle so VDF never blows up the village it defends.
    private void sanitizeMilitiaGun() {
        ItemStack held = this.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(held);
        if (iGun == null) {
            return;
        }
        ResourceLocation id = iGun.getGunId(held);
        String path = id != null ? id.getPath() : "";
        if (path.contains("rpg") || path.contains("mgl") || path.contains("m202") || path.contains("m320")) {
            ResourceLocation fallbackId = ResourceLocation.fromNamespaceAndPath("tacz", "ak47");
            ItemStack rifle = GunItemBuilder.create()
                    .setId(fallbackId)
                    .setAmmoCount(30)
                    .setFireMode(FireMode.SEMI)
                    .setCount(1)
                    .build();
            IGun rifleGun = IGun.getIGunOrNull(rifle);
            if (rifleGun != null) {
                rifleGun.setMaxDummyAmmoAmount(rifle, Integer.MAX_VALUE);
                rifleGun.setDummyAmmoAmount(rifle, 9999);
                this.setItemSlot(EquipmentSlot.MAINHAND, rifle);
                this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
            }
        }
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
        // Militia always fights as a lone-wolf DEFAULT-role unit - no SEM
        // squads, no commander chain. Must run before super so the combat
        // core SEM adds inside finalizeSpawn sees the right role.
        setRole(UnitRole.DEFAULT);
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, spawnType, groupData, tag);
        ensureVillageAnchor();
        return data;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.villageCenter != null) {
            tag.putLong("vdfCenter", this.villageCenter.asLong());       // bell anchor survives restarts
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.villageCenter = tag.contains("vdfCenter")
                ? BlockPos.of(tag.getLong("vdfCenter"))
                : null;
        ensureVillageAnchor();
        if (this.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
            equipMilitiaArmor();
        }
    }

    // ---- voice ---------------------------------------------------------------

    // Villager hums instead of SEM's military voice sets - the whole point of
    // the unit is "armed villager", so it sounds like one.
    @Override
    protected SoundEvent getCustomHurtSound() {
        return SoundEvents.VILLAGER_HURT;
    }

    @Override
    protected SoundEvent getCustomDeathSound() {
        return SoundEvents.VILLAGER_DEATH;
    }

    @Override
    protected SoundEvent getCustomAlertSound() {
        return SoundEvents.VILLAGER_AMBIENT;
    }
}
