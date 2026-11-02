package io.github.recher1001.easyplace2.printer.lanes;

import java.util.List;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.printer.AimController;
import io.github.recher1001.easyplace2.printer.BlockBreaker;

/**
 * Tunnel mit der Star-Spitzhacke (3x3), so wie ein Spieler es macht: geradeaus schauen, auf den mittleren Block
 * der Wand vor sich schlagen (der Server baut die ganze 3x3-Wand ab), einen Schritt vorgehen, wieder schlagen,
 * bis zum Ende der Bahn.
 * <p>
 * Kein Umherschauen und kein Neuplanen nach jedem Block. Hoert auf, wenn die Bahn zu Ende ist, wenn in der
 * 3x3-Wand etwas stehen bleiben soll (dann baut der normale Modus dort Block fuer Block ab) oder wenn es nicht
 * weitergeht.
 */
public final class StarTunnel
{
    public enum Result { RUNNING, DONE, FAILED }

    private static final int HALF = 1;
    private static final int HEIGHT = 3;
    /** So lange (Ticks) nach dem mittleren Block darauf warten, dass der Server den Rest der Wand abbaut. */
    private static final int WALL_GRACE = 8;
    private static final int STEP_TIMEOUT = 20 * 8;

    /** Soll dieser Block weg? */
    private Predicate<BlockPos> wanted = p -> false;
    private List<LanePath.Step> run = List.of();
    private int slot = -1;
    private int ticks;
    private int graceTicks;
    private int broken;
    @Nullable private BlockPos destroying;

    public boolean isActive()
    {
        return !this.run.isEmpty();
    }

    /** Wie oft eine Wand abgebaut wurde (fuer den Aufpasser). */
    public int broken()
    {
        return this.broken;
    }

    /**
     * @param run    die Schritte der Bahn geradeaus (alle in dieselbe Richtung, lueckenlos)
     * @param wanted welche Bloecke weg sollen
     * @param slot   das Hotbar-Fach der Star-Spitzhacke
     */
    public void start(List<LanePath.Step> run, Predicate<BlockPos> wanted, int slot)
    {
        this.run = List.copyOf(run);
        this.wanted = wanted;
        this.slot = slot;
        this.ticks = 0;
        this.graceTicks = 0;
        this.destroying = null;
    }

