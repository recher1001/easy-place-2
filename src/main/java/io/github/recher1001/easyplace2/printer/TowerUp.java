package io.github.recher1001.easyplace2.printer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.mixin.MinecraftAccessor;
import io.github.recher1001.easyplace2.placement.EasyPlace2Handler;
import io.github.recher1001.easyplace2.printer.scaffold.ScaffoldTracker;

/**
 * Hochbauen wie ein Spieler, wenn die Schematic weiter oben anfaengt: neben den untersten Bloecken senkrecht nach
 * unten schauen, springen und im Sprung einen Geruest-Block unter sich setzen, bis man auf Hoehe der Schicht steht.
 * Der Turm wird wie Geruest gemerkt und am Ende von oben nach unten wieder abgebaut.
 * <p>
 * Der Turm steht immer neben (nie unter) einem Block der Schematic, nur wo die Schematic Luft will, und so, dass
 * der oberste Turm-Block direkt neben einem Block der untersten Schicht liegt: daran wird dann gleich gesetzt.
 */
public final class TowerUp
{
    public enum Result { RUNNING, DONE, FAILED }

    /** Ab so vielen Bloecken ueber den Fuessen wird hochgebaut (darunter reichen Hilfsbloecke vom Boden aus). */
    public static final int MIN_HEIGHT = 4;
    /** Hoechstens so hoch wird gebaut. */
    public static final int MAX_HEIGHT = 48;
    private static final int SEARCH_RADIUS = 6;
    private static final int STEP_TIMEOUT = 20 * 3;

    /** Wo der Turm hin soll: Stehplatz am Boden und Hoehe der Fuesse oben. */
    public record Plan(BlockPos ground, int topFeetY)
    {
        public int height()
        {
            return this.topFeetY - this.ground.getY();
        }
    }

    @Nullable private Plan plan;
    private int ticks;
    private boolean jumping;
    private boolean clicked;
    private boolean tracked;
    private int jumpTicks;
    private double baseY;
    @Nullable private BlockPos cell;
    private int placed;

    public boolean isActive()
    {
        return this.plan != null;
    }

    /** Wie viele Turm-Bloecke seit dem Start gesetzt wurden. */
    public int placed()
    {
        return this.placed;
    }

    /**
     * Sucht in der Naehe des Spielers einen Platz fuer den Turm, neben den untersten offenen Bloecken.
     *
     * @param lowest die unterste offene Schicht (alle Bloecke darin haben diese Hoehe)
     */
    @Nullable
    public static Plan find(LocalPlayer player, Level level, Level schematic, List<BlockPos> lowest, int lowestY,
                            java.util.function.Predicate<BlockPos> excluded)
    {
        Vec3 pos = player.position();
        // Die Bloecke der untersten Schicht, die dem Spieler (waagerecht) am naechsten sind
        List<BlockPos> near = new ArrayList<>(lowest);
        near.sort(Comparator.comparingDouble(p -> sq(p.getX() + 0.5 - pos.x) + sq(p.getZ() + 0.5 - pos.z)));
        Plan best = null;
        double bestScore = Double.MAX_VALUE;
        int checked = 0;

        for (BlockPos target : near)
        {
            if (checked++ >= 12)
            {
                break;
            }

            for (Direction dir : Direction.Plane.HORIZONTAL)
            {
                // Der oberste Turm-Block liegt direkt neben dem Ziel, die Fuesse einen hoeher
                BlockPos top = target.relative(dir);
                BlockPos ground = groundBelow(level, schematic, top, lowestY - MAX_HEIGHT);

                if (ground == null || excluded.test(ground))
                {
                    continue;
                }

                int height = lowestY + 1 - ground.getY();

                if (height < 0 || height > MAX_HEIGHT || !clearColumn(level, schematic, ground, lowestY + 2))
                {
                    continue;
                }

                // Nah am Spieler (dort anfangen, wo man ist), und nicht unnoetig hoch
                double walk = Math.sqrt(sq(ground.getX() + 0.5 - pos.x) + sq(ground.getZ() + 0.5 - pos.z));
                double score = walk + height * 0.5 + Math.abs(ground.getY() - player.getBlockY()) * 0.5;

                if (score < bestScore)
                {
                    bestScore = score;
                    best = new Plan(ground.immutable(), lowestY + 1);
                }
            }
        }

        return best;
    }

