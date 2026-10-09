package io.github.recher1001.easyplace2.printer;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import io.github.recher1001.easyplace2.placement.PlacementPlanner;

/**
 * Sucht Punkte, auf die der Printer das Fadenkreuz drehen kann, damit ein normaler Rechtsklick
 * (strenges Fadenkreuz) genau den Schematic-Block setzt.
 * <p>
 * Ein Punkt zaehlt nur, wenn
 * <ul>
 *     <li>der echte Strahl vom Auge die Nachbarflaeche trifft (sichtbar und in Reichweite),</li>
 *     <li>derselbe Strahl vorher durch den Geisterblock geht (sonst sieht Litematica ihn nicht),</li>
 *     <li>Vanilla mit genau der Blickrichtung, die beim Hinschauen entsteht, den richtigen Zustand setzen wuerde.</li>
 * </ul>
 * Die Blickrichtung wird dafuer nur kurz im Speicher ausprobiert und sofort zurueckgesetzt,
 * sie wird nie an den Server geschickt. Gedreht wird danach ganz normal ueber {@link AimController}.
 */
public final class AimPlanner
{
    private AimPlanner()
    {
    }

    /**
     * Wie {@link #find}, aber so gerechnet, als wuerde der Spieler auf {@code feet} stehen.
     * Die Position wird dafuer nur kurz im Speicher gesetzt und sofort zurueckgesetzt (kein Paket).
     */
    public static boolean canPlaceFrom(LocalPlayer player, BlockPos feet, Level level, Level schematic, BlockPos target,
                                       BlockState wanted, ItemStack stack, BlockItem item, double maxDist)
    {
        Vec3 old = player.position();

        try
        {
            player.setPosRaw(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
            return !find(player, level, schematic, target, wanted, stack, item, maxDist).isEmpty();
        }
        finally
        {
            player.setPosRaw(old.x, old.y, old.z);
        }
    }

    /**
     * @param maxDist groesster Abstand Auge → Klickpunkt (Reichweite minus Sicherheitsabstand)
     */
    public static List<BlockHitResult> find(LocalPlayer player, Level level, Level schematic, BlockPos target,
                                            BlockState wanted, ItemStack stack, BlockItem item, double maxDist)
    {
        PlacementPlanner planner = new PlacementPlanner(player, level, target, wanted, InteractionHand.MAIN_HAND, stack, item);
        List<BlockHitResult> result = new ArrayList<>();
        Vec3 eye = player.getEyePosition();
        VoxelShape ghost = wanted.getShape(schematic, target);

        float yaw = player.getYRot();
        float pitch = player.getXRot();
        float yawO = player.yRotO;
        float pitchO = player.xRotO;

        try
        {
            for (BlockHitResult hit : planner.visibleCandidates())
            {
                Vec3 point = hit.getLocation();
                Vec3 beyond = eye.add(point.subtract(eye).scale(1.001));

                if (point.distanceTo(eye) > maxDist || ghost.isEmpty() || ghost.clip(eye, beyond, target) == null)
                {
                    continue;
                }

                setLook(player, eye, point);

                if (planner.test(hit))
                {
                    result.add(hit);
                }
            }
        }
        finally
        {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO = yawO;
            player.xRotO = pitchO;
        }

        return result;
    }

    private static void setLook(LocalPlayer player, Vec3 eye, Vec3 point)
    {
        float[] angles = AimController.lookAngles(eye, point);
        player.setYRot(angles[0]);
        player.setXRot(Mth.clamp(angles[1], -90.0f, 90.0f));
    }
}
