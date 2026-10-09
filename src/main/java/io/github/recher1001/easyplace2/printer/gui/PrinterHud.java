package io.github.recher1001.easyplace2.printer.gui;

import java.util.Locale;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import io.github.recher1001.easyplace2.EasyPlace2;
import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.printer.PrinterController;
import io.github.recher1001.easyplace2.printer.SchematicScanner;

/**
 * Kleine Statusanzeige oben links, solange der Printer laeuft.
 */
public final class PrinterHud
{
    private static float shown = -1f;

    private PrinterHud()
    {
    }

    public static void register()
    {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(EasyPlace2.MOD_ID, "printer"), PrinterHud::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta)
    {
        Minecraft mc = Minecraft.getInstance();
        PrinterController printer = PrinterController.INSTANCE;

        if (!printer.isRunning() || !Ep2Configs.PRINTER_HUD.getBooleanValue() || mc.options.hideGui || mc.player == null)
        {
            return;
        }

        Font font = mc.font;
        SchematicScanner.Snapshot snap = printer.getSnapshot();
        double progress = snap != null ? snap.progress() : 0.0;
        shown = shown < 0 ? (float) progress : shown + ((float) progress - shown) * 0.1f;

        int x = 6, y = 6, w = 150, h = 38;
        Ui.card(g, x, y, x + w, y + h, 0xD0141923, Ui.BORDER);
        Ui.gradientH(g, x + 3, y + 1, x + w - 3, y + 2, Ui.ACCENT, Ui.ACCENT_2);

        PrinterController.State state = printer.getState();
        int color = Ui.stateColor(state);
        Ui.round(g, x + 6, y + 7, x + 11, y + 12, color);
        g.drawString(font, Component.literal("Printer"), x + 15, y + 6, Ui.TEXT, false);
        Component name = Ui.stateName(state);
        g.drawString(font, name, x + w - 6 - font.width(name), y + 6, color, false);

        int bx0 = x + 6, bx1 = x + w - 6, by = y + 18;
        Ui.round(g, bx0, by, bx1, by + 5, Ui.TRACK);
        int fill = bx0 + Math.round((bx1 - bx0) * Math.max(0f, Math.min(1f, shown)));

        if (fill > bx0 + 1)
        {
            Ui.gradientH(g, bx0, by, fill, by + 5, Ui.ACCENT, Ui.ACCENT_2);
        }

        String line = snap == null ? "…" : String.format(Locale.ROOT, "%d/%d  ·  %.0f/min  ·  %s",
                snap.done(), snap.total(), printer.getRate(), Ui.formatDuration(printer.getEtaSeconds()));
        g.drawString(font, line, x + 6, y + 27, Ui.TEXT_DIM, false);
    }
}