    /** Der Stehplatz am Boden unter {@code top}: der erste begehbare Platz nach unten. */
    @Nullable
    private static BlockPos groundBelow(Level level, Level schematic, BlockPos top, int minY)
    {
        BlockPos.MutableBlockPos p = top.mutable();

        for (int y = top.getY(); y >= Math.max(minY, level.getMinY() + 1); y--)
        {
            p.setY(y);
            BlockState below = level.getBlockState(p.below());

            if (!free(level, p))
            {
                return null;
            }

            if (below.isFaceSturdy(level, p.below(), Direction.UP) && below.getFluidState().isEmpty())
            {
                return p.immutable();
            }
        }

        return null;
    }

    /** Ist die Saeule frei (Welt und Schematic), vom Boden bis {@code topY}? */
    private static boolean clearColumn(Level level, Level schematic, BlockPos ground, int topY)
    {
        BlockPos.MutableBlockPos p = ground.mutable();

        for (int y = ground.getY(); y <= topY; y++)
        {
            p.setY(y);

            if (!free(level, p) || !schematic.getBlockState(p).isAir())
            {
                return false;
            }
        }

        return true;
    }

    private static boolean free(Level level, BlockPos pos)
    {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    private static double sq(double v)
    {
        return v * v;
    }

    /** Steht der Spieler auf dem Stehplatz des Turms (dann kann es losgehen)? */
    public static boolean atGround(LocalPlayer player, Plan plan)
    {
        BlockPos feet = player.blockPosition();
        return player.onGround() && feet.getX() == plan.ground().getX() && feet.getZ() == plan.ground().getZ() &&
               feet.getY() >= plan.ground().getY() && feet.getY() < plan.topFeetY();
    }

    public void start(Plan plan)
    {
        this.plan = plan;
        this.ticks = 0;
        this.jumping = false;
        this.clicked = false;
        this.cell = null;
    }

    public Result tick(Minecraft mc)
    {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        Plan plan = this.plan;

        if (player == null || level == null || plan == null)
        {
            this.stop(mc);
            return Result.FAILED;
        }

        this.ticks++;
        BlockPos feet = player.blockPosition();

        if (player.onGround() && feet.getY() >= plan.topFeetY())
        {
            this.stop(mc);
            return Result.DONE;
        }

        // Ueber dem Kopf muss Platz sein, sonst stoesst man sich nur den Kopf
        if (!free(level, feet.above(2)) || this.ticks > STEP_TIMEOUT)
        {
            this.stop(mc);
            return Result.FAILED;
        }

        // Senkrecht nach unten schauen (ein Hauch in Blickrichtung, damit sich der Kopf nicht wild dreht)
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        Vec3 down = eye.add(look.x * 0.02, -3.0, look.z * 0.02);
        AimController.INSTANCE.aimAt(down, Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());

        if (!this.jumping)
        {
            if (player.onGround() && AimController.INSTANCE.isOnTarget())
            {
                mc.options.keyJump.setDown(true);
                this.jumping = true;
                this.jumpTicks = 0;
                this.clicked = false;
                this.tracked = false;
                this.baseY = player.getY();
                this.cell = feet.immutable();
            }

            return Result.RUNNING;
        }

        this.jumpTicks++;

        if (!player.onGround())
        {
            mc.options.keyJump.setDown(false);
        }

        BlockPos cell = this.cell;

        if (!this.clicked && cell != null && player.getY() >= this.baseY + 1.02 && free(level, cell) &&
            mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(cell.below()) && hit.getDirection() == Direction.UP)
        {
            // Ganz normaler Rechtsklick (Easy Place 2.0 haelt sich raus: der Turm steht nicht in der Schematic)
            EasyPlace2Handler.runVanilla(((MinecraftAccessor) mc)::easyplace2$startUseItem);
            this.clicked = true;
        }

        if (this.clicked && !this.tracked && cell != null && !free(level, cell))
        {
            // Merken, damit der Turm am Ende wieder abgebaut wird
            ScaffoldTracker.INSTANCE.add(cell);
            this.tracked = true;
        }

        // Gelandet (oder gar nicht erst abgehoben): naechster Sprung
        if (player.onGround() && this.jumpTicks > 2)
        {
            mc.options.keyJump.setDown(false);

            if (player.getY() >= this.baseY + 0.9)
            {
                this.placed++;
                this.ticks = 0;
            }

            this.jumping = false;
        }

        return Result.RUNNING;
    }

    public void stop(Minecraft mc)
    {
        this.plan = null;
        this.jumping = false;
        mc.options.keyJump.setDown(false);
        AimController.INSTANCE.stop();
    }
}
