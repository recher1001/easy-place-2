package io.github.recher1001.easyplace2.modules.singleplayer;

import java.util.ArrayList;
import java.util.List;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.config.Ep2Configs;

/**
 * Nur fuer Einzelspieler: zeigt die Umrisse von Hoehlen in der Naehe durch Waende hindurch (keine Erze).
 * <p>
 * Eine Hoehle ist hier Luft unter der Oberflaeche (unter der hoechsten Stelle der Welt an dieser X/Z-Position).
 * Wo so eine Luft an einen festen Block grenzt, liegt eine Hoehlenwand. Gezeichnet werden nur die Raender jeder
 * ebenen Wandflaeche, so sieht man die Form der Hoehle statt eines Gitters. Gescannt wird in Stuecken pro Tick.
 * <p>
 * Auf Servern (auch LAN-Welten anderer) ist das aus: es laeuft nur, wenn die Welt auf diesem Rechner laeuft.
 */
public final class CaveOutlines
{
    public static final CaveOutlines INSTANCE = new CaveOutlines();

    /** So viele Bloecke werden pro Tick geprueft. */
    private static final int BLOCKS_PER_TICK = 24_000;
    /** Hoechstens so viele Linien werden gezeichnet (die naechsten zuerst). */
    private static final int MAX_LINES = 12_000;
    private static final int COLOR = 0xFF33DDFF;
    private static final int COLOR_DEEP = 0xFF8A5CFF;

    /** Nur fuer den Spieltest: auch auf einem Server (dort gibt es keine Einzelspielerwelt). */
    public static boolean allowAnywhere;

    private record Line(Vec3 a, Vec3 b, int color, double dist) {}

    private List<Line> lines = List.of();
    private int emitted;

    // laufender Scan
    private final LongOpenHashSet cave = new LongOpenHashSet();
    private BlockPos.MutableBlockPos cursor;
    private BlockPos min = BlockPos.ZERO, max = BlockPos.ZERO;
    private boolean scanning;
    private boolean hintShown;

    private CaveOutlines()
    {
    }

    public void register()
    {
        ClientTickEvents.END_CLIENT_TICK.register(mc ->
        {
            this.tick(mc);
            // Am Ende jedes Ticks: Minecraft sammelt hier die Linien eines Ticks und zeichnet sie bis zum naechsten
            this.emit();
        });
    }

    /** Darf es hier laufen? Nur in der eigenen Einzelspielerwelt. */
    public static boolean allowed(Minecraft mc)
    {
        return allowAnywhere || mc.hasSingleplayerServer();
    }

    public static boolean active(Minecraft mc)
    {
        return Ep2Configs.SP_CAVES.getBooleanValue() && mc.level != null && mc.player != null && allowed(mc);
    }

    /** Taste: an/aus. Auf einem Server nur ein Hinweis. */
    public static void toggle(Minecraft mc)
    {
        if (!allowed(mc))
        {
            Ep2Configs.SP_CAVES.setBooleanValue(false);
            message(mc, Component.translatable("easyplace2.caves.msg.singleplayer_only").withStyle(ChatFormatting.GOLD));
            return;
        }

        boolean on = !Ep2Configs.SP_CAVES.getBooleanValue();
        Ep2Configs.SP_CAVES.setBooleanValue(on);
        message(mc, Component.translatable(on ? "easyplace2.caves.msg.on" : "easyplace2.caves.msg.off")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
    }

    /** Wie viele Linien beim letzten Zeichnen ausgegeben wurden (fuer den Spieltest). */
    public int emitted()
    {
        return this.emitted;
    }

    /** Wie viele Linien gerade bereitstehen (fuer den Spieltest). */
    public int lineCount()
    {
        return this.lines.size();
    }

    private void tick(Minecraft mc)
    {
        if (!active(mc))
        {
            if (!allowed(mc) && Ep2Configs.SP_CAVES.getBooleanValue() && mc.level != null && !this.hintShown)
            {
                // Auf einem Server angeschaltet (z. B. aus der gespeicherten Einstellung): einmal sagen, warum nichts kommt
                this.hintShown = true;
                message(mc, Component.translatable("easyplace2.caves.msg.singleplayer_only").withStyle(ChatFormatting.GOLD));
            }

            this.lines = List.of();
            this.scanning = false;
            return;
        }

        this.hintShown = false;
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;

        if (!this.scanning)
        {
            int r = Ep2Configs.SP_CAVES_RANGE.getIntegerValue();
            BlockPos c = player.blockPosition();
            this.min = new BlockPos(c.getX() - r, Math.max(level.getMinY(), c.getY() - r), c.getZ() - r);
            this.max = new BlockPos(c.getX() + r, Math.min(level.getMaxY(), c.getY() + r), c.getZ() + r);
            this.cursor = this.min.mutable();
            this.cave.clear();
            this.scanning = true;
        }

        int budget = BLOCKS_PER_TICK;

        while (budget-- > 0 && this.scanning)
        {
            BlockPos.MutableBlockPos p = this.cursor;

            if (level.hasChunkAt(p) && isCaveAir(level, p))
            {
                this.cave.add(p.asLong());
            }

            if (p.getX() < this.max.getX())
            {
                p.setX(p.getX() + 1);
            }
            else if (p.getZ() < this.max.getZ())
            {
                p.set(this.min.getX(), p.getY(), p.getZ() + 1);
            }
            else if (p.getY() < this.max.getY())
            {
                p.set(this.min.getX(), p.getY() + 1, this.min.getZ());
            }
            else
            {
                this.lines = this.buildLines(level, player.getEyePosition());
                this.scanning = false;
            }
        }
    }

    private static boolean isCaveAir(ClientLevel level, BlockPos pos)
    {
        BlockState state = level.getBlockState(pos);

        if (!state.isAir())
        {
            return false;
        }

        // Unter der Oberflaeche (dort, wo von oben kein Himmel hereinkommt)
        return pos.getY() < level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()) - 1;
    }