    public Result tick(Minecraft mc)
    {
        LocalPlayer player = mc.player;
        Level level = mc.level;

        if (player == null || level == null || mc.gameMode == null || this.run.isEmpty())
        {
            this.stop(mc);
            return Result.FAILED;
        }

        if (++this.ticks > STEP_TIMEOUT)
        {
            this.stop(mc);
            return Result.FAILED;
        }

        // Die naechste Wand der Bahn, in der noch etwas weg muss
        int k = 0;

        while (k < this.run.size() && !this.hasWork(level, this.run.get(k)))
        {
            k++;
        }

        if (k >= this.run.size())
        {
            this.stop(mc);
            return Result.DONE;
        }

        Options options = mc.options;
        LanePath.Step step = this.run.get(k);
        Direction dir = step.dir();
        Vec3 pos = player.position();
        Vec3 lane = Vec3.atBottomCenterOf(step.pos());
        Direction sideDir = dir.getClockWise();
        double side = (pos.x - lane.x) * sideDir.getStepX() + (pos.z - lane.z) * sideDir.getStepZ();
        BlockPos center = step.pos().above();

        // Genau geradeaus schauen (waagerecht, in Laufrichtung): so liegt das Fadenkreuz von selbst auf der Mitte
        // der Wand vor einem (Augenhoehe = mittlere Reihe)
        Vec3 eye = player.getEyePosition();
        AimController.INSTANCE.aimAt(eye.add(dir.getStepX() * 20.0, 0.0, dir.getStepZ() * 20.0),
                Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());

        // Nicht in ein Loch laufen (dann macht der normale Modus weiter)
        BlockPos feet = player.blockPosition();
        BlockPos front = feet.relative(dir);

        if (!front.equals(step.pos()) && !passable(level, front))
        {
            BlockState frontState = level.getBlockState(front);

            if (frontState.isAir() || frontState.canBeReplaced())
            {
                this.stop(mc);
                return Result.DONE;
            }
        }

        // Soll in der 3x3-Wand etwas stehen bleiben: das macht der normale Modus Block fuer Block
        if (!this.clearable(level, step))
        {
            this.stop(mc);
            return Result.DONE;
        }

        BlockState middle = level.getBlockState(center);
        boolean middleSolid = !middle.isAir() && !middle.canBeReplaced();

        if (!middleSolid)
        {
            // Mitte schon weg, der Rest der Wand noch nicht: kurz stehen bleiben (der Server baut sie gerade ab)
            this.release(options);
            this.stopDestroying(mc);

            if (++this.graceTicks > WALL_GRACE)
            {
                this.stop(mc);
                return Result.DONE;
            }

            return Result.RUNNING;
        }

        this.graceTicks = 0;

        // Erst losgehen, wenn der Blick in Laufrichtung zeigt
        if (Math.abs(Mth.wrapDegrees(player.getYRot() - dir.toYRot())) > 15.0f)
        {
            this.release(options);
            return Result.RUNNING;
        }

        // Immer weiter geradeaus gehen (W gedrueckt), seitlich mit A/D in der Mitte der Bahn bleiben
        options.keyUp.setDown(true);
        options.keyDown.setDown(false);
        options.keyRight.setDown(side < -0.12);
        options.keyLeft.setDown(side > 0.12);

        Inventory inv = player.getInventory();

        if (this.slot >= 0 && this.slot < Inventory.getSelectionSize() && inv.getSelectedSlot() != this.slot)
        {
            inv.setSelectedSlot(this.slot);
        }

        // Linksklick halten, sobald das Fadenkreuz auf der Mitte der Wand liegt (auch schon im Gehen)
        if (mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(center) && hit.getDirection() == dir.getOpposite())
        {
            if (!center.equals(this.destroying))
            {
                mc.gameMode.startDestroyBlock(center, hit.getDirection());
                this.destroying = center.immutable();
            }
            else
            {
                mc.gameMode.continueDestroyBlock(center, hit.getDirection());
            }

            BlockBreaker.markMining(level);
            player.swing(InteractionHand.MAIN_HAND);

            if (level.getBlockState(center).isAir())
            {
                this.destroying = null;
                this.broken++;
                this.ticks = 0;
            }
        }
        else
        {
            this.stopDestroying(mc);
        }

        return Result.RUNNING;
    }

    private boolean hasWork(Level level, LanePath.Step step)
    {
        for (BlockPos p : LanePath.crossSection(step, HALF, HEIGHT))
        {
            if (this.wanted.test(p) && !level.getBlockState(p).isAir())
            {
                return true;
            }
        }

        return false;
    }

    /** Darf die ganze 3x3-Wand weg (gewollt oder schon frei)? */
    private boolean clearable(Level level, LanePath.Step step)
    {
        for (BlockPos p : LanePath.crossSection(step, HALF, HEIGHT))
        {
            BlockState state = level.getBlockState(p);

            if (!this.wanted.test(p) && !state.isAir() && !state.canBeReplaced())
            {
                return false;
            }
        }

        return true;
    }

    private static boolean passable(Level level, BlockPos feet)
    {
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() &&
               level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty() &&
               !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
    }

    private void release(Options options)
    {
        options.keyUp.setDown(false);
        options.keyDown.setDown(false);
        options.keyLeft.setDown(false);
        options.keyRight.setDown(false);
    }

    private void stopDestroying(Minecraft mc)
    {
        if (this.destroying != null && mc.gameMode != null)
        {
            mc.gameMode.stopDestroyBlock();
        }

        this.destroying = null;
    }

    public void stop(Minecraft mc)
    {
        this.stopDestroying(mc);
        this.release(mc.options);
        this.run = List.of();
        AimController.INSTANCE.stop();
    }
}
