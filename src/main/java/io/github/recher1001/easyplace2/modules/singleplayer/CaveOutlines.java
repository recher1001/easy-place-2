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
 * Auf Servern ist das aus, ausser der Spieler hat genau diesen Server selbst freigegeben (z. B. den eigenen Server,
 * auf dem seine Welt laeuft). Die Freigabe gilt nur fuer diese eine Adresse.
 */
public final class CaveOutlines
{
    public static final CaveOutlines INSTANCE = new CaveOutlines();

    /** So viele Bloecke werden pro Tick geprueft. */
    private static final int BLOCKS_PER_TICK = 24_000;
    /** Hoechstens so viele Linien werden gezeichnet (die naechsten zuerst). */
    private static final int MAX_LINES = 8_000;
    /** Hoechstens so viele Erze (die naechsten zuerst). */
    private static final int MAX_ORES = 600;
    // Leichter Schein statt harter Linien: Farbe ohne Deckkraft, die kommt beim Zeichnen dazu
    private static final int COLOR = 0x33DDFF;
    private static final int COLOR_DEEP = 0x8A5CFF;

    /** Nur fuer den Spieltest: auch auf einem Server (dort gibt es keine Einzelspielerwelt). */
    public static boolean allowAnywhere;

    private record Line(Vec3 a, Vec3 b, int color, double dist) {}

    private record Ore(BlockPos pos, int color, double dist) {}

    private List<Line> lines = List.of();
    private List<Ore> ores = List.of();
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

    /** Darf es hier laufen? In der eigenen Einzelspielerwelt, oder auf einem Server, den der Spieler selbst freigegeben hat. */
    public static boolean allowed(Minecraft mc)
    {
        if (allowAnywhere || mc.hasSingleplayerServer())
        {
            return true;
        }

        String address = currentServer(mc);
        return address != null && servers().contains(address);
    }

    /** Die Adresse des Servers, auf dem der Spieler gerade ist (klein geschrieben, ohne Standard-Port), oder null. */
    @org.jetbrains.annotations.Nullable
    public static String currentServer(Minecraft mc)
    {
        net.minecraft.client.multiplayer.ServerData data = mc.getCurrentServer();

        if (data == null || mc.hasSingleplayerServer())
        {
            return null;
        }

        return normalize(data.ip);
    }

    private static String normalize(String address)
    {
        String a = address.trim().toLowerCase(java.util.Locale.ROOT);
        return a.endsWith(":25565") ? a.substring(0, a.length() - 6) : a;
    }

    /** Die freigegebenen Server. */
    public static java.util.Set<String> servers()
    {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();

        for (String part : Ep2Configs.SP_CAVES_SERVERS.getStringValue().split(","))
        {
            if (!part.isBlank())
            {
                set.add(normalize(part));
            }
        }

        return set;
    }

    /** Den Server, auf dem man gerade ist, freigeben oder die Freigabe wieder entfernen. */
    public static void setCurrentServerAllowed(Minecraft mc, boolean allow)
    {
        String address = currentServer(mc);

        if (address == null)
        {
            return;
        }

        java.util.Set<String> set = servers();

        if (allow)
        {
            set.add(address);
        }
        else
        {
            set.remove(address);
        }

        Ep2Configs.SP_CAVES_SERVERS.setValueFromString(String.join(",", set));
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
            message(mc, Component.translatable("easyplace2.caves.msg.server_not_allowed").withStyle(ChatFormatting.GOLD));
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
                message(mc, Component.translatable("easyplace2.caves.msg.server_not_allowed").withStyle(ChatFormatting.GOLD));
            }

            this.lines = List.of();
            this.ores = List.of();
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
                this.ores = Ep2Configs.SP_CAVES_ORES.getBooleanValue() ? this.findOres(level, player.getEyePosition()) : List.of();
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

    /**
     * Erze an den Hoehlenwaenden: nur, was direkt an Hoehlenluft grenzt (also in der Hoehle zu sehen waere), keine
     * Erze tief im Stein.
     */
    private List<Ore> findOres(ClientLevel level, Vec3 eye)
    {
        List<Ore> result = new ArrayList<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();

        for (long key : this.cave)
        {
            BlockPos p = BlockPos.of(key);

            for (Direction d : Direction.values())
            {
                n.setWithOffset(p, d);

                if (!seen.add(n.asLong()))
                {
                    continue;
                }

                int color = oreColor(level.getBlockState(n));

                if (color != 0)
                {
                    result.add(new Ore(n.immutable(), color, Vec3.atCenterOf(n).distanceToSqr(eye)));
                }
            }
        }

        if (result.size() > MAX_ORES)
        {
            result.sort((a, b) -> Double.compare(a.dist(), b.dist()));
            result = new ArrayList<>(result.subList(0, MAX_ORES));
        }

        return result;
    }

    /** Farbe fuer ein Erz (ohne Deckkraft), 0 wenn es keins ist. */
    private static int oreColor(BlockState state)
    {
        if (state.is(net.minecraft.tags.BlockTags.DIAMOND_ORES)) return 0x4DF2FF;
        if (state.is(net.minecraft.tags.BlockTags.EMERALD_ORES)) return 0x3DFF6E;
        if (state.is(net.minecraft.world.level.block.Blocks.ANCIENT_DEBRIS)) return 0xB0704A;
        if (state.is(net.minecraft.tags.BlockTags.GOLD_ORES)) return 0xFFD23D;
        if (state.is(net.minecraft.tags.BlockTags.REDSTONE_ORES)) return 0xFF3B3B;
        if (state.is(net.minecraft.tags.BlockTags.LAPIS_ORES)) return 0x3D63FF;
        if (state.is(net.minecraft.tags.BlockTags.IRON_ORES)) return 0xE8C2A0;
        if (state.is(net.minecraft.tags.BlockTags.COPPER_ORES)) return 0xFF8A3D;
        if (state.is(net.minecraft.world.level.block.Blocks.NETHER_QUARTZ_ORE)) return 0xF2EDE4;
        if (state.is(net.minecraft.tags.BlockTags.COAL_ORES)) return Ep2Configs.SP_CAVES_COAL.getBooleanValue() ? 0x9A9A9A : 0;
        return 0;
    }

    /** Wie viele Erze gerade markiert sind (fuer den Spieltest). */
    public int oreCount()
    {
        return this.ores.size();
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

        // Etwas laenger als ein Tick sichtbar, damit es zwischen zwei Ticks nicht flackert. Jede Linie zweimal: breit und
        // fast durchsichtig (der Schein), darueber schmal und halb durchsichtig.
        for (Line line : this.lines)
        {
            Gizmos.line(line.a(), line.b(), 0x18000000 | line.color(), 7.0f).setAlwaysOnTop().persistForMillis(60);
            Gizmos.line(line.a(), line.b(), 0x70000000 | line.color(), 1.5f).setAlwaysOnTop().persistForMillis(60);
            this.emitted++;
        }

        // Erze: ein leicht leuchtender Kasten in der Erzfarbe
        for (Ore ore : this.ores)
        {
            Gizmos.cuboid(new net.minecraft.world.phys.AABB(ore.pos()).inflate(0.03),
                    GizmoStyle.strokeAndFill(0xD0000000 | ore.color(), 2.0f, 0x38000000 | ore.color())).setAlwaysOnTop().persistForMillis(60);
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
