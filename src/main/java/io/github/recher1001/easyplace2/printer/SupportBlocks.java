package io.github.recher1001.easyplace2.printer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.mixin.MinecraftAccessor;
import io.github.recher1001.easyplace2.placement.EasyPlace2Handler;
import io.github.recher1001.easyplace2.placement.InteractiveBlocks;
import io.github.recher1001.easyplace2.printer.refill.RefillUtil;
import io.github.recher1001.easyplace2.printer.scaffold.ScaffoldTracker;

/**
 * Hilfsbloecke: Soll ein Block frei in der Luft stehen (nichts daneben, an das man ihn setzen koennte), setzt der
 * Printer vorher einen oder ein paar Stuetzbloecke (Geruest-Items, z. B. Erde) bis zu einem vorhandenen Block, setzt
 * den eigentlichen Block daran, und baut die Stuetzen am Ende wieder ab (sie werden wie Geruest gemerkt).
 */
public final class SupportBlocks
{
    public enum Result { RUNNING, DONE, FAILED }

    private static final int MAX_CHAIN = 4;
    private static final int AIM_TIMEOUT = 20 * 3;
    private static final int CONFIRM_TIMEOUT = 20;

    @Nullable private BlockPos cell;
    @Nullable private BlockPos against;
    @Nullable private Vec3 point;
    private int ticks;
    private boolean clicked;

    public boolean isActive()
    {
        return this.cell != null;
    }

    /** Hat {@code pos} einen Nachbarn, an den man etwas setzen kann? */
    public static boolean supported(Level level, BlockPos pos)
    {
        for (Direction dir : Direction.values())
        {
            if (canPlaceAgainst(level, pos.relative(dir)))
            {
                return true;
            }
        }

        return false;
    }

    private static boolean canPlaceAgainst(Level level, BlockPos pos)
    {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.canBeReplaced() && state.getFluidState().isEmpty() && !state.getShape(level, pos).isEmpty();
    }

    /**
     * Der kuerzeste Weg aus Stuetzbloecken von einem vorhandenen Block bis neben {@code target}. Stuetzen kommen
     * nur dorthin, wo die Schematic Luft will und gerade nichts steht.
     *
     * @return die Stuetzen in Setz-Reihenfolge (die erste liegt an einem vorhandenen Block), oder null
     */
    @Nullable
    public static List<BlockPos> chain(LocalPlayer player, Level level, Level schematic, BlockPos target)
    {
        AABB body = player.getBoundingBox().inflate(0.05);
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, Integer> depth = new HashMap<>();

        // Unten zuerst (eine Stuetze darunter ist am natuerlichsten), dann die Seiten, oben zuletzt
        Direction[] order = { Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP };

        for (Direction dir : order)
        {
            BlockPos c = target.relative(dir);

            if (free(level, schematic, c, body))
            {
                parent.put(c, null);
                depth.put(c, 1);
                queue.add(c);
            }
        }

        while (!queue.isEmpty())
        {
            BlockPos c = queue.poll();

            if (supported(level, c))
            {
                List<BlockPos> chain = new ArrayList<>();

                for (BlockPos p = c; p != null; p = parent.get(p))
                {
                    chain.add(p);
                }

                return chain;
            }

            if (depth.get(c) >= MAX_CHAIN)
            {
                continue;
            }

            for (Direction dir : order)
            {
                BlockPos n = c.relative(dir);

                if (!n.equals(target) && !depth.containsKey(n) && free(level, schematic, n, body))
                {
                    parent.put(n, c);
                    depth.put(n, depth.get(c) + 1);
                    queue.add(n);
                }
            }
        }

        return null;
    }

    private static boolean free(Level level, Level schematic, BlockPos pos, AABB body)
    {
        BlockState state = level.getBlockState(pos);
        return (state.isAir() || state.canBeReplaced()) && state.getFluidState().isEmpty() &&
               schematic.getBlockState(pos).isAir() && !body.intersects(new AABB(pos));
    }

