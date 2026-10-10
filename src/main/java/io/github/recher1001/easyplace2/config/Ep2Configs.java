package io.github.recher1001.easyplace2.config;

import java.nio.file.Files;
import java.nio.file.Path;
import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;

import io.github.recher1001.easyplace2.EasyPlace2;

/**
 * Eigene Einstellungen von Easy Place 2.0, gespeichert in config/easyplace2.json.
 * Litematicas eigene Einstellungen werden nicht angefasst.
 */
public class Ep2Configs implements IConfigHandler
{
    private static final String CONFIG_FILE_NAME = EasyPlace2.MOD_ID + ".json";
    private static final String KEY = EasyPlace2.MOD_ID + ".config";

    /** Vanilla wartet beim gedrueckt gehaltenen Rechtsklick 4 Ticks zwischen zwei Klicks. */
    public static final int VANILLA_USE_DELAY = 4;

    public static final ConfigBooleanHotkeyed EASY_PLACE_2   = new ConfigBooleanHotkeyed("easyPlace2Mode", false, "").apply(KEY);
    public static final ConfigInteger         DELAY_TICKS    = new ConfigInteger("easyPlace2DelayTicks", VANILLA_USE_DELAY, VANILLA_USE_DELAY, 40).apply(KEY);
    public static final ConfigBoolean         STRICT_CROSSHAIR = new ConfigBoolean("easyPlace2StrictCrosshair", false).apply(KEY);
    public static final ConfigBoolean         DEBUG          = new ConfigBoolean("easyPlace2Debug", false).apply(KEY);

    // Printer: baut die ganze Schematic automatisch, platziert wird aber immer ueber Easy Place 2.0
    public static final ConfigHotkey  PRINTER_MENU        = new ConfigHotkey("printerMenuHotkey", "O").apply(KEY);
    public static final ConfigHotkey  PRINTER_TOGGLE      = new ConfigHotkey("printerToggleHotkey", "").apply(KEY);
    public static final ConfigDouble  PRINTER_TURN_SPEED  = new ConfigDouble("printerTurnSpeed", 220.0, 40.0, 720.0).apply(KEY);
    public static final ConfigDouble  PRINTER_TURN_JITTER = new ConfigDouble("printerTurnJitter", 0.25, 0.0, 1.0).apply(KEY);
    public static final ConfigInteger PRINTER_EXTRA_DELAY = new ConfigInteger("printerExtraDelay", 3, 0, 40).apply(KEY);
    public static final ConfigInteger PRINTER_INV_DELAY_MIN = new ConfigInteger("printerInventoryDelayMin", 5, 1, 40).apply(KEY);
    public static final ConfigInteger PRINTER_INV_DELAY_MAX = new ConfigInteger("printerInventoryDelayMax", 10, 1, 60).apply(KEY);
    public static final ConfigDouble  PRINTER_REACH_MARGIN = new ConfigDouble("printerReachMargin", 0.6, 0.0, 2.5).apply(KEY);
    public static final ConfigBoolean PRINTER_USE_BARITONE = new ConfigBoolean("printerUseBaritone", true).apply(KEY);
    public static final ConfigBoolean PRINTER_SPRINT      = new ConfigBoolean("printerSprint", true).apply(KEY);
    public static final ConfigBoolean PRINTER_LAYER_RANGE = new ConfigBoolean("printerUseLayerRange", true).apply(KEY);
    public static final ConfigBoolean PRINTER_HUD         = new ConfigBoolean("printerHud", true).apply(KEY);
    // Drehen und Klicken
    public static final ConfigBoolean PRINTER_FAST_TURN   = new ConfigBoolean("printerFastTurn", false).apply(KEY);
    public static final ConfigDouble  PRINTER_FAST_TURN_MAX_ANGLE = new ConfigDouble("printerFastTurnMaxAngle", 75.0, 5.0, 180.0).apply(KEY);
    public static final ConfigInteger PRINTER_REACTION_MIN = new ConfigInteger("printerReactionMin", 1, 1, 20).apply(KEY);
    public static final ConfigInteger PRINTER_REACTION_MAX = new ConfigInteger("printerReactionMax", 3, 1, 30).apply(KEY);
    public static final ConfigBoolean PRINTER_PAUSE_ON_DAMAGE = new ConfigBoolean("printerPauseOnDamage", true).apply(KEY);
    // Aussehen des Menues
    public static final ConfigString  PRINTER_THEME       = new ConfigString("printerTheme", "vault").apply(KEY);
    public static final ConfigBoolean PRINTER_GLASS       = new ConfigBoolean("printerGlass", true).apply(KEY);
    public static final ConfigBoolean PRINTER_GRID_VIEW   = new ConfigBoolean("printerGridView", false).apply(KEY);

    public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
            EASY_PLACE_2,
            DELAY_TICKS,
            STRICT_CROSSHAIR,
            DEBUG,
            PRINTER_MENU,
            PRINTER_TOGGLE,
            PRINTER_TURN_SPEED,
            PRINTER_TURN_JITTER,
            PRINTER_EXTRA_DELAY,
            PRINTER_INV_DELAY_MIN,
            PRINTER_INV_DELAY_MAX,
            PRINTER_REACH_MARGIN,
            PRINTER_USE_BARITONE,
            PRINTER_SPRINT,
            PRINTER_LAYER_RANGE,
            PRINTER_HUD,
            PRINTER_FAST_TURN,
            PRINTER_FAST_TURN_MAX_ANGLE,
            PRINTER_REACTION_MIN,
            PRINTER_REACTION_MAX,
            PRINTER_PAUSE_ON_DAMAGE,
            PRINTER_THEME,
            PRINTER_GLASS,
            PRINTER_GRID_VIEW
    );

    public static void loadFromFile()
    {
        Path configFile = FileUtils.getConfigDirectoryAsPath().resolve(CONFIG_FILE_NAME);

        if (Files.exists(configFile) && Files.isReadable(configFile))
        {
            JsonElement element = JsonUtils.parseJsonFileAsPath(configFile);

            if (element != null && element.isJsonObject())
            {
                ConfigUtils.readConfigBase(element.getAsJsonObject(), "Generic", OPTIONS);
            }
            else
            {
                EasyPlace2.LOGGER.error("Konnte '{}' nicht lesen", configFile.toAbsolutePath());
            }
        }
    }

    public static void saveToFile()
    {
        Path dir = FileUtils.getConfigDirectoryAsPath();

        if (!Files.exists(dir))
        {
            FileUtils.createDirectoriesIfMissing(dir);
        }

        if (Files.isDirectory(dir))
        {
            JsonObject root = new JsonObject();
            ConfigUtils.writeConfigBase(root, "Generic", OPTIONS);
            JsonUtils.writeJsonToFileAsPath(root, dir.resolve(CONFIG_FILE_NAME));
        }
    }

    @Override
    public void load()
    {
        loadFromFile();
    }

    @Override
    public void save()
    {
        saveToFile();
    }
}
