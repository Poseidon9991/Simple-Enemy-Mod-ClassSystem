# Rendering & Animation

## VDF rig (`VdfUnitModel` / `VdfUnitRenderer`)

Standalone `HierarchicalModel` — bone tree/pivots ported 1:1 from SEM's
`UnitModelDefinitions` so the shared `UNIT_*` clips and armor-bone sync
work, plus a villager nose cube under `head`. `setupAnim`:

```
resetPose → UNIT_WALK | UNIT_IDLE → UNIT_HURT/UNIT_DEATH
→ UnitProceduralAnimState.update → HeadTrackingLayer
→ applyCombatStance (non-minigun) → AdvancedWeaponPoseLayer
→ tail pass (locomotion, gun procedural, use-item, prone, crouch stance)
```

`VdfHeldItemLayer` anchors guns at `rightHandAnchor` with SEM's three
stances: shouldered (combat), lowered carry (idle), minigun hip-carry —
blended on `smoothCombatBlend` with recoil kick and reload cant.

## Procedural pose pipeline

- `UnitProceduralAnimState` — per-entity client state (`WeakHashMap`-keyed):
  combat/walk/crouch/prone blends, recoil impulse, reload + bolt progress
- `UnitProceduralPoses` — the pose library extracted from
  `MixinUnitModelCrouch`: low-ready↔combat blend, locomotion polish,
  recoil/reload/bolt, use-item, prone, 7 class crouch stances.
  `effectiveClass()` infers class from held gun for classless units (VDF)
- SEM layers reused directly: `AdvancedWeaponPoseLayer` (rifle/hipfire/
  pistol arm poses), `HeadTrackingLayer` (clamped head-follow)
- `CombatStateLayer` NOT reused — it hard-casts `AbstractUnit`; its logic
  was ported into `UnitProceduralPoses` for VDF

## Armor layers

`BhqArmorItem` + GeckoLib geo (`geo/*.geo.json`, `textures/models/armor/`).
`VdfArmorLayer` binds the same armor pieces to the VDF rig;
`MixinUniversalArmorLayer`/`MixinGeckoArmorTexture` patch SEM's armor
rendering (e.g., texture overrides, marker bones `armorLeftLeg`/`armorRightLeg`
restored in boots geos for correct leg tracking).

## Curios

`SemcsCurioItem` base — `spotting_scope` (head, `SpottingScopeCurioRenderer`
HumanoidModel-following 3D monocular on a 64×64 atlas), `sergeants_insignia`
(charm), `ifak_pouch` (belt, 8 uses). `SemcsCurios` registers them as ICurio
behind `ModList.isLoaded("curios")`; `UnitCuriosRenderLayer` + the
`bhqunit.json` entity file let units wear curios in all 10 slots.

## Other renderers

- `SemcsArmorItemRenderer` — item-form armor previews
- `TabletScreen`, `GuardPostScreen`, `RecruitTableScreen` — GUIs
- Particles: `MuzzleFlashParticle`, `SparkParticle`, `ZzzParticle`
- `client/xaero/` — unit markers/routes on minimap + worldmap
