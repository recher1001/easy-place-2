package io.github.recher1001.easyplace2.printer.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import org.jetbrains.annotations.Nullable;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Eigene Wegsuche (A*) auf dem Blockraster, ohne Baritone. Bewegungen wie ein Spieler zu Fuss:
 * geradeaus und schraeg gehen, einen Block hoch springen, bis zu 3 Bloecke herunterfallen.
 * Nichts wird abgebaut oder gesetzt. Wasser, Lava, Feuer, Spinnweben usw. werden gemieden.
 * <p>
 * Die Suche laeuft in kleinen Stuecken pro Tick (kein Ruckeln) und ist nach wenigen Ticks fertig. Findet sie
 * keinen ganzen Weg, liefert sie einen Teilweg zu dem Platz, der dem Ziel am naechsten kommt (danach wird von
 * dort aus weitergesucht). Kommt man dem Ziel gar nicht naeher, gibt es keinen Weg: dann wird nicht
 * herumgeirrt.
 */
public final class PathFinder
{
    /** Wohin. */
    public interface Goal
    {
        boolean reached(BlockPos feet);

        /** Geschaetzte Restentfernung (Bloecke), 0 am Ziel. */
        double estimate(BlockPos feet);
    }

    public enum Status { SEARCHING, FOUND, PARTIAL, NONE }

    private static final int MAX_DROP = 3;
    private static final double GREED = 1.4;
    private static final double COST_DIAGONAL = Math.sqrt(2.0);
    private static final double COST_UP = 2.0;

    private static final class Node implements Comparable<Node>
    {
        final long pos;
        double g;
        final double h;
        @Nullable Node parent;
        boolean closed;

        Node(long pos, double g, double h, @Nullable Node parent)
        {
            this.pos = pos;
            this.g = g;
            this.h = h;
            this.parent = parent;
        }

        double f()
        {
            return this.g + this.h * GREED;
        }

        @Override
        public int compareTo(Node o)
        {
            return Double.compare(this.f(), o.f());
        }
    }

    private final Level level;
    private final Goal goal;
    private final int maxNodes;
    private final PriorityQueue<Node> open = new PriorityQueue<>();
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final Node start;
    private Node best;
    private Status status = Status.SEARCHING;
    private List<BlockPos> path = List.of();
    private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

    public PathFinder(Level level, BlockPos startFeet, Goal goal, int maxNodes)
    {
        this.level = level;
        this.goal = goal;
        this.maxNodes = maxNodes;
        this.start = new Node(startFeet.asLong(), 0.0, goal.estimate(startFeet), null);
        this.best = this.start;
        this.nodes.put(this.start.pos, this.start);
        this.open.add(this.start);
    }

    public Status status()
    {
        return this.status;
    }

    /** Der gefundene (Teil-)Weg, erster Eintrag ist der Start. */
    public List<BlockPos> path()
    {
        return this.path;
    }

    /**
     * Ein Stueck weitersuchen.
     *
     * @param budget wie viele Knoten hoechstens in diesem Aufruf
     */
    public Status step(int budget)
    {
        if (this.status != Status.SEARCHING)
        {
            return this.status;
        }

        while (budget-- > 0)
        {
            Node n = this.open.poll();

            if (n == null || this.nodes.size() > this.maxNodes)
            {
                return this.finishPartial();
            }

            if (n.closed)
            {
                continue;
            }

            n.closed = true;
            BlockPos pos = BlockPos.of(n.pos);

            if (this.goal.reached(pos))
            {
                this.path = build(n);
                this.status = Status.FOUND;
                return this.status;
            }

            if (n.h < this.best.h || (n.h == this.best.h && n.g < this.best.g))
            {
                this.best = n;
            }

            this.expand(n, pos);
        }

        return this.status;
    }

    private Status finishPartial()
    {
        // Ein Teilweg lohnt nur, wenn er dem Ziel spuerbar naeher kommt
        if (this.best != this.start && this.best.h < this.start.h - 1.5)
        {
            this.path = build(this.best);
            this.status = Status.PARTIAL;
        }
        else
        {
            this.status = Status.NONE;
        }

        return this.status;
    }

