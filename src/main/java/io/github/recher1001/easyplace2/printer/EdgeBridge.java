package io.github.recher1001.easyplace2.printer;

import java.util.List;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.config.Ep2Configs;

/**
 * Bauen ueber Luft, wie ein Spieler "bridged": mit gedrueckter Schleichtaste an die Kante des letzten Blocks
 * gehen (man faellt dabei nicht herunter), nach unten schauen und den naechsten Block an dessen Seite setzen.
 * Dann einen Schritt weiter und wieder. So entsteht eine Linie ueber dem Abgrund, auch rueckwaerts.
 * <p>
 * Gesetzt wird danach ganz normal ueber den Printer (Easy Place 2.0, strenges Fadenkreuz); diese Klasse bringt
 * den Spieler nur an die richtige Stelle und haelt solange Schleichen gedrueckt.
 */
public final class EdgeBridge
{
    public enum Result { RUNNING, DONE, FAILED }

    private enum Phase { GET_ON, EDGE }

    private static final int GET_ON_TIMEOUT = 20 * 8;
    private static final int EDGE_TIMEOUT = 20 * 4;
    /** So weit (Bloecke) ragt der Spieler beim Schleichen ueber die Kante hinaus (die Hitbox ist 0,6 breit). */
    private static final double OVERHANG = 0.2;

    /** Das Stueck Bruecke, das gebaut werden soll. */
    public record Plan(BlockPos target, BlockPos support, Direction dir) {}

    @Nullable private Plan plan;
    private Phase phase = Phase.GET_ON;
    private int ticks;
    private int still;
    private Vec3 last = Vec3.ZERO;
    private boolean baritoneStarted;
    private boolean sneaking;

    public boolean isActive()
    {
        return this.plan != null;
    }

    @Nullable
    public Plan plan()
    {
        return this.plan;
    }

    /**
     * Sucht unter den naechsten offenen Bloecken einen ohne Boden darunter, der sich an die Seite eines schon
     * stehenden Blocks setzen laesst, auf dem man stehen kann.
     */
    @Nullable
    public static Plan find(LocalPlayer player, Level level, List<BlockPos> targets)
    {
        Vec3 eye = player.getEyePosition();
        Plan best = null;
        double bestDist = Double.MAX_VALUE;
        int checked = 0;

        for (BlockPos p : targets)
        {
            if (checked++ > 24)
            {
                break;
            }

            // Mit Boden darunter klappt das normale Setzen
            if (level.getBlockState(p.below()).blocksMotion() || !free(level, p.above()) || !free(level, p.above(2)))
            {
                continue;
            }

            for (Direction dir : Direction.Plane.HORIZONTAL)
            {
                BlockPos support = p.relative(dir.getOpposite());

                // Nur auf einer Hoehe, auf die man laufen kann (sonst sucht Baritone ewig einen Weg nach oben)
                if (Math.abs(support.getY() + 1 - player.getBlockY()) > 1)
                {
                    continue;
                }

                if (!level.getBlockState(support).isFaceSturdy(level, support, dir) ||
                    !level.getBlockState(support).blocksMotion() ||
                    !free(level, support.above()) || !free(level, support.above(2)))
                {
                    continue;
                }

                double dist = Vec3.atCenterOf(support.above()).distanceToSqr(eye);

                if (dist < bestDist)
                {
                    bestDist = dist;
                    best = new Plan(p.immutable(), support.immutable(), dir);
                }
            }
        }

        return best;
    }

    private static boolean free(Level level, BlockPos pos)
    {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty();
    }

    public void start(Plan plan)
    {
        this.plan = plan;
        this.phase = Phase.GET_ON;
        this.ticks = 0;
        this.still = 0;
        this.baritoneStarted = false;
    }

