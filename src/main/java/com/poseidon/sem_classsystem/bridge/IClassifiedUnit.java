// Bridge package - interfaces our mixins inject onto SEM entity classes
package com.poseidon.sem_classsystem.bridge;

import com.poseidon.sem_classsystem.faction.WeaponClass;

// Injected onto AbstractUnit by MixinAbstractUnit (sewv-style interface bridge).
// Lets any code ask an entity "which weapon class is this unit?" without
// casting to our mixin class - the entity itself IS the implementation.
public interface IClassifiedUnit {

    // The unit's assigned class, or null if it has not rolled one yet
    WeaponClass semcs$getWeaponClass();

    // Assigns the unit's class (called once at spawn, or restored from NBT)
    void semcs$setWeaponClass(WeaponClass weaponClass);
}
