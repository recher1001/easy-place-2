package io.github.recher1001.easyplace2.printer.lanes;

import java.util.List;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

        LanePath.Step step = this.run.get(k);
        Direction dir = step.dir();
        BlockPos stand = step.pos().relative(dir.getOpposite());
        Vec3 pos = player.position();
        Vec3 standCenter = Vec3.atBottomCenterOf(stand);
        double along = (pos.x - standCenter.x) * dir.getStepX() + (pos.z - standCenter.z) * dir.getStepZ();
        double side = (pos.x - standCenter.x) * dir.getClockWise().getStepX() + (pos.z - standCenter.z) * dir.getClockWise().getStepZ();
        BlockPos center = step.pos().above();
        // Geradeaus auf die Mitte der Wand schauen (die Seite, die zum Spieler zeigt)
        Vec3 face = Vec3.atCenterOf(center).subtract(dir.getStepX() * 0.5, 0.0, dir.getStepZ() * 0.5);
        AimController.INSTANCE.aimAt(face, Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());

        // Noch nicht vor der Wand: geradeaus vorgehen (die Felder davor sind schon frei)
        if (along < -0.3 || Math.abs(side) > 0.35)
        {
            this.stopDestroying(mc);

            if (!passable(level, stand) || Math.abs(pos.y - stand.getY()) > 0.6)
            {
                this.stop(mc);
                return Result.FAILED;
            }

            this.press(mc.options, player.getYRot(), standCenter.subtract(pos));
            return Result.RUNNING;
        }

        this.release(mc.options);

        // Soll in der 3x3-Wand etwas stehen bleiben: das macht der normale Modus Block fuer Block
        if (!this.clearable(level, step))
        {
            this.stop(mc);
            return Result.DONE;
        }

        BlockState middle = level.getBlockState(center);

        if (middle.isAir() || middle.canBeReplaced())
        {
            // Mitte schon weg, der Rest der Wand noch nicht: kurz warten (der Server baut sie gerade ab)
            if (++this.graceTicks > WALL_GRACE)
            {
                this.stop(mc);
                return Result.DONE;
            }

            return Result.RUNNING;
        }

        this.graceTicks = 0;
        Inventory inv = player.getInventory();

        if (this.slot >= 0 && this.slot < Inventory.getSelectionSize() && inv.getSelectedSlot() != this.slot)
        {
            inv.setSelectedSlot(this.slot);
        }

        // Linksklick halten, solange das Fadenkreuz auf der Mitte der Wand liegt
        if (mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(center))
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

    /** Die Lauftasten druecken, die (beim aktuellen Blick) in Richtung {@code move} fuehren. */
    private void press(Options options, float yaw, Vec3 move)
    {
        double len = Math.sqrt(move.x * move.x + move.z * move.z);

        if (len < 1.0E-3)
        {
            this.release(options);
            return;
        }

        double mx = move.x / len, mz = move.z / len;
        double rad = Math.toRadians(yaw);
        double fx = -Math.sin(rad), fz = Math.cos(rad);
        double rx = -Math.cos(rad), rz = -Math.sin(rad);
        double forward = fx * mx + fz * mz;
        double right = rx * mx + rz * mz;
        options.keyUp.setDown(forward > 0.3);
        options.keyDown.setDown(forward < -0.3);
        options.keyRight.setDown(right > 0.3);
        options.keyLeft.setDown(right < -0.3);
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
