package io.github.recher1001.easyplace2.printer;

import java.util.Random;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.config.Ep2Configs;

/**
 * Dreht die Kamera wie eine Mausbewegung auf einen Punkt: jedes Bild ein kleines Stueck,
 * mit begrenzter Geschwindigkeit, weichem Abbremsen und etwas Unruhe.
 * <p>
 * Gedreht wird ueber {@link LocalPlayer#turn(double, double)}, also genau den Weg, den auch die
 * Maus nimmt, die Drehung ist also auf dem Bildschirm sichtbar.
 * <p>
 * Schnelldrehung: der Kopf springt innerhalb eines Bildes auf das Ziel (wie ein schneller Mausruck),
 * aber nur wenn der Winkel bis dahin hoechstens so gross ist wie eingestellt.
 */
public final class AimController
{
    public static final AimController INSTANCE = new AimController();

    /** Vanilla rechnet Mausbewegung mit diesem Faktor in Grad um (Entity#turn). */
    private static final double TURN_SCALE = 0.15;
    private static final double SNAP_DEGREES = 0.15;

    private final Random random = new Random();
    @Nullable private Vec3 target;
    private boolean onTarget;
    private boolean fast;
    private long lastFrame;
    private double noise;
    private double phase;

    private AimController()
    {
    }

    public void aimAt(Vec3 point, boolean fast)
    {
        if (this.target == null || this.target.distanceToSqr(point) > 1.0E-6)
        {
            this.onTarget = false;
            this.phase = this.random.nextDouble() * Math.PI * 2;
        }

        this.target = point;
        this.fast = fast;
    }

    public void stop()
    {
        this.target = null;
        this.onTarget = false;
    }

    public boolean isOnTarget()
    {
        return this.target != null && this.onTarget;
    }

    /** Winkel (Grad) zwischen der aktuellen Blickrichtung und einem Punkt. */
    public static double angleTo(LocalPlayer player, Vec3 point)
    {
        float[] want = lookAngles(player.getEyePosition(), point);
        double dYaw = Mth.wrapDegrees(want[0] - player.getYRot());
        double dPitch = want[1] - player.getXRot();
        return Math.sqrt(dYaw * dYaw + dPitch * dPitch);
    }

    public static float[] lookAngles(Vec3 eye, Vec3 point)
    {
        Vec3 d = point.subtract(eye);
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0f;
        float pitch = (float) (-(Mth.atan2(d.y, horizontal) * Mth.RAD_TO_DEG));
        return new float[] { yaw, pitch };
    }

    public void onFrame(Minecraft mc)
    {
        long now = System.nanoTime();
        // Auch bei wenigen Bildern pro Sekunde zuegig drehen (aber keine riesigen Spruenge nach einem Haenger)
        double dt = Math.min(0.25, (now - this.lastFrame) / 1.0E9);
        this.lastFrame = now;

        LocalPlayer player = mc.player;

        // Nur drehen, wenn auch ein Spieler die Maus im Spiel bewegen koennte (oder der Printer bewusst
        // hinter dem Pause-Menue oder Chat weiterarbeitet)
        if (this.target == null || player == null || mc.isPaused() || dt <= 0 ||
            (mc.screen != null && !PrinterController.INSTANCE.worksBehind(mc.screen)))
        {
            return;
        }

        float[] want = lookAngles(player.getEyePosition(), this.target);
        double dYaw = Mth.wrapDegrees(want[0] - player.getYRot());
        double dPitch = Mth.clamp(want[1], -90.0f, 90.0f) - player.getXRot();
        double dist = Math.sqrt(dYaw * dYaw + dPitch * dPitch);

        if (dist <= SNAP_DEGREES)
        {
            if (dist > 1.0E-4)
            {
                player.turn(dYaw / TURN_SCALE, dPitch / TURN_SCALE);
            }

            this.onTarget = true;
            return;
        }

        this.onTarget = false;

        if (this.fast && dist <= Ep2Configs.PRINTER_FAST_TURN_MAX_ANGLE.getDoubleValue())
        {
            // Schneller Ruck: in einem Bild aufs Ziel
            player.turn(dYaw / TURN_SCALE, dPitch / TURN_SCALE);
            return;
        }

        // Weiches Abbremsen kurz vor dem Ziel, dazu langsam schwankendes Tempo
        double jitter = Ep2Configs.PRINTER_TURN_JITTER.getDoubleValue();
        this.noise = Mth.clamp(this.noise + (this.random.nextDouble() - 0.5) * 0.6, -1.0, 1.0);
        double ease = 0.22 + 0.78 * Math.min(1.0, dist / 28.0);
        double speed = Ep2Configs.PRINTER_TURN_SPEED.getDoubleValue() * ease * (1.0 + jitter * 0.45 * this.noise);
        double step = Math.min(dist, Math.max(0.05, speed * dt));

        double ux = dYaw / dist;
        double uy = dPitch / dist;

        // Leicht gebogene Bahn statt einer perfekten Geraden, laeuft zum Ziel hin aus
        this.phase += dt * 3.0;
        double curve = jitter * 0.18 * Math.sin(this.phase) * Math.min(1.0, dist / 12.0);
        double stepYaw = (ux - uy * curve) * step;
        double stepPitch = (uy + ux * curve) * step;
        player.turn(stepYaw / TURN_SCALE, stepPitch / TURN_SCALE);
    }
}
