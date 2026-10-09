package io.github.recher1001.easyplace2.printer.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.printer.BaritoneBridge;
import io.github.recher1001.easyplace2.printer.InventoryManager;
import io.github.recher1001.easyplace2.printer.PrinterController;
import io.github.recher1001.easyplace2.printer.SchematicScanner;

/**
 * Das Printer-Menue: Uebersicht mit Fortschritt und Material, Einstellungen und Info.
 */
public class PrinterScreen extends Screen
{
    private enum Page { OVERVIEW, SETTINGS, INFO }

    private static Page page = Page.OVERVIEW;

    @Nullable private final Screen parent;
    private final List<Controls.Control> controls = new ArrayList<>();
    private final List<Controls.Control> scrolling = new ArrayList<>();
    private final List<Integer> scrollingBaseY = new ArrayList<>();
    @Nullable private Controls.Control dragging;

    private int x0, y0, x1, y1;
    private int contentTop, contentBottom;
    private int scroll, maxScroll;
    private float shownProgress = -1f;
    private float openAnim;

    public PrinterScreen(@Nullable Screen parent)
    {
        super(Component.translatable("easyplace2.printer.title"));
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        int w = Math.min(440, this.width - 16);
        int h = Math.min(290, this.height - 16);
        this.x0 = (this.width - w) / 2;
        this.y0 = (this.height - h) / 2;
        this.x1 = this.x0 + w;
        this.y1 = this.y0 + h;
        this.contentTop = this.y0 + 56;
        this.contentBottom = this.y1 - 10;
        this.controls.clear();
        this.scrolling.clear();
        this.scrollingBaseY.clear();
        this.scroll = 0;

        int tabW = 86;
        this.controls.add(new Controls.Tab(this.x0 + 10, this.y0 + 34, tabW, 16, Component.translatable("easyplace2.printer.tab.overview"),
                () -> page == Page.OVERVIEW, () -> this.switchPage(Page.OVERVIEW)));
        this.controls.add(new Controls.Tab(this.x0 + 12 + tabW, this.y0 + 34, tabW, 16, Component.translatable("easyplace2.printer.tab.settings"),
                () -> page == Page.SETTINGS, () -> this.switchPage(Page.SETTINGS)));
        this.controls.add(new Controls.Tab(this.x0 + 14 + tabW * 2, this.y0 + 34, tabW, 16, Component.translatable("easyplace2.printer.tab.info"),
                () -> page == Page.INFO, () -> this.switchPage(Page.INFO)));

        switch (page)
        {
            case OVERVIEW -> this.initOverview();
            case SETTINGS -> this.initSettings();
            case INFO -> {}
        }
    }

    private void switchPage(Page next)
    {
        page = next;
        this.rebuildWidgets();
    }

    private void initOverview()
    {
        PrinterController printer = PrinterController.INSTANCE;
        int by = this.y1 - 30;
        int bw = 110;

        this.controls.add(new Controls.Button(this.x0 + 10, by, bw, 20,
                () -> Component.translatable(printer.isRunning() ? "easyplace2.printer.button.stop" : "easyplace2.printer.button.start"),
                () -> printer.isRunning() ? Ui.RED : Ui.GREEN,
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
                }));

        Controls.Button pause = new Controls.Button(this.x0 + 16 + bw, by, bw, 20,
                () -> Component.translatable(printer.isPaused() ? "easyplace2.printer.button.resume" : "easyplace2.printer.button.pause"),
                () -> Ui.AMBER,
                () -> printer.setPaused(!printer.isPaused()));
        pause.enabled = printer::isRunning;
        this.controls.add(pause);

