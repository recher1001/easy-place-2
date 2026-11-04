package io.github.recher1001.easyplace2.printer;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import fi.dy.masa.litematica.world.SchematicWorldHandler;

/**
 * Der Printer von Easy Place 2.0 baut jetzt mit dem Printer von Lambda (wird als Jar-in-Jar mitgeliefert).
 *
 * Diese Klasse ist nur noch die Bruecke: Menue, HUD und Hotkeys von Easy Place 2.0 schalten den Lambda-Printer
 * an und aus, und der eigene Schematic-Scanner liefert weiter Fortschritt und fehlendes Material fuer die Anzeige.
 * Gesetzt, gedreht und abgebaut wird ausschliesslich von Lambda.
 */
public final class PrinterController
{
    public static final PrinterController INSTANCE = new PrinterController();

    /** Wird fuer das Menue behalten. Lambda kennt nur einen Modus: es baut alles in Reichweite um den Spieler. */
    public enum Mode { BUILD, EXCAVATE, LINE, AROUND }

    public enum State
    {
        OFF, SCANNING, SELECTING, FETCHING_ITEM, REFILLING, CLEANING, EXCAVATING, FIXING, ADJUSTING, WATERING, BRIDGING, SUPPORTING, TOWERING, TUNNELING, LANE_WALKING, MOVING, AIMING, CLICKING,
        PAUSED, PAUSED_SCREEN, MISSING_MATERIAL, NOTHING_IN_REACH, DONE
    }

    private static final String LAMBDA_PRINTER = "com.lambda.module.modules.world.Printer";

    private final SchematicScanner scanner = new SchematicScanner();
    private final Map<Item, Integer> missingMaterials = new LinkedHashMap<>();

    private Mode mode = Mode.BUILD;
    private State state = State.OFF;
    private String detail = "";
    /** Vom Spieler pausiert: Lambda ist dann aus, der Printer gilt aber weiter als gestartet. */
    private boolean userPaused;
    private boolean wasRunning;
    private int startDone = -1;
    private int placed;
    private long startMillis;

    private PrinterController()
    {
    }

