package io.github.recher1001.easyplace2.printer.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Eigene, schlichte Bedienelemente fuer das Printer-Menue (gezeichnet mit {@link Ui}).
 * Die Maus-Ereignisse leitet {@link PrinterScreen} weiter.
 */
public final class Controls
{
    private Controls()
    {
    }

    public abstract static class Control
    {
        public int x, y, w, h;
        public boolean visible = true;
        protected float hover;

        protected Control(int x, int y, int w, int h)
        {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public boolean isInside(double mx, double my)
        {
            return this.visible && mx >= this.x && my >= this.y && mx < this.x + this.w && my < this.y + this.h;
        }

        public void render(GuiGraphics g, Font font, int mx, int my)
        {
            if (!this.visible)
            {
                return;
            }

            this.hover = Ui.approach(this.hover, this.isInside(mx, my) ? 1f : 0f, 0.25f);
            this.draw(g, font, mx, my);
        }

        protected abstract void draw(GuiGraphics g, Font font, int mx, int my);

        public boolean click(double mx, double my)
        {
            return false;
        }

        public void drag(double mx)
        {
        }

        public void release()
        {
        }
    }

    /** Zeichenfunktion fuer {@link Area}. */
    @FunctionalInterface
    public interface Painter
    {
        void paint(GuiGraphics g, Font font, Area area, float hover);
    }

    /** Beliebige klickbare Flaeche, deren Aussehen von aussen kommt (Seitenleiste, Kacheln, Zeilen ...). */
    public static class Area extends Control
    {
        private final Painter painter;
        private final Runnable action;

        public Area(int x, int y, int w, int h, Painter painter, Runnable action)
        {
            super(x, y, w, h);
            this.painter = painter;
            this.action = action;
        }

        @Override
        protected void draw(GuiGraphics g, Font font, int mx, int my)
        {
            this.painter.paint(g, font, this, this.hover);
        }

        @Override
        public boolean click(double mx, double my)
        {
            if (this.action != null && this.isInside(mx, my))
            {
                this.action.run();
                return true;
            }

            return false;
        }
    }

    /** Knopf; {@code filled} = volle Akzentfarbe (Hauptaktion), sonst dezent. */
    public static class Button extends Control
    {
        private final Supplier<Component> label;
        private final Supplier<Integer> color;
        private final Runnable action;
        private final boolean filled;
        public BooleanSupplier enabled = () -> true;

        public Button(int x, int y, int w, int h, Supplier<Component> label, Supplier<Integer> color, boolean filled, Runnable action)
        {
            super(x, y, w, h);
            this.label = label;
            this.color = color;
            this.filled = filled;
            this.action = action;
        }

        @Override
        protected void draw(GuiGraphics g, Font font, int mx, int my)
        {
            boolean on = this.enabled.getAsBoolean();
            int c = on ? this.color.get() : Ui.textFaint();
            int fill = this.filled ? Ui.lerpColor(Ui.withAlpha(c, 0xC8), Ui.withAlpha(c, 0xFF), this.hover)
                                   : Ui.lerpColor(Ui.withAlpha(c, 0x26), Ui.withAlpha(c, 0x48), this.hover);
            Ui.card(g, this.x, this.y, this.x + this.w, this.y + this.h, fill, Ui.withAlpha(c, this.filled ? 0xFF : 0x70));
            Component text = this.label.get();
            int textColor = this.filled ? 0xFF0B0D1A : (on ? Ui.text() : Ui.textDim());
            g.drawString(font, text, this.x + (this.w - font.width(text)) / 2, this.y + (this.h - 8) / 2, textColor, false);
        }

        @Override
        public boolean click(double mx, double my)
        {
            if (this.isInside(mx, my) && this.enabled.getAsBoolean())
            {
                this.action.run();
                return true;
            }

            return false;
        }
    }

    /** Kleiner An/Aus-Schalter mit gleitendem Knopf. */
    public static class Switch extends Control
    {
        public static final int WIDTH = 22, HEIGHT = 12;
        private final BooleanSupplier value;
        private final Consumer<Boolean> setter;
        private float knob;

        public Switch(int x, int y, BooleanSupplier value, Consumer<Boolean> setter)
        {
            super(x, y, WIDTH, HEIGHT);
            this.value = value;
            this.setter = setter;
            this.knob = value.getAsBoolean() ? 1f : 0f;
        }

        @Override
        protected void draw(GuiGraphics g, Font font, int mx, int my)
        {
            this.knob = Ui.approach(this.knob, this.value.getAsBoolean() ? 1f : 0f, 0.3f);
            int track = Ui.lerpColor(Ui.withAlpha(Ui.track(), 0xFF), Ui.accent(), this.knob);
            Ui.round(g, this.x, this.y, this.x + this.w, this.y + this.h, Ui.lerpColor(Ui.border(), Ui.accent(), this.knob));
            Ui.round(g, this.x + 1, this.y + 1, this.x + this.w - 1, this.y + this.h - 1, track);
            int r = (this.h - 4) / 2;
            int cx = this.x + 2 + r + Math.round((this.w - 4 - 2 * r) * this.knob);
            Ui.dot(g, cx, this.y + this.h / 2, r, Ui.lerpColor(0xFFC8CCDC, Ui.WHITE, this.knob + this.hover * 0.3f));
        }

        @Override
        public boolean click(double mx, double my)
        {
            if (this.isInside(mx, my))
            {
                this.setter.accept(!this.value.getAsBoolean());
                return true;
            }

            return false;
        }
    }

    /** Schieberegler (nur die Leiste; Wert und Titel zeichnet die Zeile). */
    public static class Slider extends Control
    {
        private final DoubleSupplier value;
        private final DoubleConsumer setter;
        private final double min, max, step;
        private boolean dragging;
        private float shown = -1f;

        public Slider(int x, int y, int w, double min, double max, double step, DoubleSupplier value, DoubleConsumer setter)
        {
            super(x, y, w, 10);
            this.min = min;
            this.max = max;
            this.step = step;
            this.value = value;
            this.setter = setter;
        }

        @Override
        protected void draw(GuiGraphics g, Font font, int mx, int my)
        {
            float t = (float) Math.max(0, Math.min(1, (this.value.getAsDouble() - this.min) / (this.max - this.min)));
            this.shown = this.shown < 0 || this.dragging ? t : Ui.approach(this.shown, t, 0.35f);
            int ty = this.y + this.h / 2 - 1;
            Ui.round(g, this.x, ty - 1, this.x + this.w, ty + 3, Ui.track());
            int fx = this.x + Math.round(this.w * this.shown);

            if (fx > this.x + 1)
            {
                Ui.gradientH(g, this.x + 1, ty, fx, ty + 2, Ui.accent2(), Ui.accent());
            }

            int r = this.dragging || this.hover > 0.5f ? 4 : 3;
            Ui.dot(g, Math.max(this.x + r, Math.min(this.x + this.w - r, fx)), ty + 1, r, Ui.WHITE);
        }

        @Override
        public boolean click(double mx, double my)
        {
            if (this.isInside(mx, my))
            {
                this.dragging = true;
                this.drag(mx);
                return true;
            }

            return false;
        }

        @Override
        public void drag(double mx)
        {
            if (!this.dragging)
            {
                return;
            }

            double t = (mx - this.x) / (double) this.w;
            double v = this.min + Math.max(0, Math.min(1, t)) * (this.max - this.min);
            v = Math.round(v / this.step) * this.step;
            this.setter.accept(Math.max(this.min, Math.min(this.max, v)));
        }

        @Override
        public void release()
        {
            this.dragging = false;
        }
    }
}
