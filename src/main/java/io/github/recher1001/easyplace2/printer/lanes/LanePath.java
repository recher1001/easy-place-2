package io.github.recher1001.easyplace2.printer.lanes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Ein Weg in Bahnen ueber eine Flaeche, wie beim Rasenmaehen: eine Bahn entlang der laengeren Seite bis
 * zum Ende, dann eine Kurve zur naechsten Bahn direkt daneben und zurueck, usw.
 * <p>
 * Jeder Schritt ist ein Feld (Fusshoehe), in das der Spieler hineingeht, mit der Richtung, in die er dabei
 * laeuft. Begonnen wird an der Ecke, die dem Spieler am naechsten ist.
 */
public final class LanePath
{
    public record Step(BlockPos pos, Direction dir) {}

    private LanePath()
    {
    }

    /**
     * @param cells   die Felder, die abgedeckt werden sollen (nur X/Z zaehlen)
     * @param y       Fusshoehe der Bahnen
     * @param spacing Abstand der Bahnen (1 = direkt daneben, 3 = fuer 3x3)
     * @param half    halbe Breite einer Bahn (0 = 1 breit, 1 = 3 breit)
     */
    public static List<Step> build(Collection<BlockPos> cells, int y, int spacing, int half, Vec3 player)
    {
        if (cells.isEmpty())
        {
            return List.of();
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockPos p : cells)
        {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }

        return build(minX, maxX, minZ, maxZ, y, spacing, half, player);
    }

    public static List<Step> build(int minX, int maxX, int minZ, int maxZ, int y, int spacing, int half, Vec3 player)
    {
        // Bahnen entlang der laengeren Seite
        boolean alongX = (maxX - minX) >= (maxZ - minZ);
        int minA = alongX ? minX : minZ, maxA = alongX ? maxX : maxZ;
        int minC = alongX ? minZ : minX, maxC = alongX ? maxZ : maxX;
        int step = Math.max(1, spacing);

        List<Integer> lanes = new ArrayList<>();

        for (int c = minC + half; ; c += step)
        {
            lanes.add(c);

            if (c + half >= maxC)
            {
                break;
            }
        }

        // An der naechsten Ecke anfangen
        double pa = alongX ? player.x : player.z;
        double pc = alongX ? player.z : player.x;
        boolean reverseLanes = Math.abs(pc - (lanes.get(lanes.size() - 1) + 0.5)) < Math.abs(pc - (lanes.get(0) + 0.5));
        boolean forward = Math.abs(pa - (minA + 0.5)) <= Math.abs(pa - (maxA + 0.5));

        if (reverseLanes)
        {
            java.util.Collections.reverse(lanes);
        }

        List<Step> path = new ArrayList<>();
        Integer previousLane = null;

        for (int lane : lanes)
        {
            int startA = forward ? minA : maxA;
            int endA = forward ? maxA : minA;
            int da = forward ? 1 : -1;

            // Kurve: von der vorigen Bahn seitlich herueber
            if (previousLane != null)
            {
                int dc = lane > previousLane ? 1 : -1;

                for (int c = previousLane + dc; c != lane + dc; c += dc)
                {
                    path.add(new Step(pos(alongX, startA, c, y), dir(alongX, false, dc)));
                }
            }

            for (int a = startA; a != endA + da; a += da)
            {
                if (previousLane != null && a == startA)
                {
                    continue; // schon mit der Kurve erreicht
                }

                path.add(new Step(pos(alongX, a, lane, y), dir(alongX, true, da)));
            }

            previousLane = lane;
            forward = !forward;
        }

        return path;
    }

    /** Die Felder quer zur Laufrichtung (Breite {@code 2*half+1}) und {@code height} hoch, die dieser Schritt braucht. */
    public static List<BlockPos> crossSection(Step step, int half, int height)
    {
        List<BlockPos> list = new ArrayList<>();
        Direction side = step.dir().getClockWise();

        for (int h = 0; h < height; h++)
        {
            for (int s = -half; s <= half; s++)
            {
                list.add(step.pos().above(h).relative(side, s));
            }
        }

        return list;
    }

    private static BlockPos pos(boolean alongX, int a, int c, int y)
    {
        return alongX ? new BlockPos(a, y, c) : new BlockPos(c, y, a);
    }

    private static Direction dir(boolean alongX, boolean alongAxis, int sign)
    {
        boolean xAxis = alongAxis == alongX;

        if (xAxis)
        {
            return sign > 0 ? Direction.EAST : Direction.WEST;
        }

        return sign > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