    public void register()
    {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> this.stop(null));
    }

    // ---------------------------------------------------------------- Steuerung

    public boolean isRunning()
    {
        return this.userPaused || lambdaEnabled();
    }

    public boolean isPaused()
    {
        return this.userPaused;
    }

    public void toggle()
    {
        if (this.isRunning())
        {
            this.stop(Component.translatable("easyplace2.printer.msg.stopped"));
        }
        else
        {
            this.start();
        }
    }

    public Mode getMode()
    {
        return this.mode;
    }

    /** Lambda baut immer nur in Reichweite um den Spieler; fuer den Scanner gilt jede Stelle. */
    public boolean aroundAllows(net.minecraft.core.BlockPos pos)
    {
        return true;
    }

    /** Einen reinen Aushoehlen-Modus gibt es mit Lambda nicht. */
    public boolean excavateOnly()
    {
        return false;
    }

    public void start()
    {
        this.start(Mode.BUILD);
    }

    public void startLine()
    {
        this.start(Mode.LINE);
    }

    public void start(Mode mode)
    {
        Minecraft mc = Minecraft.getInstance();

        if (this.isRunning() || mc.player == null || mc.level == null)
        {
            return;
        }

        if (lambdaPrinter() == null)
        {
            message(Component.literal("Lambda-Printer nicht gefunden. Ist Fabric Language Kotlin installiert?").withStyle(ChatFormatting.RED));
            return;
        }

        if (mode == Mode.EXCAVATE || mode == Mode.LINE)
        {
            message(Component.literal("Diesen Modus gibt es mit dem Lambda-Printer nicht. Er baut alles in Reichweite um dich herum.").withStyle(ChatFormatting.YELLOW));
            return;
        }

        if (SchematicWorldHandler.getSchematicWorld() == null)
        {
            message(Component.translatable("easyplace2.printer.msg.no_schematic").withStyle(ChatFormatting.RED));
            return;
        }

        // Printer und Makro gleichzeitig wuerden sich gegenseitig die Maus wegnehmen
        io.github.recher1001.easyplace2.modules.macro.MacroPlayer.INSTANCE.stop(mc, null);
        io.github.recher1001.easyplace2.modules.macro.MacroRecorder.INSTANCE.stop(mc);

        this.scanner.reset();
        this.missingMaterials.clear();
        this.mode = mode;
        this.userPaused = false;
        this.startDone = -1;
        this.placed = 0;
        this.startMillis = System.currentTimeMillis();

        if (setLambdaEnabled(true))
        {
            this.wasRunning = true;
            this.setState(State.SCANNING, "Lambda");
            message(Component.translatable("easyplace2.printer.msg.started").withStyle(ChatFormatting.GREEN));
        }
    }

    public void stop(@Nullable Component reason)
    {
        if (!this.isRunning())
        {
            return;
        }

        this.userPaused = false;
        this.wasRunning = false;
        setLambdaEnabled(false);
        this.setState(State.OFF, "");

        if (reason != null)
        {
            message(reason);
        }
    }

    public void setPaused(boolean paused)
    {
        if (!this.isRunning() || this.userPaused == paused)
        {
            return;
        }

        this.userPaused = paused;
        setLambdaEnabled(!paused);
        this.setState(paused ? State.PAUSED : State.SCANNING, "Lambda");
    }

    // ---------------------------------------------------------------- Werte fuer Menue und HUD

    public State getState()
    {
        return this.state;
    }

    public String getDetail()
    {
        return this.detail;
    }

    @Nullable
    public SchematicScanner.Snapshot getSnapshot()
    {
        return this.scanner.latest();
    }

    /** Welchen Block Lambda gerade setzt, weiss Easy Place 2.0 nicht. */
    @Nullable
    public SchematicScanner.Target getCurrent()
    {
        return null;
    }

    public Map<Item, Integer> getMissingMaterials()
    {
        return Collections.unmodifiableMap(this.missingMaterials);
    }

    public int getPlaced()
    {
        return this.placed;
    }

    /** Bloecke pro Minute seit dem Start. */
    public double getRate()
    {
        double minutes = (System.currentTimeMillis() - this.startMillis) / 60000.0;
        return minutes < 0.05 ? 0.0 : this.placed / minutes;
    }

    /** Geschaetzte Restzeit in Sekunden, -1 wenn noch unbekannt. */
    public long getEtaSeconds()
    {
        SchematicScanner.Snapshot snap = this.scanner.latest();
        double rate = this.getRate();

        if (snap == null || rate <= 0.0)
        {
            return -1;
        }

        int left = Math.max(0, snap.total() - snap.done());
        return Math.round(left / rate * 60.0);
    }

    /** Lambda haelt bei offenen Menues selbst an; die Makros sollen hinter Menues nicht weiterzielen. */
    public boolean worksBehind(@Nullable Screen screen)
    {
        return false;
    }

    // ---------------------------------------------------------------- Tick

    private void tick(Minecraft mc)
    {
        boolean running = this.isRunning();

        // Ueber das Lambda-Menue an- oder ausgeschaltet: Anzeige nachziehen
        if (running && !this.wasRunning)
        {
            this.scanner.reset();
            this.startDone = -1;
            this.placed = 0;
            this.startMillis = System.currentTimeMillis();
        }

        this.wasRunning = running;

        if (!running)
        {
            if (this.state != State.OFF)
            {
                this.setState(State.OFF, "");
            }

            return;
        }

        if (mc.level == null || mc.player == null)
        {
            return;
        }

        if (SchematicWorldHandler.getSchematicWorld() != null)
        {
            this.scanner.tick(mc.level);
        }

        if (this.userPaused)
        {
            this.setState(State.PAUSED, "Lambda");
            return;
        }

        SchematicScanner.Snapshot snap = this.scanner.latest();

        if (snap == null)
        {
            this.setState(State.SCANNING, "Lambda");
            return;
        }

        if (this.startDone < 0)
        {
            this.startDone = snap.done();
        }

        this.placed = Math.max(0, snap.done() - this.startDone);
        this.updateMissing(mc.player, snap);

        if (snap.missing().isEmpty())
        {
            this.setState(State.DONE, "Lambda");
        }
        else if (!this.missingMaterials.isEmpty())
        {
            this.setState(State.MISSING_MATERIAL, "Lambda");
        }
        else
        {
            this.setState(State.CLICKING, "Lambda");
        }
    }

    /** Material, das fuer fehlende Bloecke gebraucht wird und nicht im Inventar ist (mit Anzahl der Bloecke). */
    private void updateMissing(LocalPlayer player, SchematicScanner.Snapshot snap)
    {
        this.missingMaterials.clear();
        Map<Item, Boolean> inInventory = new LinkedHashMap<>();

        for (SchematicScanner.Target t : snap.missing())
        {
            Item item = t.item();

            if (item == null)
            {
                continue;
            }

            if (!inInventory.computeIfAbsent(item, i -> hasItem(player, i)))
            {
                this.missingMaterials.merge(item, 1, Integer::sum);
            }
        }
    }

    private static boolean hasItem(LocalPlayer player, Item item)
    {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
        {
            ItemStack stack = player.getInventory().getItem(i);

            if (!stack.isEmpty() && stack.getItem() == item)
            {
                return true;
            }
        }

        return false;
    }

    private void setState(State state, String detail)
    {
        this.state = state;
        this.detail = detail;
    }

    // ---------------------------------------------------------------- Lambda (per Reflection)

    @Nullable
    private static Object lambdaPrinter()
    {
        try
        {
            return Class.forName(LAMBDA_PRINTER).getField("INSTANCE").get(null);
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    private static boolean lambdaEnabled()
    {
        Object printer = lambdaPrinter();

        if (printer == null)
        {
            return false;
        }

        try
        {
            return (Boolean) printer.getClass().getMethod("isEnabled").invoke(printer);
        }
        catch (Throwable e)
        {
            return false;
        }
    }

    private static boolean setLambdaEnabled(boolean enabled)
    {
        Object printer = lambdaPrinter();

        if (printer == null)
        {
            return false;
        }

        try
        {
            Method method = printer.getClass().getMethod(enabled ? "enable" : "disable");
            method.invoke(printer);
            return true;
        }
        catch (Throwable e)
        {
            message(Component.literal("Lambda-Printer konnte nicht geschaltet werden: " + e).withStyle(ChatFormatting.RED));
            return false;
        }
    }

    private static void message(Component text)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player != null)
        {
            mc.player.displayClientMessage(Component.literal("[Printer] ").withStyle(ChatFormatting.AQUA).append(text), false);
        }
    }
}
