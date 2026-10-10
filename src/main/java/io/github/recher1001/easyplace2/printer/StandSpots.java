package io.github.recher1001.easyplace2.printer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Sucht Stellen, an denen der Spieler stehen kann, um einen Block zu erreichen.
 * Baritone bekommt mehrere davon und nimmt die, zu der der Weg am guenstigsten ist.
 */
public final class StandSpots
{
    private static final double EYE_HEIGHT = 1.62;
    private static final int MAX_SPOTS = 8;
    /** So viele Stellen werden hoechstens genau durchgerechnet (Sichtlinie und Ausrichtung). */
    private static final int MAX_CHECKED = 48;

    private StandSpots()
    {
    }

    /**
     * @param canPlaceFrom prueft, ob Vanilla den Block von dieser Stelle aus richtig herum setzen wuerde
     *                     (z. B. Treppen: man muss auf der passenden Seite stehen)
     */
    public static List<BlockPos> find(Level level, Level schematic, BlockPos target, double reach, Vec3 playerPos,
                               Predicate<BlockPos> canPlaceFrom)
    {
        record Spot(BlockPos pos, double score) {}

        List<Spot> spots = new ArrayList<>();
        Vec3 center = Vec3.atCenterOf(target);
        int r = (int) Math.ceil(reach);

        for (int dy = -3; dy <= 2; dy++)
        {
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dz = -r; dz <= r; dz++)
                {
                    BlockPos feet = target.offset(dx, dy, dz);
                    BlockPos head = feet.above();

                    if (feet.equals(target) || head.equals(target) || !canStand(level, feet))
                    {
                        continue;
                    }

                    Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + EYE_HEIGHT, feet.getZ() + 0.5);
                    double dist = eye.distanceTo(center);

                    if (dist > reach || dist < 1.0)
                    {
                        continue;
                    }

                    // Nicht dort hinstellen, wo die Schematic selbst noch Bloecke braucht
                    double score = 0;
                    score += schematic.getBlockState(feet).isAir() ? 0 : 6;
                    score += schematic.getBlockState(head).isAir() ? 0 : 6;
                    score += playerPos.distanceTo(Vec3.atBottomCenterOf(feet)) * 0.3;
                    score += Math.abs(eye.y - center.y) * 0.25;
                    spots.add(new Spot(feet, score));
                }
            }
        }

        spots.sort(Comparator.comparingDouble(Spot::score));
        List<BlockPos> result = new ArrayList<>();

        for (int i = 0; i < spots.size() && i < MAX_CHECKED && result.size() < MAX_SPOTS; i++)
        {
            if (canPlaceFrom.test(spots.get(i).pos()))
            {
                result.add(spots.get(i).pos());
            }
        }

        return result;
    }

    private static boolean canStand(Level level, BlockPos feet)
    {
        BlockPos below = feet.below();

        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() &&
               level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty() &&
               level.getFluidState(feet).isEmpty() &&
               level.getFluidState(feet.above()).isEmpty() &&
               !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }
}
