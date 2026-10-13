package io.github.recher1001.easyplace2.printer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.printer.refill.RefillUtil;
import io.github.recher1001.easyplace2.printer.scaffold.ScaffoldTracker;

/**
 * Baut Bloecke ab wie ein Spieler, z. B. das Geruest, das Baritone gesetzt hat, oder Bloecke, die beim
 * Aushoehlen weg muessen. Welche Bloecke, sagt die {@link Source}.
 * <p>
 * Damit sich der Printer nicht selbst einsperrt oder den Weg abschneidet:
 * <ul>
 *     <li>immer von oben nach unten (der hoechste Block zuerst)</li>
 *     <li>nie den Block unter den eigenen Fuessen, ausser beim Turm-Abstieg (senkrecht runter,
 *         darunter hoechstens 3 Bloecke Luft, also kein Fallschaden)</li>
 *     <li>was nicht erreichbar ist, wird mit Baritone angelaufen; klappt das mehrmals nicht,
 *         wird der Block eine Weile uebersprungen statt es endlos zu versuchen</li>
 * </ul>
 */
public final class BlockDigger
{
    /** Woher die Bloecke kommen, die abgebaut werden sollen. */
    public interface Source
    {
        /** Alle Bloecke, die gerade abgebaut werden sollen (Reihenfolge egal). */
        Iterable<BlockPos> candidates(Level level, Level schematic);

        /** Soll dieser Block (noch) weg? */
        boolean stillWanted(Level level, Level schematic, BlockPos pos);

        /** Der Block ist erledigt (abgebaut oder nicht mehr noetig). */
        void done(BlockPos pos);
    }

    private enum Step { IDLE, CHECK, WALK, BREAK }

    private static final int MAX_ATTEMPTS = 3;
    private static final int SKIP_TICKS = 20 * 60;
    private static final int WALK_TIMEOUT = 20 * 30;
    private static final int MAX_SAFE_DROP = 3;

    private final Source source;
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    private final Map<BlockPos, Long> skipped = new HashMap<>();
    private Step step = Step.IDLE;
    @Nullable private BlockPos target;
    @Nullable private BlockBreaker breaker;
    private int stepTicks;
    private int broken;
    private boolean walkedWithScaffold;

    public BlockDigger(Source source)
    {
        this.source = source;
    }

    public boolean isActive()
    {
        return this.step != Step.IDLE;
    }

    /** Wie viele Bloecke seit dem Start abgebaut wurden. */
    public int broken()
    {
        return this.broken;
    }

    public void reset()
    {
        this.attempts.clear();
        this.skipped.clear();
        this.broken = 0;
        this.step = Step.IDLE;
        this.target = null;
        this.breaker = null;
    }

    /** Uebersprungene Bloecke wieder versuchen (z. B. wenn der Printer festhaengt). */
    public void clearSkipped()
    {
        this.attempts.clear();
        this.skipped.clear();
    }

    public String detail()
    {
        return this.target != null ? this.target.toShortString() : "";
    }

    /**
     * Gibt es Bloecke, die (laut Filter) abgebaut werden sollen und gerade nicht uebersprungen werden?
     */
    public boolean hasWork(Level level, Level schematic, Predicate<BlockPos> filter, long now)
    {
        return !this.candidates(level, schematic, filter, now).isEmpty();
    }

    /** Wie viele Bloecke gerade uebersprungen werden, weil sie nicht erreichbar sind. */
    public int skippedCount(long now)
    {
        this.skipped.values().removeIf(until -> until <= now);
        return this.skipped.size();
    }

    /**
     * Startet das Abbauen des naechsten passenden Blocks.
     *
     * @return true, wenn ein Block gewaehlt wurde
     */
    public boolean start(LocalPlayer player, Level level, Level schematic, Predicate<BlockPos> filter, long now)
    {
        List<BlockPos> list = this.candidates(level, schematic, filter, now);

        if (list.isEmpty())
        {
            return false;
        }

        BlockPos feet = player.blockPosition();
        BlockPos support = feet.below();
        int top = list.get(0).getY();
        BlockPos chosen = null;

        for (BlockPos p : list)
        {
            // Von oben nach unten: nur die oberste Lage
            if (p.getY() < top)
            {
                break;
            }

            if (p.equals(feet) || p.equals(feet.above()))
            {
                // Steckt der Spieler darin (sollte nicht vorkommen), zuerst den Kopf-Block
                chosen = p;
                break;
            }

            if (p.equals(support) && !safeToDropFrom(level, support))
            {
                continue;
            }

            chosen = p;
            break;
        }

        if (chosen == null)
        {
            // Die oberste Lage geht gerade nicht (z. B. nur der Block unter den Fuessen ueber einem Abgrund)
            BlockPos skip = list.get(0);
            this.skipped.put(skip, now + SKIP_TICKS);
            return false;
        }

        this.target = chosen;
        this.breaker = null;
        this.walkedWithScaffold = false;
        this.go(Step.CHECK);
        return true;
    }

