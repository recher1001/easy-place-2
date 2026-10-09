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
import fi.dy.masa.malilib.config.options.ConfigInteger;
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

    public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
            EASY_PLACE_2,
            DELAY_TICKS,
            STRICT_CROSSHAIR,
            DEBUG
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
