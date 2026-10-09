package io.github.recher1001.easyplace2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InitializationHandler;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.KeyCallbackToggleBooleanConfigWithMessage;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.config.Ep2Hotkeys;

public class EasyPlace2 implements ClientModInitializer
{
    public static final String MOD_ID = "easyplace2";
    public static final String MOD_NAME = "Easy Place 2.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    @Override
    public void onInitializeClient()
    {
        // Wie bei Litematica: Registrierung erst, wenn MaLiLib bereit ist
        InitializationHandler.getInstance().registerInitializationHandler(() ->
        {
            ConfigManager.getInstance().registerConfigHandler(MOD_ID, new Ep2Configs());
            InputEventHandler.getKeybindManager().registerKeybindProvider(Ep2Hotkeys.INSTANCE);

            Ep2Configs.EASY_PLACE_2.getKeybind().setCallback(
                    new KeyCallbackToggleBooleanConfigWithMessage(Ep2Configs.EASY_PLACE_2));
        });
    }
}
