package io.github.recher1001.easyplace2.printer.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import fi.dy.masa.malilib.config.IConfigResettable;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.modules.macro.Macro;
import io.github.recher1001.easyplace2.modules.macro.MacroPlayer;
import io.github.recher1001.easyplace2.modules.macro.MacroRecorder;
import io.github.recher1001.easyplace2.modules.macro.MacroStep;
import io.github.recher1001.easyplace2.modules.macro.MacroStore;
import io.github.recher1001.easyplace2.printer.BaritoneBridge;
import io.github.recher1001.easyplace2.printer.InventoryManager;
import io.github.recher1001.easyplace2.printer.PrinterController;
import io.github.recher1001.easyplace2.printer.SchematicScanner;
import io.github.recher1001.easyplace2.printer.ShulkerContents;
import io.github.recher1001.easyplace2.printer.gui.OptionEntry.Page;
import io.github.recher1001.easyplace2.printer.refill.StorageRegistry;

/**
 * Das Printer-Menue: Seitenleiste mit Kategorien, rechts Uebersicht, Optionen als Karten
 * (Liste oder Kacheln), Themes, Tasten und Info.
 */
public class PrinterScreen extends Screen
{
    private static Page page = Page.OVERVIEW;
    private static String query = "";

    private static final int SIDEBAR_W = 132;
    private static final int ROW_H = 30;
    private static final int GAP = 6;

    @Nullable private final Screen parent;
    private final List<Controls.Control> sidebar = new ArrayList<>();
    private final List<Controls.Control> header = new ArrayList<>();
    private final List<Controls.Control> content = new ArrayList<>();
    private final List<Integer> contentBaseY = new ArrayList<>();
    private final Map<Page, Integer> sidebarY = new EnumMap<>(Page.class);
    @Nullable private Controls.Control dragging;
    @Nullable private EditBox search;
    @Nullable private IHotkey capturing;

    private int x0, y0, x1, y1;
    private int cx0, cx1, headerTop, contentTop, contentBottom;
    private float scroll, scrollTarget;
    private int maxScroll;
    private final List<Integer> sidebarBaseY = new ArrayList<>();
    private int sideTop, sideBottom, sideStep = 19, sideScroll, sideMaxScroll;
    private float highlightY = -1f;
    private float shownProgress = -1f;
    private long openedAt;

    public PrinterScreen(@Nullable Screen parent)
    {
        this(parent, null);
    }

    public PrinterScreen(@Nullable Screen parent, @Nullable Page start)
    {
        super(Component.translatable("easyplace2.printer.title"));
        this.parent = parent;

        if (start != null)
        {
            page = start;
            query = "";
        }
    }

    // ---------------------------------------------------------------- Aufbau

    @Override
    protected void init()
    {
        int w = Math.min(560, this.width - 20);
        int h = Math.min(340, this.height - 20);
        this.x0 = (this.width - w) / 2;
        this.y0 = (this.height - h) / 2;
        this.x1 = this.x0 + w;
        this.y1 = this.y0 + h;
        this.cx0 = this.x0 + 6 + SIDEBAR_W + 12;
        this.cx1 = this.x1 - 14;
        this.headerTop = this.y0 + 12;
        this.contentTop = this.y0 + 60;
        this.contentBottom = this.y1 - 12;

        if (this.openedAt == 0)
        {
            this.openedAt = System.currentTimeMillis();
        }

        this.buildSidebar();

        int boxW = Math.max(70, Math.min(150, (this.cx1 - this.cx0) / 3));
        this.search = new EditBox(this.font, this.cx1 - boxW + 18, this.headerTop + 3, boxW - 24, 10, Component.empty());
        this.search.setBordered(false);
        this.search.setMaxLength(40);
        this.search.setTextColor(Ui.text());
        this.search.setHint(Component.translatable("easyplace2.printer.search"));
        this.search.setValue(query);
        this.search.setResponder(text ->
        {
            if (!text.equals(query))
            {
                query = text;
                this.scrollTarget = 0;
                this.rebuildContent();
            }
        });
        this.addWidget(this.search);

        this.rebuildContent();
    }

    private void buildSidebar()
    {
        this.sidebar.clear();
        this.sidebarBaseY.clear();
        this.sidebarY.clear();
        int sx = this.x0 + 6;
        this.sideTop = this.y0 + 38;
        this.sideBottom = this.y1 - 32;

        // Zeilenhoehe an den Platz anpassen (kleine Fenster / grosse GUI-Skalierung), notfalls scrollen
        int pages = Page.values().length;
        int fixed = OptionEntry.Section.values().length * 12 + 6;
        this.sideStep = Math.max(15, Math.min(19, (this.sideBottom - this.sideTop - fixed) / pages));
        int y = this.sideTop;
        OptionEntry.Section section = null;

        for (Page p : Page.values())
        {
            if (p.section != section)
            {
                if (section != null)
                {
                    y += 6;
                }

                section = p.section;
                OptionEntry.Section label = section;
                this.addSidebar(new Controls.Area(sx + 10, y, SIDEBAR_W - 20, 10, (g, font, a, hover) ->
                        Ui.scaledText(g, font, Component.translatable("easyplace2.printer.section." + label.name().toLowerCase(Locale.ROOT)),
                                a.x + 2, a.y + 2, 0.75f, Ui.textFaint()), null));
                y += 12;
            }

            Page target = p;
            this.sidebarY.put(p, y);
            this.addSidebar(new Controls.Area(sx + 6, y, SIDEBAR_W - 12, this.sideStep - 1,
                    (g, font, a, hover) -> this.paintSidebarItem(g, font, a, hover, target), () -> this.openPage(target)));
            y += this.sideStep;
        }

        this.sideMaxScroll = Math.max(0, y + 2 - this.sideBottom);
        this.sideScroll = Math.min(this.sideScroll, this.sideMaxScroll);

        // Die offene Seite soll in der Leiste sichtbar sein
        int selY = this.sidebarY.getOrDefault(page, this.sideTop);

        if (selY - this.sideScroll + this.sideStep > this.sideBottom)
        {
            this.sideScroll = Math.min(this.sideMaxScroll, selY + this.sideStep + 4 - this.sideBottom);
        }
        else if (selY - this.sideScroll < this.sideTop)
        {
            this.sideScroll = Math.max(0, selY - this.sideTop - 14);
        }
    }

    private void addSidebar(Controls.Control control)
    {
        this.sidebar.add(control);
        this.sidebarBaseY.add(control.y);
    }

    private void openPage(Page target)
    {
        page = target;
        query = "";
        this.capturing = null;
        this.scroll = this.scrollTarget = 0;

        if (this.search != null)
        {
            this.search.setValue("");
        }

        this.rebuildContent();
    }

    private boolean searching()
    {
        return !query.isBlank();
    }

    /** Baut Kopf-Bedienelemente und Inhalt der aktuellen Seite neu auf. */
    private void rebuildContent()
    {
        this.header.clear();
        this.content.clear();
        this.contentBaseY.clear();
        this.contentBottom = page == Page.THEME && !this.searching() ? this.y1 - 22 : this.y1 - 12;
        // Seiten ohne Zeile fuer Zuruecksetzen/Ansicht ruecken naeher an den Titel
        this.contentTop = this.searching() || page.hasOptions() ? this.y0 + 60 : this.y0 + 46;

        if (this.searching())
        {
            List<OptionEntry> results = OptionEntry.ALL.stream().filter(e -> e.matches(query)).toList();
            this.addViewToggles();
            this.buildOptions(results);
        }
        else
        {
            switch (page)
            {
                case OVERVIEW -> this.buildOverview();
                case MATERIALS -> this.buildMaterials();
                case STORAGE -> this.buildStorage();
                case THEME -> this.buildThemes();
                case KEYS -> this.buildKeys();
                case INFO -> this.buildInfo();
                case MACROS -> this.buildMacros();
                default ->
                {
                    this.addViewToggles();
                    this.addResetChip();
                    this.buildOptions(OptionEntry.of(page));
                }
            }
        }

        int bottom = this.contentTop;

        for (int i = 0; i < this.content.size(); i++)
        {
            bottom = Math.max(bottom, this.contentBaseY.get(i) + this.content.get(i).h);
        }

        this.maxScroll = Math.max(0, bottom + 4 - this.contentBottom);
        this.scrollTarget = Math.min(this.scrollTarget, this.maxScroll);
        this.scroll = Math.min(this.scroll, this.maxScroll);
    }

    private void add(Controls.Control control)
    {
        this.content.add(control);
        this.contentBaseY.add(control.y);
    }

    // ---------------------------------------------------------------- Optionskarten

    private void addViewToggles()
    {
        int y = this.headerTop + 26;
        this.header.add(new Controls.Area(this.cx1 - 40, y, 18, 16, (g, font, a, hover) -> this.paintViewIcon(g, a, hover, false),
                () -> this.setGrid(false)));
        this.header.add(new Controls.Area(this.cx1 - 18, y, 18, 16, (g, font, a, hover) -> this.paintViewIcon(g, a, hover, true),
                () -> this.setGrid(true)));
    }

    private void addResetChip()
    {
        Component label = Component.translatable("easyplace2.printer.reset");
        int w = this.font.width(label) + 16;
        this.header.add(new Controls.Area(this.cx0, this.headerTop + 26, w, 16, (g, font, a, hover) ->
        {
            Ui.card(g, a.x, a.y, a.x + a.w, a.y + a.h, Ui.lerpColor(Ui.card(), Ui.cardHover(), hover), Ui.border());
            Ui.text(g, font, label, a.x + 8, a.y + 4, Ui.lerpColor(Ui.textDim(), Ui.text(), hover));
        }, () ->
        {
            for (OptionEntry e : OptionEntry.of(page))
            {
                if (e.config instanceof IConfigResettable r)
                {
                    r.resetToDefault();
                }
            }
        }));
    }

