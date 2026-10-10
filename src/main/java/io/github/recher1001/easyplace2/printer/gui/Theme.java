package io.github.recher1001.easyplace2.printer.gui;

import java.util.List;

import net.minecraft.network.chat.Component;

import io.github.recher1001.easyplace2.config.Ep2Configs;

/**
 * Farbschema des Printer-Menues und HUDs. Alle Farben ohne Alpha (0xRRGGBB);
 * die Deckkraft legt {@link Ui} je nach Glas-Effekt fest.
 *
 * @param background Hauptpanel
 * @param sidebar    Seitenleiste (etwas dunkler)
 * @param card       Karten, Zeilen, Kacheln
 * @param cardHover  Karte unter der Maus / ausgewaehlter Eintrag
 * @param border     Raender
 * @param accent     Akzentfarbe (Titelwort, Schalter, Balken)
 * @param accent2    zweite Akzentfarbe fuer Verlaeufe
 */
public record Theme(String id, int background, int sidebar, int card, int cardHover, int border, int accent, int accent2)
{
    public static final List<Theme> ALL = List.of(
            new Theme("vault",   0x0E1030, 0x0A0C26, 0x151A42, 0x1E2456, 0x262C60, 0x8C7CFF, 0x5B6CFF),
            new Theme("nova",    0x0B1426, 0x08101F, 0x122038, 0x182A48, 0x1F3354, 0x22D3EE, 0x3B82F6),
            new Theme("rose",    0x1A0B14, 0x140810, 0x26111E, 0x321628, 0x3D1C31, 0xEC4899, 0xF472B6),
            new Theme("ember",   0x17110D, 0x110C09, 0x221912, 0x2D2118, 0x3A2A1D, 0xF97316, 0xFB923C),
            new Theme("forest",  0x0C1612, 0x08110D, 0x12211A, 0x182C22, 0x1F382B, 0x10B981, 0x34D399),
            new Theme("indigo",  0x0D0F24, 0x090B1C, 0x151935, 0x1C2145, 0x252B57, 0x6366F1, 0x818CF8),
            new Theme("magenta", 0x170B1C, 0x110815, 0x231129, 0x2E1736, 0x3A1D44, 0xD946EF, 0xE879F9),
            new Theme("ocean",   0x0A1222, 0x070D19, 0x111D33, 0x172742, 0x1E3152, 0x3B82F6, 0x60A5FA),
            new Theme("crimson", 0x140A0B, 0x0E0708, 0x1F1012, 0x2A1518, 0x361B1F, 0xEF4444, 0xF87171),
            new Theme("lime",    0x11140B, 0x0C0F08, 0x1A1F11, 0x232A17, 0x2D361D, 0x84CC16, 0xA3E635),
            new Theme("gold",    0x16120A, 0x100D07, 0x211B10, 0x2C2415, 0x382E1B, 0xFBBF24, 0xF59E0B),
            new Theme("aurora",  0x0A1615, 0x07100F, 0x10221F, 0x162D29, 0x1D3934, 0x5EEAD4, 0x2DD4BF)
    );

    public static Theme current()
    {
        String id = Ep2Configs.PRINTER_THEME.getStringValue();

        for (Theme theme : ALL)
        {
            if (theme.id.equals(id))
            {
                return theme;
            }
        }

        return ALL.get(0);
    }

    public Component displayName()
    {
        return Component.translatable("easyplace2.printer.theme." + this.id);
    }

    public Component description()
    {
        return Component.translatable("easyplace2.printer.theme." + this.id + ".desc");
    }
}
