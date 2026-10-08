package com.poseidon.sem_classsystem;

import com.mojang.logging.LogUtils;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(SemClassSystem.MODID)
public class SemClassSystem {

    public static final String MODID = "sem_classsystem";
    private static final Logger LOGGER = LogUtils.getLogger();

    public SemClassSystem() {
        ClassSystemConfig.register();
        LOGGER.info("[SEM-CS] Class System loaded - per-faction unit attributes active");
    }
}
