package io.github.recher1001.easyplace2.modules;

import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.config.Ep2Configs;

/**
 * Modul "Invsee": schreibt per Taste den Server-Befehl {@code /invsee <Name>} fuer den Spieler,
 * der innerhalb des eingestellten Blickwinkels am naechsten steht.
 * <p>
 * Der Befehl selbst kommt vom Server (z. B. ein Plugin); ob er erlaubt ist, entscheidet der Server.
 * Verwendet wird nur der echte Spielername, ohne Rang, Team-Praefix oder Farben.
 */
public final class InvseeModule
{
    /** Gueltige Minecraft-Spielernamen. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private InvseeModule()
    {
    }

    public static void trigger(Minecraft mc)
    {
        LocalPlayer self = mc.player;

        if (self == null || mc.level == null || mc.getConnection() == null)
        {
            return;
        }

        AbstractClientPlayer target = findTarget(mc, self);

        if (target == null)
        {
            self.displayClientMessage(Component.translatable("easyplace2.module.invsee.none"), true);
            return;
        }

        // Nur der reine Spielername (Profilname), nicht der angezeigte Name mit Rang
        String name = target.getScoreboardName();

        if (!NAME.matcher(name).matches())
        {
            self.displayClientMessage(Component.translatable("easyplace2.module.invsee.none"), true);
            return;
        }

        run(mc, name);
    }

    /** Fuehrt den eingestellten Invsee-Befehl fuer diesen Spielernamen sofort aus. */
    public static void run(Minecraft mc, String name)
    {
        if (mc.player == null || mc.getConnection() == null || !NAME.matcher(name).matches())
        {
            return;
        }

        String command = Ep2Configs.MODULE_INVSEE_COMMAND.getStringValue().trim().replace("{name}", name);

        if (command.startsWith("/"))
        {
            command = command.substring(1);
        }

        mc.getConnection().sendCommand(command);
        mc.player.displayClientMessage(Component.literal("/" + command), true);
    }

    /** Der naechste Spieler, der hoechstens den eingestellten Winkel neben der Blickrichtung steht. */
    @Nullable
    private static AbstractClientPlayer findTarget(Minecraft mc, LocalPlayer self)
    {
        Vec3 eye = self.getEyePosition();
        Vec3 look = self.getViewVector(1.0f);
        double maxAngle = Ep2Configs.MODULE_INVSEE_ANGLE.getDoubleValue();
        double range = Ep2Configs.MODULE_INVSEE_RANGE.getDoubleValue();
        AbstractClientPlayer best = null;
        double bestDist = Double.MAX_VALUE;

        for (AbstractClientPlayer other : mc.level.players())
        {
            if (other == self)
            {
                continue;
            }

            Vec3 to = other.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();

            if (dist > range || dist < 1.0E-3)
            {
                continue;
            }

            double angle = Math.toDegrees(Math.acos(Mth.clamp(to.normalize().dot(look), -1.0, 1.0)));

            if (angle <= maxAngle && dist < bestDist)
            {
                best = other;
                bestDist = dist;
            }
        }

        return best;
    }
}
