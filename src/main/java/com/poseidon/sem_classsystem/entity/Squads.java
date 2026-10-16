// Entity package - fireteam tags on owned units
package com.poseidon.sem_classsystem.entity;

import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

// One-word squad tag ("alpha", "recon") stored in persistentData NBT so it
// survives reloads. Applied via /semcs group, consumed by order_group /
// dismiss_group and shown on the tablet roster.
public final class Squads {

    private static final String KEY = "semcs:squad";
    public static final int MAX_LEN = 12;

    private Squads() {
    }

    // Normalised squad name (lowercase, "" when unassigned).
    public static String squad(PmcUnitEntity unit) {
        return unit.getPersistentData().getString(KEY);
    }

    public static void setSquad(PmcUnitEntity unit, String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("clear")
                || name.equalsIgnoreCase("none")) {
            unit.getPersistentData().remove(KEY);
        } else {
            String trimmed = name.trim().toLowerCase(java.util.Locale.ROOT);
            unit.getPersistentData().putString(KEY,
                    trimmed.length() > MAX_LEN ? trimmed.substring(0, MAX_LEN) : trimmed);
        }
    }
}
