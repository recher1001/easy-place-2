package io.github.recher1001.easyplace2.config;

import java.util.List;

import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;

import io.github.recher1001.easyplace2.EasyPlace2;

public class Ep2Hotkeys implements IKeybindProvider
{
    public static final Ep2Hotkeys INSTANCE = new Ep2Hotkeys();

    private static final List<IHotkey> HOTKEYS = List.of(Ep2Configs.EASY_PLACE_2, Ep2Configs.PRINTER_MENU, Ep2Configs.PRINTER_TOGGLE,
            Ep2Configs.PRINTER_MARK_STORAGE, Ep2Configs.MODULE_INVSEE);

    @Override
    public void addKeysToMap(IKeybindManager manager)
    {
        for (IHotkey hotkey : HOTKEYS)
        {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
    }

    @Override
    public void addHotkeys(IKeybindManager manager)
    {
        manager.addHotkeysForCategory(EasyPlace2.MOD_NAME, EasyPlace2.MOD_ID + ".hotkeys.category.generic", HOTKEYS);
    }
}
