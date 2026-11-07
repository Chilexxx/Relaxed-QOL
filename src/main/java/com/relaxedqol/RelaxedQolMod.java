package com.relaxedqol;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RelaxedQolMod implements ModInitializer {
    public static final String MOD_ID = "relaxed-qol";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Relaxed QOL");
    }
}
