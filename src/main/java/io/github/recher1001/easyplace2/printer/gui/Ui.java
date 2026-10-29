package io.github.recher1001.easyplace2.printer.gui;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import io.github.recher1001.easyplace2.EasyPlace2;
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

    /** Vorhandene Rundungen (weiche, kantengeglaettete Sprites in textures/gui/sprites/ui). */
    private static final int[] RADII = { 2, 3, 4, 5, 6, 8 };
    private static final Identifier[] FILL = new Identifier[RADII.length];
    private static final Identifier[] RING = new Identifier[RADII.length];
    private static final Identifier CIRCLE = sprite("circle");
    private static final Identifier SHADOW = sprite("shadow");

    static
    {
        for (int i = 0; i < RADII.length; i++)
        {
            FILL[i] = sprite("fill_r" + RADII[i]);
            RING[i] = sprite("ring_r" + RADII[i]);
        }
    }

    private static Identifier sprite(String name)
    {
        return Identifier.fromNamespaceAndPath(EasyPlace2.MOD_ID, "ui/" + name);
    }

    /** Index der groessten vorhandenen Rundung, die in w x h passt, oder -1. */
    private static int radiusIndex(int w, int h, int radius)
    {
        int r = Math.min(radius, Math.min(w, h) / 2);
        int found = -1;

        for (int i = 0; i < RADII.length; i++)
        {
            if (RADII[i] <= r)
            {
                found = i;
            }
        }

        return found;
    }

    /** Weich abgerundete Flaeche. */
    public static void rrect(GuiGraphics g, int x1, int y1, int x2, int y2, int radius, int color)
    {
        int w = x2 - x1, h = y2 - y1;

        if (w <= 0 || h <= 0 || (color >>> 24) == 0)
        {
            return;
        }

        int i = radiusIndex(w, h, radius);

        if (i < 0)
        {
            g.fill(x1, y1, x2, y2, color);
            return;
        }

        g.blitSprite(RenderPipelines.GUI_TEXTURED, FILL[i], x1, y1, w, h, color);
    }

    /** Weicher 1-px-Rand mit Rundung. */
    public static void ring(GuiGraphics g, int x1, int y1, int x2, int y2, int radius, int color)
    {
        int w = x2 - x1, h = y2 - y1;

        if (w <= 0 || h <= 0 || (color >>> 24) == 0)
        {
            return;
        }

        int i = radiusIndex(w, h, radius);

        if (i < 0)
        {
            g.renderOutline(x1, y1, w, h, color);
            return;
        }

        g.blitSprite(RenderPipelines.GUI_TEXTURED, RING[i], x1, y1, w, h, color);
    }

    /** Abgerundete Flaeche (kleine Elemente: Knoepfe, Chips, Balken). */
    public static void round(GuiGraphics g, int x1, int y1, int x2, int y2, int color)
    {
        rrect(g, x1, y1, x2, y2, 5, color);
    }

    /** Pille: so rund wie moeglich (Schalter, Balken, Badges). */
    public static void pill(GuiGraphics g, int x1, int y1, int x2, int y2, int color)
    {
        rrect(g, x1, y1, x2, y2, (y2 - y1) / 2, color);
    }

    /** Abgerundete Karte mit feinem Rand. */
    public static void card(GuiGraphics g, int x1, int y1, int x2, int y2, int fill, int border)
    {
        card(g, x1, y1, x2, y2, 6, fill, border);
    }

    public static void card(GuiGraphics g, int x1, int y1, int x2, int y2, int radius, int fill, int border)
    {
        rrect(g, x1, y1, x2, y2, radius, fill);
        ring(g, x1, y1, x2, y2, radius, border);
    }

    /** Waagerechter Farbverlauf (in schmalen Streifen gezeichnet). */
    public static void gradientH(GuiGraphics g, int x1, int y1, int x2, int y2, int from, int to)
    {
        int w = x2 - x1;
        // Schmale Streifen; bei sehr breiten Balken etwas breitere, damit es nicht zu viele werden
        int step = Math.max(1, w / 160);

        for (int x = 0; x < w; x += step)
        {
            int c = lerpColor(from, to, w <= 1 ? 0f : (float) x / (w - 1));
            g.fill(x1 + x, y1, Math.min(x2, x1 + x + step), y2, c);
        }
    }

    /** Gefuellter Balken mit Verlauf und runden Enden (Fortschritt, Regler). */
    public static void gradientPill(GuiGraphics g, int x1, int y1, int x2, int y2, int from, int to)
    {
        int h = y2 - y1;

        if (x2 - x1 < h)
        {
            pill(g, x1, y1, x1 + h, y2, from);
            return;
        }

        // Runde Kappen in der jeweiligen Endfarbe, dazwischen der Verlauf
        rrect(g, x1, y1, x1 + h, y2, h / 2, from);
        rrect(g, x2 - h, y1, x2, y2, h / 2, to);
        gradientH(g, x1 + h / 2, y1, x2 - h / 2, y2, from, to);
    }

    /** Weicher Schatten unter einem Panel. */
    public static void shadow(GuiGraphics g, int x1, int y1, int x2, int y2)
    {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, SHADOW, x1 - 12, y1 - 9, x2 - x1 + 24, y2 - y1 + 24, withAlpha(0x000000, 0xB0));
    }

    /** Kleiner, glatter Kreis (fuer Statuspunkte, Regler-Knoepfe). */
    public static void dot(GuiGraphics g, int cx, int cy, int r, int color)
    {
        circle(g, cx - r, cy - r, 2 * r + 1, color);
    }

    /** Kreis mit beliebigem Durchmesser an beliebiger (auch halber) Position. */
    public static void circle(GuiGraphics g, float x, float y, int size, int color)
    {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, CIRCLE, 0, 0, size, size, color);
        g.pose().popMatrix();
    }

    // ---------------------------------------------------------------- Text und Icons

    private static UiFont uiFont;

    /** Die glatte Menue-Schrift (Inter), siehe {@link UiFont}. */
    public static Font font()
    {
        if (uiFont == null)
        {
            uiFont = UiFont.create(Minecraft.getInstance());
        }

        return uiFont;
    }

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
        g.drawString(font, font instanceof UiFont ? UiFont.scaled(text.getVisualOrderText(), scale) : text.getVisualOrderText(), 0, 0, color, false);
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

    public static float easeInOut(float t)
    {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
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
