package io.github.recher1001.easyplace2.printer.path;

import java.util.List;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.EasyPlace2;
import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.printer.AimController;

/**
 * Laeuft einen Weg ab wie ein Spieler: in Laufrichtung schauen (sichtbar gedreht), W/A/S/D druecken, an
 * Stufen springen, auf geraden Stuecken sprinten. Nach einem Teilweg wird von dort aus weitergesucht.
 * <p>
 * Geht es nicht weiter (haengt fest, vom Weg abgekommen), wird ein paar Mal neu gesucht und dann aufgegeben
 * (nie endlos herumlaufen); der Printer probiert dann etwas anderes.
 */
public final class PathWalker
{
    public static final PathWalker INSTANCE = new PathWalker();

    private enum Phase { IDLE, SEARCHING, FOLLOWING }

    /** Knoten pro Tick fuer die Suche (wenige Millisekunden). */
    private static final int BUDGET_PER_TICK = 4000;
    private static final int MAX_NODES = 30000;
    private static final int MAX_REPLANS = 6;
    private static final int STUCK_TICKS = 25;

    private Phase phase = Phase.IDLE;
    @Nullable private PathFinder.Goal goal;
    @Nullable private PathFinder search;
    private List<BlockPos> path = List.of();
    private boolean partial;
    private int index;
    private int replans;
    private int stillTicks;
    private Vec3 lastPos = Vec3.ZERO;
    private boolean sprint;
    private boolean keysDown;
    /** Genau auf dem Stehplatz anhalten (mittig), z. B. oben auf einem Turm. */
    private boolean exact;

    private PathWalker()
    {
    }

    public boolean isBusy()
    {
        return this.phase != Phase.IDLE;
    }

    /** Laeuft zu einem der Stehplaetze (Fuesse dort). */
    public boolean goToAny(List<BlockPos> spots)
    {
        if (spots.isEmpty())
        {
            return false;
        }

        List<BlockPos> list = spots.size() > 64 ? spots.subList(0, 64) : List.copyOf(spots);
        java.util.Set<BlockPos> set = new java.util.HashSet<>(list);
        boolean ok = this.go(new PathFinder.Goal()
        {
            @Override
            public boolean reached(BlockPos feet)
            {
                return set.contains(feet);
            }

            @Override
            public double estimate(BlockPos feet)
            {
                double best = Double.MAX_VALUE;

                for (BlockPos p : list)
                {
                    best = Math.min(best, Math.sqrt(p.distSqr(feet)));
                }

                return best;
            }
        });
        this.exact = true;
        return ok;
    }

    /** Laeuft irgendwohin, wo die Fuesse hoechstens {@code range} Bloecke von {@code pos} entfernt sind. */
    public boolean goNear(BlockPos pos, int range)
    {
        BlockPos target = pos.immutable();
        return this.go(new PathFinder.Goal()
        {
            @Override
            public boolean reached(BlockPos feet)
            {
                return feet.distSqr(target) <= (double) range * range;
            }

            @Override
            public double estimate(BlockPos feet)
            {
                return Math.max(0.0, Math.sqrt(feet.distSqr(target)) - range);
            }
        });
    }

    /** Laeuft zu einer X/Z-Position (Hoehe egal). */
    public boolean goToXZ(int x, int z)
    {
        return this.go(new PathFinder.Goal()
        {
            @Override
            public boolean reached(BlockPos feet)
            {
                return feet.getX() == x && feet.getZ() == z;
            }

            @Override
            public double estimate(BlockPos feet)
            {
                double dx = feet.getX() - x, dz = feet.getZ() - z;
                return Math.sqrt(dx * dx + dz * dz);
            }
        });
    }

