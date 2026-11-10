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
    /** Wohin zuletzt gelaufen werden sollte und wie oft (klappt es nicht, wird anders weitergemacht). */
    @Nullable private BlockPos lastWalk;
    private int walkTries;

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
        this.lastWalk = null;
        this.walkTries = 0;
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

            if (wasBulk && !this.pathHasWork(primary, half, height))
            {
                // Jetzt sind nur noch Reste da, die der bisherige Weg nicht trifft: neu planen
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
        // (nie tiefer als der unterste Block: darunter soll ja nichts weg, und dort koennte man nicht stehen)
        if (this.layerY == null || top > this.layerY + height - 1)
        {
            int bottom = primary.stream().mapToInt(BlockPos::getY).min().orElseThrow();
            this.layerY = top - rowsToTake(top - bottom + 1, height) + 1;
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
            this.path = star ? starPath(level, band, layer, player.position()) : LanePath.build(band, layer, spacing, half, player.position());
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

        // Star-Spitzhacke und nur noch die Reihe auf Fusshoehe uebrig: von oben in 3x3-Stuecken abbauen
        if (starSlot >= 0 && band.stream().noneMatch(p -> p.getY() > layer))
        {
            Action patch = this.patch(level, wanted, feet, layer, starSlot);

            if (patch != null)
            {
                return patch;
            }
        }

        // Star-Spitzhacke, und der Spieler steht noch ueber der Lage (z. B. oben auf dem Berg): ueber der Bahn senkrecht
        // mit 3x3 nach unten bis auf die Lage, statt von oben Block fuer Block abzubauen
        if (starSlot >= 0 && feet.getY() > layer)
        {
            // Ein Feld vom Rand entfernt einsteigen: dort liegt die ganze 3x3-Saeule in der Flaeche
            LanePath.Step entry = j + 1 < this.path.size() && this.path.get(j + 1).dir() == step.dir() ? this.path.get(j + 1) : step;
            Action enter = this.enterFromAbove(level, wanted, feet, entry, layer, starSlot);

            if (enter != null)
            {
                return enter;
            }
        }

        // Star-Spitzhacke: vor der Wand stehen, geradeaus schauen, schlagen, vorgehen, schlagen ... bis zum Ende der Bahn
        // (Waende mit Bloecken, die bleiben sollen, und Reste macht der Tunnel selbst einzeln, wie bei der Star-Linie)
        BlockPos rel = feet.subtract(step.pos());
        int along = rel.getX() * step.dir().getStepX() + rel.getZ() * step.dir().getStepZ();
        int across = Math.abs(rel.getX() * step.dir().getStepZ() - rel.getZ() * step.dir().getStepX());

        // Die Wand liegt in der Bahn hinter dem Ruecken (z. B. nach dem Hinuntergraben): einmal umdrehen und sie von
        // dieser Seite abbauen. Eine 3x3-Wand ist von beiden Seiten dieselbe.
        if (starSlot >= 0 && rel.getY() == 0 && across == 0 && along > 0 && along <= 4)
        {
            return Action.tunnel(List.of(new LanePath.Step(step.pos(), step.dir().getOpposite())), starSlot);
        }

        // Losgraben nur von der richtigen Seite: vor der Wand (oder ein Feld daneben, dann rueckt der Tunnel in die Mitte)
        boolean inFront = rel.getY() == 0 && along < 0 && across <= 1 && inReach.contains(step.pos().above(1));

        if (starSlot >= 0 && (stand.equals(feet) || inFront || onLaneBehind(feet, step)))
        {
            List<LanePath.Step> run = new ArrayList<>();

            for (int i = j; i < this.path.size(); i++)
            {
                LanePath.Step s = this.path.get(i);

                if (i > j)
                {
                    // Lueckenlos geradeaus, oder (Reststreifen) dieselbe Blickrichtung seitlich versetzt: dann rueckt
                    // der Tunnel mit A/D weiter, ohne die Kamera zu bewegen
                    BlockPos d = s.pos().subtract(this.path.get(i - 1).pos());
                    boolean ahead = s.pos().equals(this.path.get(i - 1).pos().relative(step.dir()));
                    boolean sideways = d.getY() == 0 && d.getX() * step.dir().getStepX() + d.getZ() * step.dir().getStepZ() == 0;

                    if (s.dir() != step.dir() || !(ahead || sideways))
                    {
                        break;
                    }
                }

                run.add(s);
            }

            return Action.tunnel(run, starSlot);
        }

        // Star-Spitzhacke: erst in die Bahn stellen (dann geht es geradeaus ohne Kamerabewegung), nicht von der Seite
        // einzelne Bloecke abbauen
        if (starSlot >= 0 && feet.getY() == layer && !stand.equals(feet) && LaneWalker.standable(level, stand) && this.mayWalk(stand))
        {
            return Action.walk(stand);
        }

        if (inReach.isEmpty())
        {
            // Hingehen, aber mit einem Feld Abstand zur Wand (von dort aus abbauen)
            BlockPos walkTo = j > 1 ? this.path.get(j - 2).pos() : j > 0 ? this.path.get(j - 1).pos() : stand;
            return walkTo.equals(feet) || !this.mayWalk(walkTo) ? Action.NONE : Action.walk(walkTo);
        }

        return Action.dig(inReach, -1, avoid);
    }

    /** Trifft der aktuelle Weg (ab dem aktuellen Schritt) noch etwas, das weg soll? */
    private boolean pathHasWork(Set<BlockPos> todo, int half, int height)
    {
        if (this.path == null)
        {
            return false;
        }

        for (int i = this.index; i < this.path.size(); i++)
        {
            if (!intersect(LanePath.crossSection(this.path.get(i), half, height), todo).isEmpty())
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Der Weg fuer die Star-Spitzhacke. Normal Bahnen (3 breit). Ist die Flaeche nur 1 oder 2 breit und daneben schon
     * frei (ein schmaler Rest am Rand), wird sie von dort aus seitlich abgebaut: eine Bahn mitten hindurch wuerde mit
     * ihrer 3x3-Flaeche daneben Bloecke treffen, die stehen bleiben sollen.
     */
    private static List<LanePath.Step> starPath(Level level, List<BlockPos> band, int layer, Vec3 player)
    {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockPos p : band)
        {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }

        boolean alongX = (maxX - minX) >= (maxZ - minZ);
        int minA = alongX ? minX : minZ, maxA = alongX ? maxX : maxZ;
        int minC = alongX ? minZ : minX, maxC = alongX ? maxZ : maxX;

        if (maxC - minC <= 1)
        {
            int low = 0, high = 0;

            for (int a = minA; a <= maxA; a++)
            {
                if (LaneWalker.standable(level, alongX ? new BlockPos(a, layer, minC - 1) : new BlockPos(minC - 1, layer, a)))
                {
                    low++;
                }

                if (LaneWalker.standable(level, alongX ? new BlockPos(a, layer, maxC + 1) : new BlockPos(maxC + 1, layer, a)))
                {
                    high++;
                }
            }

            if (Math.max(low, high) >= (maxA - minA + 2) / 2)
            {
                boolean fromLow = low >= high;
                List<Integer> columns = minC == maxC ? List.of(minC) : fromLow ? List.of(minC, maxC) : List.of(maxC, minC);
                return LanePath.strip(alongX, minA, maxA, columns, layer, fromLow ? 1 : -1, 1, player);
            }
        }

        return LanePath.build(minX, maxX, minZ, maxZ, layer, 3, 1, player);
    }

    /**
     * Wie viele Reihen die naechste Lage nimmt, wenn noch {@code rows} Reihen uebrig sind. Mit der Star-Spitzhacke
     * (3 hoch) muss die mittlere Reihe einer Lage stehen, damit man geradeaus darauf schlagen kann: deshalb bei 4
     * uebrigen Reihen 2 + 2 statt 3 + 1 (eine einzelne Reihe liesse sich nur von oben abbauen).
     */
    private static int rowsToTake(int rows, int height)
    {
        return height == 3 && rows == 4 ? 2 : Math.min(height, Math.max(1, rows));
    }

    /**
     * Nur noch die Reihe auf Fusshoehe ist uebrig (nichts mehr in Kopfhoehe, worauf man geradeaus schlagen koennte):
     * oben auf die Reihe stellen, senkrecht nach unten schauen und die 3x3-Flaeche unter sich abbauen, dann zum
     * naechsten Stueck. Gewaehlt wird das Stueck mit den meisten Bloecken, bei gleich vielen das naechste.
     *
     * @return null, wenn es so ein Stueck nicht gibt (dann geht es normal weiter)
     */
    @Nullable
    private Action patch(Level level, Set<BlockPos> wanted, BlockPos feet, int layer, int starSlot)
    {
        // Die ganze Reihe zaehlt, auch ihr Rand (nicht nur der "grosse Teil")
        List<BlockPos> row = new ArrayList<>();

        for (BlockPos p : wanted)
        {
            if (p.getY() == layer)
            {
                row.add(p);
            }
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockPos p : row)
        {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }

        BlockPos best = null;
        double bestScore = -Double.MAX_VALUE;

        for (BlockPos c : row)
        {
            if (!level.getBlockState(c).is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE) ||
                !LaneWalker.standable(level, c.above()))
            {
                continue;
            }

            int count = 0;
            boolean clear = true;

            for (int dx = -1; dx <= 1 && clear; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    BlockPos p = c.offset(dx, 0, dz);
                    BlockState state = level.getBlockState(p);

                    if (wanted.contains(p))
                    {
                        count++;
                    }
                    else if (!state.isAir() && !state.canBeReplaced())
                    {
                        clear = false;
                        break;
                    }
                }
            }

            if (!clear || count < 3)
            {
                continue;
            }

            // Erst die Stuecke am Rand, dann die dazwischen: bleibt in der Mitte ein schmaler Rest, steht dessen Mitte noch
            // (am Rand liesse sich ein schmaler Rest nicht mehr von oben abbauen, ohne daneben etwas zu treffen)
            int edges = (c.getX() - 1 == minX ? 1 : 0) + (c.getX() + 1 == maxX ? 1 : 0) + (c.getZ() - 1 == minZ ? 1 : 0) +
                        (c.getZ() + 1 == maxZ ? 1 : 0);
            double score = count * 10.0 + edges * 5.0 - Math.sqrt(c.above().distSqr(feet)) * 0.5;

            if (score > bestScore)
            {
                bestScore = score;
                best = c;
            }
        }

        if (best == null)
        {
            return null;
        }

        BlockPos top = best.above();

        if (top.equals(feet))
        {
            return Action.down(layer, starSlot);
        }

        return this.mayWalk(top) ? Action.walk(top) : null;
    }

    /** Dasselbe Laufziel hoechstens ein paar Mal hintereinander (kommt man nicht hin, macht der normale Modus weiter). */
    private boolean mayWalk(BlockPos to)
    {
        if (!to.equals(this.lastWalk))
        {
            this.lastWalk = to.immutable();
            this.walkTries = 0;
        }

        return ++this.walkTries <= 3;
    }

    /**
     * Von oben in die Lage: steht der Spieler ueber einer Stelle, an der die ganze 3x3-Saeule bis zur Lage weg darf,
     * dort senkrecht hinunter. Sonst oben zu dem Feld ueber dem Anfang der Bahn laufen.
     *
     * @return null, wenn das hier nicht geht
     */
    @Nullable
    private Action enterFromAbove(Level level, Set<BlockPos> wanted, BlockPos feet, LanePath.Step step, int layer, int starSlot)
    {
        boolean here = feet.getX() == step.pos().getX() && feet.getZ() == step.pos().getZ();
        boolean canDown = wanted.contains(feet.below()) && columnClearable(level, wanted, feet, layer);

        if (here && canDown)
        {
            return Action.down(layer, starSlot);
        }

        for (int y = feet.getY() + 3; y > layer && !here; y--)
        {
            BlockPos p = new BlockPos(step.pos().getX(), y, step.pos().getZ());

            if (LaneWalker.standable(level, p))
            {
                if (wanted.contains(p.below()) && columnClearable(level, wanted, p, layer) && this.mayWalk(p))
                {
                    return Action.walk(p);
                }

                break;
            }
        }

        // Zum Bahnanfang geht es nicht: dann eben hier hinunter, wenn die Saeule hier ganz weg darf
        return canDown ? Action.down(layer, starSlot) : null;
    }

    /** Darf unter {@code feet} die ganze 3x3-Saeule bis zur Fusshoehe {@code layer} weg (oder ist schon frei)? */
    private static boolean columnClearable(Level level, Set<BlockPos> wanted, BlockPos feet, int layer)
    {
        List<BlockPos> column = new ArrayList<>();

        for (int y = feet.getY() - 1; y >= layer; y--)
        {
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    column.add(new BlockPos(feet.getX() + dx, y, feet.getZ() + dz));
                }
            }
        }

        return allClearable(level, column, wanted);
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
        int lowestBelow = Integer.MAX_VALUE;

        for (BlockPos p : primary)
        {
            if (p.getY() < layer)
            {
                highestBelow = Math.max(highestBelow, p.getY());
                lowestBelow = Math.min(lowestBelow, p.getY());
            }
        }

        if (highestBelow == Integer.MIN_VALUE)
        {
            return Action.NONE;
        }

        // Nie tiefer als der unterste Block (sind unten nur noch 1 oder 2 Reihen uebrig, nur so weit hinunter)
        int newLayer = highestBelow - rowsToTake(highestBelow - lowestBelow + 1, height) + 1;
        BlockPos feet = player.blockPosition();

        if (feet.getY() <= newLayer)
        {
            this.layerY = newLayer;
            this.pendingLayer = null;
            this.path = null;
            return null;
        }

        if (starSlot >= 0)
        {
            // Star-Spitzhacke: nicht einfach hier hinunter, sondern dort, wo die naechste Lage anfaengt (ueber dem Anfang
            // ihrer ersten Bahn). Dann passt das 3x3-Loch genau in die Bahn und es bleiben keine Reste daneben stehen.
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