        this.controls.add(new Controls.Button(this.x1 - 10 - 80, by, 80, 20,
                () -> Component.translatable("easyplace2.printer.button.close"), () -> Ui.ACCENT, this::onClose));
    }

    private void initSettings()
    {
        int colW = (this.x1 - this.x0 - 30) / 2;
        int left = this.x0 + 10;
        int right = left + colW + 10;
        int y = this.contentTop + 14;

        // Linke Spalte: Bewegung und Zeiten
        y = this.slider(left, y, colW, "turnSpeed", Ep2Configs.PRINTER_TURN_SPEED, 40, 720, 5, v -> String.format(Locale.ROOT, "%.0f°/s", v));
        y = this.slider(left, y, colW, "turnJitter", Ep2Configs.PRINTER_TURN_JITTER, 0, 1, 0.05, v -> String.format(Locale.ROOT, "%.0f%%", v * 100));
        y = this.slider(left, y, colW, "fastTurnMaxAngle", Ep2Configs.PRINTER_FAST_TURN_MAX_ANGLE, 5, 180, 5, v -> String.format(Locale.ROOT, "%.0f°", v));
        y = this.slider(left, y, colW, "placeDelay", Ep2Configs.DELAY_TICKS, 4, 40, ticks());
        y = this.slider(left, y, colW, "extraDelay", Ep2Configs.PRINTER_EXTRA_DELAY, 0, 40, ticks());
        y = this.slider(left, y, colW, "reactionMin", Ep2Configs.PRINTER_REACTION_MIN, 1, 20, ticks());
        y = this.slider(left, y, colW, "reactionMax", Ep2Configs.PRINTER_REACTION_MAX, 1, 30, ticks());
        y = this.slider(left, y, colW, "invDelayMin", Ep2Configs.PRINTER_INV_DELAY_MIN, 1, 40, ticks());
        y = this.slider(left, y, colW, "invDelayMax", Ep2Configs.PRINTER_INV_DELAY_MAX, 1, 60, ticks());
        y = this.slider(left, y, colW, "reachMargin", Ep2Configs.PRINTER_REACH_MARGIN, 0, 2.5, 0.1, v -> String.format(Locale.ROOT, "%.1f", v));
        int leftEnd = y;

        // Rechte Spalte: Schalter
        y = this.contentTop + 14;
        y = this.toggle(right, y, colW, "fastTurn", Ep2Configs.PRINTER_FAST_TURN);
        y = this.toggle(right, y, colW, "useBaritone", Ep2Configs.PRINTER_USE_BARITONE);
        y = this.toggle(right, y, colW, "sprint", Ep2Configs.PRINTER_SPRINT);
        y = this.toggle(right, y, colW, "layerRange", Ep2Configs.PRINTER_LAYER_RANGE);
        y = this.toggle(right, y, colW, "pauseOnDamage", Ep2Configs.PRINTER_PAUSE_ON_DAMAGE);
        y = this.toggle(right, y, colW, "hud", Ep2Configs.PRINTER_HUD);
        y = this.toggle(right, y, colW, "debug", Ep2Configs.DEBUG);

        this.maxScroll = Math.max(0, Math.max(leftEnd, y) + 4 - this.contentBottom);
    }

    private static java.util.function.DoubleFunction<String> ticks()
    {
        return v -> String.format(Locale.ROOT, "%d t", Math.round(v));
    }

    private int slider(int x, int y, int w, String key, ConfigDouble config, double min, double max, double step,
                       java.util.function.DoubleFunction<String> format)
    {
        this.addScrolling(new Controls.Slider(x, y, w, 22, Component.translatable("easyplace2.printer.setting." + key), min, max, step,
                config::getDoubleValue, config::setDoubleValue, format));
        return y + 24;
    }

    private int slider(int x, int y, int w, String key, ConfigInteger config, int min, int max,
                       java.util.function.DoubleFunction<String> format)
    {
        this.addScrolling(new Controls.Slider(x, y, w, 22, Component.translatable("easyplace2.printer.setting." + key), min, max, 1,
                config::getIntegerValue, v -> config.setIntegerValue((int) Math.round(v)), format));
        return y + 24;
    }

    private int toggle(int x, int y, int w, String key, ConfigBoolean config)
    {
        this.addScrolling(new Controls.Toggle(x, y, w, 18, Component.translatable("easyplace2.printer.setting." + key),
                config::getBooleanValue, config::setBooleanValue));
        return y + 20;
    }

    private void addScrolling(Controls.Control control)
    {
        this.scrolling.add(control);
        this.scrollingBaseY.add(control.y);
    }

    // ---------------------------------------------------------------- Zeichnen

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        // Eigener, ruhiger Hintergrund statt Unschaerfe
        g.fillGradient(0, 0, this.width, this.height, Ui.BACKDROP_TOP, Ui.BACKDROP_BOTTOM);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        super.render(g, mouseX, mouseY, partialTick);
        this.openAnim = Math.min(1f, this.openAnim + 0.12f);

        PrinterController printer = PrinterController.INSTANCE;

        // Panel mit Schatten und Akzentleiste
        Ui.round(g, this.x0 - 2, this.y0 - 1, this.x1 + 2, this.y1 + 3, 0x50000000);
        Ui.card(g, this.x0, this.y0, this.x1, this.y1, Ui.PANEL, Ui.BORDER);
        Ui.gradientH(g, this.x0 + 3, this.y0 + 1, this.x1 - 3, this.y0 + 3, Ui.ACCENT, Ui.ACCENT_2);

        // Kopfzeile
        g.drawString(this.font, Component.literal("Easy Place 2.0").withStyle(ChatFormatting.BOLD), this.x0 + 12, this.y0 + 12, Ui.TEXT, false);
        g.drawString(this.font, Component.translatable("easyplace2.printer.subtitle"), this.x0 + 12 + this.font.width(Component.literal("Easy Place 2.0").withStyle(ChatFormatting.BOLD)) + 6,
                this.y0 + 12, Ui.ACCENT, false);
        this.drawStatusPill(g, printer.getState(), this.x1 - 12, this.y0 + 9);
        g.fill(this.x0 + 10, this.y0 + 52, this.x1 - 10, this.y0 + 53, Ui.BORDER);

        switch (page)
        {
            case OVERVIEW -> this.renderOverview(g, mouseX, mouseY, printer);
            case SETTINGS -> this.renderSettingsHeader(g);
            case INFO -> this.renderInfo(g);
        }

        for (Controls.Control c : this.controls)
        {
            c.render(g, this.font, mouseX, mouseY, partialTick);
        }

        if (!this.scrolling.isEmpty())
        {
            g.enableScissor(this.x0 + 2, this.contentTop + 12, this.x1 - 2, this.contentBottom);

            for (int i = 0; i < this.scrolling.size(); i++)
            {
                Controls.Control c = this.scrolling.get(i);
                c.y = this.scrollingBaseY.get(i) - this.scroll;
                c.visible = c.y + c.h > this.contentTop + 12 && c.y < this.contentBottom;
                c.render(g, this.font, mouseX, mouseY, partialTick);
            }

            g.disableScissor();
        }
    }

    private void drawStatusPill(GuiGraphics g, PrinterController.State state, int right, int y)
    {
        Component name = Ui.stateName(state);
        int color = Ui.stateColor(state);
        int w = this.font.width(name) + 22;
        int x = right - w;
        Ui.round(g, x, y, right, y + 15, Ui.withAlpha(color, 0x30));
        // pulsierender Punkt, solange gearbeitet wird
        float pulse = state == PrinterController.State.OFF ? 1f : 0.55f + 0.45f * (float) Math.sin(System.currentTimeMillis() / 220.0);
        Ui.round(g, x + 6, y + 5, x + 11, y + 10, Ui.withAlpha(color, (int) (0xFF * pulse)));
        g.drawString(this.font, name, x + 15, y + 4, color, false);
    }

    private void renderOverview(GuiGraphics g, int mouseX, int mouseY, PrinterController printer)
    {
        SchematicScanner.Snapshot snap = printer.getSnapshot();
        int cx0 = this.x0 + 10, cx1 = this.x1 - 10;
        int top = this.contentTop;

        // Fortschritt
        Ui.card(g, cx0, top, cx1, top + 46, Ui.PANEL_INNER, Ui.BORDER);
        double progress = snap != null ? snap.progress() : 0.0;
        this.shownProgress = this.shownProgress < 0 ? (float) progress : this.shownProgress + ((float) progress - this.shownProgress) * 0.12f;
        String percent = String.format(Locale.ROOT, "%.1f %%", this.shownProgress * 100);
        g.drawString(this.font, Component.translatable("easyplace2.printer.label.progress"), cx0 + 8, top + 7, Ui.TEXT_DIM, false);
        g.drawString(this.font, Component.literal(percent).withStyle(ChatFormatting.BOLD), cx1 - 8 - this.font.width(Component.literal(percent).withStyle(ChatFormatting.BOLD)), top + 7, Ui.TEXT, false);

        int bx0 = cx0 + 8, bx1 = cx1 - 8, by = top + 19;
        Ui.round(g, bx0, by, bx1, by + 8, Ui.TRACK);
        int fill = bx0 + Math.round((bx1 - bx0) * Math.max(0f, Math.min(1f, this.shownProgress)));

        if (fill > bx0 + 2)
        {
            Ui.gradientH(g, bx0 + 1, by + 1, fill - 1, by + 7, Ui.ACCENT, Ui.ACCENT_2);
            // Glanzlicht, das ueber den Balken laeuft, solange gebaut wird
            if (printer.isRunning())
            {
                int shine = bx0 + (int) ((System.currentTimeMillis() / 6) % Math.max(1, fill - bx0));
                g.fill(Math.max(bx0 + 1, shine - 6), by + 1, Math.min(fill - 1, shine + 6), by + 7, 0x30FFFFFF);
            }
        }

        String stats = snap == null ? Component.translatable("easyplace2.printer.label.scanning").getString()
                : Component.translatable("easyplace2.printer.label.stats", snap.done(), snap.total(),
                        String.format(Locale.ROOT, "%.0f", printer.getRate()), Ui.formatDuration(printer.getEtaSeconds())).getString();
        g.drawString(this.font, stats, cx0 + 8, top + 33, Ui.TEXT_DIM, false);

        // Linke Karte: Status
        int cardTop = top + 52;
        int cardBottom = this.y1 - 36;
        int mid = (cx0 + cx1) / 2;
        Ui.card(g, cx0, cardTop, mid - 3, cardBottom, Ui.PANEL_INNER, Ui.BORDER);
        g.drawString(this.font, Component.translatable("easyplace2.printer.label.status"), cx0 + 8, cardTop + 7, Ui.TEXT_DIM, false);

        SchematicScanner.Target target = printer.getCurrent();
        int ry = cardTop + 21;
        ry = this.row(g, cx0 + 8, mid - 11, ry, "schematic", snap == null || snap.names().isEmpty() ? "–" : snap.names());
        ry = this.row(g, cx0 + 8, mid - 11, ry, "target", target == null ? "–" : target.state().getBlock().getName().getString());
        ry = this.row(g, cx0 + 8, mid - 11, ry, "detail", this.detailText(printer));
        ry = this.row(g, cx0 + 8, mid - 11, ry, "placed", String.valueOf(printer.getPlaced()));
        ry = this.row(g, cx0 + 8, mid - 11, ry, "wrong", snap == null ? "–" : String.valueOf(snap.wrong()));
        this.row(g, cx0 + 8, mid - 11, ry, "unplaceable", snap == null ? "–" : String.valueOf(snap.unplaceable()));

        // Rechte Karte: Material
        Ui.card(g, mid + 3, cardTop, cx1, cardBottom, Ui.PANEL_INNER, Ui.BORDER);
        Map<Item, Integer> missing = printer.getMissingMaterials();
        boolean anyMissing = !missing.isEmpty();
        g.drawString(this.font, Component.translatable(anyMissing ? "easyplace2.printer.label.missing" : "easyplace2.printer.label.materials"),
                mid + 11, cardTop + 7, anyMissing ? Ui.RED : Ui.TEXT_DIM, false);
        this.renderMaterials(g, mouseX, mouseY, mid + 11, cardTop + 20, cx1 - 8, cardBottom - 4, snap, missing);
    }

    private String detailText(PrinterController printer)
    {
        String detail = printer.getDetail();

        if ("skipped".equals(detail))
        {
            return Component.translatable("easyplace2.printer.detail.skipped").getString();
        }

        if ("damage".equals(detail))
        {
            return Component.translatable("easyplace2.printer.detail.damage").getString();
        }

        return detail.isEmpty() ? Ui.stateName(printer.getState()).getString() : detail;
    }

    private int row(GuiGraphics g, int x, int right, int y, String key, String value)
    {
        Component label = Component.translatable("easyplace2.printer.row." + key);
        g.drawString(this.font, label, x, y, Ui.TEXT_FAINT, false);
        int vx = x + 74;
        String shown = this.font.plainSubstrByWidth(value, Math.max(10, right - vx));
        g.drawString(this.font, shown, vx, y, Ui.TEXT, false);
        return y + 12;
    }

    private void renderMaterials(GuiGraphics g, int mouseX, int mouseY, int x, int y, int right, int bottom,
                                 @Nullable SchematicScanner.Snapshot snap, Map<Item, Integer> missing)
    {
        Minecraft mc = Minecraft.getInstance();

        if (snap == null || mc.player == null)
        {
            return;
        }

        // Fehlendes Material zuerst, sonst die meistgebrauchten Items
        Map<Item, Integer> source = missing.isEmpty() ? snap.neededItems() : missing;
        List<Map.Entry<Item, Integer>> entries = new ArrayList<>(source.entrySet());
        entries.sort(Map.Entry.<Item, Integer>comparingByValue(Comparator.reverseOrder()));

        if (entries.isEmpty())
        {
            g.drawString(this.font, Component.translatable("easyplace2.printer.label.nothing_needed"), x, y + 4, Ui.GREEN, false);
            return;
        }

        int cell = 20;
        int cols = Math.max(1, (right - x) / cell);
        int rows = Math.max(1, (bottom - y - 12) / cell);
        Component hovered = null;

        for (int i = 0; i < entries.size() && i < cols * rows; i++)
        {
            Item item = entries.get(i).getKey();
            int need = entries.get(i).getValue();
            int have = InventoryManager.countItem(mc.player, item);
            int ix = x + (i % cols) * cell;
            int iy = y + (i / cols) * cell;
            ItemStack stack = new ItemStack(item);
            boolean hover = mouseX >= ix && mouseY >= iy && mouseX < ix + 18 && mouseY < iy + 18;

            Ui.round(g, ix, iy, ix + 18, iy + 18, hover ? Ui.PANEL_HOVER : Ui.withAlpha(have >= need ? Ui.GREEN : Ui.RED, 0x22));
            g.renderItem(stack, ix + 1, iy + 1);
            g.renderItemDecorations(this.font, stack, ix + 1, iy + 1, compact(need));

            if (hover)
            {
                hovered = Component.literal(stack.getHoverName().getString() + ": ")
                        .append(Component.translatable("easyplace2.printer.label.need_have", need, have));
            }
        }

        if (hovered != null)
        {
            g.drawString(this.font, this.font.plainSubstrByWidth(hovered.getString(), right - x), x, bottom - 10, Ui.TEXT, false);
        }
    }

    private static String compact(int n)
    {
        return n >= 10000 ? (n / 1000) + "k" : n >= 1000 ? String.format(Locale.ROOT, "%.1fk", n / 1000.0) : String.valueOf(n);
    }

    private void renderSettingsHeader(GuiGraphics g)
    {
        int colW = (this.x1 - this.x0 - 30) / 2;
        g.drawString(this.font, Component.translatable("easyplace2.printer.section.timing"), this.x0 + 16, this.contentTop + 2, Ui.ACCENT, false);
        g.drawString(this.font, Component.translatable("easyplace2.printer.section.behaviour"), this.x0 + 26 + colW, this.contentTop + 2, Ui.ACCENT, false);

        if (this.maxScroll > 0)
        {
            int trackTop = this.contentTop + 14, trackBottom = this.contentBottom;
            int h = Math.max(16, (trackBottom - trackTop) * (trackBottom - trackTop) / (trackBottom - trackTop + this.maxScroll));
            int y = trackTop + (trackBottom - trackTop - h) * this.scroll / this.maxScroll;
            g.fill(this.x1 - 6, trackTop, this.x1 - 4, trackBottom, Ui.TRACK);
            g.fill(this.x1 - 6, y, this.x1 - 4, y + h, Ui.ACCENT);
        }
    }

    private void renderInfo(GuiGraphics g)
    {
        int x = this.x0 + 16;
        int y = this.contentTop + 2;
        Minecraft mc = Minecraft.getInstance();

        boolean schematic = SchematicWorldHandler.getSchematicWorld() != null &&
                            !DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().isEmpty();

        g.drawString(this.font, Component.translatable("easyplace2.printer.section.checks"), x, y, Ui.ACCENT, false);
        y += 14;
        y = this.check(g, x, y, "litematica", true);
        y = this.check(g, x, y, "schematic", schematic);
        y = this.check(g, x, y, "baritone", BaritoneBridge.isAvailable());
        y = this.check(g, x, y, "easyplace", Ep2Configs.EASY_PLACE_2.getBooleanValue());
        y = this.check(g, x, y, "strict", Ep2Configs.STRICT_CROSSHAIR.getBooleanValue());
        y += 8;

        g.drawString(this.font, Component.translatable("easyplace2.printer.section.hotkeys"), x, y, Ui.ACCENT, false);
        y += 14;
        y = this.keyRow(g, x, y, "menu", Ep2Configs.PRINTER_MENU.getKeybind().getKeysDisplayString());
        y = this.keyRow(g, x, y, "toggle", Ep2Configs.PRINTER_TOGGLE.getKeybind().getKeysDisplayString());
        y += 8;

        g.drawString(this.font, Component.translatable("easyplace2.printer.section.how"), x, y, Ui.ACCENT, false);
        y += 14;

        for (String key : new String[] { "how1", "how2", "how3", "how4" })
        {
            for (var line : this.font.split(Component.translatable("easyplace2.printer.info." + key), this.x1 - this.x0 - 40))
            {
                if (y > this.contentBottom - 10)
                {
                    return;
                }

                g.drawString(this.font, line, x + 4, y, Ui.TEXT_DIM, false);
                y += 10;
            }

            y += 2;
        }

        if (mc.player == null)
        {
            g.drawString(this.font, Component.translatable("easyplace2.printer.info.no_world"), x, y, Ui.AMBER, false);
        }
    }

    private int check(GuiGraphics g, int x, int y, String key, boolean ok)
    {
        int color = ok ? Ui.GREEN : Ui.RED;
        Ui.round(g, x, y, x + 8, y + 8, Ui.withAlpha(color, 0x50));
        Ui.round(g, x + 2, y + 2, x + 6, y + 6, color);
        g.drawString(this.font, Component.translatable("easyplace2.printer.check." + key), x + 14, y, Ui.TEXT, false);
        Component state = Component.translatable(ok ? "easyplace2.printer.check.ok" : "easyplace2.printer.check.missing");
        g.drawString(this.font, state, this.x1 - 16 - this.font.width(state), y, color, false);
        return y + 12;
    }

    private int keyRow(GuiGraphics g, int x, int y, String key, String keys)
    {
        String shown = keys == null || keys.isEmpty() ? "–" : keys;
        g.drawString(this.font, Component.translatable("easyplace2.printer.key." + key), x, y, Ui.TEXT, false);
        int w = this.font.width(shown) + 8;
        Ui.round(g, this.x1 - 16 - w, y - 2, this.x1 - 16, y + 10, Ui.TRACK);
        g.drawString(this.font, shown, this.x1 - 12 - w, y, Ui.ACCENT, false);
        return y + 13;
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
    {
        if (event.button() == 0)
        {
            for (Controls.Control c : this.allControls())
            {
                if (c.click(event.x(), event.y()))
                {
                    this.dragging = c;
                    return true;
                }
            }
        }

        return super.mouseClicked(event, doubleClick);
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
        if (this.maxScroll > 0)
        {
            this.scroll = Math.max(0, Math.min(this.maxScroll, this.scroll - (int) Math.round(scrollY * 16)));
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private List<Controls.Control> allControls()
    {
        List<Controls.Control> all = new ArrayList<>(this.controls);
        all.addAll(this.scrolling);
        return all;
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
        super.removed();
    }

    @Override
    public void onClose()
    {
        Minecraft.getInstance().setScreen(this.parent);
    }
}
