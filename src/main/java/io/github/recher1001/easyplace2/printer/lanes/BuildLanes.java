package io.github.recher1001.easyplace2.printer.lanes;

import java.util.List;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.config.Ep2Configs;

/**
 * Bauen in Bahnen: ist nichts mehr in Reichweite, geht der Printer nicht zum naechstbesten Block, sondern
 * folgt der Bahn (geradeaus bis zum Ende, Kurve, daneben zurueck) zum naechsten Platz, von dem aus es
 * etwas zu bauen gibt.
 */
public final class BuildLanes
{
    @Nullable private List<LanePath.Step> path;
    private int index;
    private int layer = Integer.MIN_VALUE;

    public void reset()
    {
        this.path = null;
        this.index = 0;
        this.layer = Integer.MIN_VALUE;
    }

    /**
     * Der naechste Stehplatz in der Bahn, von dem aus mindestens einer der Bloecke erreichbar ist, oder null
     * (dann sucht der Printer wie bisher einen Stehplatz).
     */
    @Nullable
    public BlockPos next(LocalPlayer player, Level level, List<BlockPos> targets, int layerY, double reach)
    {
        if (targets.isEmpty())
        {
            return null;
        }

        if (this.path == null || layerY != this.layer)
        {
            int spacing = Math.max(1, Ep2Configs.PRINTER_LANE_SPACING.getIntegerValue());
            this.path = LanePath.build(targets, layerY, spacing, 0, player.position());
            this.index = 0;
            this.layer = layerY;
        }

        BlockPos feet = player.blockPosition();

        for (int i = this.index; i < this.path.size(); i++)
        {
            BlockPos p = this.path.get(i).pos();

            if (p.equals(feet) || !LaneWalker.standable(level, p))
            {
                continue;
            }

            Vec3 eye = new Vec3(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);

            for (BlockPos t : targets)
            {
                if (!t.equals(p) && !t.equals(p.above()) && Vec3.atCenterOf(t).distanceTo(eye) <= reach)
                {
                    this.index = i + 1;
                    return p;
                }
            }
        }

        // Bahn zu Ende: beim naechsten Mal neu (die Bloecke haben sich geaendert)
        this.path = null;
        return null;
    }
}
