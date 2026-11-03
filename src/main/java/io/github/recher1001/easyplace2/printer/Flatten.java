package io.github.recher1001.easyplace2.printer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.config.Ep2Configs;

/**
 * Abbau-Hoehe ("Flatten"): auf Wunsch nur Bloecke ab Fusshoehe abbauen, damit nie unter einem gegraben wird. Als Treppe
 * darf es in Blickrichtung mit jedem Block Abstand einen Block tiefer gehen. Schleichen erlaubt eine Stufe tiefer.
 */
public final class Flatten
{
    private Flatten()
    {
    }

    public static boolean allows(BlockPos pos)
    {
        if (!Ep2Configs.PRINTER_FLATTEN.getBooleanValue())
        {
            return true;
        }

        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null)
        {
            return true;
        }

        int floor = player.getBlockY() - (player.isShiftKeyDown() ? 1 : 0);

        if (pos.getY() >= floor)
        {
            return true;
        }

        if (!Ep2Configs.PRINTER_FLATTEN_STAIRS.getBooleanValue())
        {
            return false;
        }

        // Treppe: in Blickrichtung je Block Abstand eine Stufe tiefer
        Vec3 look = player.getViewVector(1.0f);
        Vec3 flat = new Vec3(look.x, 0.0, look.z);

        if (flat.lengthSqr() < 1.0E-4)
        {
            return false;
        }

        flat = flat.normalize();
        double along = (pos.getX() + 0.5 - player.getX()) * flat.x + (pos.getZ() + 0.5 - player.getZ()) * flat.z;
        return along > 0.5 && pos.getY() >= floor - Math.floor(along);
    }
}