    private void setGrid(boolean grid)
    {
        Ep2Configs.PRINTER_GRID_VIEW.setBooleanValue(grid);
        this.rebuildContent();
    }

    private void buildOptions(List<OptionEntry> entries)
    {
        int width = this.cx1 - this.cx0;
        boolean grid = Ep2Configs.PRINTER_GRID_VIEW.getBooleanValue();

        if (entries.isEmpty())
        {
            this.add(new Controls.Area(this.cx0, this.contentTop, width, ROW_H, (g, font, a, hover) ->
                    Ui.text(g, font, Component.translatable("easyplace2.printer.search.none"), a.x + 4, a.y + 10, Ui.textDim()), null));
            return;
        }

        if (!grid)
        {
            int y = this.contentTop;

            for (OptionEntry e : entries)
            {
                this.addListRow(e, this.cx0, y, width);
                y += ROW_H + GAP;
            }

            return;
        }

        int cols = width >= 330 ? 3 : 2;
        int cardW = (width - (cols - 1) * GAP) / cols;
        int cardH = 64;

        for (int i = 0; i < entries.size(); i++)
        {
            int x = this.cx0 + (i % cols) * (cardW + GAP);
            int y = this.contentTop + (i / cols) * (cardH + GAP);
            this.addGridCard(entries.get(i), x, y, cardW, cardH);
        }
    }

    private void addListRow(OptionEntry e, int x, int y, int w)
    {
        int right = x + w;

        // Schieberegler rechts unten, der Wert steht rechts oben darueber
        int sliderW = Math.max(60, Math.min(110, w / 3));

        if (e.isBoolean())
        {
            this.add(new Controls.Switch(right - 12 - Controls.Switch.WIDTH, y + (ROW_H - Controls.Switch.HEIGHT) / 2, e::getBoolean, e::setBoolean));
        }
        else
        {
            this.add(new Controls.Slider(right - 12 - sliderW, y + 16, sliderW, e.min, e.max, e.step, e::getNumber, e::setNumber));
        }

        this.add(new Controls.Area(x, y, w, ROW_H, (g, font, a, hover) ->
        {
            this.paintCard(g, a, hover);
            Component title = e.title().copy().withStyle(ChatFormatting.BOLD);

            if (e.isBoolean())
            {
                Ui.text(g, font, Component.literal(Ui.clip(font, title.getString(), a.w - 80)).withStyle(ChatFormatting.BOLD),
                        a.x + 10, a.y + 6, Ui.text());
                Ui.text(g, font, Ui.clip(font, e.description().getString(), a.w - 70), a.x + 10, a.y + 18, Ui.textFaint());
                Ui.text(g, font, "›", a.x + a.w - 46, a.y + 11, Ui.lerpColor(Ui.textFaint(), Ui.text(), hover));
            }
            else
            {
                String value = e.formattedValue();
                int valueW = font.width(value);
                Ui.text(g, font, value, a.x + a.w - 12 - valueW, a.y + 5, Ui.accent());
                Ui.text(g, font, Component.literal(Ui.clip(font, title.getString(), a.w - 30 - valueW)).withStyle(ChatFormatting.BOLD),
                        a.x + 10, a.y + 6, Ui.text());
                Ui.text(g, font, Ui.clip(font, e.description().getString(), a.w - 30 - sliderW), a.x + 10, a.y + 18, Ui.textFaint());
            }
        }, e.isBoolean() ? () -> e.setBoolean(!e.getBoolean()) : null));
    }

    private void addGridCard(OptionEntry e, int x, int y, int w, int h)
    {
        if (e.isBoolean())
        {
            this.add(new Controls.Switch(x + w - 10 - Controls.Switch.WIDTH, y + 8, e::getBoolean, e::setBoolean));
        }
        else
        {
            this.add(new Controls.Slider(x + 10, y + h - 16, w - 64, e.min, e.max, e.step, e::getNumber, e::setNumber));
        }

        this.add(new Controls.Area(x, y, w, h, (g, font, a, hover) ->
        {
            this.paintCard(g, a, hover);
            Ui.text(g, font, Ui.clip(font, e.title().getString(), a.w - 46), a.x + 10, a.y + 9, Ui.text());
            List<FormattedCharSequence> lines = font.split(e.description(), a.w - 20);

            for (int i = 0; i < Math.min(2, lines.size()); i++)
            {
                g.drawString(font, lines.get(i), a.x + 10, a.y + 23 + i * 10, Ui.textFaint(), false);
            }

            if (e.isBoolean())
            {
                Ui.text(g, font, "›", a.x + a.w - 14, a.y + a.h - 15, Ui.lerpColor(Ui.textFaint(), Ui.text(), hover));
            }
            else
            {
                String value = e.formattedValue();
                Ui.text(g, font, value, a.x + a.w - 10 - font.width(value), a.y + a.h - 17, Ui.accent());
            }
        }, e.isBoolean() ? () -> e.setBoolean(!e.getBoolean()) : null));
    }

    private void paintCard(GuiGraphics g, Controls.Area a, float hover)
    {
        Ui.card(g, a.x, a.y, a.x + a.w, a.y + a.h, Ui.lerpColor(Ui.card(), Ui.cardHover(), hover),
                Ui.lerpColor(Ui.border(), Ui.withAlpha(Ui.accent(), 0x90), hover * 0.6f));
    }

    // ---------------------------------------------------------------- Uebersicht

    private void buildOverview()
    {
        PrinterController printer = PrinterController.INSTANCE;
        int width = this.cx1 - this.cx0;
        int top = this.contentTop;

        // Fortschritt
        this.add(new Controls.Area(this.cx0, top, width, 50, (g, font, a, hover) -> this.paintProgress(g, font, a), null));

        // Knoepfe direkt darunter, alle gleich breit
        int by = top + 56;
        int buttonW = (width - 2 * GAP) / 3;
        Controls.Button start = new Controls.Button(this.cx0, by, buttonW, 20,
                () -> Component.translatable(printer.isRunning() ? "easyplace2.printer.button.stop" : "easyplace2.printer.button.start"),
                () -> printer.isRunning() ? Ui.RED : Ui.accent(), true,
                () ->
                {
                    if (printer.isRunning())
                    {
                        printer.stop(Component.translatable("easyplace2.printer.msg.stopped"));
                    }
                    else
                    {
                        // Gebaut wird nur ohne offenes Menue, also direkt zurueck ins Spiel
                        this.onClose();
                        printer.start();
                    }
                });
        this.add(start);

        Controls.Button pause = new Controls.Button(this.cx0 + buttonW + GAP, by, buttonW, 20,
                () -> Component.translatable(printer.isPaused() ? "easyplace2.printer.button.resume" : "easyplace2.printer.button.pause"),
                () -> Ui.AMBER, false, () -> printer.setPaused(!printer.isPaused()));
        pause.enabled = printer::isRunning;
        this.add(pause);

        this.add(new Controls.Button(this.cx1 - buttonW, by, buttonW, 20, () -> Component.translatable("easyplace2.printer.button.close"),
                Ui::textDim, false, this::onClose));

        // Statistik-Kacheln
        int tilesTop = by + 26;
        int tileW = (width - 3 * GAP) / 4;
        String[] keys = { "placed", "rate", "eta", "wrong" };

        for (int i = 0; i < 4; i++)
        {
            String key = keys[i];
            this.add(new Controls.Area(this.cx0 + i * (tileW + GAP), tilesTop, tileW, 34, (g, font, a, hover) ->
            {
                this.paintCard(g, a, hover);
                Ui.scaledText(g, font, Component.literal(this.statValue(key)).withStyle(ChatFormatting.BOLD), a.x + 8, a.y + 6, 1.25f, Ui.text());
                Ui.text(g, font, Ui.clip(font, Component.translatable("easyplace2.printer.stat." + key).getString(), a.w - 14),
                        a.x + 8, a.y + 22, Ui.textFaint());
            }, null));
        }

        // Status und Material: mindestens so hoch, dass alles lesbar ist, sonst scrollt die Seite
        int cardsTop = tilesTop + 40;
        int cardsH = Math.max(92, this.contentBottom - cardsTop);
        int half = (width - GAP) / 2;
        this.add(new Controls.Area(this.cx0, cardsTop, half, cardsH, (g, font, a, hover) -> this.paintStatus(g, font, a, printer), null));
        this.add(new Controls.Area(this.cx0 + half + GAP, cardsTop, width - half - GAP, cardsH,
                (g, font, a, hover) -> this.paintMaterials(g, font, a, printer), null));
    }

    private String statValue(String key)
    {
        PrinterController printer = PrinterController.INSTANCE;
        SchematicScanner.Snapshot snap = printer.getSnapshot();

        return switch (key)
        {
            case "placed" -> String.valueOf(printer.getPlaced());
            case "rate" -> String.format(Locale.ROOT, "%.0f", printer.getRate());
            case "eta" -> Ui.formatDuration(printer.getEtaSeconds());
            default -> snap == null ? "–" : String.valueOf(snap.wrong());
        };
    }

