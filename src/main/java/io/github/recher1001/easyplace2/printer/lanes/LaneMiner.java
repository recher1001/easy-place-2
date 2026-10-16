package io.github.recher1001.easyplace2.printer.lanes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.printer.BlockDigger;
import io.github.recher1001.easyplace2.printer.Excavation;
import io.github.recher1001.easyplace2.printer.refill.RefillUtil;

/**
 * Aushoehlen in Bahnen: ein Gang (1 breit und 2 hoch, mit Star-Spitzhacke 3x3) geradeaus bis zum Ende,
 * Kurve, und direkt daneben zurueck. Ist eine Lage fertig, wird nach unten gegraben (2 bzw. 3 Bloecke)
 * und die naechste Lage genauso abgebaut.
 * <p>
 * Mit Star-Spitzhacke steht der Spieler in der Mitte der 3 breiten Wand und schlaegt auf den mittleren
 * Block vor sich: der Server baut dann die ganze 3x3-Wand ab. Nach unten wird dreimal auf den Block unter
 * den Fuessen geschlagen. Wo die 3x3-Flaeche etwas treffen wuerde, das stehen bleiben soll, wird stattdessen
 * mit einem anderen Werkzeug Block fuer Block abgebaut.
 */
public final class LaneMiner
{
    public enum Kind { DIG, WALK, NONE }

    public record Action(Kind kind, Set<BlockPos> dig, @Nullable BlockPos walkTo, int forceSlot, int avoidSlot)
    {
        static final Action NONE = new Action(Kind.NONE, Set.of(), null, -1, -1);

        static Action dig(Set<BlockPos> dig, int forceSlot, int avoidSlot)
        {
            return new Action(Kind.DIG, dig, null, forceSlot, avoidSlot);
        }

        static Action walk(BlockPos to)
        {
            return new Action(Kind.WALK, Set.of(), to, -1, -1);
        }
    }

    @Nullable private Integer layerY;
    @Nullable private Integer pendingLayer;
    @Nullable private List<LanePath.Step> path;
    private int index;
    private String mode = "";

    public void reset()
    {
        this.layerY = null;
        this.pendingLayer = null;
        this.path = null;
        this.index = 0;
    }

    /** Der Weg der aktuellen Lage (fuer Anzeige und Tests). */
    public List<LanePath.Step> path()
    {
        return this.path != null ? this.path : List.of();
    }

    public Action next(LocalPlayer player, Level level, Level schematic, Excavation excavation, BlockDigger digger, long now)
    {
        return this.next(player, level, schematic, excavation, digger, now, 0);
    }

    private Action next(LocalPlayer player, Level level, Level schematic, Excavation excavation, BlockDigger digger, long now, int depth)
    {
        boolean star = Ep2Configs.PRINTER_STAR_TOOL.getBooleanValue();
        int height = star ? 3 : 2;
        int half = star ? 1 : 0;
        int spacing = star ? 3 : Math.max(1, Ep2Configs.PRINTER_LANE_SPACING.getIntegerValue());
        String mode = height + "/" + half + "/" + spacing;

        if (!mode.equals(this.mode))
        {
            this.mode = mode;
            this.reset();
        }

        Set<BlockPos> wanted = new HashSet<>();

        for (BlockPos p : excavation.candidates(level, schematic))
        {
            if (level.hasChunkAt(p) && !digger.isSkipped(p, now) && excavation.stillWanted(level, schematic, p))
            {
                wanted.add(p);
            }
        }

        if (wanted.isEmpty())
        {
            this.reset();
            return Action.NONE;
        }

        BlockPos feet = player.blockPosition();
        int top = wanted.stream().mapToInt(BlockPos::getY).max().orElseThrow();

        // Unten angekommen: neue Lage
        if (this.pendingLayer != null && feet.getY() <= this.pendingLayer)
        {
            this.layerY = this.pendingLayer;
            this.pendingLayer = null;
            this.path = null;
        }

        // Oben ist (wieder) etwas dazugekommen, oder es geht gerade erst los: oben anfangen
        if (this.layerY == null || top > this.layerY + height - 1)
        {
            this.layerY = top - height + 1;
            this.pendingLayer = null;
            this.path = null;
        }

        int layer = this.layerY;
        List<BlockPos> band = new ArrayList<>();

        for (BlockPos p : wanted)
        {
            if (p.getY() >= layer && p.getY() < layer + height)
            {
                band.add(p);
            }
        }

        int starSlot = star ? StarTool.findSlot(player) : -1;
        int avoid = StarTool.findSlot(player);

        // Diese Lage ist fertig: nach unten
        if (band.isEmpty())
        {
            Action down = this.descend(player, level, wanted, layer, height, half, starSlot, avoid);

            // Schon unten (oder Luecke): gleich mit der neuen Lage weiter
            return down != null ? down : depth < 8 ? this.next(player, level, schematic, excavation, digger, now, depth + 1) : Action.NONE;
        }

        if (this.path == null)
        {
            this.path = LanePath.build(band, layer, spacing, half, player.position());
            this.index = 0;
        }

        // Naechster Schritt der Bahn, in dem noch etwas weg muss
        int j = this.index;

        while (j < this.path.size() && intersect(LanePath.crossSection(this.path.get(j), half, height), wanted).isEmpty())
        {
            j++;
        }

        if (j >= this.path.size())
        {
            // Rest, den die Bahnen nicht treffen (unregelmaessige Form): das macht der normale Modus
            return Action.NONE;
        }

        this.index = j;
        LanePath.Step step = this.path.get(j);
        List<BlockPos> cross = LanePath.crossSection(step, half, height);
        Set<BlockPos> todo = intersect(cross, wanted);
        Vec3 eye = player.getEyePosition();
        double reach = RefillUtil.maxDistance(player) + 0.5;
        Set<BlockPos> inReach = new HashSet<>();

        for (BlockPos p : todo)
        {
            // Nah genug und auch zu sehen (nicht hinter noch nicht abgebauten Bloecken)
            if (Vec3.atCenterOf(p).distanceTo(eye) <= reach && !RefillUtil.visiblePoints(player, level, null, p, null).isEmpty())
            {
                inReach.add(p);
            }
        }

        if (inReach.isEmpty())
        {
            // Hingehen: auf das Feld davor in der Bahn
            BlockPos stand = j > 0 ? this.path.get(j - 1).pos() : step.pos().relative(step.dir().getOpposite());
            return stand.equals(feet) ? Action.NONE : Action.walk(stand);
        }

        // Star-Spitzhacke: auf den mittleren Block der Wand schlagen, wenn die ganze 3x3-Flaeche weg darf
        if (starSlot >= 0)
        {
            BlockPos center = step.pos().above(1);

            if (inReach.contains(center) && allClearable(level, cross, wanted))
            {
                return Action.dig(Set.of(center), starSlot, -1);
            }
        }

        return Action.dig(inReach, -1, avoid);
    }

