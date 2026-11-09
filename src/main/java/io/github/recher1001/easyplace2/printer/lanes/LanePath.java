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

        // An der naechsten Ecke anfangen
        double pa = alongX ? player.x : player.z;
        double pc = alongX ? player.z : player.x;
        boolean forward = Math.abs(pa - (minA + 0.5)) <= Math.abs(pa - (maxA + 0.5));

        // Die Bahnen (Mitte der Bahn), von der Seite aus, die dem Spieler naeher ist. Eine Bahn ragt nie ueber den Rand:
        // bleibt am Ende ein Rest, der schmaler als eine Bahn ist, rueckt die letzte Bahn an den Rand (ihre Mitte muss
        // aber noch stehen, sonst gaebe es nichts, worauf man schlagen kann). Bleibt bei 3 breiten Bahnen genau ein
        // Streifen uebrig, wird der von der letzten Bahn aus seitlich abgebaut (siehe unten).
        List<Integer> lanes = new ArrayList<>();
        Integer strip = null;
        int sgn = 1;
        int lo = minC + half, hi = maxC - half;

        if (hi < lo)
        {
            lanes.add(minC + Math.min(half, (maxC - minC) / 2));
        }
        else
        {
            boolean fromMax = Math.abs(pc - (hi + 0.5)) < Math.abs(pc - (lo + 0.5));
            sgn = fromMax ? -1 : 1;
            int edge = fromMax ? minC : maxC;
            int lastCenter = fromMax ? lo : hi;
            int c = fromMax ? hi : lo;

            while (true)
            {
                lanes.add(c);
                int covered = c + sgn * half;

                if (covered == edge)
                {
                    break;
                }

                int next = c + sgn * step;

                if ((next - lastCenter) * sgn > 0)
                {
                    if (half > 0 && Math.abs(edge - covered) <= half)
                    {
                        strip = edge;
                        break;
                    }

                    next = lastCenter;
                }

                c = next;
            }
        }

        List<Step> path = new ArrayList<>();
        Integer previousLane = null;
        // Breite Bahnen (3x3): die Kurve liegt ein Feld vor dem Rand, damit auch ihre 3 breite Wand ganz in der Flaeche
        // liegt (sonst traefe sie Bloecke ausserhalb), und sie geht bis zur anderen Seite der neuen Bahn durch
        int inset = half > 0 && maxA - minA >= 2 * half ? half : 0;

        for (int lane : lanes)
        {
            int startA = forward ? minA : maxA;
            int endA = forward ? maxA : minA;
            int da = forward ? 1 : -1;
            int firstA = startA;

            // Kurve: von der vorigen Bahn seitlich herueber
            if (previousLane != null)
            {
                int dc = lane > previousLane ? 1 : -1;
                int turnA = startA + da * inset;
                int last = inset > 0 ? lane + dc * half : lane;

                for (int c = previousLane + dc; c != last + dc; c += dc)
                {
                    path.add(new Step(pos(alongX, turnA, c, y), dir(alongX, false, dc)));
                }

                // Der Anfang der Bahn ist mit der Kurve schon erreicht
                firstA = turnA + da;
            }

            for (int a = firstA; a != endA + da; a += da)
            {
                path.add(new Step(pos(alongX, a, lane, y), dir(alongX, true, da)));
            }

            previousLane = lane;
            forward = !forward;
        }

        // Der uebrige Streifen (1 breit): aus der letzten Bahn heraus seitlich darauf schlagen, alle 3 Felder einmal
        // (die 3x3-Flaeche liegt dann laengs im Streifen). Angefangen wird dort, wo die letzte Bahn aufgehoert hat.
        if (strip != null && previousLane != null)
        {
            int from = forward ? minA : maxA;
            int to = forward ? maxA : minA;
            int da = forward ? 1 : -1;
            int in = maxA - minA >= 2 * half ? half : 0;
            int lastA = to - da * in;
            int a = from + da * in;

            while (true)
            {
                path.add(new Step(pos(alongX, a, strip, y), dir(alongX, false, sgn)));

                if (in == 0 || a + da * in == to)
                {
                    break;
                }

                int next = a + da * (2 * half + 1);
                a = (next - lastA) * da > 0 ? lastA : next;
            }
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