    private void expand(Node n, BlockPos pos)
    {
        for (Direction dir : Direction.Plane.HORIZONTAL)
        {
            BlockPos side = pos.relative(dir);

            if (this.standable(side))
            {
                this.offer(n, side, 1.0);
            }
            else if (this.passable(side) && this.passable(side.above()))
            {
                // Herunterfallen (hoechstens 3 Bloecke, kein Fallschaden)
                for (int d = 1; d <= MAX_DROP; d++)
                {
                    BlockPos down = side.below(d);

                    if (this.standable(down))
                    {
                        this.offer(n, down, 1.0 + d * 0.5);
                        break;
                    }

                    if (!this.passable(down))
                    {
                        break;
                    }
                }
            }
            else if (this.standable(side.above()) && this.passable(pos.above(2)))
            {
                // Einen Block hoch springen
                this.offer(n, side.above(), COST_UP);
            }
        }

        // Schraeg, nur wenn beide Ecken frei sind (sonst bleibt man an der Kante haengen)
        for (int i = 0; i < 4; i++)
        {
            Direction a = Direction.from2DDataValue(i);
            Direction b = a.getClockWise();
            BlockPos diag = pos.relative(a).relative(b);

            if (this.standable(diag) && this.free2(pos.relative(a)) && this.free2(pos.relative(b)))
            {
                this.offer(n, diag, COST_DIAGONAL);
            }
        }
    }

    private void offer(Node from, BlockPos to, double cost)
    {
        long key = to.asLong();
        double g = from.g + cost;
        Node existing = this.nodes.get(key);

        if (existing == null)
        {
            Node node = new Node(key, g, this.goal.estimate(to), from);
            this.nodes.put(key, node);
            this.open.add(node);
        }
        else if (!existing.closed && g < existing.g)
        {
            // Besserer Weg zu einem bekannten Knoten: neu einreihen (der alte Eintrag wird uebersprungen)
            Node node = new Node(key, g, existing.h, from);
            existing.closed = true;
            this.nodes.put(key, node);
            this.open.add(node);
        }
    }

    private static List<BlockPos> build(Node end)
    {
        List<BlockPos> list = new ArrayList<>();

        for (Node n = end; n != null; n = n.parent)
        {
            list.add(BlockPos.of(n.pos));
        }

        Collections.reverse(list);
        return list;
    }

    // ---------------------------------------------------------------- Blockpruefungen

    /** Kann man mit den Fuessen in diesem Feld stehen? (ohne Suche, z. B. fuer gerade Abkuerzungen) */
    public static boolean standableAt(Level level, BlockPos feet)
    {
        return new PathFinder(level).standable(feet);
    }

    /** Nur fuer Blockpruefungen. */
    private PathFinder(Level level)
    {
        this.level = level;
        this.goal = new Goal()
        {
            @Override
            public boolean reached(BlockPos feet)
            {
                return false;
            }

            @Override
            public double estimate(BlockPos feet)
            {
                return 0.0;
            }
        };
        this.maxNodes = 0;
        this.start = new Node(0L, 0.0, 0.0, null);
        this.best = this.start;
        this.status = Status.NONE;
    }

    /** Kann man mit den Fuessen in diesem Feld stehen? */
    public boolean standable(BlockPos feet)
    {
        return this.passable(feet) && this.passable(feet.above()) && this.floor(feet.below());
    }

    private boolean free2(BlockPos feet)
    {
        return this.passable(feet) && this.passable(feet.above());
    }

    /** Kann der Koerper durch dieses Feld? */
    public boolean passable(BlockPos pos)
    {
        if (!this.level.hasChunkAt(pos))
        {
            return false;
        }

        BlockState state = this.level.getBlockState(pos);

        if (!state.getFluidState().isEmpty() || dangerous(state) || !state.getCollisionShape(this.level, pos).isEmpty())
        {
            return false;
        }

        // Ragt der Block darunter hinein (Zaun, Mauer)? Dann kommt man auch hier nicht durch
        this.m.setWithOffset(pos, Direction.DOWN);
        VoxelShape below = this.level.getBlockState(this.m).getCollisionShape(this.level, this.m);
        return below.isEmpty() || below.max(Direction.Axis.Y) <= 1.0;
    }

    /** Kann man auf diesem Block stehen? */
    private boolean floor(BlockPos pos)
    {
        if (!this.level.hasChunkAt(pos))
        {
            return false;
        }

        BlockState state = this.level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(this.level, pos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0 && shape.max(Direction.Axis.Y) > 0.0 &&
               !(state.getBlock() instanceof MagmaBlock) && !(state.getBlock() instanceof CampfireBlock) && !state.is(Blocks.CACTUS);
    }

    private static boolean dangerous(BlockState state)
    {
        return state.getBlock() instanceof BaseFireBlock || state.getBlock() instanceof WebBlock ||
               state.getBlock() instanceof SweetBerryBushBlock || state.getBlock() instanceof PowderSnowBlock ||
               state.is(Blocks.CACTUS) || state.is(Blocks.WITHER_ROSE);
    }
}