    private boolean go(PathFinder.Goal goal)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || mc.level == null)
        {
            return false;
        }

        this.release(mc.options);
        this.exact = false;
        this.goal = goal;
        this.replans = 0;
        this.sprint = Ep2Configs.PRINTER_SPRINT.getBooleanValue();
        this.startSearch(mc.player, mc.level);
        return true;
    }

    private void startSearch(LocalPlayer player, Level level)
    {
        BlockPos feet = player.blockPosition();
        PathFinder probe = new PathFinder(level, feet, this.goal, 1);

        // Auf einer Stufe o. Ae. steht man "im" Block: dann zaehlt das Feld darueber
        if (!probe.standable(feet) && probe.standable(feet.above()))
        {
            feet = feet.above();
        }

        this.search = new PathFinder(level, feet, this.goal, MAX_NODES);
        this.phase = Phase.SEARCHING;
    }

    public void cancel()
    {
        Minecraft mc = Minecraft.getInstance();
        this.phase = Phase.IDLE;
        this.search = null;
        this.goal = null;
        this.path = List.of();
        this.release(mc.options);
    }

    public void tick(Minecraft mc)
    {
        if (this.phase == Phase.IDLE)
        {
            return;
        }

        LocalPlayer player = mc.player;
        Level level = mc.level;

        if (player == null || level == null || this.goal == null)
        {
            this.cancel();
            return;
        }

        if (this.phase == Phase.SEARCHING)
        {
            PathFinder search = this.search;

            if (search == null)
            {
                this.cancel();
                return;
            }

            PathFinder.Status status = search.step(BUDGET_PER_TICK);

            if (status == PathFinder.Status.SEARCHING)
            {
                return;
            }

            if (status == PathFinder.Status.NONE || search.path().size() < 2)
            {
                // Schon da, oder kein Weg, der naeher heranfuehrt: nicht herumirren
                if (status == PathFinder.Status.NONE && Ep2Configs.DEBUG.getBooleanValue())
                {
                    EasyPlace2.LOGGER.info("[Printer] Eigenes Laufen: kein Weg gefunden");
                }

                this.cancel();
                return;
            }

            this.path = search.path();
            this.partial = status == PathFinder.Status.PARTIAL;
            this.index = 1;
            this.stillTicks = 0;
            this.lastPos = player.position();
            this.phase = Phase.FOLLOWING;
        }

        this.follow(mc, player, level);
    }

    private void follow(Minecraft mc, LocalPlayer player, Level level)
    {
        Options options = mc.options;
        BlockPos feet = player.blockPosition();

        if (this.goal != null && player.onGround() && this.goal.reached(feet))
        {
            double cx = feet.getX() + 0.5 - player.getX(), cz = feet.getZ() + 0.5 - player.getZ();

            if (!this.exact || cx * cx + cz * cz < 0.12 * 0.12)
            {
                this.cancel();
                return;
            }

            // Genau in die Mitte des Platzes, langsam (schleichend), damit man nicht halb auf dem Nachbarblock steht
            this.press(options, player.getYRot(), cx, cz, false);
            options.keyShift.setDown(true);
            options.keyJump.setDown(false);
            this.keysDown = true;

            if (++this.stillTicks > 40)
            {
                this.cancel();
            }

            return;
        }

        Vec3 pos = player.position();

        // Knoten erreicht? (Zwischenpunkte grosszuegig, damit es fluessig weitergeht)
        while (this.index < this.path.size())
        {
            BlockPos node = this.path.get(this.index);
            boolean last = this.index == this.path.size() - 1;
            double dx = node.getX() + 0.5 - pos.x, dz = node.getZ() + 0.5 - pos.z;
            double hd = Math.sqrt(dx * dx + dz * dz);
            double dy = pos.y - node.getY();

            if (hd < (last ? 0.3 : 0.55) && dy > -0.6 && dy < 0.9 && (player.onGround() || !last))
            {
                this.index++;
                continue;
            }

            break;
        }

        // Schon naeher am uebernaechsten Knoten (Ecke abgekuerzt)? Dann weiter
        while (this.index < this.path.size() - 1 && player.onGround())
        {
            BlockPos a = this.path.get(this.index), b = this.path.get(this.index + 1);

            if (a.getY() != b.getY() || a.getY() != feet.getY() ||
                Vec3.atBottomCenterOf(b).distanceToSqr(pos) >= Vec3.atBottomCenterOf(a).distanceToSqr(pos))
            {
                break;
            }

            this.index++;
        }

        if (this.index >= this.path.size())
        {
            // Teilweg zu Ende: von hier aus weitersuchen
            if (this.partial && this.replans++ < MAX_REPLANS)
            {
                this.release(options);
                this.startSearch(player, level);
                return;
            }

            this.cancel();
            return;
        }

        BlockPos node = this.path.get(this.index);
        // Gerade Linie statt Zickzack: auf den weitesten Knoten zuhalten, zu dem es auf gleicher Hoehe frei geradeaus geht
        BlockPos steer = this.path.get(this.farthestStraight(level, pos, feet));
        Vec3 target = Vec3.atBottomCenterOf(steer);

        // Haengt fest oder ist vom Weg abgekommen: neu suchen (ein paar Mal), dann aufgeben
        boolean moved = pos.distanceToSqr(this.lastPos) > 0.0025;
        this.stillTicks = moved ? 0 : this.stillTicks + 1;
        this.lastPos = pos;

        if (this.stillTicks > STUCK_TICKS || Vec3.atBottomCenterOf(node).distanceTo(pos) > 4.0)
        {
            this.release(options);

            if (this.replans++ < MAX_REPLANS)
            {
                this.startSearch(player, level);
            }
            else
            {
                this.cancel();
            }

            return;
        }

        // Geradeaus in Laufrichtung schauen (waagerecht, wie ein Spieler beim Laufen)
        Vec3 heading = new Vec3(target.x - pos.x, 0.0, target.z - pos.z);

        if (heading.horizontalDistanceSqr() > 0.09)
        {
            Vec3 dirH = heading.normalize();
            AimController.INSTANCE.aimAt(player.getEyePosition().add(dirH.x * 10.0, 0.0, dirH.z * 10.0),
                    Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());
        }

        // Tasten Richtung Knoten
        double mx = target.x - pos.x, mz = target.z - pos.z;
        double len = Math.sqrt(mx * mx + mz * mz);
        double forward = 0, right = 0;

        if (len > 0.05)
        {
            double rad = Math.toRadians(player.getYRot());
            double fx = -Math.sin(rad), fz = Math.cos(rad);
            double rx = -Math.cos(rad), rz = -Math.sin(rad);
            forward = (fx * mx + fz * mz) / len;
            right = (rx * mx + rz * mz) / len;
        }

        this.press(options, player.getYRot(), mx, mz, false);
        options.keyShift.setDown(false);

        // Sprinten nur geradeaus und nicht kurz vor dem Ziel
        boolean straight = forward > 0.92 && this.path.size() - this.index > 3;
        options.keySprint.setDown(this.sprint && straight);

        // Springen: der naechste Knoten liegt hoeher, oder man laeuft gegen eine Stufe
        BlockPos prev = this.path.get(Math.max(0, this.index - 1));
        boolean up = node.getY() > prev.getY() &&
                     Math.sqrt(Math.pow(node.getX() + 0.5 - pos.x, 2) + Math.pow(node.getZ() + 0.5 - pos.z, 2)) < 1.6;
        options.keyJump.setDown(player.onGround() && (up || (player.horizontalCollision && len > 0.2 && node.getY() >= feet.getY())));
        this.keysDown = true;
    }

    /**
     * Der weiteste Knoten ab dem aktuellen (hoechstens 12 weiter), zu dem man auf gleicher Hoehe geradeaus laufen
     * kann, ohne irgendwo anzustossen oder in ein Loch zu treten (der ganze Koerper, 0,6 breit, wird geprueft).
     */
    private int farthestStraight(Level level, Vec3 pos, BlockPos feet)
    {
        int best = this.index;
        int y = this.path.get(this.index).getY();

        if (y != feet.getY())
        {
            return best;
        }

        for (int j = this.index + 1; j < Math.min(this.path.size(), this.index + 12); j++)
        {
            BlockPos n = this.path.get(j);

            if (n.getY() != y || !this.clearLine(level, pos, Vec3.atBottomCenterOf(n), y))
            {
                break;
            }

            best = j;
        }

        return best;
    }

    private boolean clearLine(Level level, Vec3 from, Vec3 to, int y)
    {
        double dx = to.x - from.x, dz = to.z - from.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(len / 0.25));
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        java.util.Set<Long> checked = new java.util.HashSet<>();

        for (int i = 0; i <= steps; i++)
        {
            double t = (double) i / steps;
            double x = from.x + dx * t, z = from.z + dz * t;

            for (double ox = -0.3; ox <= 0.3; ox += 0.6)
            {
                for (double oz = -0.3; oz <= 0.3; oz += 0.6)
                {
                    m.set(Mth.floor(x + ox), y, Mth.floor(z + oz));

                    if (checked.add(m.asLong()) && !PathFinder.standableAt(level, m))
                    {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    /** Die Lauftasten druecken, die (beim aktuellen Blick) in Richtung (mx, mz) fuehren. */
    private void press(Options options, float yaw, double mx, double mz, boolean unused)
    {
        double len = Math.sqrt(mx * mx + mz * mz);
        double forward = 0, right = 0;

        if (len > 1.0E-3)
        {
            double rad = Math.toRadians(yaw);
            double fx = -Math.sin(rad), fz = Math.cos(rad);
            double rx = -Math.cos(rad), rz = -Math.sin(rad);
            forward = (fx * mx + fz * mz) / len;
            right = (rx * mx + rz * mz) / len;
        }

        options.keyUp.setDown(forward > 0.38);
        options.keyDown.setDown(forward < -0.38);
        options.keyRight.setDown(right > 0.38);
        options.keyLeft.setDown(right < -0.38);
    }

    private void release(Options options)
    {
        if (!this.keysDown)
        {
            return;
        }

        options.keyUp.setDown(false);
        options.keyDown.setDown(false);
        options.keyLeft.setDown(false);
        options.keyRight.setDown(false);
        options.keyJump.setDown(false);
        options.keySprint.setDown(false);
        options.keyShift.setDown(false);
        AimController.INSTANCE.stop();
        this.keysDown = false;
    }
}
