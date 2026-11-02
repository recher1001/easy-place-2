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
    public enum Kind { DIG, WALK, TUNNEL, DOWN, NONE }

    public record Action(Kind kind, Set<BlockPos> dig, @Nullable BlockPos walkTo, int forceSlot, int avoidSlot, List<LanePath.Step> run)
    {
        static final Action NONE = new Action(Kind.NONE, Set.of(), null, -1, -1, List.of());

        static Action dig(Set<BlockPos> dig, int forceSlot, int avoidSlot)
        {
            return new Action(Kind.DIG, dig, null, forceSlot, avoidSlot, List.of());
        }

        static Action walk(BlockPos to)
        {
            return new Action(Kind.WALK, Set.of(), to, -1, -1, List.of());
        }

        /** Star-Spitzhacke: senkrecht nach unten graben bis zur Fusshoehe {@code layer} (siehe {@link StarTunnel}). */
        static Action down(int layer, int slot)
        {
            return new Action(Kind.DOWN, Set.of(), new BlockPos(0, layer, 0), slot, -1, List.of());
        }

        /** Star-Spitzhacke: die Bahn geradeaus durchgraben (siehe {@link StarTunnel}). */
        static Action tunnel(List<LanePath.Step> run, int slot)
        {
            return new Action(Kind.TUNNEL, Set.of(), null, slot, -1, run);
        }
    }

    @Nullable private Integer layerY;
    @Nullable private Integer pendingLayer;
    @Nullable private List<LanePath.Step> path;
    private int index;
    private String mode = "";
    /** Bloecke im "grossen Teil" (mit vielen abzubauenden Nachbarn), berechnet aus der letzten Scan-Liste. */
    private Set<BlockPos> bulk = Set.of();
    @Nullable private Object bulkSource;
    /** Gibt es noch etwas im grossen Teil? Dann werden einzelne Reste erst einmal ignoriert. */
    private boolean bulkLeft;

    /** Mindestzahl abzubauender Nachbarn (von 26), damit ein Block zum grossen Teil zaehlt. */
    private static final int BULK_NEIGHBOURS = 6;

    /**
     * Erst den grossen Teil: solange es den gibt, zaehlen einzelne vergessene Bloecke (in schon ausgehoehlten
     * Bereichen) nicht. Danach alles.
     */
    public boolean preferred(BlockPos pos)
    {
        return !this.bulkLeft || this.bulk.contains(pos);
    }

    /** Gibt es noch etwas im grossen Teil? */
    public boolean hasBulk()
    {
        return this.bulkLeft;
    }

    private void updateBulk(Iterable<BlockPos> candidates)
    {
        if (candidates == this.bulkSource)
        {
            return;
        }

        this.bulkSource = candidates;
        Set<BlockPos> all = new HashSet<>();

        for (BlockPos p : candidates)
        {
            all.add(p);
        }

        Set<BlockPos> result = new HashSet<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (BlockPos p : all)
        {
            int n = 0;

            for (int dx = -1; dx <= 1 && n < BULK_NEIGHBOURS; dx++)
            {
                for (int dy = -1; dy <= 1 && n < BULK_NEIGHBOURS; dy++)
                {
                    for (int dz = -1; dz <= 1 && n < BULK_NEIGHBOURS; dz++)
                    {
                        if ((dx != 0 || dy != 0 || dz != 0) && all.contains(m.setWithOffset(p, dx, dy, dz)))
                        {
                            n++;
                        }
                    }
                }
            }

            if (n >= BULK_NEIGHBOURS)
            {
                result.add(p);
            }
        }

        this.bulk = result;
    }

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
            this.bulkLeft = false;
            return Action.NONE;
        }

        // Erst der grosse Teil; einzelne Reste in schon ausgehoehlten Bereichen kommen ganz zum Schluss
        this.updateBulk(excavation.candidates(level, schematic));
        Set<BlockPos> primary = new HashSet<>();

        for (BlockPos p : wanted)
        {
            if (this.bulk.contains(p))
            {
                primary.add(p);
            }
        }

        boolean wasBulk = this.bulkLeft;
        this.bulkLeft = !primary.isEmpty();

        if (!this.bulkLeft)
        {
            primary = wanted;

            if (wasBulk)
            {
                // Jetzt sind nur noch Reste da: neu planen
                this.path = null;
                this.layerY = null;
            }
        }

        BlockPos feet = player.blockPosition();
        int top = primary.stream().mapToInt(BlockPos::getY).max().orElseThrow();

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

        for (BlockPos p : primary)
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
            Action down = this.descend(player, level, wanted, primary, layer, height, half, starSlot, avoid);

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

        while (j < this.path.size() && intersect(LanePath.crossSection(this.path.get(j), half, height), primary).isEmpty())
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

        BlockPos stand = step.pos().relative(step.dir().getOpposite());

        // Star-Spitzhacke: vor der Wand stehen, geradeaus schauen, schlagen, vorgehen, schlagen ... bis zum Ende der Bahn
        if (starSlot >= 0 && allClearable(level, cross, wanted) && wanted.contains(step.pos().above(1)) && (stand.equals(feet) || inReach.contains(step.pos().above(1)) || onLaneBehind(feet, step)))
        {
            List<LanePath.Step> run = new ArrayList<>();

            for (int i = j; i < this.path.size(); i++)
            {
                LanePath.Step s = this.path.get(i);

                if (i > j && (s.dir() != step.dir() || !s.pos().equals(this.path.get(i - 1).pos().relative(step.dir()))))
                {
                    break;
                }

                run.add(s);
            }

            return Action.tunnel(run, starSlot);
        }

        if (inReach.isEmpty())
        {
            // Hingehen: auf das Feld davor in der Bahn
            BlockPos walkTo = j > 0 ? this.path.get(j - 1).pos() : stand;
            return walkTo.equals(feet) ? Action.NONE : Action.walk(walkTo);
        }

        return Action.dig(inReach, -1, avoid);
    }

    /**
     * Nach unten graben (unter den Fuessen), bis die naechste Lage erreicht ist.
     *
     * @return null, wenn der Spieler schon auf der neuen Lage steht (dann gleich weiter)
     */
    @Nullable
    private Action descend(LocalPlayer player, Level level, Set<BlockPos> wanted, Set<BlockPos> primary, int layer, int height, int half,
                           int starSlot, int avoid)
    {
        int highestBelow = Integer.MIN_VALUE;

        for (BlockPos p : primary)
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
                    // Einmal senkrecht nach unten schauen und ohne die Kamera zu bewegen Lage fuer Lage runter
                    return Action.down(newLayer, starSlot);
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

    /** Steht der Spieler schon in dieser Bahn, ein Stueck dahinter (im schon gegrabenen Teil)? */
    private static boolean onLaneBehind(BlockPos feet, LanePath.Step step)
    {
        BlockPos d = feet.subtract(step.pos());
        int along = d.getX() * step.dir().getStepX() + d.getZ() * step.dir().getStepZ();
        int across = d.getX() * step.dir().getStepZ() - d.getZ() * step.dir().getStepX();
        return d.getY() == 0 && across == 0 && along < 0 && along >= -8;
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
