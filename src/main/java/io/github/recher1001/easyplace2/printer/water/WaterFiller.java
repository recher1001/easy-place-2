package io.github.recher1001.easyplace2.printer.water;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;

import io.github.recher1001.easyplace2.EasyPlace2;
import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.mixin.MinecraftAccessor;
import io.github.recher1001.easyplace2.placement.EasyPlace2Handler;
import io.github.recher1001.easyplace2.placement.InteractiveBlocks;
import io.github.recher1001.easyplace2.printer.AimController;
import io.github.recher1001.easyplace2.printer.BaritoneBridge;
import io.github.recher1001.easyplace2.printer.BlockBreaker;
import io.github.recher1001.easyplace2.printer.Excavation;
import io.github.recher1001.easyplace2.printer.InventoryManager;
import io.github.recher1001.easyplace2.printer.StandSpots;
import io.github.recher1001.easyplace2.printer.refill.RefillUtil;

/**
 * Setzt das Wasser der Schematic mit Eimern, wie ein Spieler, und zwar sicher und sparsam:
 * <ul>
 *     <li><b>Nie auslaufen:</b> Ein Wasser-Bereich wird erst gefuellt, wenn er rundherum und unten dicht ist
 *         (jeder Nachbar, in den Wasser fliessen koennte, ist schon ein fester Block). Sonst koennte Wasser
 *         herauslaufen und z. B. Redstone wegspuelen; solches Wasser bleibt ungesetzt und wird gemeldet.</li>
 *     <li><b>Von unten nach oben</b>, Lage fuer Lage.</li>
 *     <li><b>Unendliche Quellen nutzen:</b> Ein Feld mit zwei Wasserquellen daneben (und festem Boden) wird
 *         von selbst zur Quelle. Der Printer waehlt die Stellen so, dass davon moeglichst viel passiert, und
 *         beginnt mit zwei Eimern diagonal, damit gleich eine unendliche Quelle entsteht. Fuer eine grosse
 *         Wasserflaeche reichen so wenige Eimer.</li>
 *     <li><b>Nachfuellen</b> nur an unendlichen Quellen (im Bereich selbst, in der Naehe, oder in einer
 *         2x2-Quelle, die der Printer dafuer direkt neben dem Bereich anlegt).</li>
 * </ul>
 */
public final class WaterFiller
{
    public enum Step { IDLE, AIM_PLACE, CONFIRM_PLACE, AIM_FILL, CONFIRM_FILL, DIG, WALK }

    /** Warum gerade nichts geht (fuer Anzeige und Abschlussmeldung). */
    public enum Problem { NONE, NO_BUCKET, NO_SOURCE, OUT_OF_REACH }

    private static final int BODY_LIMIT = 40_000;
    private static final int PLAN_TICKS = 20;
    private static final int AIM_TIMEOUT = 20 * 3;
    private static final int CONFIRM_TIMEOUT = 20;
    private static final int WALK_TIMEOUT = 20 * 30;
    private static final int SEARCH_RADIUS = 24;
    private static final long FAIL_COOLDOWN = 20 * 15;

    private final InventoryManager inventory;

    private List<BlockPos> targets = List.of();
    private Plan plan = Plan.EMPTY;
    private long planTick = Long.MIN_VALUE;
    private final Map<BlockPos, Long> failed = new HashMap<>();

    private Step step = Step.IDLE;
    private int stepTicks;
    @Nullable private BlockPos target;
    @Nullable private BlockPos neighbor;
    @Nullable private Vec3 point;
    private int bucketsBefore;
    @Nullable private BlockPos forcedPartner;
    /** Selbst angelegte 2x2-Quelle: erst abbauen, dann zwei Eimer diagonal hinein. */
    private final List<BlockPos> siteDig = new ArrayList<>();
    private final List<BlockPos> sitePour = new ArrayList<>();
    @Nullable private BlockBreaker breaker;
    private Problem problem = Problem.NONE;
    private boolean settling;
    private int placed;
    private String detail = "";

    /** Ein zusammenhaengender Wasser-Bereich der Schematic. */
    private record Body(Set<BlockPos> cells, boolean sealed, List<BlockPos> targets) {}

    private record Plan(List<Body> bodies, int blocked)
    {
        static final Plan EMPTY = new Plan(List.of(), 0);
    }

    public WaterFiller(InventoryManager inventory)
    {
        this.inventory = inventory;
    }

