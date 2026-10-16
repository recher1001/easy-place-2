package io.github.recher1001.easyplace2.printer.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleFunction;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;

import io.github.recher1001.easyplace2.config.Ep2Configs;

/**
 * Eine Einstellung, wie sie im Printer-Menue als Karte ("Modul") erscheint.
 */
public final class OptionEntry
{
    /** Seiten in der Seitenleiste. */
    public enum Page
    {
        OVERVIEW(Section.PRINTER, Items.COMPASS),
        MATERIALS(Section.PRINTER, Items.SHULKER_BOX),
        STORAGE(Section.PRINTER, Items.BARREL),
        PLACE(Section.PRINTER, Items.GRASS_BLOCK),
        TURN(Section.PRINTER, Items.ENDER_EYE),
        TIMING(Section.PRINTER, Items.CLOCK),
        WALK(Section.PRINTER, Items.LEATHER_BOOTS),
        INVENTORY(Section.PRINTER, Items.CHEST),
        BEHAVIOUR(Section.PRINTER, Items.WRITABLE_BOOK),
        LOOK(Section.GENERAL, Items.SPYGLASS),
        THEME(Section.GENERAL, Items.PAINTING),
        KEYS(Section.GENERAL, Items.TRIPWIRE_HOOK),
        INFO(Section.GENERAL, Items.BOOK),
        INVSEE(Section.MODULES, Items.ENDER_CHEST);

        public final Section section;
        private final net.minecraft.world.item.Item icon;

        Page(Section section, net.minecraft.world.item.Item icon)
        {
            this.section = section;
            this.icon = icon;
        }

        public ItemStack icon()
        {
            return new ItemStack(this.icon);
        }

        public String key()
        {
            return this.name().toLowerCase(Locale.ROOT);
        }

        public Component title()
        {
            return Component.translatable("easyplace2.printer.page." + this.key());
        }

        /** Seiten mit Optionskarten (Rest hat eigene Inhalte). */
        public boolean hasOptions()
        {
            return this != OVERVIEW && this != MATERIALS && this != STORAGE && this != THEME && this != KEYS && this != INFO;
        }
    }

    public enum Section { PRINTER, GENERAL, MODULES }

    public enum Kind { BOOL, INT, DOUBLE }

    public final Page page;
    public final String key;
    public final Kind kind;
    public final IConfigBase config;
    public final double min, max, step;
    public final DoubleFunction<String> format;

    private OptionEntry(Page page, String key, Kind kind, IConfigBase config, double min, double max, double step, DoubleFunction<String> format)
    {
        this.page = page;
        this.key = key;
        this.kind = kind;
        this.config = config;
        this.min = min;
        this.max = max;
        this.step = step;
        this.format = format;
    }

    public Component title()
    {
        return Component.translatable("easyplace2.printer.opt." + this.key);
    }

    public Component description()
    {
        return Component.translatable("easyplace2.printer.opt." + this.key + ".desc");
    }

    public boolean isBoolean()
    {
        return this.kind == Kind.BOOL;
    }

    public boolean getBoolean()
    {
        if (this.config instanceof ConfigBooleanHotkeyed hotkeyed)
        {
            return hotkeyed.getBooleanValue();
        }

        return this.config instanceof ConfigBoolean b && b.getBooleanValue();
    }

    public void setBoolean(boolean value)
    {
        if (this.config instanceof ConfigBooleanHotkeyed hotkeyed)
        {
            hotkeyed.setBooleanValue(value);
        }
        else if (this.config instanceof ConfigBoolean b)
        {
            b.setBooleanValue(value);
        }
    }

    public double getNumber()
    {
        if (this.config instanceof ConfigInteger i)
        {
            return i.getIntegerValue();
        }

        return this.config instanceof ConfigDouble d ? d.getDoubleValue() : 0;
    }

    public void setNumber(double value)
    {
        if (this.config instanceof ConfigInteger i)
        {
            i.setIntegerValue((int) Math.round(value));
        }
        else if (this.config instanceof ConfigDouble d)
        {
            d.setDoubleValue(value);
        }
    }

    public String formattedValue()
    {
        return this.format.apply(this.getNumber());
    }

    public boolean matches(String query)
    {
        String q = query.toLowerCase(Locale.ROOT).trim();
        return q.isEmpty() || this.title().getString().toLowerCase(Locale.ROOT).contains(q) ||
               this.description().getString().toLowerCase(Locale.ROOT).contains(q);
    }

    // ---------------------------------------------------------------- Alle Eintraege

    public static final List<OptionEntry> ALL = new ArrayList<>();