    public Result tick(Minecraft mc, boolean canWalk)
    {
        LocalPlayer player = mc.player;
        Plan plan = this.plan;

        if (player == null || mc.level == null || plan == null)
        {
            this.stop(mc);
            return Result.FAILED;
        }

        this.ticks++;
        Options options = mc.options;
        Direction dir = plan.dir();
        Vec3 pos = player.position();
        Vec3 supportTop = Vec3.atBottomCenterOf(plan.support().above());
        // Wie weit der Spieler schon ueber die Kante des Stuetzblocks ragt (in Laufrichtung)
        double along = (pos.x - supportTop.x) * dir.getStepX() + (pos.z - supportTop.z) * dir.getStepZ() - 0.5;
        double side = Math.abs((pos.x - supportTop.x) * dir.getStepZ()) + Math.abs((pos.z - supportTop.z) * dir.getStepX());
        boolean onLine = Math.abs(pos.y - supportTop.y) < 0.6 && side < 0.45 && along > -3.5;

        if (this.phase == Phase.GET_ON)
        {
            if (onLine && !BaritoneBridge.isBusy())
            {
                BaritoneBridge.cancel();
                this.phase = Phase.EDGE;
                this.ticks = 0;
                this.last = pos;
            }
            else if (!this.baritoneStarted)
            {
                if (!canWalk || !BaritoneBridge.goToAny(List.of(plan.support().above())))
                {
                    this.stop(mc);
                    return Result.FAILED;
                }

                this.baritoneStarted = true;
                return Result.RUNNING;
            }
            else if ((this.ticks > 10 && !BaritoneBridge.isBusy()) || this.ticks > GET_ON_TIMEOUT)
            {
                BaritoneBridge.cancel();
                this.stop(mc);
                return Result.FAILED;
            }
            else
            {
                return Result.RUNNING;
            }
        }

        // Schleichen (nie herunterfallen) und in Laufrichtung gehen, waehrend der Blick schon auf die Seite des
        // Stuetzblocks geht, an die der neue Block kommt
        options.keyShift.setDown(true);
        this.sneaking = true;
        Vec3 face = Vec3.atCenterOf(plan.support()).add(dir.getStepX() * 0.5, 0.25, dir.getStepZ() * 0.5);
        AimController.INSTANCE.aimAt(face, Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());

        boolean moved = pos.distanceToSqr(this.last) > 1.0E-4;
        this.still = moved ? 0 : this.still + 1;
        this.last = pos;

        if (along >= OVERHANG || (this.ticks > 6 && this.still >= 4))
        {
            this.releaseMove(options);
            return Result.DONE;
        }

        if (this.ticks > EDGE_TIMEOUT)
        {
            this.stop(mc);
            return Result.FAILED;
        }

        this.pressToward(options, player.getYRot(), dir);
        return Result.RUNNING;
    }

    /** Die Lauftaste druecken, die (beim aktuellen Blick) am ehesten in Richtung {@code dir} fuehrt. */
    private void pressToward(Options options, float yaw, Direction dir)
    {
        double rad = Math.toRadians(yaw);
        double fx = -Math.sin(rad), fz = Math.cos(rad);
        double rx = -Math.cos(rad), rz = -Math.sin(rad);
        double forward = fx * dir.getStepX() + fz * dir.getStepZ();
        double right = rx * dir.getStepX() + rz * dir.getStepZ();

        options.keyUp.setDown(forward > 0.38);
        options.keyDown.setDown(forward < -0.38);
        options.keyRight.setDown(right > 0.38);
        options.keyLeft.setDown(right < -0.38);
    }

    private void releaseMove(Options options)
    {
        options.keyUp.setDown(false);
        options.keyDown.setDown(false);
        options.keyLeft.setDown(false);
        options.keyRight.setDown(false);
    }

    /** Fertig mit dieser Stelle (die Schleichtaste bleibt gedrueckt, bis {@link #release} kommt). */
    public void finish()
    {
        this.plan = null;
    }

    /** Alles loslassen, auch Schleichen. */
    public void stop(Minecraft mc)
    {
        this.plan = null;
        this.release(mc);
    }

    public void release(Minecraft mc)
    {
        this.releaseMove(mc.options);

        if (this.sneaking)
        {
            mc.options.keyShift.setDown(false);
            this.sneaking = false;
        }
    }

    public boolean isSneaking()
    {
        return this.sneaking;
    }
}