    // ---------------------------------------------------------------- Zustand nach aussen

    public void update(List<BlockPos> targets)
    {
        if (targets.size() != this.targets.size() || !targets.equals(this.targets))
        {
            this.targets = targets;
            this.planTick = Long.MIN_VALUE;
        }
    }

    public void reset()
    {
        this.targets = List.of();
        this.plan = Plan.EMPTY;
        this.planTick = Long.MIN_VALUE;
        this.failed.clear();
        this.siteDig.clear();
        this.sitePour.clear();
        this.forcedPartner = null;
        this.problem = Problem.NONE;
        this.settling = false;
        this.placed = 0;
        this.go(Step.IDLE);
    }

    public boolean isActive()
    {
        return this.step != Step.IDLE;
    }

    /** Muss der Printer noch auf Wasser warten (es breitet sich gerade aus)? */
    public boolean isSettling()
    {
        return this.settling;
    }

    /** Wasser, das nicht gesetzt wird, weil es auslaufen wuerde. */
    public int blockedCount()
    {
        return this.plan.blocked();
    }

    /** Wasser, das noch fehlt (gesetzt werden koennte). */
    public int remainingCount()
    {
        return this.plan.bodies().stream().filter(Body::sealed).mapToInt(b -> b.targets().size()).sum();
    }

    public Problem problem()
    {
        return this.problem;
    }

    public int placed()
    {
        return this.placed;
    }

    public String detail()
    {
        return this.detail;
    }

    /** Braucht dieser Block Wasser, um gesetzt zu werden (Seerose, Seegras ...)? */
    public static boolean needsWater(BlockState state)
    {
        return state.is(Blocks.LILY_PAD) || state.is(Blocks.FROGSPAWN) ||
               (!state.getFluidState().isEmpty() && !(state.getBlock() instanceof net.minecraft.world.level.block.SimpleWaterloggedBlock));
    }

    public void cancel(Minecraft mc)
    {
        if (this.breaker != null)
        {
            this.breaker.stop(mc);
            this.breaker = null;
        }

        if (this.step == Step.WALK)
        {
            BaritoneBridge.cancel();
        }

        AimController.INSTANCE.stop();
        this.go(Step.IDLE);
    }

    // ---------------------------------------------------------------- Planen