    /** @return true, wenn der aktuelle Block erledigt ist (abgebaut oder aufgegeben) */
    public boolean tick(Minecraft mc, long now, boolean canWalk)
    {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        Level schematic = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

        if (player == null || level == null || this.target == null)
        {
            this.finish();
            return true;
        }

        this.stepTicks++;

        switch (this.step)
        {
            case CHECK ->
            {
                if (schematic == null || !this.source.stillWanted(level, schematic, this.target))
                {
                    this.source.done(this.target);
                    this.finish();
                    return true;
                }

                if (!RefillUtil.visiblePoints(player, level, null, this.target, null).isEmpty())
                {
                    this.breaker = new BlockBreaker(this.target);
                    this.go(Step.BREAK);
                }
                else if (canWalk && schematic != null && this.walk(player, level, schematic))
                {
                    this.go(Step.WALK);
                }
                else
                {
                    this.failed(now);
                    return true;
                }
            }
            case WALK ->
            {
                if ((this.stepTicks > 10 && !BaritoneBridge.isBusy()) || this.stepTicks > WALK_TIMEOUT)
                {
                    BaritoneBridge.cancel();
                    BaritoneBridge.setScaffold(false, List.of());

                    if (RefillUtil.visiblePoints(player, level, null, this.target, null).isEmpty())
                    {
                        this.failed(now);
                        return true;
                    }

                    this.go(Step.CHECK);
                }
            }
            case BREAK ->
            {
                BlockBreaker.Result result = this.breaker != null ? this.breaker.tick(mc, player, level) : BlockBreaker.Result.FAILED;

                if (result == BlockBreaker.Result.DONE)
                {
                    this.source.done(this.target);
                    this.attempts.remove(this.target);
                    this.broken++;
                    this.finish();
                    return true;
                }

                if (result == BlockBreaker.Result.FAILED)
                {
                    this.failed(now);
                    return true;
                }
            }
            case IDLE ->
            {
                return true;
            }
        }

        return false;
    }

    public void cancel(Minecraft mc)
    {
        if (this.breaker != null)
        {
            this.breaker.stop(mc);
        }

        if (this.step == Step.WALK)
        {
            BaritoneBridge.cancel();
        }

        this.finish();
    }

    // ---------------------------------------------------------------- intern

    private List<BlockPos> candidates(Level level, Level schematic, Predicate<BlockPos> filter, long now)
    {
        this.skipped.values().removeIf(until -> until <= now);
        List<BlockPos> list = new ArrayList<>();

        for (BlockPos p : this.source.candidates(level, schematic))
        {
            if (level.hasChunkAt(p) && !this.skipped.containsKey(p) && filter.test(p) && this.source.stillWanted(level, schematic, p))
            {
                list.add(p);
            }
        }

        // Hoechster zuerst, dann der naechste
        Vec3 center = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.position() : Vec3.ZERO;
        list.sort(Comparator.<BlockPos>comparingInt(p -> -p.getY())
                .thenComparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(center)));
        return list;
    }

    /** Darf der Block unter den Fuessen weg? Nur wenn man danach hoechstens ein paar Bloecke faellt. */
    private static boolean safeToDropFrom(Level level, BlockPos support)
    {
        for (int i = 1; i <= MAX_SAFE_DROP; i++)
        {
            BlockPos below = support.below(i);

            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty())
            {
                return level.getFluidState(below.above()).isEmpty();
            }
        }

        return false;
    }

    private boolean walk(LocalPlayer player, Level level, Level schematic)
    {
        BlockPos target = this.target;
        double reach = RefillUtil.maxDistance(player);
        List<BlockPos> spots = StandSpots.find(level, schematic, target, reach, player.position(),
                feet -> RefillUtil.visibleFrom(player, feet, level, null, target));

        if (spots.isEmpty())
        {
            return false;
        }

        // Erst ohne neues Geruest; nur wenn das schon einmal nicht geklappt hat, darf Baritone setzen
        int attempt = this.attempts.getOrDefault(target, 0);
        boolean scaffold = attempt >= 1 && Ep2Configs.PRINTER_SCAFFOLD.getBooleanValue();
        BaritoneBridge.setScaffold(scaffold, scaffold ? ScaffoldTracker.INSTANCE.usable() : List.of());
        this.walkedWithScaffold = scaffold;
        AimController.INSTANCE.stop();
        return BaritoneBridge.goToAny(spots);
    }

    private void failed(long now)
    {
        if (this.target != null && this.attempts.merge(this.target, 1, Integer::sum) >= MAX_ATTEMPTS)
        {
            this.attempts.remove(this.target);
            this.skipped.put(this.target, now + SKIP_TICKS);
        }

        this.finish();
    }

    private void finish()
    {
        if (this.walkedWithScaffold)
        {
            BaritoneBridge.setScaffold(false, List.of());
            this.walkedWithScaffold = false;
        }

        this.step = Step.IDLE;
        this.target = null;
        this.breaker = null;
    }

    private void go(Step next)
    {
        this.step = next;
        this.stepTicks = 0;
    }
}
