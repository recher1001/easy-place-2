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
    /** So viel Abstand (Bloecke, von der Koerpermitte bis zur Wand) haelt der Spieler beim Abbauen. */
    private static final double KEEP_DISTANCE = 1.8;

    /** Soll dieser Block weg? */
    private Predicate<BlockPos> wanted = p -> false;
    private List<LanePath.Step> run = List.of();
    private int slot = -1;
    private int ticks;
    private int graceTicks;
    private int broken;
    @Nullable private BlockPos destroying;
    /** Waende, an denen nur noch Reste am Rand stehen (stoeren beim Gehen nicht, kommen spaeter dran). */
    private final java.util.Set<Integer> passed = new java.util.HashSet<>();
    /** Blick steht geradeaus: ab jetzt wird die Maus nicht mehr bewegt. */
    private boolean aligned;
    /** Ticks Pause nach einem Abbau mit der Star-Spitzhacke (der Server soll ihn erst bestaetigen). */
    private int cooldown;
    /** Star-Linie: Reste und Waende mit festen Bloecken gleich einzeln abbauen (Schaufel, Diamant-Spitzhacke ...). */
    private boolean cleanup;
    @Nullable private BlockBreaker single;
    /** Nach unten graben statt geradeaus: bis zu dieser Fusshoehe (sonst Integer.MIN_VALUE). */
    private int downTo = Integer.MIN_VALUE;
    private float downYaw;

    public boolean isActive()
    {
        return !this.run.isEmpty();
    }

    /** Wie oft eine Wand abgebaut wurde (fuer den Aufpasser). */
    /** Steht der Blick gerade fest geradeaus (kein Ausrichten, kein Einzelabbau von Resten)? */
    public boolean isSteady()
    {
        return this.aligned && this.single == null;
    }

    public int broken()
    {
        return this.broken;
    }

    /**
     * @param run    die Schritte der Bahn geradeaus (alle in dieselbe Richtung, lueckenlos)
     * @param wanted welche Bloecke weg sollen
     * @param slot   das Hotbar-Fach der Star-Spitzhacke
     */
    /**
     * Senkrecht nach unten graben: einmal gerade nach unten schauen, dann ohne die Kamera zu bewegen auf den Block
     * unter den Fuessen schlagen (der Server baut die 3x3-Flaeche ab), fallen, wieder schlagen, bis zur Fusshoehe
     * {@code layer}. Danach geht es mit der neuen Lage geradeaus weiter.
     */
    public void startDown(int layer, Predicate<BlockPos> wanted, int slot, float yaw)
    {
        this.start(List.of(new LanePath.Step(BlockPos.ZERO, Direction.NORTH)), wanted, slot);
        this.downTo = layer;
        // Die Blickrichtung bleibt, nur nach unten (auf die naechste Himmelsrichtung gerundet, damit es sauber aussieht)
        this.downYaw = Direction.fromYRot(yaw).toYRot();
    }

    /** Wie {@link #start}, aber Reste gleich einzeln mit dem passenden Werkzeug abbauen (fuer die Star-Linie). */
    public void startWithCleanup(List<LanePath.Step> run, Predicate<BlockPos> wanted, int slot)
    {
        this.start(run, wanted, slot);
        this.cleanup = true;
    }

    public void start(List<LanePath.Step> run, Predicate<BlockPos> wanted, int slot)
    {
        this.downTo = Integer.MIN_VALUE;
        this.cleanup = false;
        this.single = null;
        this.run = List.copyOf(run);
        this.wanted = wanted;
        this.slot = slot;
        this.ticks = 0;
        this.graceTicks = 0;
        this.destroying = null;
        this.passed.clear();
        this.aligned = false;
        this.cooldown = 0;
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

        // Pause nach einem Schlag: stehen bleiben und abwarten, ob der Server den Abbau bestaetigt (taucht der Block
        // wieder auf, wird er danach einfach noch einmal abgebaut)
        if (this.cooldown > 0)
        {
            this.cooldown--;
            this.release(mc.options);
            this.stopDestroying(mc);
            return Result.RUNNING;
        }

        if (this.downTo != Integer.MIN_VALUE)
        {
            return this.tickDown(mc, player, level);
        }

        // Die naechste Wand der Bahn, in der noch etwas weg muss
        int k = 0;

        while (k < this.run.size() && (this.passed.contains(k) || !this.hasWork(level, this.run.get(k))))
        {
            k++;
        }

        if (k >= this.run.size())
        {
            this.stop(mc);
            return Result.DONE;
        }

        Options options = mc.options;

        // Gerade dabei, einen Rest einzeln abzubauen (nie mit der Star-Spitzhacke, sonst ginge 3x3 weg)
        if (this.single != null)
        {
            this.release(options);
            BlockBreaker.Result result = this.single.tick(mc, player, level);

            if (result != BlockBreaker.Result.RUNNING)
            {
                if (result == BlockBreaker.Result.DONE)
                {
                    this.broken++;
                }

                this.single = null;
                this.ticks = 0;
                this.aligned = false;
            }

            return Result.RUNNING;
        }
        LanePath.Step step = this.run.get(k);
        Direction dir = step.dir();
        Vec3 pos = player.position();
        Vec3 lane = Vec3.atBottomCenterOf(step.pos());
        Direction sideDir = dir.getClockWise();
        double side = (pos.x - lane.x) * sideDir.getStepX() + (pos.z - lane.z) * sideDir.getStepZ();
        BlockPos center = step.pos().above();

        // Blick: einmal genau waagerecht geradeaus in Laufrichtung drehen, danach die Maus nicht mehr anfassen.
        // Augenhoehe = mittlere Reihe, das Fadenkreuz liegt so von selbst auf dem mittleren der 9 Bloecke.
        float yawError = Math.abs(Mth.wrapDegrees(player.getYRot() - dir.toYRot()));
        float pitchError = Math.abs(player.getXRot());

        if (!this.aligned || yawError > 5.0f || pitchError > 5.0f)
        {
            if (yawError < 0.6f && pitchError < 0.6f)
            {
                this.aligned = true;
                AimController.INSTANCE.stop();
            }
            else
            {
                this.aligned = false;
                this.release(options);
                this.stopDestroying(mc);
                AimController.INSTANCE.aimAt(player.getEyePosition().add(dir.getStepX() * 20.0, 0.0, dir.getStepZ() * 20.0),
                        Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());
                return Result.RUNNING;
            }
        }

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

        // Soll in der 3x3-Wand etwas stehen bleiben: nicht 3x3 schlagen. Bei der Star-Linie die anderen Bloecke dieser
        // Wand einzeln abbauen (erst hingehen), sonst macht der normale Modus Block fuer Block weiter
        if (!this.clearable(level, step))
        {
            if (!this.cleanup)
            {
                this.stop(mc);
                return Result.DONE;
            }

            BlockPos stand = step.pos().relative(dir.getOpposite());
            double along = (pos.x - (stand.getX() + 0.5)) * dir.getStepX() + (pos.z - (stand.getZ() + 0.5)) * dir.getStepZ();

            if (along < -0.3 && passable(level, feet.relative(dir)))
            {
                options.keyUp.setDown(true);
                options.keyRight.setDown(side < -0.12);
                options.keyLeft.setDown(side > 0.12);
                this.stopDestroying(mc);
                return Result.RUNNING;
            }

            return this.cleanupWall(mc, player, level, step, k);
        }

        BlockState middle = level.getBlockState(center);
        boolean middleSolid = !middle.isAir() && !middle.canBeReplaced();

        if (!middleSolid)
        {
            BlockState bottom = level.getBlockState(step.pos());

            if (this.cleanup)
            {
                // Star-Linie: kurz warten (der Server baut die Wand ab), dann was stehen blieb (Kies, Erde ...) mitnehmen
                if (++this.graceTicks > WALL_GRACE)
                {
                    return this.cleanupWall(mc, player, level, step, k);
                }

                this.release(options);
                this.stopDestroying(mc);
                return Result.RUNNING;
            }

            if (!this.cleanup && bottom.getCollisionShape(level, step.pos()).isEmpty())
            {
                // Der Weg ist frei: Reste am Rand (oben, links, rechts) stoeren nicht, weitergehen. Die holt der
                // normale Modus nach der Bahn.
                this.passed.add(k);
                this.graceTicks = 0;
                return Result.RUNNING;
            }

            // Unten in der Mitte steht noch etwas: kurz warten (der Server baut die Wand gerade ab)
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

        // Geradeaus gehen (W), aber mit Abstand zur Wand stehen bleiben und von dort abbauen; seitlich mit A/D in der
        // Mitte der Bahn bleiben (ohne den Blick)
        double face = (step.pos().getX() + 0.5) * dir.getStepX() + (step.pos().getZ() + 0.5) * dir.getStepZ() - 0.5;
        double gap = face - (pos.x * dir.getStepX() + pos.z * dir.getStepZ());
        options.keyUp.setDown(gap > KEEP_DISTANCE);
        options.keyDown.setDown(false);
        options.keyRight.setDown(side < -0.12);
        options.keyLeft.setDown(side > 0.12);

        // Immer die Star-Spitzhacke auf die Mitte (auch wenn dort Kies oder Erde ist): dann geht die ganze Wand weg.
        // Was sie am Rand stehen laesst (Erde, Kies, Ton ...), holt der normale Modus nach der Bahn mit der Schaufel.
        Inventory inv = player.getInventory();

        if (this.slot >= 0 && this.slot < Inventory.getSelectionSize() && inv.getSelectedSlot() != this.slot)
        {
            inv.setSelectedSlot(this.slot);
        }

        // Linksklick halten, sobald das Fadenkreuz (unbewegt, geradeaus) auf der Mitte der Wand liegt
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
                // Kurz warten, bevor der naechste Schlag kommt: sonst lehnt der Server den Abbau ab (Geisterblock)
                this.cooldown = Ep2Configs.PRINTER_STAR_DELAY.getIntegerValue();
            }
        }
        else
        {
            this.stopDestroying(mc);
        }

        return Result.RUNNING;
    }

    private Result tickDown(Minecraft mc, LocalPlayer player, Level level)
    {
        BlockPos feet = player.blockPosition();

        if (player.onGround() && feet.getY() <= this.downTo)
        {
            // Unten angekommen: die neue Lage geht geradeaus weiter (dort richtet sich der Blick einmal neu aus)
            this.stop(mc);
            return Result.DONE;
        }

        // Blick: einmal senkrecht nach unten, danach die Maus nicht mehr anfassen
        float yawError = Math.abs(Mth.wrapDegrees(player.getYRot() - this.downYaw));
        float pitchError = Math.abs(90.0f - player.getXRot());

        if (!this.aligned || pitchError > 5.0f)
        {
            if (pitchError < 0.6f && yawError < 2.0f)
            {
                this.aligned = true;
                AimController.INSTANCE.stop();
            }
            else
            {
                this.aligned = false;
                this.stopDestroying(mc);
                double rad = Math.toRadians(this.downYaw);
                Vec3 eye = player.getEyePosition();
                // Ein Hauch in Blickrichtung, sonst ist die Richtung bei genau senkrecht unbestimmt
                AimController.INSTANCE.aimAt(eye.add(-Math.sin(rad) * 0.001, -5.0, Math.cos(rad) * 0.001),
                        Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());
                return Result.RUNNING;
            }
        }

        if (!player.onGround())
        {
            // Faellt gerade eine Stufe tiefer
            this.stopDestroying(mc);
            return Result.RUNNING;
        }

        BlockPos below = feet.below();
        BlockState state = level.getBlockState(below);

        // Nur, wenn die ganze 3x3-Flaeche darunter weg darf (sonst macht der normale Modus weiter)
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dz = -1; dz <= 1; dz++)
            {
                BlockPos p = below.offset(dx, 0, dz);
                BlockState s = level.getBlockState(p);

                if (!this.wanted.test(p) && !s.isAir() && !s.canBeReplaced())
                {
                    this.stop(mc);
                    return Result.DONE;
                }
            }
        }

        if (state.isAir() || state.canBeReplaced())
        {
            // Mitte schon weg, der Server baut den Rest der Flaeche gerade ab: kurz warten
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

        if (mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(below) && hit.getDirection() == Direction.UP)
        {
            if (!below.equals(this.destroying))
            {
                mc.gameMode.startDestroyBlock(below, hit.getDirection());
                this.destroying = below.immutable();
            }
            else
            {
                mc.gameMode.continueDestroyBlock(below, hit.getDirection());
            }

            BlockBreaker.markMining(level);
            player.swing(InteractionHand.MAIN_HAND);

            if (level.getBlockState(below).isAir())
            {
                this.destroying = null;
                this.broken++;
                this.ticks = 0;
                // Kurz warten, bevor der naechste Schlag kommt: sonst lehnt der Server den Abbau ab (Geisterblock)
                this.cooldown = Ep2Configs.PRINTER_STAR_DELAY.getIntegerValue();
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

    /** Die Reste dieser Wand einzeln abbauen; ist nichts mehr zu sehen, weiter (oder aufhoeren, wenn der Weg zu ist). */
    private Result cleanupWall(Minecraft mc, LocalPlayer player, Level level, LanePath.Step step, int k)
    {
        this.release(mc.options);
        this.stopDestroying(mc);
        List<BlockPos> cells = new java.util.ArrayList<>(LanePath.crossSection(step, HALF, HEIGHT));
        cells.sort(java.util.Comparator.comparingInt(p -> -p.getY()));

        for (BlockPos p : cells)
        {
            BlockState state = level.getBlockState(p);

            if (this.wanted.test(p) && !state.isAir() && !state.canBeReplaced() &&
                !io.github.recher1001.easyplace2.printer.refill.RefillUtil.visiblePoints(player, level, null, p, null).isEmpty())
            {
                // Bestes Werkzeug aus der Hotbar (Schaufel fuer Kies, Diamant-Spitzhacke fuer Stein ...), nie die Star-Spitzhacke
                this.single = new BlockBreaker(p, -1, this.slot);
                this.graceTicks = 0;
                return Result.RUNNING;
            }
        }

        BlockState bottom = level.getBlockState(step.pos());
        BlockState middle = level.getBlockState(step.pos().above());

        if (bottom.getCollisionShape(level, step.pos()).isEmpty() && middle.getCollisionShape(level, step.pos().above()).isEmpty())
        {
            // Weg frei, Rest (falls da) nicht erreichbar: weiter
            this.passed.add(k);
            this.graceTicks = 0;
            this.aligned = false;
            return Result.RUNNING;
        }

        this.stop(mc);
        return Result.DONE;
    }

    public void stop(Minecraft mc)
    {
        if (this.single != null)
        {
            this.single.stop(mc);
            this.single = null;
        }

        this.stopDestroying(mc);
        this.release(mc.options);
        this.run = List.of();
        AimController.INSTANCE.stop();
    }
}