    private void paintProgress(GuiGraphics g, Font font, Controls.Area a)
    {
        PrinterController printer = PrinterController.INSTANCE;
        SchematicScanner.Snapshot snap = printer.getSnapshot();
        Ui.card(g, a.x, a.y, a.x + a.w, a.y + a.h, Ui.card(), Ui.border());

        double progress = snap != null ? snap.progress() : 0.0;
        this.shownProgress = this.shownProgress < 0 ? (float) progress : Ui.approach(this.shownProgress, (float) progress, 0.08f);

        Ui.text(g, font, Component.translatable("easyplace2.printer.label.progress"), a.x + 10, a.y + 8, Ui.textDim());
        Component percent = Component.literal(String.format(Locale.ROOT, "%.1f %%", this.shownProgress * 100)).withStyle(ChatFormatting.BOLD);
        Ui.scaledText(g, font, percent, a.x + a.w - 10 - Ui.scaledWidth(font, percent, 1.25f), a.y + 6, 1.25f, Ui.text());

        int bx0 = a.x + 10, bx1 = a.x + a.w - 10, by = a.y + 22;
        Ui.round(g, bx0, by, bx1, by + 8, Ui.track());
        int fill = bx0 + Math.round((bx1 - bx0) * Math.max(0f, Math.min(1f, this.shownProgress)));

        if (fill > bx0 + 2)
        {
            Ui.gradientH(g, bx0 + 1, by + 1, fill - 1, by + 7, Ui.accent2(), Ui.accent());

            if (printer.isRunning())
            {
                // Glanzlicht, das ueber den Balken laeuft, solange gebaut wird
                int shine = bx0 + (int) ((System.currentTimeMillis() / 6) % Math.max(1, fill - bx0));
                g.fill(Math.max(bx0 + 1, shine - 8), by + 1, Math.min(fill - 1, shine + 8), by + 7, 0x30FFFFFF);
            }
        }

        String stats = snap == null ? Component.translatable("easyplace2.printer.label.scanning").getString()
                : Component.translatable("easyplace2.printer.label.stats", snap.done(), snap.total(), snap.missing().size()).getString();
        Ui.text(g, font, Ui.clip(font, stats, a.w - 20), a.x + 10, a.y + 36, Ui.textFaint());
    }

    private void paintStatus(GuiGraphics g, Font font, Controls.Area a, PrinterController printer)
    {
        Ui.card(g, a.x, a.y, a.x + a.w, a.y + a.h, Ui.card(), Ui.border());
        Ui.text(g, font, Component.translatable("easyplace2.printer.label.status").copy().withStyle(ChatFormatting.BOLD), a.x + 10, a.y + 8, Ui.text());

        // Zustand als Pille oben rechts
        PrinterController.State state = printer.getState();
        Component name = Ui.stateName(state);
        int color = Ui.stateColor(state);
        int pw = font.width(name) + 18;
        Ui.round(g, a.x + a.w - 8 - pw, a.y + 5, a.x + a.w - 8, a.y + 18, Ui.withAlpha(color, 0x30));
        float pulse = state == PrinterController.State.OFF ? 1f : 0.55f + 0.45f * (float) Math.sin(System.currentTimeMillis() / 220.0);
        Ui.dot(g, a.x + a.w - 8 - pw + 7, a.y + 11, 2, Ui.withAlpha(color, (int) (0xFF * pulse)));
        Ui.text(g, font, name, a.x + a.w - 8 - pw + 12, a.y + 8, color);

        SchematicScanner.Snapshot snap = printer.getSnapshot();
        SchematicScanner.Target target = printer.getCurrent();
        int y = a.y + 26;
        y = this.statusRow(g, font, a, y, "schematic", snap == null || snap.names().isEmpty() ? "–" : snap.names());
        y = this.statusRow(g, font, a, y, "target", target == null ? "–" : target.state().getBlock().getName().getString());
        y = this.statusRow(g, font, a, y, "detail", this.detailText(printer));
        y = this.statusRow(g, font, a, y, "unplaceable", snap == null ? "–" : String.valueOf(snap.unplaceable()));
        this.statusRow(g, font, a, y, "baritone", Component.translatable(BaritoneBridge.isAvailable()
                ? "easyplace2.printer.check.ok" : "easyplace2.printer.check.missing").getString());
    }

    private int statusRow(GuiGraphics g, Font font, Controls.Area a, int y, String key, String value)
    {
        if (y > a.y + a.h - 10)
        {
            return y;
        }

        Ui.text(g, font, Component.translatable("easyplace2.printer.row." + key), a.x + 10, y, Ui.textFaint());
        int vx = a.x + 82;
        Ui.text(g, font, Ui.clip(font, value, a.x + a.w - 10 - vx), vx, y, Ui.text());
        return y + 12;
    }

    private String detailText(PrinterController printer)
    {
        String detail = printer.getDetail();

        if ("skipped".equals(detail) || "damage".equals(detail) || "stuck".equals(detail) || "escape".equals(detail))
        {
            return Component.translatable("easyplace2.printer.detail." + detail).getString();
        }

        return detail.isEmpty() ? Ui.stateName(printer.getState()).getString() : detail;
    }

    private void paintMaterials(GuiGraphics g, Font font, Controls.Area a, PrinterController printer)
    {
        Ui.card(g, a.x, a.y, a.x + a.w, a.y + a.h, Ui.card(), Ui.border());
        Map<Item, Integer> missing = printer.getMissingMaterials();
        boolean anyMissing = !missing.isEmpty();
        Ui.text(g, font, Component.translatable(anyMissing ? "easyplace2.printer.label.missing" : "easyplace2.printer.label.materials")
                .copy().withStyle(ChatFormatting.BOLD), a.x + 10, a.y + 8, anyMissing ? Ui.RED : Ui.text());

        Minecraft mc = Minecraft.getInstance();
        SchematicScanner.Snapshot snap = printer.getSnapshot();

        if (snap == null || mc.player == null)
        {
            return;
        }

        // Fehlendes Material zuerst, sonst die meistgebrauchten Items
        Map<Item, Integer> source = anyMissing ? missing : snap.neededItems();
        List<Map.Entry<Item, Integer>> entries = new ArrayList<>(source.entrySet());
        entries.sort(Map.Entry.<Item, Integer>comparingByValue(Comparator.reverseOrder()));

        if (entries.isEmpty())
        {
            Ui.text(g, font, Component.translatable("easyplace2.printer.label.nothing_needed"), a.x + 10, a.y + 26, Ui.GREEN);
            return;
        }

        int x = a.x + 10, y = a.y + 24, cell = 21;
        int cols = Math.max(1, (a.w - 20) / cell);
        int rows = Math.max(1, (a.h - 24 - 14) / cell);
        int mx = this.lastMouseX, my = this.lastMouseY;
        Component hovered = null;

        for (int i = 0; i < entries.size() && i < cols * rows; i++)
        {
            Item item = entries.get(i).getKey();
            int need = entries.get(i).getValue();
            int have = InventoryManager.countItem(mc.player, item);
            int ix = x + (i % cols) * cell;
            int iy = y + (i / cols) * cell;
            ItemStack stack = new ItemStack(item);
            boolean hover = mx >= ix && my >= iy && mx < ix + 19 && my < iy + 19;

            Ui.round(g, ix, iy, ix + 19, iy + 19, hover ? Ui.cardHover() : Ui.withAlpha(have >= need ? Ui.GREEN : Ui.RED, 0x22));
            g.renderItem(stack, ix + 2, iy + 2);
            g.renderItemDecorations(font, stack, ix + 2, iy + 2, compact(need));

            if (hover)
            {
                hovered = Component.literal(stack.getHoverName().getString() + ": ")
                        .append(Component.translatable("easyplace2.printer.label.need_have", need, have));
            }
        }

        if (hovered != null)
        {
            Ui.text(g, font, Ui.clip(font, hovered.getString(), a.w - 20), a.x + 10, a.y + a.h - 12, Ui.text());
        }
    }

    private static String compact(int n)
    {
        return n >= 10000 ? (n / 1000) + "k" : n >= 1000 ? String.format(Locale.ROOT, "%.1fk", n / 1000.0) : String.valueOf(n);
    }

    // ---------------------------------------------------------------- Materialliste

