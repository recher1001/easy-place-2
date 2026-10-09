package io.github.recher1001.easyplace2.printer.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
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
        protected float hover;
        public boolean visible = true;
        public Supplier<Component> tooltip = () -> null;

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

        public void render(GuiGraphics g, Font font, int mx, int my, float delta)
        {
            float target = this.isInside(mx, my) ? 1f : 0f;
            this.hover += (target - this.hover) * Math.min(1f, delta * 0.35f + 0.15f);
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

    /** Knopf mit Farbe (z. B. gruen fuer Start, rot fuer Stopp). */
    public static class Button extends Control
    {
        private final Supplier<Component> label;
        private final Supplier<Integer> color;
        private final Runnable action;
        public BooleanSupplier enabled = () -> true;

        public Button(int x, int y, int w, int h, Supplier<Component> label, Supplier<Integer> color, Runnable action)
        {
            super(x, y, w, h);
            this.label = label;
            this.color = color;
            this.action = action;
        }

        @Override
        protected void draw(GuiGraphics g, Font font, int mx, int my)
        {
            boolean on = this.enabled.getAsBoolean();
            int accent = on ? this.color.get() : Ui.TEXT_FAINT;
            int fill = Ui.lerpColor(Ui.withAlpha(accent, 0x38), Ui.withAlpha(accent, 0x70), this.hover);
            Ui.card(g, this.x, this.y, this.x + this.w, this.y + this.h, fill, Ui.withAlpha(accent, on ? 0xD0 : 0x60));
            Component text = this.label.get();
            g.drawString(font, text, this.x + (this.w - font.width(text)) / 2, this.y + (this.h - 8) / 2,
                    on ? Ui.TEXT : Ui.TEXT_DIM, false);
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

    /** Reiter oben im Menue. */
    public static class Tab extends Control
    {
        private final Component label;
        private final BooleanSupplier active;
        private final Runnable select;

        public Tab(int x, int y, int w, int h, Component label, BooleanSupplier active, Runnable select)
        {
            super(x, y, w, h);
            this.label = label;
            this.active = active;
            this.select = select;
        }

        @Override
        protected void draw(GuiGraphics g, Font font, int mx, int my)
        {
            boolean on = this.active.getAsBoolean();

            if (on || this.hover > 0.01f)
            {
                Ui.round(g, this.x, this.y, this.x + this.w, this.y + this.h,
                        on ? Ui.PANEL_INNER : Ui.withAlpha(Ui.PANEL_HOVER, (int) (0xFF * this.hover)));
            }

            if (on)
            {
                Ui.gradientH(g, this.x + 6, this.y + this.h - 2, this.x + this.w - 6, this.y + this.h, Ui.ACCENT, Ui.ACCENT_2);
            }

            g.drawString(font, this.label, this.x + (this.w - font.width(this.label)) / 2, this.y + (this.h - 8) / 2 - 1,
                    on ? Ui.TEXT : Ui.TEXT_DIM, false);
        }

        @Override
        public boolean click(double mx, double my)
        {
            if (this.isInside(mx, my))
            {
                this.select.run();
                return true;
            }

            return false;
        }
    }

    /** Schalter (an/aus) mit Beschriftung links und animiertem Knopf rechts. */
    public static class Toggle extends Control
    {
        private final Component label;
        private final BooleanSupplier value;
        private final Consumer<Boolean> setter;
        private float knob;

        public Toggle(int x, int y, int w, int h, Component label, BooleanSupplier value, Consumer<Boolean> setter)
        {
            super(x, y, w, h);
            this.label = label;
            this.value = value;
            this.setter = setter;
            this.knob = value.getAsBoolean() ? 1f : 0f;
        }

        @Override
        protected void draw(GuiGraphics g, Font font, int mx, int my)
        {
            boolean on = this.value.getAsBoolean();
            this.knob += ((on ? 1f : 0f) - this.knob) * 0.3f;

            if (this.hover > 0.01f)
            {
                Ui.round(g, this.x, this.y, this.x + this.w, this.y + this.h, Ui.withAlpha(Ui.PANEL_HOVER, (int) (0xC0 * this.hover)));
            }

            g.drawString(font, this.label, this.x + 6, this.y + (this.h - 8) / 2, Ui.TEXT, false);

            int sw = 22, sh = 10;
            int sx = this.x + this.w - sw - 6;
            int sy = this.y + (this.h - sh) / 2;
            Ui.round(g, sx, sy, sx + sw, sy + sh, Ui.lerpColor(Ui.TRACK, Ui.withAlpha(Ui.ACCENT, 0xB0), this.knob));
            int kx = sx + 1 + (int) ((sw - sh) * this.knob);
            Ui.round(g, kx, sy + 1, kx + sh - 2, sy + sh - 1, Ui.lerpColor(Ui.TEXT_DIM, 0xFFFFFFFF, this.knob));
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

    /** Schieberegler mit Wertanzeige. */
    public static class Slider extends Control
    {
        private final Component label;
        private final DoubleSupplier value;
        private final DoubleConsumer setter;
        private final double min, max, step;
        private final DoubleFunction<String> format;
        private boolean dragging;

        public Slider(int x, int y, int w, int h, Component label, double min, double max, double step,
                      DoubleSupplier value, DoubleConsumer setter, DoubleFunction<String> format)
        {
            super(x, y, w, h);
            this.label = label;
            this.min = min;
            this.max = max;
            this.step = step;
            this.value = value;
            this.setter = setter;
            this.format = format;
        }

        @Override
        protected void draw(GuiGraphics g, Font font, int mx, int my)
        {
            if (this.hover > 0.01f || this.dragging)
            {
                Ui.round(g, this.x, this.y, this.x + this.w, this.y + this.h,
                        Ui.withAlpha(Ui.PANEL_HOVER, (int) (0xC0 * Math.max(this.hover, this.dragging ? 1f : 0f))));
            }

            String text = this.format.apply(this.value.getAsDouble());
            g.drawString(font, this.label, this.x + 6, this.y + 3, Ui.TEXT, false);
            g.drawString(font, text, this.x + this.w - 6 - font.width(text), this.y + 3, Ui.ACCENT, false);

            int tx1 = this.trackStart(), tx2 = this.trackEnd();
            int ty = this.y + this.h - 6;
            Ui.round(g, tx1, ty, tx2, ty + 3, Ui.TRACK);
            double t = (this.value.getAsDouble() - this.min) / (this.max - this.min);
            int fx = tx1 + (int) Math.round((tx2 - tx1) * Math.max(0, Math.min(1, t)));
            Ui.gradientH(g, tx1, ty, Math.max(tx1 + 1, fx), ty + 3, Ui.ACCENT, Ui.ACCENT_2);
            Ui.round(g, fx - 3, ty - 2, fx + 3, ty + 5, 0xFFFFFFFF);
        }

        private int trackStart()
        {
            return this.x + 6;
        }

        private int trackEnd()
        {
            return this.x + this.w - 6;
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

            double t = (mx - this.trackStart()) / (double) (this.trackEnd() - this.trackStart());
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
