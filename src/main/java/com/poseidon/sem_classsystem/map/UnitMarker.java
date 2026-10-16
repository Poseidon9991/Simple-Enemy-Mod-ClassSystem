// Map package - Xaero-free wire data for the world-map unit overlay
package com.poseidon.sem_classsystem.map;

// One synced unit on the map. A plain data record: written by the server
// tick tracker, read by the client store and (when Xaero is present) the
// element renderer. No Xaero types here on purpose - the packet handler
// must classload fine without the map mod installed.
public record UnitMarker(
        int entityId,
        double x, double y, double z,
        String dimension,        // ResourceLocation string of the unit's world
        int rgb,                 // faction colour, ARGB-free (0xRRGGBB)
        int classOrd,            // WeaponClass.ordinal(), -1 when classless
        Allegiance allegiance,
        float healthFrac,
        String name,             // custom callsign, "" when unnamed
        int orderOrd,            // SEM OrderType.ordinal() the unit holds
        int dutyMode,            // 0 none, 1 guard, 2 patrol chunk, 3 route
        int dutyChunkX,          // guard/patrol chunk (or first route node)
        int dutyChunkZ,
        long[] route,            // BlockPos.asLong waypoints; OWN route duty
                                 // only, empty otherwise
        String rankTitle,        // veterancy title ("Sgt."), "" when unranked
        float health,            // raw HP for the hover tooltip
        float maxHealth,
        int factionOrd,          // UnitClass.ordinal(), -1 when unclassified
        // --- squad supply status (tablet UI); OWN units only, 0/false else ---
        int ammoRounds,          // loose rounds + box contents + loaded mag
        boolean ammoInfinite,    // a creative/all-type ammo box is carried
        boolean hasFood,         // at least one edible item in inventory
        boolean hasMedical       // at least one bandage/medkit/heal potion
) {

    public enum Allegiance {
        OWN,        // commandable - owned by the viewing player
        FRIENDLY,   // owned by someone else
        HOSTILE     // ownerless field unit (RU/US patrols, wild PMC)
    }
}