    /**
     * Sucht den naechsten Schritt.
     *
     * @return true, wenn etwas zu tun ist (dann {@link #tick} aufrufen, ausser es wird gerade ein Item geholt)
     */
    public boolean start(Minecraft mc, LocalPlayer player, ClientLevel level, Level schematic, long now, boolean canWalk)
    {
        this.go(Step.IDLE);
        this.settling = false;
        this.problem = Problem.NONE;
        this.failed.values().removeIf(until -> until <= now);

        if (this.targets.isEmpty() && this.siteDig.isEmpty() && this.sitePour.isEmpty())
        {
            this.plan = Plan.EMPTY;
            return false;
        }

        if (now - this.planTick >= PLAN_TICKS)
        {
            this.plan = this.computePlan(level, schematic);
            this.planTick = now;
        }

        int water = InventoryManager.countItem(player, Items.WATER_BUCKET);
        int empty = InventoryManager.countItem(player, Items.BUCKET);

        // Angefangene eigene Quelle zuerst fertig machen
        if (!this.siteDig.isEmpty())
        {
            return this.startSiteDig(player, level);
        }

        if (!this.sitePour.isEmpty())
        {
            this.sitePour.removeIf(p -> isWaterSource(level, p));

            if (!this.sitePour.isEmpty() && water > 0)
            {
                BlockPos cell = this.sitePour.get(0);
                return this.startPlace(player, level, cell, water);
            }

            this.sitePour.clear();
        }

        List<BlockPos> layer = this.nextLayer(player, level);

        if (layer.isEmpty())
        {
            return false;
        }

        if (water == 0 && empty == 0)
        {
            this.problem = Problem.NO_BUCKET;
            this.detail = "no_bucket";
            return false;
        }

        // Was ohnehin von selbst zur Quelle wird, braucht keinen Eimer
        Set<BlockPos> layerSet = new HashSet<>(layer);
        Set<BlockPos> sources = sourcesAround(level, layerSet);
        Set<BlockPos> willFill = closure(level, layerSet, sources, null);
        List<BlockPos> notFilling = layer.stream().filter(p -> !willFill.contains(p)).toList();
        List<BlockPos> open = notFilling.stream().filter(p -> !this.failed.containsKey(p)).toList();

        if (open.isEmpty())
        {
            // Wasser breitet sich noch aus, oder ein Feld wird nach einer kurzen Pause noch einmal versucht
            this.settling = !willFill.isEmpty() || !notFilling.isEmpty();
            this.detail = "settling";
            return false;
        }

        if (water == 0)
        {
            boolean ok = this.startRefill(player, level, schematic, canWalk);

            // Gerade entsteht vielleicht erst die unendliche Quelle (zwei Eimer diagonal): kurz abwarten
            if (!ok && !willFill.isEmpty())
            {
                this.settling = true;
                this.problem = Problem.NONE;
            }

            return ok;
        }

        int remaining = this.remainingCount();
        boolean infinite = this.findInfinite(level, player, SEARCH_RADIUS, false) != null;

        // Mit den letzten zwei Eimern und ohne unendliche Quelle: erst eine schaffen
        if (water == 1 && this.forcedPartner != null && open.contains(this.forcedPartner))
        {
            BlockPos partner = this.forcedPartner;
            this.forcedPartner = null;

            if (this.startPlace(player, level, partner, water))
            {
                return true;
            }
        }

        this.forcedPartner = null;

        if (water == 2 && !infinite && remaining > 2)
        {
            BlockPos[] pair = this.diagonalPair(player, level, layerSet, open);

            if (pair != null)
            {
                this.forcedPartner = pair[1];
                return this.startPlace(player, level, pair[0], water);
            }

            if (Ep2Configs.PRINTER_WATER_MAKE_SOURCE.getBooleanValue() && this.planSite(player, level))
            {
                return this.startSiteDig(player, level);
            }
        }

        BlockPos best = this.bestCell(player, level, layerSet, sources, open);

        if (best != null)
        {
            return this.startPlace(player, level, best, water);
        }

        // Nichts in Reichweite: hinlaufen
        if (canWalk && this.walkNear(player, level, open))
        {
            return true;
        }

        this.problem = Problem.OUT_OF_REACH;
        this.detail = "out_of_reach";
        return false;
    }

    /** Die unterste noch offene Lage des naechsten dichten Bereichs. */
    private List<BlockPos> nextLayer(LocalPlayer player, Level level)
    {
        Vec3 eye = player.getEyePosition();
        List<BlockPos> best = List.of();
        double bestDist = Double.MAX_VALUE;

        for (Body body : this.plan.bodies())
        {
            if (!body.sealed())
            {
                continue;
            }

            List<BlockPos> open = body.targets().stream().filter(p -> !isWaterSource(level, p) && canHoldWater(level, p)).toList();

            if (open.isEmpty())
            {
                continue;
            }

            int lowest = open.stream().mapToInt(BlockPos::getY).min().getAsInt();
            List<BlockPos> layer = open.stream().filter(p -> p.getY() == lowest).toList();
            double dist = layer.stream().mapToDouble(p -> p.distToCenterSqr(eye)).min().orElse(Double.MAX_VALUE);

            if (dist < bestDist)
            {
                bestDist = dist;
                best = layer;
            }
        }

        return best;
    }

    private Plan computePlan(Level level, Level schematic)
    {
        List<Body> bodies = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        int blocked = 0;

        for (BlockPos start : this.targets)
        {
            if (seen.contains(start))
            {
                continue;
            }

            Set<BlockPos> cells = new HashSet<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            cells.add(start);

            while (!queue.isEmpty() && cells.size() < BODY_LIMIT)
            {
                BlockPos p = queue.poll();

                for (Direction dir : Direction.values())
                {
                    BlockPos n = p.relative(dir);

                    if (!cells.contains(n) && schematic.getBlockState(n).is(Blocks.WATER))
                    {
                        cells.add(n);
                        queue.add(n);
                    }
                }
            }

            seen.addAll(cells);
            boolean sealed = cells.size() < BODY_LIMIT && isSealed(level, cells);
            List<BlockPos> bodyTargets = this.targets.stream().filter(cells::contains).toList();

            if (!sealed)
            {
                blocked += bodyTargets.size();
            }

            bodies.add(new Body(cells, sealed, bodyTargets));
        }

        if (blocked > 0 && Ep2Configs.DEBUG.getBooleanValue())
        {
            EasyPlace2.LOGGER.info("[Printer] {} Wasser nicht dicht (wuerde auslaufen)", blocked);
        }

        return new Plan(bodies, blocked);
    }