    static
    {
        bool(Page.PLACE, "easyPlace", Ep2Configs.EASY_PLACE_2);
        bool(Page.PLACE, "strict", Ep2Configs.STRICT_CROSSHAIR);
        bool(Page.PLACE, "restrict", Ep2Configs.RESTRICT_PLACEMENT);
        integer(Page.PLACE, "placeDelay", Ep2Configs.DELAY_TICKS, 4, 40);
        bool(Page.PLACE, "debug", Ep2Configs.DEBUG);

        number(Page.TURN, "turnSpeed", Ep2Configs.PRINTER_TURN_SPEED, 40, 720, 5, v -> String.format(Locale.ROOT, "%.0f°/s", v));
        number(Page.TURN, "turnJitter", Ep2Configs.PRINTER_TURN_JITTER, 0, 1, 0.05, v -> String.format(Locale.ROOT, "%.0f %%", v * 100));
        bool(Page.TURN, "fastTurn", Ep2Configs.PRINTER_FAST_TURN);
        number(Page.TURN, "fastTurnMaxAngle", Ep2Configs.PRINTER_FAST_TURN_MAX_ANGLE, 5, 180, 5, v -> String.format(Locale.ROOT, "%.0f°", v));

        integer(Page.TIMING, "extraDelay", Ep2Configs.PRINTER_EXTRA_DELAY, 0, 40);
        integer(Page.TIMING, "reactionMin", Ep2Configs.PRINTER_REACTION_MIN, 1, 20);
        integer(Page.TIMING, "reactionMax", Ep2Configs.PRINTER_REACTION_MAX, 1, 30);

        bool(Page.WALK, "useBaritone", Ep2Configs.PRINTER_USE_BARITONE);
        bool(Page.WALK, "sprint", Ep2Configs.PRINTER_SPRINT);
        number(Page.WALK, "reachMargin", Ep2Configs.PRINTER_REACH_MARGIN, 0, 2.5, 0.1, v -> String.format(Locale.ROOT, "%.1f", v));
        bool(Page.WALK, "scaffold", Ep2Configs.PRINTER_SCAFFOLD);
        bool(Page.WALK, "laneMode", Ep2Configs.PRINTER_LANE_MODE);
        ALL.add(new OptionEntry(Page.WALK, "laneSpacing", Kind.INT, Ep2Configs.PRINTER_LANE_SPACING, 1, 8, 1,
                v -> String.format(Locale.ROOT, "%d", Math.round(v))));
        bool(Page.WALK, "starTool", Ep2Configs.PRINTER_STAR_TOOL);
        ALL.add(new OptionEntry(Page.WALK, "stuckSeconds", Kind.INT, Ep2Configs.PRINTER_STUCK_SECONDS, 20, 600, 10,
                v -> String.format(Locale.ROOT, "%d s", Math.round(v))));

        integer(Page.INVENTORY, "invDelayMin", Ep2Configs.PRINTER_INV_DELAY_MIN, 1, 40);
        integer(Page.INVENTORY, "invDelayMax", Ep2Configs.PRINTER_INV_DELAY_MAX, 1, 60);
        bool(Page.INVENTORY, "shulkerRefill", Ep2Configs.PRINTER_SHULKER_REFILL);
        bool(Page.INVENTORY, "storageRefill", Ep2Configs.PRINTER_STORAGE_REFILL);

        bool(Page.BEHAVIOUR, "layerRange", Ep2Configs.PRINTER_LAYER_RANGE);
        bool(Page.BEHAVIOUR, "pauseOnDamage", Ep2Configs.PRINTER_PAUSE_ON_DAMAGE);
        bool(Page.BEHAVIOUR, "runInMenus", Ep2Configs.PRINTER_RUN_IN_MENUS);
        bool(Page.BEHAVIOUR, "excavate", Ep2Configs.PRINTER_EXCAVATE);
        bool(Page.BEHAVIOUR, "replaceWrong", Ep2Configs.PRINTER_REPLACE_WRONG);

        bool(Page.LOOK, "hud", Ep2Configs.PRINTER_HUD);
        bool(Page.LOOK, "glass", Ep2Configs.PRINTER_GLASS);
        bool(Page.LOOK, "gridView", Ep2Configs.PRINTER_GRID_VIEW);

        number(Page.INVSEE, "invseeAngle", Ep2Configs.MODULE_INVSEE_ANGLE, 5, 180, 5, v -> String.format(Locale.ROOT, "%.0f°", v));
        number(Page.INVSEE, "invseeRange", Ep2Configs.MODULE_INVSEE_RANGE, 4, 128, 1, v -> String.format(Locale.ROOT, "%.0f m", v));
    }

    public static List<OptionEntry> of(Page page)
    {
        return ALL.stream().filter(e -> e.page == page).toList();
    }

    private static void bool(Page page, String key, IConfigBase config)
    {
        ALL.add(new OptionEntry(page, key, Kind.BOOL, config, 0, 1, 1, v -> ""));
    }

    private static void integer(Page page, String key, ConfigInteger config, int min, int max)
    {
        ALL.add(new OptionEntry(page, key, Kind.INT, config, min, max, 1, v -> String.format(Locale.ROOT, "%d t", Math.round(v))));
    }

    private static void number(Page page, String key, ConfigDouble config, double min, double max, double step, DoubleFunction<String> format)
    {
        ALL.add(new OptionEntry(page, key, Kind.DOUBLE, config, min, max, step, format));
    }
}
