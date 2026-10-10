package io.github.recher1001.easyplace2.printer.gui;

import java.util.Locale;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.printer.PrinterController;

/**
 * Farben (aus dem aktuellen {@link Theme}) und kleine Zeichenhilfen fuer Menue und HUD.
 * Alle Rueckgabewerte sind ARGB.
 */
public final class Ui
{
    public static final int GREEN = 0xFF4ADE80;
    public static final int RED = 0xFFF87171;
    public static final int AMBER = 0xFFFBBF24;
    public static final int WHITE = 0xFFFFFFFF;

    private Ui()
    {
    }

    // ---------------------------------------------------------------- Farben

    public static Theme theme()
    {
        return Theme.current();
    }

    private static boolean glass()
    {
        return Ep2Configs.PRINTER_GLASS.getBooleanValue();
    }

    public static int background()
    {
        return withAlpha(theme().background(), glass() ? 0xD8 : 0xFA);
    }

    public static int sidebar()
    {
        return withAlpha(theme().sidebar(), glass() ? 0xC8 : 0xFF);
    }

    public static int card()
    {
        return withAlpha(theme().card(), glass() ? 0xB4 : 0xFF);
    }

    public static int cardHover()
    {
        return withAlpha(theme().cardHover(), glass() ? 0xD0 : 0xFF);
    }

    public static int border()
    {
        return withAlpha(theme().border(), glass() ? 0xA0 : 0xFF);
    }

    public static int track()
    {
        return withAlpha(theme().sidebar(), 0xFF);
    }

    public static int accent()
    {
        return withAlpha(theme().accent(), 0xFF);
    }

    public static int accent2()
    {
        return withAlpha(theme().accent2(), 0xFF);
    }

    public static int text()
    {
        return 0xFFEEF0FA;
    }

    public static int textDim()
    {
        return lerpColor(0xFF9AA0B8, accent(), 0.12f);
    }

    public static int textFaint()
    {
        return lerpColor(0xFF5E6480, accent(), 0.10f);
    }

    public static int stateColor(PrinterController.State state)
    {
        return switch (state)
        {
            case OFF -> textFaint();
            case DONE -> GREEN;
            case PAUSED, PAUSED_SCREEN, NOTHING_IN_REACH -> AMBER;
            case MISSING_MATERIAL -> RED;
            case MOVING -> accent2();
            default -> accent();
        };
    }

    public static Component stateName(PrinterController.State state)
    {
        return Component.translatable("easyplace2.printer.state." + state.name().toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- Formen

    /** Rechteck mit abgerundeten Ecken (ca. 3 Pixel Radius). */
    public static void round(GuiGraphics g, int x1, int y1, int x2, int y2, int color)
    {
        if (x2 - x1 < 6 || y2 - y1 < 6)
        {
            g.fill(x1, y1, x2, y2, color);
            return;
        }

        g.fill(x1 + 3, y1, x2 - 3, y2, color);
        g.fill(x1 + 1, y1 + 1, x1 + 3, y2 - 1, color);
        g.fill(x2 - 3, y1 + 1, x2 - 1, y2 - 1, color);
        g.fill(x1, y1 + 3, x1 + 1, y2 - 3, color);
        g.fill(x2 - 1, y1 + 3, x2, y2 - 3, color);
    }

    /** Abgerundete Karte mit 1-px-Rand. */
    public static void card(GuiGraphics g, int x1, int y1, int x2, int y2, int fill, int border)
    {
        round(g, x1, y1, x2, y2, border);
        round(g, x1 + 1, y1 + 1, x2 - 1, y2 - 1, fill);
    }

    /** Waagerechter Farbverlauf (in schmalen Streifen gezeichnet). */
    public static void gradientH(GuiGraphics g, int x1, int y1, int x2, int y2, int from, int to)
    {
        int w = x2 - x1;

        for (int x = 0; x < w; x += 2)
        {
            int c = lerpColor(from, to, w <= 1 ? 0f : (float) x / (w - 1));
            g.fill(x1 + x, y1, Math.min(x2, x1 + x + 2), y2, c);
        }
    }

    /** Weicher Schatten unter einem Panel. */
    public static void shadow(GuiGraphics g, int x1, int y1, int x2, int y2)
    {
        for (int i = 1; i <= 4; i++)
        {
            round(g, x1 - i, y1 - i + 2, x2 + i, y2 + i + 2, withAlpha(0x000000, 0x18));
        }
    }

    /** Kleiner Kreis (fuer Statuspunkte und Haken). */
    public static void dot(GuiGraphics g, int cx, int cy, int r, int color)
    {
        for (int dy = -r; dy <= r; dy++)
        {
            int dx = (int) Math.round(Math.sqrt(r * r - dy * dy));
            g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    // ---------------------------------------------------------------- Text und Icons

    public static void text(GuiGraphics g, Font font, Component text, int x, int y, int color)
    {
        g.drawString(font, text, x, y, color, false);
    }

    public static void text(GuiGraphics g, Font font, String text, int x, int y, int color)
    {
        g.drawString(font, text, x, y, color, false);
    }

    /** Text in anderer Groesse (z. B. 1.5 fuer Ueberschriften, 0.75 fuer kleine Hinweise). */
    public static void scaledText(GuiGraphics g, Font font, Component text, int x, int y, float scale, int color)
    {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popMatrix();
    }

    public static int scaledWidth(Font font, Component text, float scale)
    {
        return Math.round(font.width(text) * scale);
    }

    /** Item-Icon in anderer Groesse (16 px * scale). */
    public static void item(GuiGraphics g, ItemStack stack, int x, int y, float scale)
    {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.renderItem(stack, 0, 0);
        g.pose().popMatrix();
    }

    /** Text auf eine Breite kuerzen (mit "…"). */
    public static String clip(Font font, String text, int width)
    {
        if (font.width(text) <= width)
        {
            return text;
        }

        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }

    // ---------------------------------------------------------------- Rechnen

    public static int lerpColor(int a, int b, float t)
    {
        t = Math.max(0f, Math.min(1f, t));
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16) |
               ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    public static int withAlpha(int color, int alpha)
    {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    /** Weiche Annaeherung pro Bild (unabhaengig von der Bildrate genug fuer UI-Animationen). */
    public static float approach(float current, float target, float speed)
    {
        float next = current + (target - current) * speed;
        return Math.abs(target - next) < 0.001f ? target : next;
    }

    public static float easeOut(float t)
    {
        t = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    public static String formatDuration(long seconds)
    {
        if (seconds < 0)
        {
            return "–";
        }

        long h = seconds / 3600, m = (seconds / 60) % 60, s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                     : String.format(Locale.ROOT, "%d:%02d", m, s);
    }
}