    private static boolean solid(ClientLevel level, BlockPos pos)
    {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty() && state.isSolidRender();
    }

    /**
     * Die Raender der Hoehlenwaende: eine Wandflaeche liegt zwischen Hoehlenluft und einem festen Block. Von jeder
     * Flaeche wird eine Kante nur gezeichnet, wenn daneben (in derselben Ebene) keine Wandflaeche weitergeht.
     */
    private List<Line> buildLines(ClientLevel level, Vec3 eye)
    {
        List<Line> result = new ArrayList<>();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        int deep = level.getMinY() + (level.getMaxY() - level.getMinY()) / 6;

        for (long key : this.cave)
        {
            BlockPos p = BlockPos.of(key);

            for (Direction d : Direction.values())
            {
                n.setWithOffset(p, d);

                if (!solid(level, n))
                {
                    continue;
                }

                // Die Flaeche liegt auf der Seite d des Luftblocks p. Ihre 4 Kanten: in den beiden Richtungen quer zu d
                for (Direction t : Direction.values())
                {
                    if (t.getAxis() == d.getAxis())
                    {
                        continue;
                    }

                    // Geht die Wand in Richtung t in derselben Ebene weiter? Dann keine Kante
                    q.setWithOffset(p, t);
                    boolean continues = this.cave.contains(q.asLong()) && solid(level, q.move(d));

                    if (continues)
                    {
                        continue;
                    }

                    Vec3[] edge = edge(p, d, t);
                    double dist = edge[0].add(edge[1]).scale(0.5).distanceToSqr(eye);
                    result.add(new Line(edge[0], edge[1], p.getY() < deep ? COLOR_DEEP : COLOR, dist));
                }
            }
        }

        if (result.size() > MAX_LINES)
        {
            result.sort((a, b) -> Double.compare(a.dist(), b.dist()));
            result = new ArrayList<>(result.subList(0, MAX_LINES));
        }

        return result;
    }

    /** Die Kante der Flaeche auf Seite {@code d} von Block {@code p}, die in Richtung {@code t} liegt. */
    private static Vec3[] edge(BlockPos p, Direction d, Direction t)
    {
        // Mittelpunkt der Flaeche, dann zur Kante hin, dann entlang der dritten Achse
        Vec3 center = Vec3.atCenterOf(p).add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
        Vec3 mid = center.add(t.getStepX() * 0.5, t.getStepY() * 0.5, t.getStepZ() * 0.5);
        Direction.Axis third = thirdAxis(d.getAxis(), t.getAxis());
        Vec3 along = switch (third)
        {
            case X -> new Vec3(0.5, 0, 0);
            case Y -> new Vec3(0, 0.5, 0);
            case Z -> new Vec3(0, 0, 0.5);
        };
        return new Vec3[] { mid.subtract(along), mid.add(along) };
    }

    private static Direction.Axis thirdAxis(Direction.Axis a, Direction.Axis b)
    {
        for (Direction.Axis axis : Direction.Axis.values())
        {
            if (axis != a && axis != b)
            {
                return axis;
            }
        }

        return Direction.Axis.Y;
    }

    /** Aus dem Debug-Zeichnen von Minecraft aufgerufen (dort werden Linien gesammelt und gezeichnet). */
    public void emit()
    {
        Minecraft mc = Minecraft.getInstance();
        this.emitted = 0;

        if (!active(mc))
        {
            return;
        }

        for (Line line : this.lines)
        {
            // Etwas laenger als ein Tick sichtbar, damit es zwischen zwei Ticks nicht flackert
            Gizmos.line(line.a(), line.b(), line.color(), 2.0f).setAlwaysOnTop().persistForMillis(60);
            this.emitted++;
        }
    }

    private static void message(Minecraft mc, Component text)
    {
        if (mc.player != null)
        {
            mc.player.displayClientMessage(text, true);
        }
    }
}
