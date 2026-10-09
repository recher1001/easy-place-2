package io.github.recher1001.easyplace2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;

public class EasyPlace2 implements ClientModInitializer
{
    public static final String MOD_ID = "easyplace2";
    public static final Logger LOGGER = LoggerFactory.getLogger("Easy Place 2.0");

    @Override
    public void onInitializeClient()
    {
        LOGGER.info("Easy Place 2.0 geladen");
    }
}