    /** Kann aus dem Bereich nirgends Wasser herauslaufen (zur Seite oder nach unten)? */
    private static boolean isSealed(Level level, Set<BlockPos> cells)
    {
        for (BlockPos p : cells)
        {
            for (Direction dir : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN })
            {
                BlockPos n = p.relative(dir);

                if (cells.contains(n))
                {
                    continue;
                }

                if (!level.hasChunkAt(n) || !blocksWater(level, n))
                {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Haelt dieser Block fliessendes Wasser auf? (Wie Minecraft: Wasser fliesst in Luft, Gras, Fackeln,
     * Redstone usw., aber nicht in feste Bloecke, Tueren, Schilder, Leitern und wasserloggbare Bloecke.)
     */
    public static boolean blocksWater(Level level, BlockPos pos)
    {
        BlockState state = level.getBlockState(pos);
        FluidState fluid = state.getFluidState();

        if (!fluid.isEmpty())
        {
            // Anderes Wasser ist kein Problem, Lava dagegen schon
            return fluid.is(net.minecraft.tags.FluidTags.WATER);
        }

        if (state.isAir())
        {
            return false;
        }

        return state.getBlock() instanceof LiquidBlockContainer || state.getBlock() instanceof DoorBlock || state.is(BlockTags.SIGNS) ||
               state.is(Blocks.LADDER) || state.is(Blocks.SUGAR_CANE) || state.is(Blocks.NETHER_PORTAL) || state.blocksMotion();
    }

    private static boolean isWaterSource(Level level, BlockPos pos)
    {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.WATER) && state.getFluidState().isSource();
    }

    /** Zaehlt fuer das "Zusammenwachsen" als Quelle (auch wassergeloggte Bloecke). */
    private static boolean countsAsSource(Level level, BlockPos pos)
    {
        FluidState fluid = level.getFluidState(pos);
        return fluid.getType() == Fluids.WATER && fluid.isSource();
    }

    /** Darunter fest oder Wasserquelle: nur dann wird ein Feld zwischen zwei Quellen selbst zur Quelle. */
    private static boolean supports(Level level, BlockPos below)
    {
        return countsAsSource(level, below) || level.getBlockState(below).blocksMotion();
    }

    private static boolean canHoldWater(Level level, BlockPos pos)
    {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.is(Blocks.WATER) || state.canBeReplaced();
    }

    private static Set<BlockPos> sourcesAround(Level level, Set<BlockPos> layer)
    {
        Set<BlockPos> sources = new HashSet<>();

        for (BlockPos p : layer)
        {
            for (Direction dir : Direction.Plane.HORIZONTAL)
            {
                BlockPos n = p.relative(dir);

                if (countsAsSource(level, n))
                {
                    sources.add(n);
                }
            }
        }

        return sources;
    }

    /**
     * Welche Felder der Lage werden von selbst zur Quelle (jeweils zwei Quellen daneben, fester Boden)?
     *
     * @param extra zusaetzlich gedachte Quelle (oder null)
     */
    private static Set<BlockPos> closure(Level level, Set<BlockPos> layer, Set<BlockPos> sources, @Nullable BlockPos extra)
    {
        Set<BlockPos> s = new HashSet<>(sources);
        Set<BlockPos> result = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        if (extra != null)
        {
            s.add(extra);

            for (Direction dir : Direction.Plane.HORIZONTAL)
            {
                queue.add(extra.relative(dir));
            }
        }
        else
        {
            queue.addAll(layer);
        }

        while (!queue.isEmpty())
        {
            BlockPos p = queue.poll();

            if (!layer.contains(p) || s.contains(p) || !supports(level, p.below()))
            {
                continue;
            }

            int count = 0;

            for (Direction dir : Direction.Plane.HORIZONTAL)
            {
                if (s.contains(p.relative(dir)))
                {
                    count++;
                }
            }

            if (count >= 2)
            {
                s.add(p);
                result.add(p);

                for (Direction dir : Direction.Plane.HORIZONTAL)
                {
                    queue.add(p.relative(dir));
                }
            }
        }

        return result;
    }

    /** Das Feld, bei dem ein Eimer am meisten Wasser bringt (inklusive allem, was danach von selbst entsteht). */
    @Nullable
    private BlockPos bestCell(LocalPlayer player, Level level, Set<BlockPos> layer, Set<BlockPos> sources, List<BlockPos> open)
    {
        Set<BlockPos> base = new HashSet<>(sources);
        base.addAll(closure(level, layer, sources, null));
        Vec3 eye = player.getEyePosition();
        double reach = RefillUtil.maxDistance(player) + 1.0;

        record Scored(BlockPos pos, double score) {}
        List<Scored> scored = new ArrayList<>();

        for (BlockPos p : open)
        {
            if (Vec3.atCenterOf(p).distanceTo(eye) > reach)
            {
                continue;
            }

            int gain = closure(level, layer, base, p).size();
            int walls = 0;

            for (Direction dir : Direction.Plane.HORIZONTAL)
            {
                if (!layer.contains(p.relative(dir)))
                {
                    walls++;
                }
            }

            scored.add(new Scored(p, gain * 100.0 + walls * 3.0 - p.distToCenterSqr(eye) * 0.05));
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        for (int i = 0; i < Math.min(24, scored.size()); i++)
        {
            if (placeAim(player, level, scored.get(i).pos()) != null)
            {
                return scored.get(i).pos();
            }
        }

        return null;
    }

    /** Zwei diagonale Felder eines 2x2 mit festem Boden: daraus wird sofort eine unendliche Quelle. */
    @Nullable
    private BlockPos[] diagonalPair(LocalPlayer player, Level level, Set<BlockPos> layer, List<BlockPos> open)
    {
        Set<BlockPos> openSet = new HashSet<>(open);
        Vec3 eye = player.getEyePosition();
        List<BlockPos> sorted = new ArrayList<>(open);
        sorted.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(eye)));

        for (BlockPos a : sorted)
        {
            for (int[] d : new int[][] { { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } })
            {
                BlockPos b = a.offset(d[0], 0, d[1]);
                BlockPos c1 = a.offset(d[0], 0, 0);
                BlockPos c2 = a.offset(0, 0, d[1]);

                if (openSet.contains(b) && layer.contains(c1) && layer.contains(c2) &&
                    supports(level, a.below()) && supports(level, b.below()) && supports(level, c1.below()) && supports(level, c2.below()) &&
                    placeAim(player, level, a) != null && placeAim(player, level, b) != null)
                {
                    return new BlockPos[] { a, b };
                }
            }
        }

        return null;
    }

    // ---------------------------------------------------------------- Wasser setzen

    private record Aim(BlockPos neighbor, Direction face, Vec3 point) {}

    /** Wohin schauen, damit der Eimer genau in {@code cell} geleert wird (auf eine feste Nachbarflaeche). */
    @Nullable
    private static Aim placeAim(LocalPlayer player, Level level, BlockPos cell)
    {
        for (Direction dir : new Direction[] { Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST })
        {
            BlockPos n = cell.relative(dir);
            BlockState state = level.getBlockState(n);

            // Nicht in einen wasserloggbaren Block (der wuerde das Wasser schlucken) und nichts, was man benutzt
            if (!state.blocksMotion() || !state.getFluidState().isEmpty() || state.getBlock() instanceof LiquidBlockContainer ||
                InteractiveBlocks.isInteractive(state.getBlock()) || state.getShape(level, n).isEmpty())
            {
                continue;
            }

            Direction face = dir.getOpposite();
            List<BlockHitResult> points = RefillUtil.visiblePoints(player, level, null, n, face);

            if (!points.isEmpty())
            {
                return new Aim(n, face, points.get(0).getLocation());
            }
        }

        return null;
    }

    private boolean startPlace(LocalPlayer player, Level level, BlockPos cell, int water)
    {
        Aim aim = placeAim(player, level, cell);

        if (aim == null)
        {
            this.failed.put(cell, level.getGameTime() + FAIL_COOLDOWN);
            return false;
        }

        if (!player.getMainHandItem().is(Items.WATER_BUCKET))
        {
            return this.request(player, Items.WATER_BUCKET);
        }

        this.target = cell;
        this.neighbor = aim.neighbor();
        this.point = aim.point();
        this.bucketsBefore = water;
        this.detail = cell.toShortString();
        this.go(Step.AIM_PLACE);
        return true;
    }

    private boolean request(LocalPlayer player, Item item)
    {
        // Wird vom Printer wie jedes andere Item-Holen abgearbeitet; danach plant er neu
        this.detail = "fetch";
        return this.inventory.request(player, item, Set.of(Items.WATER_BUCKET, Items.BUCKET));
    }

    // ---------------------------------------------------------------- Nachfuellen

    /** Eine unendliche Quelle (zwei Quellen daneben, fester Boden), die nach dem Schoepfen sofort nachfliesst. */
    private static boolean isInfinite(Level level, BlockPos pos)
    {
        if (!isWaterSource(level, pos) || !supports(level, pos.below()))
        {
            return false;
        }

        int count = 0;

        for (Direction dir : Direction.Plane.HORIZONTAL)
        {
            if (countsAsSource(level, pos.relative(dir)))
            {
                count++;
            }
        }

        return count >= 2;
    }

    @Nullable
    private BlockPos findInfinite(Level level, LocalPlayer player, int radius, boolean inReach)
    {
        BlockPos feet = player.blockPosition();
        Vec3 eye = player.getEyePosition();
        double reach = RefillUtil.maxDistance(player);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int dyRange = inReach ? 5 : 6;

        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-radius, -dyRange, -radius), feet.offset(radius, dyRange, radius)))
        {
            double dist = p.distToCenterSqr(eye);

            if (dist >= bestDist || (inReach && Math.sqrt(dist) > reach + 1.0) || this.failed.containsKey(p) || !isInfinite(level, p))
            {
                continue;
            }

            if (inReach && fillPoint(player, level, p) == null)
            {
                continue;
            }

            best = p.immutable();
            bestDist = dist;
        }

        return best;
    }

    /** Punkt auf der Wasseroberflaeche, den der Eimer-Strahl (nur Quellen) als erstes trifft. */
    @Nullable
    private static Vec3 fillPoint(LocalPlayer player, Level level, BlockPos source)
    {
        Vec3 eye = player.getEyePosition();
        double reach = RefillUtil.maxDistance(player);

        for (double[] o : new double[][] { { 0.5, 0.5 }, { 0.3, 0.3 }, { 0.7, 0.7 }, { 0.3, 0.7 }, { 0.7, 0.3 } })
        {
            Vec3 point = new Vec3(source.getX() + o[0], source.getY() + 0.8, source.getZ() + o[1]);

            if (point.distanceTo(eye) > reach)
            {
                continue;
            }

            Vec3 beyond = eye.add(point.subtract(eye).scale(1.05));
            BlockHitResult hit = level.clip(new ClipContext(eye, beyond, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));

            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(source))
            {
                return hit.getLocation();
            }
        }

        return null;
    }

    private boolean startRefill(LocalPlayer player, Level level, Level schematic, boolean canWalk)
    {
        BlockPos source = this.findInfinite(level, player, 6, true);

        if (source != null)
        {
            if (!player.getMainHandItem().is(Items.BUCKET))
            {
                return this.request(player, Items.BUCKET);
            }

            this.target = source;
            this.point = fillPoint(player, level, source);
            this.bucketsBefore = InventoryManager.countItem(player, Items.WATER_BUCKET);
            this.detail = "refill";
            this.go(Step.AIM_FILL);
            return true;
        }

        BlockPos far = this.findInfinite(level, player, SEARCH_RADIUS, false);

        if (far != null && canWalk && Ep2Configs.PRINTER_USE_BARITONE.getBooleanValue() && BaritoneBridge.isAvailable())
        {
            List<BlockPos> spots = StandSpots.find(level, schematic, far, RefillUtil.maxDistance(player), player.position(),
                    feet -> level.getFluidState(feet).isEmpty());

            if (!spots.isEmpty() && BaritoneBridge.goToAny(spots.stream()
                    .sorted(Comparator.comparingDouble(p -> p.distSqr(player.blockPosition()))).toList()))
            {
                this.detail = "walk_source";
                this.go(Step.WALK);
                return true;
            }
        }

        this.problem = Problem.NO_SOURCE;
        this.detail = "no_source";
        return false;
    }

    private boolean walkNear(LocalPlayer player, Level level, List<BlockPos> open)
    {
        if (!Ep2Configs.PRINTER_USE_BARITONE.getBooleanValue() || !BaritoneBridge.isAvailable())
        {
            return false;
        }

        Level schematic = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        BlockPos nearest = open.stream().min(Comparator.comparingDouble(p -> p.distSqr(player.blockPosition()))).orElse(null);

        if (nearest == null || schematic == null)
        {
            return false;
        }

        List<BlockPos> spots = StandSpots.find(level, schematic, nearest, RefillUtil.maxDistance(player), player.position(),
                feet -> !open.contains(feet) && level.getFluidState(feet).isEmpty());

        if (!spots.isEmpty() && BaritoneBridge.goToAny(spots.stream()
                .sorted(Comparator.comparingDouble(p -> p.distSqr(player.blockPosition()))).toList()))
        {
            this.detail = "walk";
            this.go(Step.WALK);
            return true;
        }

        return false;
    }

    // ---------------------------------------------------------------- Eigene unendliche Quelle

    /**
     * Sucht direkt neben dem Bereich (nie darin) ein 2x2-Feld im festen Boden, das ausgehoben werden kann:
     * rundherum und darunter fest, darueber frei. Mit zwei Eimern diagonal wird es zur unendlichen Quelle.
     */
    private boolean planSite(LocalPlayer player, Level level)
    {
        BlockPos feet = player.blockPosition();
        Vec3 eye = player.getEyePosition();
        AABB body = player.getBoundingBox().inflate(0.3);
        List<AABB> regions = regions(2);
        List<BlockPos> best = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos c : BlockPos.betweenClosed(feet.offset(-4, -3, -4), feet.offset(4, 1, 4)))
        {
            BlockPos corner = c.immutable();
            List<BlockPos> cells = List.of(corner, corner.east(), corner.south(), corner.east().south());
            double dist = Vec3.atCenterOf(corner).distanceToSqr(eye);

            if (dist >= bestDist || !this.siteOk(player, level, cells, regions, body))
            {
                continue;
            }

            best = cells;
            bestDist = dist;
        }

        if (best == null)
        {
            return false;
        }

        this.siteDig.clear();
        this.siteDig.addAll(best);
        this.sitePour.clear();
        this.sitePour.add(best.get(0));
        this.sitePour.add(best.get(3));
        EasyPlace2.LOGGER.info("[Printer] Lege eine unendliche Wasserquelle bei {} an", best.get(0).toShortString());
        return true;
    }

    private boolean siteOk(LocalPlayer player, Level level, List<BlockPos> cells, List<AABB> regions, AABB body)
    {
        Set<BlockPos> set = new HashSet<>(cells);

        for (BlockPos p : cells)
        {
            BlockState state = level.getBlockState(p);
            float speed = state.getDestroySpeed(level, p);

            if (!state.isCollisionShapeFullBlock(level, p) || state.hasBlockEntity() || speed < 0 || speed > 2.0f ||
                !Excavation.canDig(level, p, state) || !level.getBlockState(p.above()).isAir() ||
                !level.getBlockState(p.below()).blocksMotion() || body.intersects(new AABB(p).expandTowards(0, 2, 0)))
            {
                return false;
            }

            for (AABB region : regions)
            {
                if (region.intersects(new AABB(p)))
                {
                    return false;
                }
            }

            for (Direction dir : Direction.Plane.HORIZONTAL)
            {
                BlockPos n = p.relative(dir);

                if (!set.contains(n) && !blocksWater(level, n))
                {
                    return false;
                }
            }

            if (RefillUtil.visiblePoints(player, level, null, p, Direction.UP).isEmpty())
            {
                return false;
            }
        }

        return true;
    }

    /** Die Bereiche aller Schematics, um {@code margin} Bloecke vergroessert. */
    private static List<AABB> regions(int margin)
    {
        List<AABB> list = new ArrayList<>();

        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            if (!placement.isEnabled())
            {
                continue;
            }

            for (Box box : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values())
            {
                if (box.getPos1() != null && box.getPos2() != null)
                {
                    list.add(new AABB(Vec3.atLowerCornerOf(box.getPos1()), Vec3.atLowerCornerOf(box.getPos2()))
                            .expandTowards(1, 1, 1).inflate(margin));
                }
            }
        }

        return list;
    }

    private boolean startSiteDig(LocalPlayer player, Level level)
    {
        this.siteDig.removeIf(p -> level.getBlockState(p).isAir() || level.getBlockState(p).canBeReplaced());

        if (this.siteDig.isEmpty())
        {
            // Ausgehoben: jetzt die zwei Eimer hinein
            int water = InventoryManager.countItem(player, Items.WATER_BUCKET);
            return !this.sitePour.isEmpty() && water > 0 && this.startPlace(player, level, this.sitePour.get(0), water);
        }

        this.breaker = new BlockBreaker(this.siteDig.get(0));
        this.detail = "make_source";
        this.go(Step.DIG);
        return true;
    }

    // ---------------------------------------------------------------- Ausfuehren

    /** @return true, wenn der Schritt fertig ist (geklappt oder nicht) */
    public boolean tick(Minecraft mc, long now)
    {
        LocalPlayer player = mc.player;
        Level level = mc.level;

        if (player == null || level == null)
        {
            this.cancel(mc);
            return true;
        }

        this.stepTicks++;

        switch (this.step)
        {
            case IDLE ->
            {
                return true;
            }
            case AIM_PLACE, AIM_FILL ->
            {
                if (this.point == null || this.target == null)
                {
                    this.go(Step.IDLE);
                    return true;
                }

                boolean fill = this.step == Step.AIM_FILL;
                Item needed = fill ? Items.BUCKET : Items.WATER_BUCKET;

                if (!player.getMainHandItem().is(needed))
                {
                    this.go(Step.IDLE);
                    return true;
                }

                AimController.INSTANCE.aimAt(this.point, Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());

                if (AimController.INSTANCE.isOnTarget() && this.stepTicks > 1 && this.aimedRight(mc, player, level, fill))
                {
                    AimController.INSTANCE.stop();
                    // Normaler Rechtsklick mit dem Eimer; Easy Place 2.0 soll sich da nicht einmischen
                    EasyPlace2Handler.runVanilla(((MinecraftAccessor) mc)::easyplace2$startUseItem);
                    this.go(fill ? Step.CONFIRM_FILL : Step.CONFIRM_PLACE);
                }
                else if (this.stepTicks > AIM_TIMEOUT)
                {
                    this.fail(now);
                    return true;
                }
            }
            case CONFIRM_PLACE ->
            {
                if (this.target != null && isWaterSource(level, this.target))
                {
                    this.placed++;
                    this.planTick = Long.MIN_VALUE;
                    this.go(Step.IDLE);
                    return true;
                }

                if (this.stepTicks > CONFIRM_TIMEOUT)
                {
                    this.fail(now);
                    return true;
                }
            }
            case CONFIRM_FILL ->
            {
                if (InventoryManager.countItem(player, Items.WATER_BUCKET) > this.bucketsBefore)
                {
                    this.go(Step.IDLE);
                    return true;
                }

                if (this.stepTicks > CONFIRM_TIMEOUT)
                {
                    this.fail(now);
                    return true;
                }
            }
            case DIG ->
            {
                if (this.breaker == null)
                {
                    this.go(Step.IDLE);
                    return true;
                }

                BlockBreaker.Result result = this.breaker.tick(mc, player, level);

                if (result != BlockBreaker.Result.RUNNING)
                {
                    BlockPos dug = this.breaker.pos();
                    this.breaker = null;
                    this.siteDig.remove(dug);

                    if (result == BlockBreaker.Result.FAILED)
                    {
                        // Geht nicht: diese Stelle aufgeben
                        this.siteDig.clear();
                        this.sitePour.clear();
                    }

                    this.go(Step.IDLE);
                    return true;
                }
            }
            case WALK ->
            {
                if ((this.stepTicks > 10 && !BaritoneBridge.isBusy()) || this.stepTicks > WALK_TIMEOUT)
                {
                    BaritoneBridge.cancel();
                    this.go(Step.IDLE);
                    return true;
                }
            }
        }

        return false;
    }

    /** Liegt der Blick so, dass der Server den Eimer genau dort benutzt, wo er soll? */
    private boolean aimedRight(Minecraft mc, LocalPlayer player, Level level, boolean fill)
    {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(RefillUtil.maxDistance(player) + 0.5));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                fill ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, player));

        if (hit.getType() != HitResult.Type.BLOCK)
        {
            return false;
        }

        if (fill)
        {
            return isInfinite(level, hit.getBlockPos());
        }

        return hit.getBlockPos().equals(this.neighbor) && hit.getBlockPos().relative(hit.getDirection()).equals(this.target);
    }

    private void fail(long now)
    {
        if (this.target != null)
        {
            this.failed.put(this.target, now + FAIL_COOLDOWN);
        }

        AimController.INSTANCE.stop();
        this.go(Step.IDLE);
    }

    private void go(Step step)
    {
        this.step = step;
        this.stepTicks = 0;
    }
}
