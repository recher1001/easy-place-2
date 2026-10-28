package io.github.recher1001.easyplace2.printer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;

import io.github.recher1001.easyplace2.EasyPlace2;
import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.mixin.MinecraftAccessor;
import io.github.recher1001.easyplace2.placement.EasyPlace2Handler;
import io.github.recher1001.easyplace2.placement.StateMatcher;
import io.github.recher1001.easyplace2.printer.refill.RefillManager;
import io.github.recher1001.easyplace2.printer.lanes.BuildLanes;
import io.github.recher1001.easyplace2.printer.lanes.LaneMiner;
import io.github.recher1001.easyplace2.printer.lanes.LaneWalker;
import io.github.recher1001.easyplace2.printer.lanes.StarTool;
import io.github.recher1001.easyplace2.printer.scaffold.ScaffoldSource;
import io.github.recher1001.easyplace2.printer.scaffold.ScaffoldTracker;

/**
 * Der Printer: baut die aktiven Litematica-Schematics automatisch.
 * <p>
 * Ablauf fuer jeden Block: Ziel waehlen → (mit Baritone hinlaufen) → Item wie ein Spieler in die Hand holen →
 * Kamera fluessig auf die Nachbarflaeche drehen → pruefen, dass das echte Fadenkreuz genau dort ist →
 * normaler Rechtsklick. Den Klick selbst fuehrt Easy Place 2.0 mit strengem Fadenkreuz aus.
 * Es gibt keine eigenen Platzierungspakete, keinen Air-Place und keine unsichtbare Drehung.
 */
public final class PrinterController
{
    public static final PrinterController INSTANCE = new PrinterController();

    public enum State
    {
        OFF, SCANNING, SELECTING, FETCHING_ITEM, REFILLING, CLEANING, EXCAVATING, LANE_WALKING, MOVING, AIMING, CLICKING, WAITING,
        PAUSED, PAUSED_SCREEN, MISSING_MATERIAL, NOTHING_IN_REACH, DONE
    }

    /** Wie oft ein Block scheitern darf, bevor er eine Weile uebersprungen wird. */
    private static final int MAX_FAILURES = 3;
    private static final int BLACKLIST_TICKS = 20 * 30;
    private static final int MOVE_TIMEOUT_TICKS = 20 * 60;
    private static final int AIM_TIMEOUT_TICKS = 20 * 3;
    private static final int CANDIDATES_PER_TICK = 24;
    private static final int MAX_UNLOADED_ATTEMPTS = 3;
    /** Im Notfall: so weit (Bloecke) wird aus einer verfahrenen Lage herausgelaufen. */
    private static final int ESCAPE_DISTANCE = 10;
    /** Im Notfall: Geruest in diesem Umkreis wird abgebaut. */
    private static final double CLEAR_RADIUS = 6.0;

    private final SchematicScanner scanner = new SchematicScanner();
    private final InventoryManager inventory = new InventoryManager();
    private final RefillManager refill = new RefillManager();
    private final BlockDigger cleaner = new BlockDigger(new ScaffoldSource());
    private final Excavation excavation = new Excavation();
    private final BlockDigger excavator = new BlockDigger(this.excavation);
    private final LaneMiner laneMiner = new LaneMiner();
    private final BuildLanes buildLanes = new BuildLanes();
    private final LaneWalker walker = new LaneWalker();
    /** Ging das Laufen in der Bahn schief, eine Weile normal weitermachen. */
    private long laneFailUntil;
    private final Watchdog watchdog = new Watchdog();
    /** Ist gerade ein Menue offen, hinter dem der Printer weiterbaut (ESC, Chat ...)? */
    private boolean behindScreen;
    private final Random random = new Random();

    private boolean running;
    private boolean userPaused;
    private State state = State.OFF;
    private String detail = "";

    @Nullable private SchematicScanner.Target current;
    private List<BlockHitResult> aimHits = List.of();
    private int aimIndex;
    private int stateTicks;
    private int settleTicks;
    private long nextClickTick;
    private long damagePauseUntil;
    private int selectRound;

