package io.github.recher1001.easyplace2.printer.gui;

import java.util.Locale;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
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
 * Kleine Statusanzeige oben links im Stil des Menues, solange der Printer laeuft.
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
        shown = shown < 0 ? (float) progress : Ui.approach(shown, (float) progress, 0.1f);

        int x = 6, y = 6, w = 156, h = 40;
        Ui.card(g, x, y, x + w, y + h, Ui.background(), Ui.border());
        g.fillGradient(x + 2, y + 1, x + w - 2, y + 14, Ui.withAlpha(Ui.theme().accent(), 0x18), 0x00000000);

        PrinterController.State state = printer.getState();
        int color = Ui.stateColor(state);
        float pulse = 0.55f + 0.45f * (float) Math.sin(System.currentTimeMillis() / 220.0);
        Ui.dot(g, x + 10, y + 10, 2, Ui.withAlpha(color, (int) (0xFF * pulse)));
        Ui.text(g, font, Component.literal("Printer").withStyle(ChatFormatting.ITALIC), x + 16, y + 6, Ui.accent());
        Component name = Ui.stateName(state);
        Ui.text(g, font, name, x + w - 8 - font.width(name), y + 6, color);

        int bx0 = x + 8, bx1 = x + w - 8, by = y + 19;
        Ui.round(g, bx0, by, bx1, by + 6, Ui.track());
        int fill = bx0 + Math.round((bx1 - bx0) * Math.max(0f, Math.min(1f, shown)));

        if (fill > bx0 + 1)
        {
            Ui.gradientH(g, bx0 + 1, by + 1, fill - 1, by + 5, Ui.accent2(), Ui.accent());
        }

        String line = snap == null ? "…" : String.format(Locale.ROOT, "%.1f %%  ·  %d/%d  ·  %.0f/min  ·  %s",
                shown * 100, snap.done(), snap.total(), printer.getRate(), Ui.formatDuration(printer.getEtaSeconds()));
        Ui.text(g, font, Ui.clip(font, line, w - 16), x + 8, y + 29, Ui.textDim());
    }
}
