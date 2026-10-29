package io.github.recher1001.easyplace2.printer.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import io.github.recher1001.easyplace2.EasyPlace2;
import io.github.recher1001.easyplace2.mixin.FontAccessor;

/**
 * Die Schrift des Menues: Inter (eine glatte Vektorschrift) statt der Pixelschrift von Minecraft.
 * <p>
 * Die Zeichen werden genau in der aktuellen GUI-Skalierung gerastert (eigene Schriftdatei je Skalierung),
 * damit sie auf jedem Bildschirm scharf sind. Fett (und kursiv) wird mit dem echten fetten Schnitt
 * gezeichnet statt doppelt versetzt wie bei Minecraft. Zeichen, die Inter nicht hat, kommen aus der
 * normalen Minecraft-Schrift.
 */
public final class UiFont extends Font
{
    private static final int MAX_SCALE = 6;
    /** Merker fuer "fetter Schnitt"; wird beim Zeichnen auf die passende Skalierung umgelenkt. */
    private static final FontDescription BOLD = font("ui_bold");
    private static final FontDescription[] REGULAR_BY_SCALE = new FontDescription[MAX_SCALE + 1];
    private static final FontDescription[] BOLD_BY_SCALE = new FontDescription[MAX_SCALE + 1];

    static
    {
        for (int s = 1; s <= MAX_SCALE; s++)
        {
            REGULAR_BY_SCALE[s] = font("ui_s" + s);
            BOLD_BY_SCALE[s] = font("ui_bold_s" + s);
        }
    }

    private UiFont(Font.Provider provider)
    {
        super(provider);
    }

    public static UiFont create(Minecraft mc)
    {
        Font.Provider base = ((FontAccessor) mc.font).easyplace2$provider();

        return new UiFont(new Font.Provider()
        {
            @Override
            public GlyphSource glyphs(FontDescription description)
            {
                if (description.equals(FontDescription.DEFAULT))
                {
                    return base.glyphs(REGULAR_BY_SCALE[scale(mc)]);
                }

                if (description.equals(BOLD))
                {
                    return base.glyphs(BOLD_BY_SCALE[scale(mc)]);
                }

                return base.glyphs(description);
            }

            @Override
            public EffectGlyph effect()
            {
                return base.effect();
            }
        });
    }

    private static int scale(Minecraft mc)
    {
        return Math.max(1, Math.min(MAX_SCALE, (int) Math.round(mc.getWindow().getGuiScale())));
    }

    private static FontDescription font(String name)
    {
        return new FontDescription.Resource(Identifier.fromNamespaceAndPath(EasyPlace2.MOD_ID, name));
    }

    // ---------------------------------------------------------------- Fett mit dem echten fetten Schnitt

    @Override
    public PreparedText prepareText(FormattedCharSequence text, float x, float y, int color, boolean shadow, boolean includeEmpty, int backgroundColor)
    {
        return super.prepareText(boldFace(text), x, y, color, shadow, includeEmpty, backgroundColor);
    }

    @Override
    public int width(FormattedCharSequence text)
    {
        return super.width(boldFace(text));
    }

    @Override
    public int width(FormattedText text)
    {
        return super.width(boldFace(Language.getInstance().getVisualOrder(text)));
    }

    private static FormattedCharSequence boldFace(FormattedCharSequence text)
    {
        return sink -> text.accept((index, style, codepoint) -> sink.accept(index, face(style), codepoint));
    }

    private static Style face(Style style)
    {
        if ((style.isBold() || style.isItalic()) && style.getFont().equals(FontDescription.DEFAULT))
        {
            return style.withBold(false).withItalic(false).withFont(BOLD);
        }

        return style;
    }
}
