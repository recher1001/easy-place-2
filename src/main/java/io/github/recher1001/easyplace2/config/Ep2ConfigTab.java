package io.github.recher1001.easyplace2.config;

/**
 * Merkt sich, ob im Litematica-Konfigurationsmenue gerade der Reiter "Easy Place 2.0" offen ist.
 */
public class Ep2ConfigTab
{
    private static boolean active;

    public static boolean isActive()
    {
        return active;
    }

    public static void setActive(boolean value)
    {
        active = value;
    }

    /** Wird vom Mixin in Litematicas GuiConfigs implementiert. */
    public interface Access
    {
        void easyplace2$openTab();
    }
}
