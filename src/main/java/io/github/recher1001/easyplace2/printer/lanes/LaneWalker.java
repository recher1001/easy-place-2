package io.github.recher1001.easyplace2.printer.lanes;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.printer.AimController;

/**
 * Laeuft wie ein Spieler geradeaus in ein Feld: in die Richtung drehen und die Vorwaerts-Taste halten,
 * bis man in der Mitte des Feldes steht. Fuer kurze, gerade Stuecke in einer Bahn (ohne Baritone).
 */
public final class LaneWalker
{
    public enum Result { RUNNING, DONE, FAILED }

    private static final double ARRIVED = 0.3;
    private static final int TIMEOUT = 20 * 6;
    private static final int STUCK_TICKS = 20;

    @Nullable private BlockPos target;
    private int ticks;
    private Vec3 lastPos = Vec3.ZERO;
    private int stillTicks;

    public void start(BlockPos target, LocalPlayer player)
    {
        this.target = target.immutable();
        this.ticks = 0;
        this.stillTicks = 0;
        this.lastPos = player.position();
    }

    public boolean isActive()
    {
        return this.target != null;
    }

    public Result tick(Minecraft mc)
    {
        LocalPlayer player = mc.player;

        if (player == null || this.target == null)
        {
            this.cancel(mc);
            return Result.FAILED;
        }

        Vec3 center = Vec3.atBottomCenterOf(this.target);
        double dx = center.x - player.getX();
        double dz = center.z - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist < ARRIVED)
        {
            this.cancel(mc);
            return Result.DONE;
        }

        // Kommt nicht weiter oder dauert zu lange: aufgeben (dann hilft Baritone oder der normale Modus)
        this.stillTicks = player.position().distanceTo(this.lastPos) < 0.01 ? this.stillTicks + 1 : 0;
        this.lastPos = player.position();

        if (++this.ticks > TIMEOUT || this.stillTicks > STUCK_TICKS)
        {
            this.cancel(mc);
            return Result.FAILED;
        }

        // Geradeaus schauen (Augenhoehe), in Laufrichtung
        Vec3 look = new Vec3(dx / dist, 0, dz / dist);
        AimController.INSTANCE.aimAt(player.getEyePosition().add(look.scale(3.0)), Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());
        double yawWanted = Math.toDegrees(Math.atan2(-dx, dz));
        boolean facing = Math.abs(Mth.wrapDegrees(yawWanted - player.getYRot())) < 35;
        mc.options.keyUp.setDown(facing);
        return Result.RUNNING;
    }

    public void cancel(Minecraft mc)
    {
        if (this.target != null)
        {
            mc.options.keyUp.setDown(false);
        }

        this.target = null;
    }

    /**
     * Kann man von {@code from} geradeaus nach {@code to} laufen? Beide auf derselben Hoehe, auf einer Linie
     * (X oder Z), und jedes Feld dazwischen frei mit festem Boden darunter.
     */
    public static boolean straightWalkable(Level level, BlockPos from, BlockPos to)
    {
        if (from.getY() != to.getY() || (from.getX() != to.getX() && from.getZ() != to.getZ()))
        {
            return false;
        }

        int dx = Integer.signum(to.getX() - from.getX());
        int dz = Integer.signum(to.getZ() - from.getZ());
        BlockPos p = from;

        for (int i = 0; i < 64 && !p.equals(to); i++)
        {
            p = p.offset(dx, 0, dz);

            if (!standable(level, p))
            {
                return false;
            }
        }

        return p.equals(to);
    }

    /** Kann man hier stehen? Fuesse und Kopf frei, fester Boden darunter, keine Fluessigkeit. */
    public static boolean standable(Level level, BlockPos feet)
    {
        BlockPos head = feet.above();
        BlockPos below = feet.below();
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() &&
               level.getBlockState(head).getCollisionShape(level, head).isEmpty() &&
               level.getFluidState(feet).isEmpty() && level.getFluidState(head).isEmpty() &&
               !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }
}