    private void buildMaterials()
    {
        Minecraft mc = Minecraft.getInstance();
        SchematicScanner.Snapshot snap = PrinterController.INSTANCE.getSnapshot();
        int width = this.cx1 - this.cx0;
        int y = this.contentTop;

        if (snap == null || mc.player == null)
        {
            this.add(new Controls.Area(this.cx0, y, width, 20, (g, font, a, hover) ->
                    Ui.text(g, font, Component.translatable("easyplace2.printer.material.none"), a.x + 4, a.y + 6, Ui.textDim()), null));
            return;
        }

        // Fehlendes zuerst, dann nach Menge
        List<Map.Entry<Item, Integer>> entries = new ArrayList<>(snap.neededItems().entrySet());
        entries.sort(Comparator.<Map.Entry<Item, Integer>>comparingInt(e -> InventoryManager.countItem(mc.player, e.getKey()) >= e.getValue() ? 1 : 0)
                .thenComparing(Map.Entry.<Item, Integer>comparingByValue(Comparator.reverseOrder())));

        // Spaltenkoepfe
        this.add(new Controls.Area(this.cx0, y, width, 12, (g, font, a, hover) ->
        {
            Ui.text(g, font, Component.translatable("easyplace2.printer.material.col.item"), a.x + 28, a.y + 2, Ui.textFaint());
            String[] cols = { "need", "inventory", "shulker", "storage", "status" };

            // Passen die Woerter nicht in die Spalten, gibt es kleine Icons (Truhe = Inventar, Shulker, Fass = Lager)
            ItemStack[] icons = { ItemStack.EMPTY, new ItemStack(Items.CHEST), new ItemStack(Items.SHULKER_BOX), new ItemStack(Items.BARREL), ItemStack.EMPTY };
            String[] shortText = { "#", "", "", "", "✔" };
            boolean fits = true;

            for (String col : cols)
            {
                fits &= font.width(Component.translatable("easyplace2.printer.material.col." + col)) + 6 <= materialColumnWidth(a);
            }

            for (int i = 0; i < cols.length; i++)
            {
                int cx = materialColumn(a, i);

                if (fits)
                {
                    Component c = Component.translatable("easyplace2.printer.material.col." + cols[i]);
                    Ui.text(g, font, c, cx - font.width(c), a.y + 2, Ui.textFaint());
                }
                else if (!icons[i].isEmpty())
                {
                    Ui.item(g, icons[i], cx - 10, a.y, 0.625f);
                }
                else
                {
                    Ui.text(g, font, shortText[i], cx - font.width(shortText[i]), a.y + 2, Ui.textFaint());
                }
            }
        }, null));
        y += 14;

        if (entries.isEmpty())
        {
            this.add(new Controls.Area(this.cx0, y, width, 20, (g, font, a, hover) ->
                    Ui.text(g, font, Component.translatable("easyplace2.printer.label.nothing_needed"), a.x + 4, a.y + 6, Ui.GREEN), null));
            y += 24;
        }

        for (Map.Entry<Item, Integer> entry : entries)
        {
            Item item = entry.getKey();
            this.add(new Controls.Area(this.cx0, y, width, 22, (g, font, a, hover) -> this.paintMaterialRow(g, font, a, hover, item), null));
            y += 24;
        }

        // Shulker im Inventar
        List<Integer> shulkers = ShulkerContents.shulkerSlots(mc.player);
        y += 6;
        int labelY = y;
        this.add(new Controls.Area(this.cx0, labelY, width, 12, (g, font, a, hover) ->
                Ui.text(g, font, Component.translatable("easyplace2.printer.material.shulkers", shulkers.size()).copy().withStyle(ChatFormatting.BOLD),
                        a.x + 2, a.y + 2, Ui.text()), null));
        y += 16;
        int cardW = (width - GAP) / 2;

        for (int i = 0; i < shulkers.size(); i++)
        {
            int slot = shulkers.get(i);
            int x = this.cx0 + (i % 2) * (cardW + GAP);
            int cy = y + (i / 2) * 36;
            this.add(new Controls.Area(x, cy, cardW, 32, (g, font, a, hover) -> this.paintShulkerCard(g, font, a, hover, slot), null));
        }
    }

    /** Rechte Kante der Zahlenspalte {@code i} in der Materialliste. */
    private static int materialColumnWidth(Controls.Area a)
    {
        return Math.max(36, Math.min(64, (a.w - 130) / 5));
    }

    private static int materialColumn(Controls.Area a, int i)
    {
        int colW = materialColumnWidth(a);
        return a.x + a.w - 10 - (4 - i) * colW;
    }

    private void paintMaterialRow(GuiGraphics g, Font font, Controls.Area a, float hover, Item item)
    {
        Minecraft mc = Minecraft.getInstance();
        SchematicScanner.Snapshot snap = PrinterController.INSTANCE.getSnapshot();

        if (mc.player == null || snap == null)
        {
            return;
        }

        int need = snap.neededItems().getOrDefault(item, 0);
        int inv = InventoryManager.countItem(mc.player, item);
        int shulk = ShulkerContents.countInInventoryShulkers(mc.player, item);
        int stored = StorageRegistry.INSTANCE.total(item);

        this.paintCard(g, a, hover);
        ItemStack stack = new ItemStack(item);
        Ui.item(g, stack, a.x + 6, a.y + 3, 1.0f);
        int nameRight = materialColumn(a, 0) - 46;
        Ui.text(g, font, Ui.clip(font, stack.getHoverName().getString(), Math.max(20, nameRight - a.x - 28)), a.x + 28, a.y + 7, Ui.text());

        int[] values = { need, inv, shulk, stored };

        for (int i = 0; i < values.length; i++)
        {
            String v = compact(values[i]);
            int color = i == 0 ? Ui.text() : values[i] > 0 ? Ui.textDim() : Ui.textFaint();
            Ui.text(g, font, v, materialColumn(a, i) - font.width(v), a.y + 7, color);
        }

        // Status: genug dabei / kann geholt werden / fehlt wirklich
        String status;
        int color;

        if (inv >= need)
        {
            status = "✔";
            color = Ui.GREEN;
        }
        else if (inv + shulk + stored >= need)
        {
            status = Component.translatable("easyplace2.printer.material.fetch").getString();
            color = Ui.AMBER;
        }
        else
        {
            status = "-" + compact(need - inv - shulk - stored);
            color = Ui.RED;
        }

        Ui.text(g, font, status, materialColumn(a, 4) - font.width(status), a.y + 7, color);
    }

