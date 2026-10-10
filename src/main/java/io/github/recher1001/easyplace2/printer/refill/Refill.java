package io.github.recher1001.easyplace2.printer.refill;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Ein laufender Vorgang, der Material besorgt (aus einer Shulker im Inventar oder aus dem Lager).
 */
public interface Refill
{
    /** @return true, wenn der Vorgang fertig ist (erfolgreich oder abgebrochen) */
    boolean tick(Minecraft mc);

    /** Ist das offene Menue (z. B. die Kiste) vom Vorgang selbst geoeffnet worden? */
    boolean ownsScreen(@Nullable Screen screen);

    /** Abbrechen (Menue schliessen, Tasten loslassen). */
    void cancel(Minecraft mc);

    /** Kurzer Text fuer Menue und HUD, z. B. "Shulker: Bruchstein". */
    String detail();
}
