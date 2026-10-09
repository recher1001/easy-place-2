package io.github.recher1001.easyplace2.printer.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import io.github.recher1001.easyplace2.printer.PrinterController;

/**
 * Farben und kleine Zeichenhilfen fuer Menue und HUD (alle Farben als ARGB).
 */
public final class Ui
{
    public static final int BACKDROP_TOP = 0xA0050810;
    public static final int BACKDROP_BOTTOM = 0xC0050810;
    public static final int PANEL = 0xF2141923;
    public static final int PANEL_INNER = 0xFF1B2130;
    public static final int PANEL_HOVER = 0xFF232B3D;
    public static final int BORDER = 0xFF2C3447;
    public static final int TEXT = 0xFFE8ECF4;
    public static final int TEXT_DIM = 0xFF8A93A6;
    public static final int TEXT_FAINT = 0xFF5D6578;
    public static final int ACCENT = 0xFF4FC3F7;
    public static final int ACCENT_2 = 0xFF8C6CFF;
    public static final int GREEN = 0xFF4ADE80;
    public static final int RED = 0xFFF87171;
    public static final int AMBER = 0xFFFBBF24;
    public static final int TRACK = 0xFF0F131B;

    private Ui()
    {
    }

    /** Rechteck mit abgerundeten Ecken (Radius 2 bzw. 3 Pixel). */
    public static void round(GuiGraphics g, int x1, int y1, int x2, int y2, int color)
    {
        g.fill(x1 + 2, y1, x2 - 2, y2, color);
        g.fill(x1 + 1, y1 + 1, x1 + 2, y2 - 1, color);
        g.fill(x2 - 2, y1 + 1, x2 - 1, y2 - 1, color);
        g.fill(x1, y1 + 2, x1 + 1, y2 - 2, color);
        g.fill(x2 - 1, y1 + 2, x2, y2 - 2, color);
    }

    /** Abgerundeter Rahmen mit Fuellung. */
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

    public static int stateColor(PrinterController.State state)
    {
        return switch (state)
        {
            case OFF -> TEXT_FAINT;
            case DONE -> GREEN;
            case PAUSED, PAUSED_SCREEN, NOTHING_IN_REACH -> AMBER;
            case MISSING_MATERIAL -> RED;
            case MOVING -> ACCENT_2;
            default -> ACCENT;
        };
    }

    public static Component stateName(PrinterController.State state)
    {
        return Component.translatable("easyplace2.printer.state." + state.name().toLowerCase(java.util.Locale.ROOT));
    }

    public static String formatDuration(long seconds)
    {
        if (seconds < 0)
        {
            return "–";
        }

        long h = seconds / 3600, m = (seconds / 60) % 60, s = seconds % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
                     : String.format(java.util.Locale.ROOT, "%d:%02d", m, s);
    }
}