    private void paintShulkerCard(GuiGraphics g, Font font, Controls.Area a, float hover, int slot)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null)
        {
            return;
        }

        ItemStack shulker = mc.player.getInventory().getItem(slot);
        this.paintCard(g, a, hover);

        if (!ShulkerContents.isShulker(shulker))
        {
            return;
        }

        Ui.item(g, shulker, a.x + 6, a.y + 8, 1.0f);
        Map<Item, Integer> summary = ShulkerContents.summary(shulker);
        Ui.text(g, font, Ui.clip(font, shulker.getHoverName().getString(), a.w - 34), a.x + 28, a.y + 4, Ui.text());

        if (summary.isEmpty())
        {
            Ui.text(g, font, Component.translatable("easyplace2.printer.material.empty"), a.x + 28, a.y + 17, Ui.textFaint());
            return;
        }

        int x = a.x + 28;

        for (Map.Entry<Item, Integer> e : summary.entrySet())
        {
            if (x + 14 > a.x + a.w - 6)
            {
                break;
            }

            ItemStack icon = new ItemStack(e.getKey());
            Ui.item(g, icon, x, a.y + 15, 0.75f);
            String c = compact(e.getValue());
            Ui.scaledText(g, font, Component.literal(c), x + 12 - Ui.scaledWidth(font, Component.literal(c), 0.75f) + 2, a.y + 23, 0.75f, Ui.text());
            x += 18;
        }
    }

    // ---------------------------------------------------------------- Lager

    private void buildStorage()
    {
        int width = this.cx1 - this.cx0;
        int y = this.contentTop;

        Controls.Button add = new Controls.Button(this.cx0, y, Math.min(width, 220), 20,
                () -> Component.translatable("easyplace2.printer.storage.add"), Ui::accent, true, () ->
                {
                    StorageRegistry.toggleLookedAt(Minecraft.getInstance());
                    this.rebuildContent();
                });
        this.add(add);
        y += 26;

        List<StorageRegistry.Entry> entries = StorageRegistry.INSTANCE.entries();

        if (entries.isEmpty())
        {
            Component hint = Component.translatable("easyplace2.printer.storage.empty");
            int lines = Math.max(1, this.font.split(hint, width - 8).size());
            this.add(new Controls.Area(this.cx0, y, width, lines * 10 + 4, (g, font, a, hover) ->
            {
                List<FormattedCharSequence> split = font.split(hint, a.w - 8);

                for (int i = 0; i < split.size(); i++)
                {
                    g.drawString(font, split.get(i), a.x + 4, a.y + 2 + i * 10, Ui.textDim(), false);
                }
            }, null));
            return;
        }

        for (StorageRegistry.Entry entry : List.copyOf(entries))
        {
            // Entfernen-Knopf zuerst, damit er den Klick vor der Zeile bekommt
            this.add(new Controls.Area(this.cx1 - 22, y + 7, 16, 16, (g, font, a, hover) ->
            {
                Ui.round(g, a.x, a.y, a.x + a.w, a.y + a.h, Ui.withAlpha(Ui.RED, (int) (0x30 + 0x50 * hover)));
                Ui.text(g, font, "✕", a.x + 5, a.y + 4, Ui.lerpColor(Ui.textDim(), Ui.RED, hover));
            }, () ->
            {
                StorageRegistry.INSTANCE.remove(entry);
                this.rebuildContent();
            }));
            this.add(new Controls.Area(this.cx0, y, width, 30, (g, font, a, hover) -> this.paintStorageRow(g, font, a, hover, entry), null));
            y += 34;
        }
    }

    private void paintStorageRow(GuiGraphics g, Font font, Controls.Area a, float hover, StorageRegistry.Entry entry)
    {
        Minecraft mc = Minecraft.getInstance();
        this.paintCard(g, a, hover);

        Item blockItem = mc.level != null ? mc.level.getBlockState(entry.pos).getBlock().asItem() : Items.CHEST;
        Ui.item(g, new ItemStack(blockItem == Items.AIR ? Items.BARRIER : blockItem), a.x + 6, a.y + 7, 1.0f);
        String dist = mc.player != null ? String.format(Locale.ROOT, "%.0f m", Math.sqrt(entry.pos.distToCenterSqr(mc.player.position()))) : "";
        Ui.text(g, font, Component.literal(entry.pos.toShortString()).withStyle(ChatFormatting.BOLD), a.x + 28, a.y + 5, Ui.text());
        Ui.text(g, font, dist, a.x + 28 + font.width(Component.literal(entry.pos.toShortString()).withStyle(ChatFormatting.BOLD)) + 8, a.y + 5, Ui.textFaint());

        if (!entry.known)
        {
            Ui.text(g, font, Ui.clip(font, Component.translatable("easyplace2.printer.storage.unknown").getString(), a.w - 60),
                    a.x + 28, a.y + 17, Ui.textFaint());
            return;
        }

        // Inhalt als kleine Icons (inklusive Inhalt von Shulkern darin)
        Map<Item, Integer> merged = new java.util.LinkedHashMap<>(entry.direct);
        entry.inShulkers.forEach((item, count) -> merged.merge(item, count, Integer::sum));
        merged.keySet().removeIf(ShulkerContents::isShulkerItem);
        List<Map.Entry<Item, Integer>> list = new ArrayList<>(merged.entrySet());
        list.sort(Map.Entry.<Item, Integer>comparingByValue(Comparator.reverseOrder()));
        int x = a.x + 28;

        if (list.isEmpty())
        {
            Ui.text(g, font, Component.translatable("easyplace2.printer.material.empty"), x, a.y + 17, Ui.textFaint());
        }

        for (Map.Entry<Item, Integer> e : list)
        {
            if (x + 30 > a.x + a.w - 28)
            {
                break;
            }

            Ui.item(g, new ItemStack(e.getKey()), x, a.y + 16, 0.6f);
            Ui.scaledText(g, font, Component.literal(compact(e.getValue())), x + 11, a.y + 18, 0.75f, Ui.textDim());
            x += 32;
        }
    }

    // ---------------------------------------------------------------- Themes

    private void buildThemes()
    {
        int width = this.cx1 - this.cx0;
        int cols = width >= 330 ? 3 : 2;
        int cardW = (width - (cols - 1) * GAP) / cols;
        int cardH = 56;

        for (int i = 0; i < Theme.ALL.size(); i++)
        {
            Theme theme = Theme.ALL.get(i);
            int x = this.cx0 + (i % cols) * (cardW + GAP);
            int y = this.contentTop + (i / cols) * (cardH + GAP);
            this.add(new Controls.Area(x, y, cardW, cardH, (g, font, a, hover) -> this.paintTheme(g, font, a, hover, theme),
                    () -> Ep2Configs.PRINTER_THEME.setValueFromString(theme.id())));
        }
    }

    private void paintTheme(GuiGraphics g, Font font, Controls.Area a, float hover, Theme theme)
    {
        boolean selected = Theme.current() == theme;
        int accent = Ui.withAlpha(theme.accent(), 0xFF);
        int border = selected ? accent : Ui.lerpColor(Ui.border(), Ui.withAlpha(accent, 0xA0), hover);
        Ui.card(g, a.x, a.y, a.x + a.w, a.y + a.h, selected ? Ui.cardHover() : Ui.lerpColor(Ui.card(), Ui.cardHover(), hover), border);

        // Farbfelder wie eine kleine Vorschau: drei dunkle Toene + Akzent
        int sx = a.x + 10, sy = a.y + 9, sw = Math.min(20, (a.w - 70) / 4);
        int[] swatches = { theme.sidebar(), theme.background(), theme.accent(), theme.card() };

        for (int i = 0; i < swatches.length; i++)
        {
            g.fill(sx + i * sw, sy, sx + (i + 1) * sw, sy + 14, Ui.withAlpha(swatches[i], 0xFF));
        }

        // "GLASS"-Badge
        Component badge = Component.translatable("easyplace2.printer.theme.badge");
        int bw = Ui.scaledWidth(font, badge, 0.75f) + 8;
        int bx = a.x + a.w - 10 - bw;
        Ui.round(g, bx, sy + 1, bx + bw, sy + 13, Ui.withAlpha(theme.accent(), 0x30));
        Ui.scaledText(g, font, badge, bx + 4, sy + 4, 0.75f, accent);

        if (selected)
        {
            Ui.dot(g, bx - 9, sy + 7, 5, accent);
            Ui.dot(g, bx - 9, sy + 7, 2, Ui.WHITE);
        }

        Ui.text(g, font, theme.displayName().copy().withStyle(ChatFormatting.BOLD), a.x + 10, a.y + 29, Ui.text());
        Ui.text(g, font, Ui.clip(font, theme.description().getString(), a.w - 20), a.x + 10, a.y + 41, Ui.textFaint());
    }

    // ---------------------------------------------------------------- Tasten

    private void buildKeys()
    {
        int width = this.cx1 - this.cx0;
        String[] keys = { "menu", "toggle", "easyplace", "storage", "invsee", "macroRecord", "macroPlay" };
        IHotkey[] hotkeys = { Ep2Configs.PRINTER_MENU, Ep2Configs.PRINTER_TOGGLE, Ep2Configs.EASY_PLACE_2, Ep2Configs.PRINTER_MARK_STORAGE,
                              Ep2Configs.MODULE_INVSEE, Ep2Configs.MODULE_MACRO_RECORD, Ep2Configs.MODULE_MACRO_PLAY };
        int y = this.contentTop;

        for (int i = 0; i < keys.length; i++)
        {
            String key = keys[i];
            IHotkey hotkey = hotkeys[i];
            this.add(new Controls.Area(this.cx0, y, width, ROW_H, (g, font, a, hover) ->
            {
                this.paintCard(g, a, hover);
                Ui.text(g, font, Component.translatable("easyplace2.printer.key." + key).copy().withStyle(ChatFormatting.BOLD), a.x + 10, a.y + 6, Ui.text());
                Ui.text(g, font, Ui.clip(font, Component.translatable("easyplace2.printer.key." + key + ".desc").getString(), a.w - 140),
                        a.x + 10, a.y + 18, Ui.textFaint());

                boolean active = this.capturing == hotkey;
                String shown = active ? Component.translatable("easyplace2.printer.key.press").getString()
                                      : hotkey.getKeybind().getKeysDisplayString();
                shown = shown == null || shown.isEmpty() ? "–" : shown;
                int cw = font.width(shown) + 14;
                int chipX = a.x + a.w - 10 - cw;
                float pulse = active ? 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 160.0) : 0f;
                Ui.card(g, chipX, a.y + 7, chipX + cw, a.y + a.h - 7, Ui.track(),
                        active ? Ui.withAlpha(Ui.accent(), (int) (0x80 + 0x7F * pulse)) : Ui.border());
                Ui.text(g, font, shown, chipX + 7, a.y + 11, active ? Ui.accent() : Ui.text());
            }, () -> this.capturing = this.capturing == hotkey ? null : hotkey));
            y += ROW_H + GAP;
        }

        Component hint = Component.translatable("easyplace2.printer.key.hint");
        int lines = Math.max(1, this.font.split(hint, width - 4).size());
        this.add(new Controls.Area(this.cx0, y + 4, width, lines * 10, (g, font, a, hover) ->
        {
            List<FormattedCharSequence> split = font.split(hint, a.w - 4);

            for (int i = 0; i < split.size(); i++)
            {
                g.drawString(font, split.get(i), a.x + 2, a.y + i * 10, Ui.textFaint(), false);
            }
        }, null));
    }

    // ---------------------------------------------------------------- Makros

    private void buildMacros()
    {
        Minecraft mc = Minecraft.getInstance();
        MacroStore store = MacroStore.INSTANCE;
        int width = this.cx1 - this.cx0;
        int y = this.contentTop;
        int third = (width - 2 * GAP) / 3;

        // Aufnehmen, Abspielen, neues leeres Makro
        this.add(new Controls.Button(this.cx0, y, third, 20, () -> MacroRecorder.INSTANCE.isRecording()
                ? Component.translatable("easyplace2.macro.gui.record_stop", MacroRecorder.INSTANCE.stepCount())
                : Component.translatable("easyplace2.macro.gui.record"), () -> Ui.RED, MacroRecorder.INSTANCE.isRecording(), () ->
        {
            boolean wasRecording = MacroRecorder.INSTANCE.isRecording();
            MacroRecorder.INSTANCE.toggle(mc);

            // Zum Aufnehmen das Menue schliessen, damit man gleich loslegen kann
            if (!wasRecording && MacroRecorder.INSTANCE.isRecording())
            {
                this.onClose();
            }
            else
            {
                this.rebuildContent();
            }
        }));
        Controls.Button play = new Controls.Button(this.cx0 + third + GAP, y, third, 20, () -> MacroPlayer.INSTANCE.isPlaying()
                ? Component.translatable("easyplace2.macro.gui.play_stop")
                : Component.translatable("easyplace2.macro.gui.play"), Ui::accent, true, () ->
        {
            boolean wasPlaying = MacroPlayer.INSTANCE.isPlaying();
            MacroPlayer.INSTANCE.toggle(mc);

            if (!wasPlaying && MacroPlayer.INSTANCE.isPlaying())
            {
                this.onClose();
            }
            else
            {
                this.rebuildContent();
            }
        });
        play.enabled = () -> MacroPlayer.INSTANCE.isPlaying() || (store.selected() != null && !store.selected().steps.isEmpty());
        this.add(play);
        this.add(new Controls.Button(this.cx0 + 2 * (third + GAP), y, width - 2 * (third + GAP), 20,
                () -> Component.translatable("easyplace2.macro.gui.new"), Ui::accent, false, () ->
        {
            store.add(new Macro(store.nextName(), List.of()));
            this.rebuildContent();
        }));
        y += 26;

        Macro macro = store.selected();

        if (macro == null)
        {
            this.addHint(y, Component.translatable("easyplace2.macro.gui.empty"));
            return;
        }

        // Auswahl: < Name >, Loeschen
        this.add(new Controls.Area(this.cx0, y, width, ROW_H, (g, font, a, hover) ->
        {
            this.paintCard(g, a, 0f);
            Component name = Component.literal(macro.name).withStyle(ChatFormatting.BOLD);
            Ui.text(g, font, name, a.x + (a.w - font.width(name)) / 2, a.y + 6, Ui.text());
            Component info = Component.translatable("easyplace2.macro.gui.info", store.selectedIndex() + 1, store.macros().size(),
                    macro.steps.size(), MacroStep.seconds(totalTicks(macro)));
            Ui.text(g, font, info, a.x + (a.w - font.width(info)) / 2, a.y + 18, Ui.textFaint());
        }, null));
        this.add(this.smallButton(this.cx0 + 6, y + 7, 16, "<", Ui.accent(), () -> store.select(store.selectedIndex() - 1)));
        this.add(this.smallButton(this.cx1 - 44, y + 7, 16, ">", Ui.accent(), () -> store.select(store.selectedIndex() + 1)));
        this.add(this.smallButton(this.cx1 - 22, y + 7, 16, "✕", Ui.RED, () ->
        {
            if (MacroPlayer.INSTANCE.current() == macro)
            {
                MacroPlayer.INSTANCE.stop(mc, null);
            }

            store.remove(macro);
        }));
        y += ROW_H + GAP;

        // Wiederholungen und Stopp bei Schaden
        int half = (width - GAP) / 2;
        this.add(new Controls.Area(this.cx0, y, half, ROW_H, (g, font, a, hover) ->
        {
            this.paintCard(g, a, 0f);
            Ui.text(g, font, Component.translatable("easyplace2.macro.gui.repeats").withStyle(ChatFormatting.BOLD), a.x + 10, a.y + 5, Ui.text());
            String value = macro.repeats == 0 ? "∞" : macro.repeats + "×";
            Ui.text(g, font, value, a.x + a.w - 10 - font.width(value), a.y + 5, Ui.accent());
        }, null));
        this.add(new Controls.Slider(this.cx0 + 10, y + 17, half - 20, 0, 100, 1, () -> macro.repeats, v -> macro.repeats = (int) v));
        this.add(new Controls.Area(this.cx0 + half + GAP, y, width - half - GAP, ROW_H, (g, font, a, hover) ->
        {
            this.paintCard(g, a, hover);
            Ui.text(g, font, Component.literal(Ui.clip(font, Component.translatable("easyplace2.macro.gui.stop_damage").getString(), a.w - 46))
                    .withStyle(ChatFormatting.BOLD), a.x + 10, a.y + 6, Ui.text());
            Ui.text(g, font, Ui.clip(font, Component.translatable("easyplace2.macro.gui.stop_damage.desc").getString(), a.w - 46),
                    a.x + 10, a.y + 18, Ui.textFaint());
        }, () -> macro.stopOnDamage = !macro.stopOnDamage));
        this.add(new Controls.Switch(this.cx1 - 10 - Controls.Switch.WIDTH, y + (ROW_H - Controls.Switch.HEIGHT) / 2,
                () -> macro.stopOnDamage, v -> macro.stopOnDamage = v));
        y += ROW_H + GAP + 4;

        // Schritte
        this.addHint(y, Component.translatable("easyplace2.macro.gui.steps_hint"));
        y += this.hintHeight(Component.translatable("easyplace2.macro.gui.steps_hint")) + 4;

        for (int i = 0; i < macro.steps.size(); i++)
        {
            y = this.addStepRow(mc, macro, i, y) + GAP;
        }

        // Schritte hinzufuegen
        String[] adds = { "wait", "mine", "wait_block", "use", "look", "select" };
        int bw = (width - 2 * GAP) / 3;

        for (int i = 0; i < adds.length; i++)
        {
            String key = adds[i];
            int bx = this.cx0 + (i % 3) * (bw + GAP);
            int by = y + (i / 3) * (18 + GAP);
            this.add(new Controls.Button(bx, by, i % 3 == 2 ? width - 2 * (bw + GAP) : bw, 18,
                    () -> Component.translatable("easyplace2.macro.gui.add." + key), Ui::accent, false, () ->
            {
                macro.steps.add(newStep(mc, key));
                this.rebuildContent();
                this.scrollTarget = this.maxScroll;
            }));
        }
    }

    /** Eine Zeile fuer einen Schritt; gibt die Unterkante zurueck. */
    private int addStepRow(Minecraft mc, Macro macro, int index, int y)
    {
        MacroStep step = macro.steps.get(index);
        int width = this.cx1 - this.cx0;
        int h = 34;
        this.add(new Controls.Area(this.cx0, y, width, h, (g, font, a, hover) ->
        {
            boolean active = MacroPlayer.INSTANCE.current() == macro && MacroPlayer.INSTANCE.stepIndex() == index;
            Ui.card(g, a.x, a.y, a.x + a.w, a.y + a.h, active ? Ui.cardHover() : Ui.card(),
                    active ? Ui.accent() : Ui.border());
            String number = (index + 1) + ".";
            Ui.text(g, font, number, a.x + 8, a.y + 6, Ui.textFaint());
            Ui.text(g, font, Ui.clip(font, step.describe().getString(), a.w - 90), a.x + 26, a.y + 6, Ui.text());
        }, null));

        // Hoch, runter, loeschen
        int bx = this.cx1 - 6 - 3 * 16 - 2 * 3;
        this.add(this.smallButton(bx, y + 3, 16, "↑", Ui.accent(), () -> this.moveStep(macro, index, -1)));
        this.add(this.smallButton(bx + 19, y + 3, 16, "↓", Ui.accent(), () -> this.moveStep(macro, index, 1)));
        this.add(this.smallButton(bx + 38, y + 3, 16, "✕", Ui.RED, () -> macro.steps.remove(index)));

        int lx = this.cx0 + 26;
        int ly = y + 19;
        int sliderW = Math.max(60, width / 2 - 30);

        switch (step.type)
        {
            case WAIT -> this.add(new Controls.Slider(lx, ly, width - 36, 0, 200, 1, () -> step.ticks, v -> step.ticks = (int) v));
            case KEYS -> this.add(new Controls.Slider(lx, ly, width - 36, 1, 200, 1, () -> step.ticks, v -> step.ticks = (int) v));
            case WAIT_FOR_BLOCK ->
            {
                this.add(new Controls.Slider(lx, ly, sliderW, 0, 1200, 20, () -> step.ticks, v -> step.ticks = (int) v));
                this.add(this.chip(lx + sliderW + 10, ly - 2, Component.translatable("easyplace2.macro.gui.from_crosshair"), () ->
                {
                    String block = crosshairBlockId(mc);

                    if (block != null)
                    {
                        step.block = block;
                    }
                }));
            }
            case MINE ->
            {
                Controls.Button mode = this.chip(lx, ly - 2, step.mode == MacroStep.MineMode.WHILE_BLOCK
                        ? Component.translatable("easyplace2.macro.gui.mode_while")
                        : Component.translatable("easyplace2.macro.gui.mode_once"), () ->
                {
                    step.mode = step.mode == MacroStep.MineMode.ONCE ? MacroStep.MineMode.WHILE_BLOCK : MacroStep.MineMode.ONCE;
                });
                this.add(mode);
                this.add(this.chip(lx + mode.w + 6, ly - 2, Component.translatable("easyplace2.macro.gui.from_crosshair"), () ->
                {
                    String block = crosshairBlockId(mc);

                    if (block != null)
                    {
                        step.block = block;
                        step.mode = MacroStep.MineMode.WHILE_BLOCK;
                    }
                }));
            }
            case LOOK -> this.add(this.chip(lx, ly - 2, Component.translatable("easyplace2.macro.gui.take_look"), () ->
            {
                if (mc.player != null)
                {
                    step.yaw = mc.player.getYRot();
                    step.pitch = mc.player.getXRot();
                }
            }));
            case SELECT -> this.add(this.chip(lx, ly - 2, Component.translatable("easyplace2.macro.gui.take_item"), () ->
            {
                if (mc.player != null)
                {
                    ItemStack held = mc.player.getMainHandItem();
                    step.item = held.isEmpty() ? "" : MacroStep.id(held.getItem());
                    step.slot = mc.player.getInventory().getSelectedSlot();
                }
            }));
            case USE -> this.add(new Controls.Area(lx, ly - 1, width - 36, 10, (g, font, a, hover) ->
                    Ui.text(g, font, Component.translatable("easyplace2.macro.gui.use_desc"), a.x, a.y, Ui.textFaint()), null));
        }

        return y + h;
    }

    private static MacroStep newStep(Minecraft mc, String key)
    {
        String block = crosshairBlockId(mc);

        return switch (key)
        {
            case "mine" -> block != null ? MacroStep.mine(MacroStep.MineMode.WHILE_BLOCK, block) : MacroStep.mine(MacroStep.MineMode.ONCE, "");
            case "wait_block" -> MacroStep.waitForBlock(block != null ? block : "minecraft:oak_log", 0);
            case "use" -> MacroStep.use();
            case "look" -> mc.player != null ? MacroStep.look(mc.player.getYRot(), mc.player.getXRot()) : MacroStep.look(0, 0);
            case "select" ->
            {
                ItemStack held = mc.player != null ? mc.player.getMainHandItem() : ItemStack.EMPTY;
                yield MacroStep.select(held.isEmpty() ? "" : MacroStep.id(held.getItem()),
                        mc.player != null ? mc.player.getInventory().getSelectedSlot() : 0);
            }
            default -> MacroStep.wait(20);
        };
    }

    /** Der Block, auf den der Spieler vor dem Oeffnen des Menues geschaut hat. */
    @Nullable
    private static String crosshairBlockId(Minecraft mc)
    {
        if (mc.level != null && mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK)
        {
            return MacroStep.id(mc.level.getBlockState(bhr.getBlockPos()));
        }

        return null;
    }

    private static int totalTicks(Macro macro)
    {
        int sum = 0;

        for (MacroStep step : macro.steps)
        {
            if (step.type == MacroStep.Type.WAIT || step.type == MacroStep.Type.KEYS)
            {
                sum += step.ticks;
            }
        }

        return sum;
    }

    private void moveStep(Macro macro, int index, int delta)
    {
        int to = index + delta;

        if (to >= 0 && to < macro.steps.size())
        {
            macro.steps.add(to, macro.steps.remove(index));
        }
    }

    /** Kleiner quadratischer Knopf mit Zeichen; baut die Seite danach neu auf und speichert. */
    private Controls.Area smallButton(int x, int y, int size, String symbol, int color, Runnable action)
    {
        return new Controls.Area(x, y, size, size, (g, font, a, hover) ->
        {
            Ui.round(g, a.x, a.y, a.x + a.w, a.y + a.h, Ui.withAlpha(color, (int) (0x30 + 0x50 * hover)));
            Ui.text(g, font, symbol, a.x + (a.w - font.width(symbol) + 1) / 2, a.y + (a.h - 8) / 2 + 1, Ui.lerpColor(Ui.textDim(), color, hover));
        }, () ->
        {
            action.run();
            MacroStore.INSTANCE.save();
            this.rebuildContent();
        });
    }

    /** Kleiner Textknopf in einer Schrittzeile; baut die Seite danach neu auf und speichert. */
    private Controls.Button chip(int x, int y, Component label, Runnable action)
    {
        return new Controls.Button(x, y, this.font.width(label) + 12, 13, () -> label, Ui::accent, false, () ->
        {
            action.run();
            MacroStore.INSTANCE.save();
            this.rebuildContent();
        });
    }

    private int hintHeight(Component text)
    {
        return Math.max(1, this.font.split(text, this.cx1 - this.cx0 - 4).size()) * 10;
    }

    private void addHint(int y, Component text)
    {
        this.add(new Controls.Area(this.cx0, y, this.cx1 - this.cx0, this.hintHeight(text), (g, font, a, hover) ->
        {
            List<FormattedCharSequence> split = font.split(text, a.w - 4);

            for (int i = 0; i < split.size(); i++)
            {
                g.drawString(font, split.get(i), a.x + 2, a.y + i * 10, Ui.textFaint(), false);
            }
        }, null));
    }

    // ---------------------------------------------------------------- Info

    private void buildInfo()
    {
        int width = this.cx1 - this.cx0;
        this.add(new Controls.Area(this.cx0, this.contentTop, width, 82, (g, font, a, hover) ->
        {
            Ui.card(g, a.x, a.y, a.x + a.w, a.y + a.h, Ui.card(), Ui.border());
            boolean schematic = SchematicWorldHandler.getSchematicWorld() != null &&
                                !DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().isEmpty();
            int y = a.y + 8;
            y = this.check(g, font, a, y, "litematica", true);
            y = this.check(g, font, a, y, "schematic", schematic);
            y = this.check(g, font, a, y, "baritone", BaritoneBridge.isAvailable());
            y = this.check(g, font, a, y, "easyplace", Ep2Configs.EASY_PLACE_2.getBooleanValue());
            this.check(g, font, a, y, "strict", Ep2Configs.STRICT_CROSSHAIR.getBooleanValue());
        }, null));

        int y = this.contentTop + 88;

        for (String key : new String[] { "how1", "how2", "how3", "how4", "server" })
        {
            Component text = Component.translatable("easyplace2.printer.info." + key);
            int lines = Math.max(1, this.font.split(text, width - 20).size());
            int h = 12 + lines * 10;
            this.add(new Controls.Area(this.cx0, y, width, h, (g, font, a, hover) ->
            {
                this.paintCard(g, a, hover);
                List<FormattedCharSequence> split = font.split(text, a.w - 20);

                for (int i = 0; i < split.size(); i++)
                {
                    g.drawString(font, split.get(i), a.x + 10, a.y + 7 + i * 10, key.equals("server") ? Ui.AMBER : Ui.textDim(), false);
                }
            }, null));
            y += h + 4;
        }
    }

    private int check(GuiGraphics g, Font font, Controls.Area a, int y, String key, boolean ok)
    {
        int color = ok ? Ui.GREEN : Ui.RED;
        Ui.dot(g, a.x + 14, y + 3, 4, Ui.withAlpha(color, 0x50));
        Ui.dot(g, a.x + 14, y + 3, 2, color);
        Ui.text(g, font, Component.translatable("easyplace2.printer.check." + key), a.x + 24, y, Ui.text());
        Component state = Component.translatable(ok ? "easyplace2.printer.check.ok" : "easyplace2.printer.check.missing");
        Ui.text(g, font, state, a.x + a.w - 10 - font.width(state), y, color);
        return y + 14;
    }

    // ---------------------------------------------------------------- Zeichnen

    private int lastMouseX, lastMouseY;

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        if (Ep2Configs.PRINTER_GLASS.getBooleanValue() && this.minecraft != null && this.minecraft.level != null)
        {
            // Vanilla-Unschaerfe hinter dem Glas-Panel (wird pro Bild nur einmal aufgerufen)
            super.renderBackground(g, mouseX, mouseY, partialTick);
        }
        else
        {
            g.fillGradient(0, 0, this.width, this.height, 0xB0050712, 0xD0050712);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        super.render(g, mouseX, mouseY, partialTick);
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.scroll = Ui.approach(this.scroll, this.scrollTarget, 0.3f);

        // Beim Oeffnen kurz von unten hereingleiten
        float open = Ui.easeOut((System.currentTimeMillis() - this.openedAt) / 220f);
        int slide = Math.round((1f - open) * 10f);
        g.pose().pushMatrix();
        g.pose().translate(0, slide);
        int my = mouseY - slide;

        this.renderFrame(g);
        this.renderSidebar(g, mouseX, my);
        this.renderHeader(g, mouseX, my);

        for (Controls.Control c : this.header)
        {
            c.render(g, this.font, mouseX, my);
        }

        g.enableScissor(this.cx0 - 2, this.contentTop, this.cx1 + 6, this.contentBottom);
        int offset = Math.round(this.scroll);

        for (int i = 0; i < this.content.size(); i++)
        {
            Controls.Control c = this.content.get(i);
            c.y = this.contentBaseY.get(i) - offset;
            c.visible = c.y + c.h > this.contentTop && c.y < this.contentBottom;
            boolean mouseInView = my >= this.contentTop && my < this.contentBottom;
            c.render(g, this.font, mouseX, mouseInView ? my : -1000);
        }

        g.disableScissor();
        this.renderScrollbar(g);

        if (this.search != null)
        {
            this.search.render(g, mouseX, my, partialTick);
        }

        if (page == Page.THEME && !this.searching())
        {
            Component footer = Component.literal("● ").withColor(Ui.accent() & 0xFFFFFF)
                    .append(Component.translatable("easyplace2.printer.theme.footer", Theme.current().displayName()).withColor(Ui.textFaint() & 0xFFFFFF));
            Ui.text(g, this.font, footer, this.x1 - 14 - this.font.width(footer), this.y1 - 16, Ui.textFaint());
        }

        g.pose().popMatrix();
    }

    private void renderFrame(GuiGraphics g)
    {
        Ui.shadow(g, this.x0, this.y0, this.x1, this.y1);
        Ui.card(g, this.x0, this.y0, this.x1, this.y1, Ui.background(), Ui.border());
        // feiner Lichtschein oben im Panel
        g.fillGradient(this.x0 + 2, this.y0 + 1, this.x1 - 2, this.y0 + 60, Ui.withAlpha(Ui.theme().accent(), 0x14), 0x00000000);
        // Seitenleiste als eigenes Innenpanel
        Ui.card(g, this.x0 + 6, this.y0 + 6, this.x0 + 6 + SIDEBAR_W, this.y1 - 6, Ui.sidebar(), Ui.border());
    }

    private void renderSidebar(GuiGraphics g, int mouseX, int mouseY)
    {
        int sx = this.x0 + 6;

        // Logo und Name
        Ui.item(g, new ItemStack(Items.GOLDEN_HELMET), sx + 9, this.y0 + 15, 0.75f);
        MutableComponent name = Component.literal("Easy Place ").withStyle(ChatFormatting.BOLD);
        Ui.text(g, this.font, name, sx + 24, this.y0 + 14, Ui.text());
        Ui.text(g, this.font, Component.literal("Printer").withStyle(ChatFormatting.ITALIC), sx + 24 + this.font.width(name), this.y0 + 14, Ui.accent());
        Ui.scaledText(g, this.font, Component.translatable("easyplace2.printer.version", "0.2.0"), sx + 24, this.y0 + 25, 0.75f, Ui.textFaint());

        g.enableScissor(sx + 1, this.sideTop - 2, sx + SIDEBAR_W - 1, this.sideBottom);

        // gleitende Markierung des gewaehlten Eintrags
        Integer targetY = this.sidebarY.get(page);

        if (targetY != null)
        {
            this.highlightY = this.highlightY < 0 ? targetY : Ui.approach(this.highlightY, targetY, 0.3f);
            int hy = Math.round(this.highlightY) - this.sideScroll;
            Ui.round(g, sx + 6, hy, sx + SIDEBAR_W - 6, hy + this.sideStep - 1,
                    Ui.lerpColor(Ui.cardHover(), Ui.withAlpha(Ui.theme().accent(), 0xFF), 0.18f));
        }

        boolean mouseInList = mouseY >= this.sideTop && mouseY < this.sideBottom;

        for (int i = 0; i < this.sidebar.size(); i++)
        {
            Controls.Control c = this.sidebar.get(i);
            c.y = this.sidebarBaseY.get(i) - this.sideScroll;
            c.visible = c.y + c.h > this.sideTop - 2 && c.y < this.sideBottom;
            c.render(g, this.font, mouseX, mouseInList ? mouseY : -1000);
        }

        g.disableScissor();

        if (this.sideMaxScroll > 0)
        {
            // feiner Hinweis, dass die Liste weitergeht
            int trackX = sx + SIDEBAR_W - 4;
            int view = this.sideBottom - this.sideTop;
            int h = Math.max(12, view * view / (view + this.sideMaxScroll));
            int y = this.sideTop + (view - h) * this.sideScroll / this.sideMaxScroll;
            g.fill(trackX, y, trackX + 1, y + h, Ui.withAlpha(Ui.theme().accent(), 0xA0));
        }

        // Status ganz unten in der Seitenleiste
        PrinterController.State state = PrinterController.INSTANCE.getState();
        int color = Ui.stateColor(state);
        int py = this.y1 - 30;
        Ui.card(g, sx + 6, py, sx + SIDEBAR_W - 6, py + 18, Ui.card(), Ui.border());
        float pulse = state == PrinterController.State.OFF ? 1f : 0.55f + 0.45f * (float) Math.sin(System.currentTimeMillis() / 220.0);
        Ui.dot(g, sx + 15, py + 9, 2, Ui.withAlpha(color, (int) (0xFF * pulse)));
        Ui.text(g, this.font, Ui.stateName(state), sx + 22, py + 5, color);
    }

    private void paintSidebarItem(GuiGraphics g, Font font, Controls.Area a, float hover, Page p)
    {
        boolean selected = p == page && !this.searching();

        if (!selected && hover > 0.01f)
        {
            Ui.round(g, a.x, a.y, a.x + a.w, a.y + a.h, Ui.withAlpha(Ui.theme().cardHover(), (int) (0xB0 * hover)));
        }

        int ty = a.y + (a.h - 8) / 2;
        Ui.item(g, p.icon(), a.x + 5, a.y + (a.h - 12) / 2, 0.75f);
        Ui.text(g, font, p.title(), a.x + 22, ty, selected ? Ui.text() : Ui.lerpColor(Ui.textDim(), Ui.text(), hover));

        int count = p.hasOptions() ? OptionEntry.of(p).size() : p == Page.THEME ? Theme.ALL.size() : p == Page.KEYS ? 7
                  : p == Page.STORAGE ? StorageRegistry.INSTANCE.entries().size()
                  : p == Page.MACROS ? MacroStore.INSTANCE.macros().size() : 0;

        if (count > 0)
        {
            String c = String.valueOf(count);
            Ui.scaledText(g, font, Component.literal(c), a.x + a.w - 6 - Ui.scaledWidth(font, Component.literal(c), 0.75f), ty + 1, 0.75f, Ui.textFaint());
        }
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY)
    {
        String keyA, keyB;
        Component subtitle;

        if (this.searching())
        {
            keyA = "easyplace2.printer.head.search.a";
            keyB = "easyplace2.printer.head.search.b";
            long count = OptionEntry.ALL.stream().filter(e -> e.matches(query)).count();
            subtitle = Component.translatable("easyplace2.printer.head.search.sub", count, query);
        }
        else
        {
            keyA = "easyplace2.printer.head." + page.key() + ".a";
            keyB = "easyplace2.printer.head." + page.key() + ".b";

            if (page.hasOptions())
            {
                List<OptionEntry> entries = OptionEntry.of(page);
                long switches = entries.stream().filter(OptionEntry::isBoolean).count();
                long enabled = entries.stream().filter(e -> e.isBoolean() && e.getBoolean()).count();
                subtitle = switches > 0 ? Component.translatable("easyplace2.printer.head.options.sub", entries.size(), enabled)
                                        : Component.translatable("easyplace2.printer.head.options.count", entries.size());
            }
            else
            {
                subtitle = Component.translatable("easyplace2.printer.head." + page.key() + ".sub");
            }
        }

        // Grosser Titel: Akzentwort kursiv, Rest fett
        Component a = Component.translatable(keyA).withStyle(ChatFormatting.ITALIC);
        Component b = Component.translatable(keyB).withStyle(ChatFormatting.BOLD);
        // Titel kleiner, wenn er sonst unter das Suchfeld laufen wuerde
        int titleRoom = (this.search != null ? this.search.getX() - 24 : this.cx1) - this.cx0;
        float scale = 1.5f;

        while (scale > 1.0f && Ui.scaledWidth(this.font, a, scale) + 6 + Ui.scaledWidth(this.font, b, scale) > titleRoom)
        {
            scale -= 0.1f;
        }

        Ui.scaledText(g, this.font, a, this.cx0, this.headerTop, scale, Ui.accent());
        Ui.scaledText(g, this.font, b, this.cx0 + Ui.scaledWidth(this.font, a, scale) + 6, this.headerTop, scale, Ui.text());
        Ui.text(g, this.font, Component.literal(Ui.clip(this.font, subtitle.getString(), this.cx1 - this.cx0)),
                this.cx0, this.headerTop + 16, Ui.textFaint());

        // Suchfeld oben rechts
        if (this.search != null)
        {
            int bx0 = this.search.getX() - 18, bx1 = this.cx1;
            int by0 = this.headerTop - 2, by1 = this.headerTop + 15;
            boolean focused = this.search.isFocused();
            Ui.card(g, bx0, by0, bx1, by1, Ui.card(), focused ? Ui.withAlpha(Ui.accent(), 0xC0) : Ui.border());
            // Lupe
            int lx = bx0 + 8, ly = by0 + 7;
            Ui.dot(g, lx, ly, 3, Ui.textDim());
            Ui.dot(g, lx, ly, 2, Ui.card());
            g.fill(lx + 2, ly + 2, lx + 4, ly + 4, Ui.textDim());
        }
    }

    private void paintViewIcon(GuiGraphics g, Controls.Area a, float hover, boolean grid)
    {
        boolean active = Ep2Configs.PRINTER_GRID_VIEW.getBooleanValue() == grid;
        Ui.round(g, a.x, a.y, a.x + a.w, a.y + a.h, active ? Ui.withAlpha(Ui.theme().accent(), 0x40)
                                                          : Ui.withAlpha(Ui.theme().cardHover(), (int) (0xC0 * hover)));
        int c = active ? Ui.accent() : Ui.textDim();
        int ix = a.x + 5, iy = a.y + 4;

        if (grid)
        {
            g.fill(ix, iy, ix + 3, iy + 3, c);
            g.fill(ix + 5, iy, ix + 8, iy + 3, c);
            g.fill(ix, iy + 5, ix + 3, iy + 8, c);
            g.fill(ix + 5, iy + 5, ix + 8, iy + 8, c);
        }
        else
        {
            g.fill(ix, iy, ix + 8, iy + 1, c);
            g.fill(ix, iy + 3, ix + 8, iy + 4, c);
            g.fill(ix, iy + 6, ix + 8, iy + 7, c);
        }
    }

    private void renderScrollbar(GuiGraphics g)
    {
        if (this.maxScroll <= 0)
        {
            return;
        }

        int top = this.contentTop, bottom = this.contentBottom, view = bottom - top;
        int h = Math.max(16, view * view / (view + this.maxScroll));
        int y = top + Math.round((view - h) * (this.scroll / this.maxScroll));
        g.fill(this.cx1 + 4, top, this.cx1 + 6, bottom, Ui.withAlpha(Ui.theme().sidebar(), 0xFF));
        g.fill(this.cx1 + 4, y, this.cx1 + 6, y + h, Ui.accent());
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
    {
        double mx = event.x(), my = event.y();

        if (event.button() == 0)
        {
            if (this.search != null && !this.isOverSearch(mx, my))
            {
                this.search.setFocused(false);
            }

            if (my >= this.sideTop && my < this.sideBottom)
            {
                for (Controls.Control c : this.sidebar)
                {
                    if (c.click(mx, my))
                    {
                        return true;
                    }
                }
            }

            for (Controls.Control c : this.header)
            {
                if (c.click(mx, my))
                {
                    return true;
                }
            }

            if (my >= this.contentTop && my < this.contentBottom)
            {
                for (Controls.Control c : new ArrayList<>(this.content))
                {
                    if (c.click(mx, my))
                    {
                        this.dragging = c;
                        return true;
                    }
                }
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    private boolean isOverSearch(double mx, double my)
    {
        return this.search != null && mx >= this.search.getX() - 18 && mx < this.cx1 && my >= this.headerTop - 2 && my < this.headerTop + 15;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy)
    {
        if (this.dragging != null)
        {
            this.dragging.drag(event.x());
            return true;
        }

        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event)
    {
        if (this.dragging != null)
        {
            this.dragging.release();
            this.dragging = null;
        }

        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY)
    {
        // Ueber der Seitenleiste scrollt die Seitenleiste
        if (mouseX < this.x0 + 6 + SIDEBAR_W && this.sideMaxScroll > 0)
        {
            this.sideScroll = Math.max(0, Math.min(this.sideMaxScroll, this.sideScroll - (int) Math.round(scrollY * 19)));
            return true;
        }

        if (this.maxScroll > 0)
        {
            this.scrollTarget = Math.max(0, Math.min(this.maxScroll, this.scrollTarget - (float) scrollY * 24f));
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event)
    {
        if (this.capturing != null)
        {
            int key = event.key();

            if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE)
            {
                this.capturing.getKeybind().clearKeys();
            }
            else if (key != GLFW.GLFW_KEY_ESCAPE)
            {
                this.capturing.getKeybind().setValueFromString(KeyCodes.getNameForKey(key));
            }

            InputEventHandler.getKeybindManager().updateUsedKeys();
            Ep2Configs.saveToFile();
            this.capturing = null;
            return true;
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    @Override
    public void removed()
    {
        Ep2Configs.saveToFile();
        MacroStore.INSTANCE.save();
        super.removed();
    }

    @Override
    public void onClose()
    {
        Minecraft.getInstance().setScreen(this.parent);
    }
}
