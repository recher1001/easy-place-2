package io.github.recher1001.easyplace2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InitializationHandler;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.KeyCallbackToggleBooleanConfigWithMessage;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.config.Ep2Hotkeys;
import io.github.recher1001.easyplace2.modules.InvseeModule;
import io.github.recher1001.easyplace2.printer.PrinterController;
import io.github.recher1001.easyplace2.printer.refill.StorageRegistry;
import io.github.recher1001.easyplace2.printer.gui.PrinterHud;
import io.github.recher1001.easyplace2.printer.gui.PrinterScreen;

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

            Ep2Configs.PRINTER_MENU.getKeybind().setCallback((action, key) ->
            {
                Minecraft.getInstance().setScreen(new PrinterScreen(null));
                return true;
            });
            Ep2Configs.PRINTER_TOGGLE.getKeybind().setCallback((action, key) ->
            {
                PrinterController.INSTANCE.toggle();
                return true;
            });
            Ep2Configs.PRINTER_MARK_STORAGE.getKeybind().setCallback((action, key) ->
            {
                StorageRegistry.toggleLookedAt(Minecraft.getInstance());
                return true;
            });
            Ep2Configs.MODULE_INVSEE.getKeybind().setCallback((action, key) ->
            {
                InvseeModule.trigger(Minecraft.getInstance());
                return true;
            });
        });

        PrinterController.INSTANCE.register();
        StorageRegistry.INSTANCE.register();
        PrinterHud.register();
    }
}
