package io.github.recher1001.easyplace2.printer;

import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.config.Ep2Configs;

/**
 * Passt auf, dass der Printer nie endlos im Kreis laeuft.
 * <ul>
 *     <li><b>Festhaengen beim Laufen:</b> Baritone laeuft, aber der Spieler kommt seit ein paar Sekunden
 *         nicht vom Fleck.</li>
 *     <li><b>Kein Fortschritt:</b> seit der eingestellten Zeit wurde weder ein Block gesetzt noch Geruest
 *         abgebaut noch Material geholt. Dann wird stufenweise eingegriffen (siehe {@link Level}).</li>
 * </ul>
 */
public final class Watchdog
{
    /** Die Stufen, in denen eingegriffen wird, wenn nichts mehr vorangeht. */
    public enum Level
    {
        /** Sperrlisten leeren und alles neu versuchen. */
        RETRY,
        /** Aus der Lage herauslaufen und neu planen. */
        ESCAPE,
        /** Eigenes Geruest in der Naehe abbauen, falls es den Weg versperrt. */
        CLEAR_SCAFFOLD,
        /** Anhalten und melden. */
        GIVE_UP
    }

    private static final int STUCK_TICKS = 20 * 7;
    private static final double STUCK_DISTANCE = 0.5;

    private int idleTicks;
    private int nextLevel;
    private Vec3 anchor = Vec3.ZERO;
    private int anchorTicks;

    public void reset()
    {
        this.idleTicks = 0;
        this.nextLevel = 0;
        this.anchorTicks = 0;
    }

    /** Es ging voran (Block gesetzt, Geruest abgebaut, Material geholt). */
    public void progress()
    {
        this.idleTicks = 0;
        this.nextLevel = 0;
    }

    public int idleSeconds()
    {
        return this.idleTicks / 20;
    }

    /**
     * Einmal pro Tick aufrufen, solange der Printer etwas tun will (nicht in Pausen).
     *
     * @return die Stufe, die jetzt dran ist, oder null
     */
    public Level tick()
    {
        this.idleTicks++;
        int limit = Ep2Configs.PRINTER_STUCK_SECONDS.getIntegerValue() * 20;

        // Stufe 1 nach der eingestellten Zeit, jede weitere eine halbe Zeit spaeter
        if (this.nextLevel < Level.values().length && this.idleTicks >= limit + this.nextLevel * (limit / 2))
        {
            return Level.values()[this.nextLevel++];
        }

        return null;
    }

    /** Waehrend Baritone laeuft: steckt der Spieler fest? */
    public boolean stuckWhileMoving(Vec3 pos)
    {
        if (pos.distanceTo(this.anchor) > STUCK_DISTANCE)
        {
            this.anchor = pos;
            this.anchorTicks = 0;
            return false;
        }

        return ++this.anchorTicks > STUCK_TICKS;
    }

    public void startMoving(Vec3 pos)
    {
        this.anchor = pos;
        this.anchorTicks = 0;
    }
}