    @Nullable private BlockPos moveTarget;
    private long moveStartTick;
    private Vec3 moveStartPos = Vec3.ZERO;
    private final Map<BlockPos, Integer> unloadedAttempts = new HashMap<>();
    private final Map<BlockPos, Integer> moveAttempts = new HashMap<>();
    private final Map<BlockPos, Integer> failures = new HashMap<>();
    private final Map<BlockPos, Long> blacklist = new HashMap<>();
    private final Map<Item, Integer> missingMaterials = new LinkedHashMap<>();
    private final List<Pending> pending = new ArrayList<>();

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
        return this.running;
    }

    public boolean isPaused()
    {
        return this.userPaused;
    }

    public void toggle()
    {
        if (this.running)
        {
            this.stop(Component.translatable("easyplace2.printer.msg.stopped"));
        }
        else
        {
            this.start();
        }
    }

    public void start()
    {
        Minecraft mc = Minecraft.getInstance();

        if (this.running || mc.player == null || mc.level == null)
        {
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

        // Platziert wird immer ueber Easy Place 2.0, und zwar nur dort, wo das Fadenkreuz wirklich hinzeigt
        Ep2Configs.EASY_PLACE_2.setBooleanValue(true);
        Ep2Configs.STRICT_CROSSHAIR.setBooleanValue(true);

        if (Ep2Configs.PRINTER_USE_BARITONE.getBooleanValue())
        {
            BaritoneBridge.applySettings(Ep2Configs.PRINTER_SPRINT.getBooleanValue());
            BaritoneBridge.setScaffold(false, List.of());
        }

        this.scanner.reset();
        this.cleaner.reset();
        this.excavator.reset();
        this.laneMiner.reset();
        this.buildLanes.reset();
        this.laneFailUntil = 0;
        this.watchdog.reset();
        this.unloadedAttempts.clear();
        this.failures.clear();
        this.blacklist.clear();
        this.moveAttempts.clear();
        this.missingMaterials.clear();
        this.pending.clear();
        this.placed = 0;
        this.startMillis = System.currentTimeMillis();
        this.running = true;
        this.userPaused = false;
        this.setState(State.SCANNING, "");
        message(Component.translatable("easyplace2.printer.msg.started").withStyle(ChatFormatting.GREEN));
    }

    public void stop(@Nullable Component reason)
    {
        if (!this.running)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        this.running = false;
        this.userPaused = false;
        this.current = null;
        this.moveTarget = null;
        AimController.INSTANCE.stop();
        this.refill.cancel(mc);
        this.cleaner.cancel(mc);
        this.excavator.cancel(mc);
        this.walker.cancel(mc);
        this.inventory.cancel(mc);
        BaritoneBridge.cancel();
        BaritoneBridge.restoreSettings();
        this.setState(State.OFF, "");

        if (reason != null)
        {
            message(reason);
        }
    }

    public void setPaused(boolean paused)
    {
        if (!this.running || this.userPaused == paused)
        {
            return;
        }

        this.userPaused = paused;

        if (paused)
        {
            this.interrupt(Minecraft.getInstance());
            this.setState(State.PAUSED, "");
        }
        else
        {
            this.watchdog.progress();
            this.setState(State.SELECTING, "");
        }
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

    @Nullable
    public SchematicScanner.Target getCurrent()
    {
        return this.current;
    }

    public Map<Item, Integer> getMissingMaterials()
    {
        return this.missingMaterials;
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

        return Math.round(snap.missing().size() / rate * 60.0);
    }

    // ---------------------------------------------------------------- Ablauf

    private void tick(Minecraft mc)
    {
        if (!this.running)
        {
            return;
        }

        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        Level schematic = SchematicWorldHandler.getSchematicWorld();

        if (player == null || level == null || mc.gameMode == null)
        {
            this.stop(null);
            return;
        }

        if (player.isDeadOrDying())
        {
            this.stop(Component.translatable("easyplace2.printer.msg.died").withStyle(ChatFormatting.RED));
            return;
        }

        if (schematic == null)
        {
            this.stop(Component.translatable("easyplace2.printer.msg.no_schematic").withStyle(ChatFormatting.RED));
            return;
        }

        this.scanner.tick(level);

        // Bei Kisten und Inventaren wartet der Printer (sonst kaemen sich die Klicks in die Quere). Hinter dem
        // Pause-Menue (ESC), dem Chat oder dem Printer-Menue baut er auf Wunsch weiter.
        boolean foreignScreen = mc.screen != null && !this.inventory.ownsScreen(mc.screen) && !this.refill.ownsScreen(mc.screen);
        this.behindScreen = foreignScreen && this.worksBehind(mc.screen);

        if (this.behindScreen && this.state == State.REFILLING)
        {
            // Material holen braucht Kisten-Menues: abbrechen, nach dem Menue geht es weiter
            this.refill.cancel(mc);
            this.setState(State.SELECTING, "");
        }

        if (foreignScreen && !this.behindScreen)
        {
            if (this.state != State.PAUSED_SCREEN)
            {
                this.interrupt(mc);
                this.setState(State.PAUSED_SCREEN, "");
            }

            return;
        }

        if (this.state == State.PAUSED_SCREEN)
        {
            this.setState(this.userPaused ? State.PAUSED : State.SELECTING, "");
        }

        if (this.userPaused)
        {
            return;
        }

        long now = level.getGameTime();
        this.checkPending(level, now);

        // Bei Schaden kurz alles loslassen und abwarten (z. B. Mob, Lava, Fall)
        if (Ep2Configs.PRINTER_PAUSE_ON_DAMAGE.getBooleanValue() && player.hurtTime > 0)
        {
            this.damagePauseUntil = now + 60;
        }

        if (now < this.damagePauseUntil)
        {
            if (this.state != State.PAUSED)
            {
                this.interrupt(mc);
                this.setState(State.PAUSED, "damage");
            }

            return;
        }

        if (this.state == State.PAUSED)
        {
            this.setState(State.SELECTING, "");
        }

        // Beim Material-Holen steuert der Vorgang das Inventar selbst
        if (this.state != State.REFILLING && this.inventory.isBusy())
        {
            this.inventory.tick(mc);

            if (this.inventory.isBusy())
            {
                this.setState(State.FETCHING_ITEM, this.detail);
                return;
            }

            this.setState(State.SELECTING, "");
        }

        this.stateTicks++;

        // Nie endlos im Kreis: geht nichts mehr voran, wird stufenweise eingegriffen
        if (this.watchdogCounts())
        {
            Watchdog.Level level0 = this.watchdog.tick();

            if (level0 != null)
            {
                this.escalate(mc, player, level, schematic, level0, now);
                return;
            }
        }

        switch (this.state)
        {
            case SCANNING, SELECTING, MISSING_MATERIAL, NOTHING_IN_REACH, FETCHING_ITEM ->
            {
                if (this.stateTicks > 0)
                {
                    this.select(mc, player, level, schematic, now);
                }
            }
            case REFILLING ->
            {
                if (this.refill.tick(mc, now))
                {
                    this.setState(State.SELECTING, "");
                }
                else
                {
                    this.detail = this.refill.detail();
                }
            }
            case CLEANING, EXCAVATING ->
            {
                BlockDigger digger = this.state == State.CLEANING ? this.cleaner : this.excavator;
                int before = digger.broken();

                if (digger.tick(mc, now, this.canWalk()))
                {
                    boolean broke = digger.broken() > before;

                    if (broke)
                    {
                        this.watchdog.progress();
                    }

                    // Aushoehlen: gleich mit dem naechsten Block weitermachen, ohne Pause und ohne neuen Scan
                    if (broke && digger == this.excavator && !Ep2Configs.PRINTER_LANE_MODE.getBooleanValue() &&
                        this.excavator.start(player, level, schematic, p -> true, now))
                    {
                        this.detail = this.excavator.detail();
                        return;
                    }

                    if (broke && digger == this.cleaner)
                    {
                        // Neu scannen: an der Stelle fehlt jetzt evtl. ein Block der Schematic
                        this.scanner.reset();
                    }

                    // Kurz durchatmen (z. B. nach dem Runterfallen beim Turm-Abstieg), dann neu planen
                    this.setState(State.SELECTING, "");
                    this.stateTicks = broke && digger == this.cleaner ? -(3 + this.random.nextInt(4)) : 0;
                }
            }
            case LANE_WALKING ->
            {
                LaneWalker.Result result = this.walker.tick(mc);

                if (result == LaneWalker.Result.FAILED)
                {
                    debug("Laufen in der Bahn ging nicht, eine Weile normal weiter");
                    this.laneFailUntil = now + 20 * 10;
                    this.laneMiner.reset();
                    this.buildLanes.reset();
                }

                if (result != LaneWalker.Result.RUNNING)
                {
                    this.setState(State.SELECTING, "");
                }
            }
            case MOVING -> this.tickMoving(player, now);
            case AIMING -> this.tickAiming(level);
            case CLICKING -> this.tickClicking(mc, player, level, now);
            case WAITING -> this.tickWaiting(level);
            default -> {}
        }
    }

    private void select(Minecraft mc, LocalPlayer player, ClientLevel level, Level schematic, long now)
    {
        SchematicScanner.Snapshot snap = this.scanner.latest();

        if (snap == null)
        {
            this.setState(State.SCANNING, "");
            return;
        }

        if (snap.total() == 0 && snap.unloaded().isEmpty())
        {
            this.stop(Component.translatable("easyplace2.printer.msg.no_schematic").withStyle(ChatFormatting.RED));
            return;
        }

        this.blacklist.values().removeIf(until -> until <= now);

        // Steht noch eine Shulker vom Material-Holen herum, wird sie zuerst wieder eingesammelt
        if (!this.behindScreen && this.refill.resumeLeftover())
        {
            this.setState(State.REFILLING, this.refill.detail());
            return;
        }

        this.updateScaffoldItems(player, snap);
        List<SchematicScanner.Target> open = new ArrayList<>();

        for (SchematicScanner.Target t : snap.missing())
        {
            if (!this.blacklist.containsKey(t.pos()) && isStillMissing(level, t))
            {
                open.add(t);
            }
        }

        // Geruest, das auf einem Platz der Schematic steht, wird ersetzt, sobald diese Schicht dran ist.
        // Hoeher liegendes bleibt vorerst stehen, damit sich der Printer nicht den Weg nach oben abbaut.
        if (!snap.scaffold().isEmpty())
        {
            int lowestOpen = open.stream().mapToInt(t -> t.pos().getY()).min().orElse(Integer.MAX_VALUE - 1);
            Set<BlockPos> blocking = new HashSet<>(snap.scaffold());

            if (this.cleaner.start(player, level, schematic, p -> blocking.contains(p) && p.getY() <= lowestOpen + 1, now))
            {
                AimController.INSTANCE.stop();
                this.setState(State.CLEANING, this.cleaner.detail());
                return;
            }
        }

        // Aushoehlen: was weg muss, kommt zuerst (von oben nach unten), danach wird gebaut
        this.excavation.update(snap.excavate());

        if (!snap.excavate().isEmpty())
        {
            // In Bahnen: Gang geradeaus, Kurve, daneben zurueck, dann eine Lage tiefer
            if (Ep2Configs.PRINTER_LANE_MODE.getBooleanValue() && now >= this.laneFailUntil)
            {
                LaneMiner.Action action = this.laneMiner.next(player, level, schematic, this.excavation, this.excavator, now);

                if (action.kind() == LaneMiner.Kind.DIG)
                {
                    this.excavator.setTool(action.forceSlot(), action.avoidSlot());
                    Set<BlockPos> dig = action.dig();

                    if (this.excavator.start(player, level, schematic, dig::contains, now))
                    {
                        this.setState(State.EXCAVATING, this.excavator.detail());
                        return;
                    }
                }
                else if (action.kind() == LaneMiner.Kind.WALK && action.walkTo() != null && this.walkLane(player, level, action.walkTo(), now))
                {
                    return;
                }
            }

            // Sonst (oder fuer den Rest): der naechste Block in Reichweite; nie mit der Star-Spitzhacke
            this.excavator.setTool(-1, StarTool.findSlot(player));

            if (this.excavator.start(player, level, schematic, p -> true, now))
            {
                AimController.INSTANCE.stop();
                this.setState(State.EXCAVATING, this.excavator.detail());
                return;
            }
        }

        if (open.isEmpty())
        {
            // Hier ist alles gebaut: erst alles Geruest wieder abbauen, dann fertig (oder zum naechsten Teil laufen)
            if (snap.missing().isEmpty() && this.cleaner.start(player, level, schematic, p -> true, now))
            {
                AimController.INSTANCE.stop();
                this.setState(State.CLEANING, this.cleaner.detail());
                return;
            }

            if (!snap.unloaded().isEmpty() && this.canWalk())
            {
                this.walkToUnloaded(player, snap.unloaded(), now);
            }
            else if (snap.missing().isEmpty() && snap.unloaded().isEmpty() && this.cleaner.skippedCount(now) == 0 &&
                     this.excavator.skippedCount(now) == 0 && !this.excavator.hasWork(level, schematic, p -> true, now))
            {
                // Uebersprungenes Geruest wird vorher noch einmal versucht (der Watchdog sorgt dafuer, dass das endet)
                int left = (int) ScaffoldTracker.INSTANCE.positions().stream().filter(level::hasChunkAt).count();
                this.setState(State.DONE, "");
                this.stop(Component.translatable("easyplace2.printer.msg.done", snap.done(), snap.total(), snap.wrong()).withStyle(ChatFormatting.GREEN));

                if (left > 0)
                {
                    message(Component.translatable("easyplace2.printer.msg.scaffold_left", left).withStyle(ChatFormatting.GOLD));
                }
            }
            else
            {
                this.setState(State.SELECTING, "skipped");
            }

            return;
        }

        // Material: was gebraucht wird, aber gar nicht im Inventar ist
        this.missingMaterials.clear();
        Map<Item, Integer> haveCache = new HashMap<>();
        List<SchematicScanner.Target> buildable = new ArrayList<>();

        for (SchematicScanner.Target t : open)
        {
            if (haveCache.computeIfAbsent(t.item(), item -> InventoryManager.countItem(player, item)) > 0)
            {
                buildable.add(t);
            }
            else
            {
                this.missingMaterials.merge(t.item(), 1, Integer::sum);
            }
        }

        if (buildable.isEmpty())
        {
            AimController.INSTANCE.stop();

            // Material besorgen: Shulker im Inventar, sonst Lager (geht nicht hinter einem Menue)
            if (!this.behindScreen && this.refill.tryStart(player, level, schematic, new HashSet<>(this.missingMaterials.keySet()), this.inventory, now))
            {
                this.setState(State.REFILLING, this.refill.detail());
                return;
            }

            this.setState(State.MISSING_MATERIAL, "");
            return;
        }

        // Von unten nach oben bauen: nur die unterste offene Schicht (und eine darueber)
        int lowest = buildable.stream().mapToInt(t -> t.pos().getY()).min().orElse(0);
        Vec3 eye = player.getEyePosition();
        List<SchematicScanner.Target> layer = new ArrayList<>(buildable.stream().filter(t -> t.pos().getY() <= lowest + 1).toList());
        layer.sort(Comparator.comparingDouble(t -> Vec3.atCenterOf(t.pos()).distanceToSqr(eye)));

        double reach = player.blockInteractionRange();
        int tried = 0;

        // Reihenfolge, nicht zufaellig: von unten nach oben; in einer Schicht von hinten nach vorne (sonst
        // verdecken die nahen spaeter die Flaechen fuer die hinteren); unter den hintersten dann der, zu dem
        // man sich am wenigsten drehen muss, am liebsten mit dem Item, das schon in der Hand ist
        List<SchematicScanner.Target> reachable = new ArrayList<>();

        for (SchematicScanner.Target t : layer)
        {
            if (Vec3.atCenterOf(t.pos()).distanceTo(eye) > reach + 1.0)
            {
                break;
            }

            reachable.add(t);
        }

        reachable.sort(Comparator.<SchematicScanner.Target>comparingInt(t -> t.pos().getY())
                .thenComparing(Comparator.<SchematicScanner.Target>comparingDouble(t -> Vec3.atCenterOf(t.pos()).distanceToSqr(eye)).reversed()));

        // Pro Tick nur ein Teil; klappt keiner, beim naechsten Tick die naechsten (der Reihe nach)
        int start = this.selectRound * CANDIDATES_PER_TICK;

        if (start >= reachable.size())
        {
            start = 0;
            this.selectRound = 0;
        }

        int end = Math.min(reachable.size(), start + CANDIDATES_PER_TICK);
        SchematicScanner.Target best = null;
        List<BlockHitResult> bestHits = List.of();
        double bestScore = Double.MAX_VALUE;
        int bandY = 0;
        double bandDist = 0;
        int found = 0;

        for (int i = start; i < end; i++)
        {
            SchematicScanner.Target t = reachable.get(i);
            double dist = Vec3.atCenterOf(t.pos()).distanceTo(eye);

            // Nur die hinterste Gruppe der untersten Hoehe (die Liste ist so sortiert, der Rest kommt spaeter)
            if (found > 0 && (t.pos().getY() != bandY || dist < bandDist - 1.0))
            {
                break;
            }

            tried++;
            ItemStack stack = new ItemStack(t.item());
            boolean held = EntityUtils.getUsedHandForItem(player, stack) != null;
            boolean inHotbar = held || InventoryManager.isInHotbar(player, t.item());

            // Hinter einem Menue kann das Inventar nicht geoeffnet werden: nur, was schon in der Hotbar liegt
            if (this.behindScreen && !inHotbar)
            {
                continue;
            }

            List<BlockHitResult> hits = AimPlanner.find(player, level, schematic, t.pos(), t.state(), stack, (BlockItem) t.item(),
                    reach - Ep2Configs.PRINTER_REACH_MARGIN.getDoubleValue());

            if (hits.isEmpty())
            {
                continue;
            }

            if (found++ == 0)
            {
                bandY = t.pos().getY();
                bandDist = dist;
            }

            double score = AimController.angleTo(player, hits.get(0).getLocation()) / 15.0 + (held ? 0 : inHotbar ? 2 : 6);

            if (score < bestScore)
            {
                best = t;
                bestHits = hits;
                bestScore = score;
            }

            if (found >= 8)
            {
                break;
            }
        }

        if (best == null && end < reachable.size())
        {
            // Die naechsten Kandidaten beim naechsten Tick, bevor weggelaufen wird
            this.selectRound++;
            this.setState(State.SELECTING, "");
            return;
        }

        this.selectRound = 0;

        if (best != null)
        {
            SchematicScanner.Target t = best;
            ItemStack stack = new ItemStack(t.item());
            this.current = t;
            this.aimHits = bestHits;
            this.aimIndex = 0;

            // Erst das Item wie ein Spieler holen, danach wird neu geplant (die Position kann sich nicht aendern)
            if (EntityUtils.getUsedHandForItem(player, stack) == null)
            {
                if (this.inventory.request(player, t.item(), snap.neededItems().keySet()))
                {
                    this.setState(State.FETCHING_ITEM, stack.getHoverName().getString());
                }
                else
                {
                    this.fail(t.pos(), now);
                }

                return;
            }

            AimController.INSTANCE.aimAt(bestHits.get(0).getLocation(), Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());
            this.setState(State.AIMING, blockName(t.state()));
            return;
        }

        // Nichts in Reichweite: in Bahnen zum naechsten Platz mit Arbeit (geht auch ohne Baritone, wenn geradeaus)
        if (Ep2Configs.PRINTER_LANE_MODE.getBooleanValue() && now >= this.laneFailUntil)
        {
            List<BlockPos> layerCells = layer.stream().map(SchematicScanner.Target::pos).toList();
            BlockPos next = this.buildLanes.next(player, level, layerCells, lowest, reach - Ep2Configs.PRINTER_REACH_MARGIN.getDoubleValue());

            if (next != null && this.walkLane(player, level, next, now))
            {
                return;
            }
        }

        // Nichts in Reichweite: hinlaufen
        if (!this.canWalk())
        {
            if (this.state != State.NOTHING_IN_REACH)
            {
                debug("Kein Klick moeglich: {} offen, {} mit Material, {} in Reichweite geprueft, Auge {}",
                        open.size(), buildable.size(), tried, eye);
            }

            AimController.INSTANCE.stop();
            this.setState(State.NOTHING_IN_REACH, "");
            return;
        }

        double maxDist = reach - Ep2Configs.PRINTER_REACH_MARGIN.getDoubleValue();
        int planned = 0;

        // Darf Baritone Geruest setzen? Dann muss ein Stapel davon in der Hotbar liegen (Baritone nimmt nur von dort)
        List<Item> usable = ScaffoldTracker.INSTANCE.usable();
        boolean scaffold = Ep2Configs.PRINTER_SCAFFOLD.getBooleanValue() && !usable.isEmpty();

        if (scaffold && this.behindScreen && usable.stream().noneMatch(item -> InventoryManager.isInHotbar(player, item)))
        {
            scaffold = false;
        }

        if (scaffold && usable.stream().noneMatch(item -> InventoryManager.isInHotbar(player, item)))
        {
            if (this.inventory.request(player, usable.get(0), snap.neededItems().keySet()))
            {
                this.setState(State.FETCHING_ITEM, new ItemStack(usable.get(0)).getHoverName().getString());
                return;
            }

            scaffold = false;
        }

        for (SchematicScanner.Target t : layer)
        {
            // Stehplaetze genau durchrechnen ist teuer, deshalb nur wenige Bloecke pro Tick
            if (planned++ >= 4)
            {
                this.setState(State.SELECTING, "");
                return;
            }

            ItemStack stack = new ItemStack(t.item());
            BlockItem item = (BlockItem) t.item();
            List<BlockPos> spots = StandSpots.find(level, schematic, t.pos(), maxDist, player.position(), scaffold,
                    feet -> AimPlanner.canPlaceFrom(player, feet, level, schematic, t.pos(), t.state(), stack, item, maxDist));
            int attempts = this.moveAttempts.merge(t.pos(), 1, Integer::sum);

            if (spots.isEmpty() || attempts > MAX_FAILURES)
            {
                this.moveAttempts.remove(t.pos());
                this.blacklist.put(t.pos(), now + BLACKLIST_TICKS);
                continue;
            }

            BaritoneBridge.setScaffold(scaffold, usable);

            if (BaritoneBridge.goToAny(spots))
            {
                AimController.INSTANCE.stop();
                this.moveTarget = t.pos();
                this.moveStartTick = now;
                this.moveStartPos = player.position();
                this.watchdog.startMoving(player.position());
                this.setState(State.MOVING, blockName(t.state()));
            }

            return;
        }

        this.setState(State.NOTHING_IN_REACH, "");
    }

    private void walkToUnloaded(LocalPlayer player, List<BlockPos> unloaded, long now)
    {
        Vec3 pos = player.position();
        // Jedes Ziel nur ein paar Mal versuchen, sonst laeuft der Printer endlos hin und her
        BlockPos nearest = unloaded.stream()
                .filter(p -> this.unloadedAttempts.getOrDefault(p, 0) < MAX_UNLOADED_ATTEMPTS)
                .min(Comparator.comparingDouble(p -> Math.pow(p.getX() + 0.5 - pos.x, 2) + Math.pow(p.getZ() + 0.5 - pos.z, 2)))
                .orElse(null);

        if (nearest == null)
        {
            this.setState(State.SELECTING, "skipped");
            return;
        }

        this.unloadedAttempts.merge(nearest, 1, Integer::sum);
        List<Item> usable = ScaffoldTracker.INSTANCE.usable();
        BaritoneBridge.setScaffold(Ep2Configs.PRINTER_SCAFFOLD.getBooleanValue(), usable);

        if (BaritoneBridge.goToXZ(nearest.getX(), nearest.getZ()))
        {
            AimController.INSTANCE.stop();
            this.moveTarget = null;
            this.moveStartTick = now;
            this.moveStartPos = player.position();
            this.watchdog.startMoving(player.position());
            this.setState(State.MOVING, nearest.getX() + ", " + nearest.getZ());
        }
        else
        {
            this.setState(State.SELECTING, "skipped");
        }
    }

    private void tickMoving(LocalPlayer player, long now)
    {
        // Baritone braucht einen Moment, bis der Weg berechnet ist
        if (this.stateTicks < 10 || BaritoneBridge.isBusy())
        {
            // Zu lange unterwegs oder seit Sekunden kein Stueck weitergekommen: anderes Ziel nehmen
            boolean stuck = this.stateTicks >= 10 && this.watchdog.stuckWhileMoving(player.position());

            if (now - this.moveStartTick > MOVE_TIMEOUT_TICKS || stuck)
            {
                debug("Laufen abgebrochen ({}), Ziel {}", stuck ? "steckt fest" : "dauert zu lange",
                        this.moveTarget != null ? this.moveTarget.toShortString() : "-");
                BaritoneBridge.cancel();
                BaritoneBridge.setScaffold(false, List.of());

                if (this.moveTarget != null)
                {
                    this.blacklist.put(this.moveTarget, now + BLACKLIST_TICKS);
                }

                this.setState(State.SELECTING, "");
            }

            return;
        }

        BaritoneBridge.setScaffold(false, List.of());

        // Kein Weg gefunden (kaum vom Fleck gekommen): zaehlt als Fehlversuch fuer diesen Block
        if (this.moveTarget != null && player.position().distanceTo(this.moveStartPos) < 1.0)
        {
            this.moveAttempts.merge(this.moveTarget, 1, Integer::sum);
        }

        // Angekommen: kurz "umschauen", dann neu planen
        this.settleTicks = 3 + this.random.nextInt(5);
        this.setState(State.SELECTING, "");
        this.stateTicks = -this.settleTicks;
    }

    private void tickAiming(ClientLevel level)
    {
        if (this.current == null || !isStillMissing(level, this.current))
        {
            AimController.INSTANCE.stop();
            this.setState(State.SELECTING, "");
            return;
        }

        if (AimController.INSTANCE.isOnTarget())
        {
            // Wie ein Spieler: einen Augenblick auf dem Ziel bleiben, dann klicken (schnell bauen: sofort)
            int min = Ep2Configs.PRINTER_REACTION_MIN.getIntegerValue();
            int max = Math.max(min, Ep2Configs.PRINTER_REACTION_MAX.getIntegerValue());
            this.settleTicks = Ep2Configs.PRINTER_FAST_BUILD.getBooleanValue() ? 0 : min - 1 + this.random.nextInt(max - min + 1);
            this.setState(State.CLICKING, this.detail);
        }
        else if (this.stateTicks > AIM_TIMEOUT_TICKS)
        {
            this.nextAimPoint(level.getGameTime());
        }
    }

    private void tickClicking(Minecraft mc, LocalPlayer player, ClientLevel level, long now)
    {
        SchematicScanner.Target t = this.current;

        if (t == null || !isStillMissing(level, t))
        {
            this.setState(State.SELECTING, "");
            return;
        }

        if (this.settleTicks-- > 0 || now < this.nextClickTick)
        {
            return;
        }

        if (EntityUtils.getUsedHandForItem(player, new ItemStack(t.item())) == null)
        {
            this.setState(State.SELECTING, "");
            return;
        }

        // Strenges Fadenkreuz: das echte Fadenkreuz muss genau auf der geplanten Flaeche liegen
        // und Litematica muss genau diesen Geisterblock davor sehen
        BlockHitResult planned = this.aimHits.get(this.aimIndex);
        boolean crosshairOk = mc.hitResult instanceof BlockHitResult bhr &&
                              bhr.getBlockPos().equals(planned.getBlockPos()) &&
                              bhr.getDirection() == planned.getDirection();
        // Dieselbe Auswahl wie Easy Place 2.0 beim Klick: der hinterste setzbare Geisterblock im Blick
        Level schematicWorld = SchematicWorldHandler.getSchematicWorld();
        EasyPlace2Handler.Choice choice = schematicWorld != null ? EasyPlace2Handler.choose(mc, player, level, schematicWorld, player) : null;
        boolean ghostOk = choice != null && choice.plan() != null && choice.target().equals(t.pos());

        if (!crosshairOk || !ghostOk)
        {
            debug("Fadenkreuz passt nicht bei {}: Fadenkreuz {} (geplant {} {}), Geisterblock {}", t.pos().toShortString(),
                    mc.hitResult instanceof BlockHitResult bhr ? bhr.getBlockPos().toShortString() + " " + bhr.getDirection() : String.valueOf(mc.hitResult),
                    planned.getBlockPos().toShortString(), planned.getDirection(),
                    choice == null ? "-" : choice.target().toShortString() + (choice.plan() == null ? " (" + choice.reason() + ")" : ""));
            this.nextAimPoint(now);
            return;
        }

        ((MinecraftAccessor) mc).easyplace2$startUseItem();

        if (Ep2Configs.PRINTER_FAST_BUILD.getBooleanValue())
        {
            // So schnell wie gehaltene rechte Maustaste; ob der Server den Block behaelt, wird nebenbei geprueft,
            // waehrend schon der naechste Block angepeilt wird
            this.nextClickTick = now + Ep2Configs.DELAY_TICKS.getIntegerValue();
            this.pending.add(new Pending(t, now));
            this.current = null;
            this.setState(State.SELECTING, "");
            return;
        }

        this.nextClickTick = now + Ep2Configs.DELAY_TICKS.getIntegerValue() + this.random.nextInt(Ep2Configs.PRINTER_EXTRA_DELAY.getIntegerValue() + 1);
        this.setState(State.WAITING, this.detail);
    }

    /** Gesetzte Bloecke, bei denen noch geprueft wird, ob der Server sie behaelt. */
    private record Pending(SchematicScanner.Target target, long tick) {}

    private void checkPending(ClientLevel level, long now)
    {
        java.util.Iterator<Pending> it = this.pending.iterator();

        while (it.hasNext())
        {
            Pending p = it.next();

            if (now - p.tick() < 4)
            {
                continue;
            }

            it.remove();
            BlockPos pos = p.target().pos();

            if (StateMatcher.isComplete(level.getBlockState(pos), p.target().state()))
            {
                this.placed++;
                this.watchdog.progress();
                this.failures.remove(pos);
                this.moveAttempts.remove(pos);
            }
            else
            {
                this.fail(pos, now);
            }
        }
    }

    private void tickWaiting(ClientLevel level)
    {
        SchematicScanner.Target t = this.current;

        // Der Client setzt den Block sofort; ein paar Ticks warten, falls der Server ihn zuruecknimmt
        if (this.stateTicks < 3 || t == null)
        {
            return;
        }

        if (StateMatcher.isComplete(level.getBlockState(t.pos()), t.state()))
        {
            this.placed++;
            this.watchdog.progress();
            this.failures.remove(t.pos());
            this.moveAttempts.remove(t.pos());
        }
        else
        {
            this.fail(t.pos(), level.getGameTime());
        }

        this.current = null;
        this.setState(State.SELECTING, "");
    }

    private void nextAimPoint(long now)
    {
        if (++this.aimIndex < this.aimHits.size())
        {
            AimController.INSTANCE.aimAt(this.aimHits.get(this.aimIndex).getLocation(), Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());
            this.setState(State.AIMING, this.detail);
            return;
        }

        if (this.current != null)
        {
            this.fail(this.current.pos(), now);
        }

        this.current = null;
        this.setState(State.SELECTING, "");
    }

    private void fail(BlockPos pos, long now)
    {
        if (this.failures.merge(pos, 1, Integer::sum) >= MAX_FAILURES)
        {
            this.failures.remove(pos);
            this.blacklist.put(pos, now + BLACKLIST_TICKS);
        }
    }

    /**
     * Baut der Printer hinter diesem Menue weiter? Ja beim Pause-Menue (ESC), Chat, Printer-Menue usw.,
     * nein bei Inventaren und Kisten (dort wuerden sich Klicks in die Quere kommen).
     */
    public boolean worksBehind(@Nullable Screen screen)
    {
        return this.running && !this.userPaused && screen != null && Ep2Configs.PRINTER_RUN_IN_MENUS.getBooleanValue() &&
               !(screen instanceof AbstractContainerScreen<?>);
    }

    /**
     * Zu einem Feld in der Bahn laufen: geradeaus selbst (Vorwaerts-Taste), sonst mit Baritone.
     *
     * @return false, wenn es so nicht geht
     */
    private boolean walkLane(LocalPlayer player, ClientLevel level, BlockPos target, long now)
    {
        if (LaneWalker.straightWalkable(level, player.blockPosition(), target))
        {
            AimController.INSTANCE.stop();
            this.walker.start(target, player);
            this.setState(State.LANE_WALKING, target.toShortString());
            return true;
        }

        if (this.canWalk() && BaritoneBridge.goToAny(List.of(target)))
        {
            AimController.INSTANCE.stop();
            this.moveTarget = null;
            this.moveStartTick = now;
            this.moveStartPos = player.position();
            this.watchdog.startMoving(player.position());
            this.setState(State.MOVING, target.toShortString());
            return true;
        }

        return false;
    }

    /** Alles loslassen: Drehen, Laufen, Material holen, Geruest abbauen, Inventar. */
    private void interrupt(Minecraft mc)
    {
        AimController.INSTANCE.stop();
        BaritoneBridge.cancel();
        BaritoneBridge.setScaffold(false, List.of());
        this.refill.cancel(mc);
        this.cleaner.cancel(mc);
        this.excavator.cancel(mc);
        this.walker.cancel(mc);
        this.inventory.cancel(mc);
    }

    /** Zaehlt die Zeit ohne Fortschritt? Nicht, wenn der Printer bewusst wartet (Material fehlt, kein Baritone). */
    private boolean watchdogCounts()
    {
        if (this.behindScreen)
        {
            return false;
        }

        return switch (this.state)
        {
            case OFF, DONE, PAUSED, PAUSED_SCREEN, MISSING_MATERIAL -> false;
            case NOTHING_IN_REACH -> this.canWalk();
            default -> true;
        };
    }

    /** Geht nichts voran: stufenweise einen Ausweg suchen, zuletzt anhalten und melden. */
    private void escalate(Minecraft mc, LocalPlayer player, ClientLevel level, Level schematic, Watchdog.Level step, long now)
    {
        debug("Kein Fortschritt seit {} s: {}", this.watchdog.idleSeconds(), step);

        switch (step)
        {
            case RETRY ->
            {
                // Alles Gesperrte noch einmal versuchen
                this.blacklist.clear();
                this.moveAttempts.clear();
                this.failures.clear();
                this.unloadedAttempts.clear();
                this.cleaner.clearSkipped();
                this.excavator.clearSkipped();
            }
            case ESCAPE ->
            {
                if (this.canWalk())
                {
                    this.interrupt(mc);
                    double angle = this.random.nextDouble() * Math.PI * 2;
                    int x = (int) Math.floor(player.getX() + Math.cos(angle) * ESCAPE_DISTANCE);
                    int z = (int) Math.floor(player.getZ() + Math.sin(angle) * ESCAPE_DISTANCE);
                    BaritoneBridge.setScaffold(Ep2Configs.PRINTER_SCAFFOLD.getBooleanValue(), ScaffoldTracker.INSTANCE.usable());

                    if (BaritoneBridge.goToXZ(x, z))
                    {
                        this.current = null;
                        this.moveTarget = null;
                        this.moveStartTick = now;
                        this.moveStartPos = player.position();
                        this.watchdog.startMoving(player.position());
                        this.setState(State.MOVING, "escape");
                    }
                }
            }
            case CLEAR_SCAFFOLD ->
            {
                Vec3 pos = player.position();
                this.interrupt(mc);
                this.cleaner.clearSkipped();

                if (this.cleaner.start(player, level, schematic, p -> Vec3.atCenterOf(p).distanceTo(pos) <= CLEAR_RADIUS, now))
                {
                    this.current = null;
                    this.setState(State.CLEANING, this.cleaner.detail());
                }
            }
            case GIVE_UP ->
            {
                this.interrupt(mc);
                this.current = null;
                this.userPaused = true;
                this.setState(State.PAUSED, "stuck");
                SchematicScanner.Snapshot snap = this.scanner.latest();
                int open = snap != null ? snap.missing().size() : 0;
                message(Component.translatable("easyplace2.printer.msg.stuck", this.watchdog.idleSeconds(), open, this.blacklist.size())
                        .withStyle(ChatFormatting.GOLD));
            }
        }
    }

    /** Welche Geruest-Items Baritone verbauen darf: im Inventar und nicht als Baumaterial gebraucht. */
    private void updateScaffoldItems(LocalPlayer player, SchematicScanner.Snapshot snap)
    {
        List<Item> usable = new ArrayList<>();

        for (Item item : ScaffoldTracker.INSTANCE.items())
        {
            if (!snap.neededItems().containsKey(item) && InventoryManager.countItem(player, item) > 0)
            {
                usable.add(item);
            }
        }

        ScaffoldTracker.INSTANCE.setUsable(usable);
    }

    private boolean canWalk()
    {
        return Ep2Configs.PRINTER_USE_BARITONE.getBooleanValue() && BaritoneBridge.isAvailable();
    }

    private void setState(State state, String detail)
    {
        if (this.state != state)
        {
            this.stateTicks = 0;
            debug("Zustand {} -> {} ({}){}", this.state, state, detail,
                    this.current != null ? " Ziel " + this.current.pos().toShortString() : "");
        }

        this.state = state;
        this.detail = detail;
    }

    /** Ablauf im Log nachvollziehen (nur mit eingeschalteten Debug-Meldungen). */
    private static void debug(String format, Object... args)
    {
        if (Ep2Configs.DEBUG.getBooleanValue())
        {
            EasyPlace2.LOGGER.info("[Printer] " + format, args);
        }
    }

    private static boolean isStillMissing(ClientLevel level, SchematicScanner.Target t)
    {
        BlockState current = level.getBlockState(t.pos());
        return !StateMatcher.isComplete(current, t.state()) &&
               (current.canBeReplaced() || current.getBlock() == t.state().getBlock());
    }

    private static String blockName(BlockState state)
    {
        return state.getBlock().getName().getString();
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
