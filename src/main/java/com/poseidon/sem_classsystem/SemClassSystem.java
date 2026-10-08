// Package declaration - every class in this mod lives under com.poseidon.sem_classsystem
package com.poseidon.sem_classsystem;

// Mojang's logging helper - gives us a Logger that writes to latest.log / debug.log
import com.mojang.logging.LogUtils;
// Our own config class - defines the per-faction attribute values in the .toml file
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
// Forge's @Mod annotation - marks this class as THE entry point of the mod
import net.minecraftforge.fml.common.Mod;
// The Logger interface itself (SLF4J implementation underneath)
import org.slf4j.Logger;

// @Mod registers this class with Forge. MODID must match the modId in mods.toml.
@Mod(SemClassSystem.MODID)
public class SemClassSystem {

    // The mod's unique ID. Forge uses it everywhere: mods.toml, mixin config, log lines.
    // Rules: lowercase letters, digits and underscores only.
    public static final String MODID = "sem_classsystem";

    // Shared logger so we can print messages to the game log/console.
    // Public because the datapack listener reports loadout parse issues through it.
    public static final Logger LOGGER = LogUtils.getLogger();

    // The mod constructor. Forge calls this once when it discovers and loads the mod.
    // No-arg on purpose - works on every Forge 47.x version (SEM 0.1.5 uses a
    // constructor that takes FMLJavaModLoadingContext, which older Forge can't call).
    public SemClassSystem() {
        // Register our config file. Type.SERVER means Forge creates
        // saves/<world>/serverconfig/sem_classsystem-server.toml for each world.
        ClassSystemConfig.register();
        // Print a line to the log so you can confirm the mod actually loaded in-game
        LOGGER.info("[SEM-CS] Class System loaded - per-faction unit attributes active");
    }
}