    /** Ein Geruest-Item, mit dem Stuetzen gesetzt werden (Erde, Bruchstein ... aus den Einstellungen). */
    @Nullable
    public static Item item(LocalPlayer player)
    {
        for (Item item : ScaffoldTracker.INSTANCE.usable())
        {
            if (InventoryManager.countItem(player, item) > 0)
            {
                return item;
            }
        }

        // Notfalls auch eines, das die Schematic braucht (es kommt beim Abbauen ja zurueck)
        for (Item item : ScaffoldTracker.INSTANCE.items())
        {
            if (InventoryManager.countItem(player, item) > 0)
            {
                return item;
            }
        }

        return null;
    }

    /**
     * Wohin schauen, um {@code cell} zu setzen: auf die Seite eines vorhandenen Nachbarn, die zu {@code cell} zeigt.
     */
    @Nullable
    private static BlockHitResult aim(LocalPlayer player, Level level, BlockPos cell)
    {
        for (Direction dir : Direction.values())
        {
            BlockPos n = cell.relative(dir);

            if (!canPlaceAgainst(level, n) || InteractiveBlocks.isInteractive(level.getBlockState(n).getBlock()))
            {
                continue;
            }

            List<BlockHitResult> points = RefillUtil.visiblePoints(player, level, null, n, dir.getOpposite());

            if (!points.isEmpty())
            {
                return points.get(0);
            }
        }

        return null;
    }

    /** Kann die Stuetze von hier aus gesetzt werden? */
    public static boolean reachable(LocalPlayer player, Level level, BlockPos cell)
    {
        return aim(player, level, cell) != null;
    }

    public boolean start(LocalPlayer player, Level level, BlockPos cell)
    {
        BlockHitResult hit = aim(player, level, cell);

        if (hit == null)
        {
            return false;
        }

        this.cell = cell.immutable();
        this.against = hit.getBlockPos().immutable();
        this.point = hit.getLocation();
        this.ticks = 0;
        this.clicked = false;
        return true;
    }

    public Result tick(Minecraft mc)
    {
        LocalPlayer player = mc.player;
        Level level = mc.level;

        if (player == null || level == null || this.cell == null || this.point == null)
        {
            this.cancel();
            return Result.FAILED;
        }

        this.ticks++;

        if (this.clicked)
        {
            if (canPlaceAgainst(level, this.cell))
            {
                // Merken, damit die Stuetze am Ende wieder abgebaut wird
                ScaffoldTracker.INSTANCE.add(this.cell);
                this.cancel();
                return Result.DONE;
            }

            if (this.ticks > CONFIRM_TIMEOUT)
            {
                this.cancel();
                return Result.FAILED;
            }

            return Result.RUNNING;
        }

        AimController.INSTANCE.aimAt(this.point, Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());

        if (AimController.INSTANCE.isOnTarget() && this.ticks > 1 && this.aimedRight(player, level))
        {
            AimController.INSTANCE.stop();
            // Ganz normaler Rechtsklick: Easy Place 2.0 wuerde ihn sonst sperren (Stuetzen stehen nicht in der Schematic)
            EasyPlace2Handler.runVanilla(((MinecraftAccessor) mc)::easyplace2$startUseItem);
            this.clicked = true;
            this.ticks = 0;
            return Result.RUNNING;
        }

        if (this.ticks > AIM_TIMEOUT)
        {
            this.cancel();
            return Result.FAILED;
        }

        return Result.RUNNING;
    }

    private boolean aimedRight(LocalPlayer player, Level level)
    {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(RefillUtil.maxDistance(player) + 0.5));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(this.against) &&
               hit.getBlockPos().relative(hit.getDirection()).equals(this.cell);
    }

    public void cancel()
    {
        this.cell = null;
        this.against = null;
        this.point = null;
        AimController.INSTANCE.stop();
    }

    @Nullable
    public BlockPos cell()
    {
        return this.cell;
    }
}
