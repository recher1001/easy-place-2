package io.github.recher1001.easyplace2.printer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Drehung nur fuer den Server, wie beim Lambda-Printer: Beim Senden des Bewegungspakets wird kurz die
 * Printer-Drehung eingesetzt und danach sofort wieder die echte. Die Kamera bleibt dadurch frei;
 * in der dritten Person und fuer andere Spieler dreht sich der Kopf.
 */
public final class ServerRotation
{
    /** Gewuenschte Drehung fuer den Server, null = die echte Drehung des Spielers. */
    @Nullable private static float[] target;

    /** Zuletzt an den Server geschickte Drehung. */
    private static float sentYaw;
    private static float sentPitch;
    private static boolean sentKnown;

    private static boolean swapped;
    private static float realYaw;
    private static float realPitch;

    private ServerRotation()
    {
    }

    /**
     * Setzt die Drehung, die beim naechsten Bewegungspaket an den Server geht. Der Gierwinkel wird so gewaehlt,
     * dass er ohne Sprung an die zuletzt gesendete Drehung anschliesst (kein 360-Grad-Sprung).
     */
    public static void set(LocalPlayer player, float yaw, float pitch)
    {
        float base = sentKnown ? sentYaw : player.getYRot();
        float y = base + Mth.wrapDegrees(yaw - base);
        float p = Mth.clamp(pitch, -90.0f, 90.0f);
        target = new float[] { y, p };
    }

    public static void clear()
    {
        target = null;
    }

    public static boolean isActive()
    {
        return target != null;
    }

    /** Ist genau die gesetzte Drehung schon beim Server angekommen (mit dem letzten Bewegungspaket)? */
    public static boolean isSent()
    {
        float[] t = target;
        return t != null && sentKnown && Math.abs(sentYaw - t[0]) < 0.01f && Math.abs(sentPitch - t[1]) < 0.01f;
    }

    // ---------------------------------------------------------------- vom Mixin aufgerufen (LocalPlayer.sendPosition)

    public static void beforeSend(LocalPlayer player)
    {
        float[] t = target;

        if (t == null)
        {
            return;
        }

        realYaw = player.getYRot();
        realPitch = player.getXRot();
        player.setYRot(t[0]);
        player.setXRot(t[1]);
        swapped = true;
    }

    public static void afterSend(LocalPlayer player)
    {
        sentYaw = player.getYRot();
        sentPitch = player.getXRot();
        sentKnown = true;

        if (swapped)
        {
            player.setYRot(realYaw);
            player.setXRot(realPitch);
            swapped = false;
        }
    }
}