    /**
     * Nach unten graben (unter den Fuessen), bis die naechste Lage erreicht ist.
     *
     * @return null, wenn der Spieler schon auf der neuen Lage steht (dann gleich weiter)
     */
    @Nullable
    private Action descend(LocalPlayer player, Level level, Set<BlockPos> wanted, int layer, int height, int half,
                           int starSlot, int avoid)
    {
        int highestBelow = Integer.MIN_VALUE;

        for (BlockPos p : wanted)
        {
            if (p.getY() < layer)
            {
                highestBelow = Math.max(highestBelow, p.getY());
            }
        }

        if (highestBelow == Integer.MIN_VALUE)
        {
            return Action.NONE;
        }

        int newLayer = Math.max(layer - height, highestBelow - height + 1);
        BlockPos feet = player.blockPosition();

        if (feet.getY() <= newLayer)
        {
            this.layerY = newLayer;
            this.pendingLayer = null;
            this.path = null;
            return null;
        }

        this.pendingLayer = newLayer;

        // Die Saeule unter dem Spieler: alles muss weg duerfen (oder schon Luft sein)
        List<BlockPos> column = new ArrayList<>();

        for (int y = feet.getY() - 1; y >= newLayer; y--)
        {
            for (int dx = -half; dx <= half; dx++)
            {
                for (int dz = -half; dz <= half; dz++)
                {
                    column.add(new BlockPos(feet.getX() + dx, y, feet.getZ() + dz));
                }
            }
        }

        Set<BlockPos> todo = intersect(column, wanted);

        if (!todo.isEmpty() && allClearable(level, column, wanted))
        {
            BlockPos below = feet.below();

            if (starSlot >= 0 && wanted.contains(below))
            {
                List<BlockPos> plane = new ArrayList<>();

                for (int dx = -1; dx <= 1; dx++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        plane.add(below.offset(dx, 0, dz));
                    }
                }

                if (allClearable(level, plane, wanted))
                {
                    return Action.dig(Set.of(below), starSlot, -1);
                }
            }

            return Action.dig(todo, -1, avoid);
        }

        // Hier geht es nicht runter (z. B. unten soll etwas stehen bleiben): eine andere Stelle suchen
        if (this.path != null)
        {
            for (LanePath.Step s : this.path)
            {
                BlockPos p = s.pos();

                if (!p.equals(feet) && LaneWalker.standable(level, p) && wanted.contains(p.below()))
                {
                    return Action.walk(p);
                }
            }
        }

        return Action.NONE;
    }

    /** Darf jedes dieser Felder weg (gewollt) oder ist es schon frei? */
    private static boolean allClearable(Level level, List<BlockPos> cells, Set<BlockPos> wanted)
    {
        for (BlockPos p : cells)
        {
            BlockState state = level.getBlockState(p);

            if (!wanted.contains(p) && !state.isAir() && !state.canBeReplaced())
            {
                return false;
            }
        }

        return true;
    }

    private static Set<BlockPos> intersect(List<BlockPos> cells, Set<BlockPos> wanted)
    {
        Set<BlockPos> result = new HashSet<>();

        for (BlockPos p : cells)
        {
            if (wanted.contains(p))
            {
                result.add(p);
            }
        }

        return result;
    }
}
