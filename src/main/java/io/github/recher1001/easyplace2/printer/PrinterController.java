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
import io.github.recher1001.easyplace2.printer.refill.RefillUtil;
import io.github.recher1001.easyplace2.printer.lanes.BuildLanes;
import io.github.recher1001.easyplace2.printer.lanes.LaneMiner;
import io.github.recher1001.easyplace2.printer.lanes.LaneWalker;
import io.github.recher1001.easyplace2.printer.lanes.StarTool;
import io.github.recher1001.easyplace2.printer.scaffold.ScaffoldSource;
import io.github.recher1001.easyplace2.printer.scaffold.ScaffoldTracker;
import io.github.recher1001.easyplace2.printer.water.WaterFiller;

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

    /** Was der Printer macht: bauen (normal), nur aushoehlen, oder eine Linie mit der Star-Spitzhacke graben. */
    public enum Mode { BUILD, EXCAVATE, LINE }

    public enum State
    {
        OFF, SCANNING, SELECTING, FETCHING_ITEM, REFILLING, CLEANING, EXCAVATING, FIXING, ADJUSTING, WATERING, BRIDGING, SUPPORTING, TOWERING, TUNNELING, LANE_WALKING, MOVING, AIMING, CLICKING,
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
    /** Ging ein Weg nicht: so lange (Ticks) wird diese Ecke gemieden und erst etwas anderes ausprobiert. */
    private static final int TRY_OTHER_TICKS = 20 * 10;
    /** Um eine gescheiterte Stelle herum (Bloecke) wird erst einmal nichts anderes angelaufen. */
    private static final double FAILED_AREA_RADIUS = 2.5;
    /** Kommt der Spieler so lange (Ticks) nach dem Losgehen nicht vom Fleck: gleich das naechste Ziel probieren. */
    private static final int NO_START_TICKS = 25;
    /** Steht der Spieler waehrend des Laufens so lange (Ticks) still: abbrechen und anders probieren. */
    private static final int STUCK_WALK_TICKS = 40;
    /** Kommt der Spieler so lange (Ticks) dem Ziel nicht naeher (Baritone irrt herum): anders probieren. */
    private static final int NO_CLOSER_TICKS = 20 * 4;

    private final SchematicScanner scanner = new SchematicScanner();
    private final InventoryManager inventory = new InventoryManager();
    private final RefillManager refill = new RefillManager();
    private final BlockDigger cleaner = new BlockDigger(new ScaffoldSource());
    private final Excavation excavation = new Excavation();
    private final BlockDigger excavator = new BlockDigger(this.excavation);
    private final WaterFiller water = new WaterFiller(this.inventory);
    /** Falsch ausgerichtete Bloecke: abbauen, danach wird der richtige gesetzt. */
    private final Reorient reorient = new Reorient();
    private final BlockDigger reorienter = new BlockDigger(this.reorient);
    /** Repeater, Komparatoren ... per Rechtsklick einstellen. */
    private final BlockAdjuster adjuster = new BlockAdjuster();
    private final Map<BlockPos, Integer> adjustAttempts = new HashMap<>();
    /** Gerade abgebaut (Ausrichtung): sofort neu setzen, ohne auf den naechsten Scan zu warten. */
    private final List<SchematicScanner.Target> injected = new ArrayList<>();
    /** Stellen, zu denen der Weg gerade nicht ging (bis wann sie gemieden werden). */
    private final Map<BlockPos, Long> failedAreas = new HashMap<>();
    private int adjusted;
    @Nullable private BlockPos reorienterTarget;
    /** Hochbauen, wenn die Schematic weiter oben anfaengt. */
    private final TowerUp tower = new TowerUp();
    @Nullable private TowerUp.Plan towerPlan;
    /** Gebaute Tuerme (am Ende von oben nach unten wieder abbauen, dabei oben draufstellen). */
    private final List<TowerUp.Plan> towers = new ArrayList<>();
    private final Map<TowerUp.Plan, Integer> towerWalks = new HashMap<>();
    private boolean towerHintShown;
    private double bestMoveDist;
    private long bestMoveTick;
    private final LaneMiner laneMiner = new LaneMiner();
    private final BuildLanes buildLanes = new BuildLanes();
    private final LaneWalker walker = new LaneWalker();
    /** Star-Spitzhacke: geradeaus durch die Bahn graben. */
    private final io.github.recher1001.easyplace2.printer.lanes.StarTunnel tunnel = new io.github.recher1001.easyplace2.printer.lanes.StarTunnel();
    /** Ging das Laufen in der Bahn schief, eine Weile normal weitermachen. */
    private long laneFailUntil;
    private final Watchdog watchdog = new Watchdog();
    /** Ist gerade ein Menue offen, hinter dem der Printer weiterbaut (ESC, Chat ...)? */
    private boolean behindScreen;
    private final Random random = new Random();

    private boolean running;
    private Mode mode = Mode.BUILD;
    private boolean lineStarted;
    private int excavateRetries;
    private int tunnelStart;
    private int tunnelRunStart;
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
    /** Laeuft der Printer gerade nur "in die Naehe" (schnell), statt zu einem genau berechneten Stehplatz? */
    private boolean walkingNear;
    /** Bloecke, die von "irgendwo in der Naehe" nicht gingen (z. B. Treppe falsch herum): genau planen. */
    private final Set<BlockPos> preciseWalk = new HashSet<>();
    /** Laeuft der Printer gerade selbst geradeaus (ohne Baritone) zum naechsten Block? */
    private boolean directWalk;
    private long directFailUntil;
    /** Bauen ueber Luft: an die Kante schleichen und an die Seite setzen. */
    private final EdgeBridge bridge = new EdgeBridge();
    private final Map<BlockPos, Integer> bridgeAttempts = new HashMap<>();
    /** Hilfsbloecke fuer Bloecke, die frei in der Luft stehen sollen. */
    private final SupportBlocks support = new SupportBlocks();
    private final Map<BlockPos, Integer> supportAttempts = new HashMap<>();
    private boolean supportHintShown;
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
        Walking.register();
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

    public Mode getMode()
    {
        return this.mode;
    }

    /** Laeuft der Printer gerade nur zum Abbauen (Aushoehlen oder Star-Linie)? */
    public boolean excavateOnly()
    {
        return this.running && this.mode != Mode.BUILD;
    }

    public void start()
    {
        this.start(Mode.BUILD);
    }

    /** Eine ganze Linie mit der Star-Spitzhacke graben: von hier aus in Blickrichtung bis zum Rand der Schematic. */
    public void startLine()
    {
        this.start(Mode.LINE);
    }

    public void start(Mode mode)
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
            Walking.applySettings(Ep2Configs.PRINTER_SPRINT.getBooleanValue());
            Walking.setScaffold(false, List.of());
        }

        this.scanner.reset();
        this.cleaner.reset();
        this.excavator.reset();
        this.water.reset();
        this.reorient.reset();
        this.reorienter.reset();
        this.adjuster.cancel();
        this.adjustAttempts.clear();
        this.injected.clear();
        this.failedAreas.clear();
        this.adjusted = 0;
        this.towerPlan = null;
        this.towers.clear();
        this.towerWalks.clear();
        this.towerHintShown = false;
        this.laneMiner.reset();
        this.buildLanes.reset();
        this.laneFailUntil = 0;
        this.watchdog.reset();
        this.unloadedAttempts.clear();
        this.failures.clear();
        this.blacklist.clear();
        this.moveAttempts.clear();
        this.preciseWalk.clear();
        this.directWalk = false;
        this.directFailUntil = 0;
        this.bridgeAttempts.clear();
        this.supportAttempts.clear();
        this.supportHintShown = false;
        this.missingMaterials.clear();
        this.pending.clear();
        this.placed = 0;
        this.startMillis = System.currentTimeMillis();
        this.mode = mode;
        this.lineStarted = false;
        this.excavateRetries = 0;
        this.tunnelStart = this.tunnel.broken();
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
        this.water.cancel(mc);
        this.reorienter.cancel(mc);
        this.adjuster.cancel();
        this.tower.stop(mc);
        this.tunnel.stop(mc);
        this.bridge.stop(mc);
        this.support.cancel();
        this.walker.cancel(mc);
        this.inventory.cancel(mc);
        Walking.cancel();
        Walking.restoreSettings();
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
            case FIXING ->
            {
                BlockPos fixing = this.reorienter.isActive() ? this.reorienterTarget : null;
                int before = this.reorienter.broken();

                if (this.reorienter.tick(mc, now, this.canWalk()))
                {
                    if (this.reorienter.broken() > before && fixing != null)
                    {
                        // Abgebaut: gleich richtig herum neu setzen
                        this.watchdog.progress();
                        this.reorient.brokeAt(fixing);
                        this.inject(fixing, schematic);
                    }

                    this.reorienterTarget = null;
                    this.setState(State.SELECTING, "");
                    this.stateTicks = 1;
                }
                else
                {
                    this.detail = this.reorienter.detail();
                }
            }
            case TUNNELING ->
            {
                int before = this.tunnel.broken();
                io.github.recher1001.easyplace2.printer.lanes.StarTunnel.Result result = this.tunnel.tick(mc);

                if (this.tunnel.broken() > before)
                {
                    this.watchdog.progress();
                }

                if (result != io.github.recher1001.easyplace2.printer.lanes.StarTunnel.Result.RUNNING)
                {
                    // Ging gar nichts (nichts abgebaut): eine Weile normal weiter, sonst begaenne derselbe Tunnel sofort neu
                    if (this.tunnel.broken() == this.tunnelRunStart && this.mode != Mode.LINE)
                    {
                        debug("Star-Tunnel ging nicht weiter, eine Weile normal weiter");
                        this.laneFailUntil = now + 20 * 10;
                        this.laneMiner.reset();
                    }

                    this.setState(State.SELECTING, "");
                    this.stateTicks = 1;
                }
            }
            case TOWERING ->
            {
                int before = this.tower.placed();
                TowerUp.Result result = this.tower.tick(mc);

                if (this.tower.placed() > before)
                {
                    this.watchdog.progress();
                }

                if (result != TowerUp.Result.RUNNING)
                {
                    if (result == TowerUp.Result.FAILED && this.towerPlan != null)
                    {
                        // Hier geht es nicht hoch: woanders probieren
                        debug("Hochbauen ging nicht bei {}", this.towerPlan.ground().toShortString());
                        this.blacklist.put(this.towerPlan.ground(), now + BLACKLIST_TICKS);
                    }

                    this.towerPlan = null;
                    this.setState(State.SELECTING, "");
                    this.stateTicks = 1;
                }
            }
            case ADJUSTING ->
            {
                BlockPos at = this.adjuster.pos();
                BlockAdjuster.Result result = this.adjuster.tick(mc, now);

                if (result != BlockAdjuster.Result.RUNNING)
                {
                    if (result == BlockAdjuster.Result.DONE)
                    {
                        this.watchdog.progress();
                        this.adjusted++;

                        if (at != null)
                        {
                            this.adjustAttempts.remove(at);
                        }
                    }
                    else if (at != null)
                    {
                        this.adjustAttempts.merge(at, 1, Integer::sum);
                    }

                    this.setState(State.SELECTING, "");
                    this.stateTicks = 1;
                }
            }
            case WATERING ->
            {
                int before = this.water.placed();

                if (this.water.tick(mc, now))
                {
                    if (this.water.placed() > before)
                    {
                        this.watchdog.progress();
                        this.placed++;
                    }

                    this.setState(State.SELECTING, "");
                }
                else
                {
                    this.detail = this.water.detail();
                }
            }
            case SUPPORTING ->
            {
                if (this.support.tick(mc) != SupportBlocks.Result.RUNNING)
                {
                    this.setState(State.SELECTING, "");
                    this.stateTicks = 1;
                }
            }
            case BRIDGING ->
            {
                EdgeBridge.Result result = this.bridge.tick(mc, this.canWalk());

                if (result != EdgeBridge.Result.RUNNING)
                {
                    // An der Kante angekommen: jetzt normal setzen (die Schleichtaste bleibt gedrueckt)
                    this.bridge.finish();
                    this.setState(State.SELECTING, "");
                    this.stateTicks = 1;
                }
            }
            case LANE_WALKING ->
            {
                LaneWalker.Result result = this.walker.tick(mc);

                if (result != LaneWalker.Result.RUNNING && this.directWalk)
                {
                    // Geradeaus hat nicht geklappt (Hindernis): eine Weile Baritone nehmen
                    this.directWalk = false;

                    if (result == LaneWalker.Result.FAILED)
                    {
                        this.directFailUntil = now + 20 * 5;
                    }

                    this.setState(State.SELECTING, "");
                    this.stateTicks = 1;
                    return;
                }

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

        if (this.mode == Mode.LINE)
        {
            this.selectLine(mc, player, level, schematic);
            return;
        }

        if (snap.total() == 0 && snap.unloaded().isEmpty() && this.mode == Mode.EXCAVATE && snap.excavate().isEmpty())
        {
            // Schematic nur aus Luft und alles ist weg: fertig ausgehoehlt
            this.finishExcavate(player, level, schematic, snap, now);
            return;
        }

        if (snap.total() == 0 && snap.unloaded().isEmpty() && this.mode == Mode.BUILD)
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
        this.reorient.update(snap.reorient(), now);
        this.failedAreas.values().removeIf(until -> until <= now);
        this.injected.removeIf(t -> !isStillMissing(level, t));
        List<SchematicScanner.Target> open = new ArrayList<>();
        List<SchematicScanner.Target> all = this.injected.isEmpty() ? snap.missing() : new ArrayList<>(snap.missing());

        if (!this.injected.isEmpty())
        {
            all.addAll(this.injected);
        }

        for (SchematicScanner.Target t : all)
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
                else if (action.kind() == LaneMiner.Kind.TUNNEL)
                {
                    this.bridge.release(mc);
                    // Wie die Star-Linie: geradeaus mit Abstand, Reste gleich mit Schaufel/Spitzhacke mitnehmen
                    this.tunnel.startWithCleanup(action.run(), p -> this.excavation.stillWanted(level, schematic, p) &&
                            !this.excavator.isSkipped(p, now), action.forceSlot());
                    this.tunnelRunStart = this.tunnel.broken();
                    this.setState(State.TUNNELING, action.run().get(0).pos().toShortString());
                    return;
                }
                else if (action.kind() == LaneMiner.Kind.DOWN && action.walkTo() != null)
                {
                    this.bridge.release(mc);
                    this.tunnel.startDown(action.walkTo().getY(), p -> this.excavation.stillWanted(level, schematic, p) &&
                            !this.excavator.isSkipped(p, now), action.forceSlot(), player.getYRot());
                    this.tunnelRunStart = this.tunnel.broken();
                    this.setState(State.TUNNELING, "down");
                    return;
                }
                else if (action.kind() == LaneMiner.Kind.WALK && action.walkTo() != null && this.walkLane(player, level, action.walkTo(), now))
                {
                    return;
                }
            }

            // Sonst (oder fuer den Rest): der naechste Block in Reichweite; nie mit der Star-Spitzhacke
            this.excavator.setTool(-1, StarTool.findSlot(player));

            // Auch hier erst der grosse Teil (einzelne vergessene Bloecke weiter weg kommen zum Schluss)
            boolean lanes = Ep2Configs.PRINTER_LANE_MODE.getBooleanValue();

            if ((lanes && this.excavator.start(player, level, schematic, this.laneMiner::preferred, now)) ||
                this.excavator.start(player, level, schematic, p -> true, now))
            {
                AimController.INSTANCE.stop();
                this.setState(State.EXCAVATING, this.excavator.detail());
                return;
            }
        }

        // Nur aushoehlen: nichts setzen. Ist nichts mehr abzubauen, fertig (Uebersprungenes wird vorher noch ein paar Mal
        // versucht, ohne dazwischen lange herumzustehen)
        if (this.mode == Mode.EXCAVATE)
        {
            this.finishExcavate(player, level, schematic, snap, now);
            return;
        }

        // Repeater, Komparatoren ... einstellen: nur ein paar Rechtsklicks, deshalb gleich, solange sie in Reichweite sind
        if (this.tryAdjust(mc, player, level, schematic, snap, now, false))
        {
            return;
        }

        // Wasser zuletzt: erst wenn sonst nichts mehr zu setzen ist (ausser Seerosen, Seegras ...), und nur in
        // Bereiche, aus denen es nicht herauslaufen kann
        this.water.update(Ep2Configs.PRINTER_WATER.getBooleanValue() ? snap.water() : List.of());

        if (!snap.water().isEmpty() && Ep2Configs.PRINTER_WATER.getBooleanValue() &&
            open.stream().allMatch(t -> WaterFiller.needsWater(t.state())))
        {
            if (this.water.start(mc, player, level, schematic, now, this.canWalk()))
            {
                AimController.INSTANCE.stop();
                this.setState(State.WATERING, this.water.detail());
                return;
            }

            if (this.water.isSettling())
            {
                // Das Wasser breitet sich gerade aus (Quellen wachsen zusammen): kurz warten
                this.setState(State.SELECTING, "water_settling");
                this.stateTicks = -10;
                return;
            }
        }

        if (open.isEmpty())
        {
            // Alles gesetzt: jetzt die falsch herum stehenden Bloecke neu setzen und den Rest einstellen (auch weiter weg)
            if (this.tryReorient(mc, player, level, schematic, now, true) || this.tryAdjust(mc, player, level, schematic, snap, now, true))
            {
                return;
            }

            if (this.reorient.hasWaiting(level, schematic))
            {
                this.setState(State.SELECTING, "");
                return;
            }

            // Tuerme: oben draufstellen und Block fuer Block nach unten abbauen (nie von der Seite, sonst kommt man
            // vom gebauten Teil nicht mehr herunter)
            if (snap.missing().isEmpty() && this.descendTower(player, level, schematic, now))
            {
                return;
            }

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
                int waterLeft = !Ep2Configs.PRINTER_WATER.getBooleanValue() ? 0 : (int) snap.water().stream()
                        .filter(p -> !(level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.WATER) && level.getFluidState(p).isSource()))
                        .count();
                WaterFiller.Problem waterProblem = this.water.problem();
                int waterBlocked = this.water.blockedCount();
                this.setState(State.DONE, "");
                int wrong = snap.wrong() + this.reorient.remaining(level, schematic);
                this.stop(Component.translatable("easyplace2.printer.msg.done", snap.done(), snap.total(), wrong).withStyle(ChatFormatting.GREEN));

                if (waterBlocked > 0)
                {
                    message(Component.translatable("easyplace2.printer.msg.water_blocked", waterBlocked).withStyle(ChatFormatting.GOLD));
                }

                if (waterLeft > waterBlocked)
                {
                    String why = waterProblem == WaterFiller.Problem.NONE ? "out_of_reach" : waterProblem.name().toLowerCase(java.util.Locale.ROOT);
                    message(Component.translatable("easyplace2.printer.msg.water_left", waterLeft - waterBlocked,
                            Component.translatable("easyplace2.printer.water." + why)).withStyle(ChatFormatting.GOLD));
                }

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

        // Hier gibt es gerade nichts zu setzen: bevor weitergelaufen wird, falsch herum stehende Bloecke in der Naehe
        // abbauen (danach werden sie gleich richtig gesetzt)
        if (this.tryReorient(mc, player, level, schematic, now, false))
        {
            return;
        }

        // Die Schematic faengt weiter oben an: nicht unten herumlaufen und Wege suchen, sondern in der Naehe hochbauen
        // (auch schon ab 2 Bloecken, wenn der Weg zu so einem Block gerade nicht ging: vom Boden aus sieht man die
        // Oberseite darunter nicht, und das eigene Laufen setzt unterwegs keine Bloecke)
        int gap = lowest - player.getBlockY();
        boolean stuckBelow = gap >= 2 && layer.stream().anyMatch(t -> t.pos().getY() == lowest && this.nearFailedArea(t.pos()));

        if ((gap >= TowerUp.MIN_HEIGHT || stuckBelow) && this.tryTower(mc, player, level, schematic, snap, layer, lowest, now))
        {
            return;
        }

        // Frei in der Luft (nichts daneben, an das man setzen koennte): erst Hilfsbloecke bis zu einem vorhandenen
        // Block setzen, dann den Block daran; die Hilfsbloecke werden am Ende wie Geruest wieder abgebaut
        for (SchematicScanner.Target t : reachable)
        {
            if (SupportBlocks.supported(level, t.pos()) || this.supportAttempts.getOrDefault(t.pos(), 0) >= 4)
            {
                continue;
            }

            List<BlockPos> chain = SupportBlocks.chain(player, level, schematic, t.pos());

            if (chain == null || !SupportBlocks.reachable(player, level, chain.get(0)))
            {
                continue;
            }

            Item helper = SupportBlocks.item(player);

            if (helper == null)
            {
                if (!this.supportHintShown)
                {
                    this.supportHintShown = true;
                    message(Component.translatable("easyplace2.printer.msg.no_support_item").withStyle(ChatFormatting.GOLD));
                }

                break;
            }

            if (!player.getMainHandItem().is(helper))
            {
                if (this.inventory.request(player, helper, snap.neededItems().keySet()))
                {
                    this.setState(State.FETCHING_ITEM, new ItemStack(helper).getHoverName().getString());
                    return;
                }

                break;
            }

            if (this.support.start(player, level, chain.get(0)))
            {
                this.supportAttempts.merge(t.pos(), 1, Integer::sum);
                this.setState(State.SUPPORTING, chain.get(0).toShortString());
                return;
            }
        }

        // Ueber Luft (kein Boden darunter): an die Kante schleichen und an die Seite des letzten Blocks setzen,
        // wie ein Spieler, der eine Bruecke baut. Geht auch ohne Baritone, wenn man schon auf der Linie steht.
        EdgeBridge.Plan bridgePlan = EdgeBridge.find(player, level, layer.stream().map(SchematicScanner.Target::pos)
                .filter(p -> this.bridgeAttempts.getOrDefault(p, 0) < 3 && !this.blacklist.containsKey(p)).toList());

        if (bridgePlan != null)
        {
            this.bridgeAttempts.merge(bridgePlan.target(), 1, Integer::sum);
            this.bridge.start(bridgePlan);
            AimController.INSTANCE.stop();
            this.setState(State.BRIDGING, bridgePlan.target().toShortString());
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

        // War der Printer schon "in der Naehe" und konnte trotzdem nicht setzen, braucht dieser Block einen genau
        // berechneten Stehplatz (z. B. Treppen, die nur von einer Seite richtig herum gesetzt werden)
        if (this.moveTarget != null && this.walkingNear && Vec3.atCenterOf(this.moveTarget).distanceTo(eye) <= maxDist + 0.5)
        {
            this.preciseWalk.add(this.moveTarget);
        }

        // Schnell: einfach zum naechsten Block laufen; Baritone sucht den Weg, angehalten wird, sobald er in Reichweite ist
        // Nur Bloecke, die wirklich ausser Reichweite sind: die nahen gingen von hier aus ja gerade nicht
        SchematicScanner.Target nearest = layer.stream()
                .filter(t -> !this.preciseWalk.contains(t.pos()) && this.moveAttempts.getOrDefault(t.pos(), 0) < 2 && !this.nearFailedArea(t.pos()) &&
                             Vec3.atCenterOf(t.pos()).distanceTo(eye) > maxDist)
                .min(Comparator.comparingDouble(t -> t.pos().distToCenterSqr(eye)))
                .orElse(null);
        int nearRange = Math.max(2, (int) Math.floor(maxDist - 1.0));

        // Freier, ebener Weg geradeaus: selbst hinlaufen (sprinten), ganz ohne Wegberechnung
        if (nearest != null && now >= this.directFailUntil &&
            LaneWalker.directWalkable(level, player.position(), nearest.pos(), maxDist - 0.3))
        {
            this.bridge.release(Minecraft.getInstance());
            AimController.INSTANCE.stop();
            this.directWalk = true;
            this.walker.startToward(nearest.pos(), player, maxDist - 0.3, Ep2Configs.PRINTER_SPRINT.getBooleanValue());
            this.setState(State.LANE_WALKING, blockName(nearest.state()));
            return;
        }

        if (nearest != null && StandSpots.anyStandable(level, nearest.pos(), nearRange) && Walking.goNear(nearest.pos(), nearRange))
        {
            this.bridge.release(Minecraft.getInstance());
            AimController.INSTANCE.stop();
            this.moveTarget = nearest.pos();
            this.walkingNear = true;
            this.moveStartTick = now;
            this.moveStartPos = player.position();
            this.watchdog.startMoving(player.position());
            this.setState(State.MOVING, blockName(nearest.state()));
            return;
        }

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

            Walking.setScaffold(scaffold, usable);

            if (Walking.goToAny(spots))
            {
                this.bridge.release(Minecraft.getInstance());
                AimController.INSTANCE.stop();
                this.moveTarget = t.pos();
                this.walkingNear = false;
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
        Walking.setScaffold(Ep2Configs.PRINTER_SCAFFOLD.getBooleanValue(), usable);

        if (Walking.goToXZ(nearest.getX(), nearest.getZ()))
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
        // Schon in Reichweite: sofort anhalten und setzen, nicht erst bis zum Ende des Weges laufen
        if (this.walkingNear && this.moveTarget != null && this.stateTicks >= 2 && player.onGround() &&
            Vec3.atCenterOf(this.moveTarget).distanceTo(player.getEyePosition()) <= RefillUtil.maxDistance(player) - 0.3)
        {
            Walking.cancel();
            this.setState(State.SELECTING, "");
            this.stateTicks = 1;
            return;
        }

        // Kommt er dem Ziel seit Sekunden nicht naeher (Baritone irrt z. B. unten herum, obwohl das Ziel oben ist):
        // nicht endlos weiterlaufen, sondern etwas anderes probieren
        if (this.moveTarget != null)
        {
            double dist = Vec3.atCenterOf(this.moveTarget).distanceTo(player.position());

            if (this.stateTicks <= 1 || dist < this.bestMoveDist - 0.5)
            {
                this.bestMoveDist = dist;
                this.bestMoveTick = now;
            }
            else if (now - this.bestMoveTick > NO_CLOSER_TICKS)
            {
                debug("Laufen abgebrochen (kommt nicht naeher), Ziel {}", this.moveTarget.toShortString());
                Walking.cancel();
                Walking.setScaffold(false, List.of());
                this.markFailed(this.moveTarget, now);
                this.setState(State.SELECTING, "");
                this.stateTicks = 1;
                return;
            }
        }

        // Baritone braucht einen Moment, bis der Weg berechnet ist
        if (this.stateTicks < 4 || Walking.isBusy())
        {
            // Nicht lange ueberlegen, einfach ausprobieren: kommt er gar nicht erst los, steht er unterwegs still oder
            // dauert es zu lange, wird gleich ein anderes Ziel probiert (diese Ecke spaeter noch einmal)
            boolean noStart = this.stateTicks >= NO_START_TICKS && player.position().distanceTo(this.moveStartPos) < 0.3;
            boolean stuck = this.stateTicks >= 10 && this.watchdog.stuckWhileMoving(player.position(), STUCK_WALK_TICKS);

            if (now - this.moveStartTick > MOVE_TIMEOUT_TICKS || stuck || noStart)
            {
                debug("Laufen abgebrochen ({}), Ziel {}", noStart ? "kommt nicht los" : stuck ? "steckt fest" : "dauert zu lange",
                        this.moveTarget != null ? this.moveTarget.toShortString() : "-");
                Walking.cancel();
                Walking.setScaffold(false, List.of());
                this.markFailed(this.moveTarget, now);
                this.setState(State.SELECTING, "");
                this.stateTicks = 1;
            }

            return;
        }

        Walking.setScaffold(false, List.of());

        // Kein Weg gefunden (kaum vom Fleck gekommen): zaehlt als Fehlversuch, und erst einmal etwas anderes probieren
        if (this.moveTarget != null && player.position().distanceTo(this.moveStartPos) < 1.0 &&
            Vec3.atBottomCenterOf(this.moveTarget).distanceTo(player.position()) > 1.5)
        {
            this.moveAttempts.merge(this.moveTarget, 1, Integer::sum);
            this.markFailed(this.moveTarget, now);
            this.setState(State.SELECTING, "");
            this.stateTicks = 1;
            return;
        }

        // Angekommen: ganz kurz "umschauen", dann neu planen
        this.settleTicks = 1 + this.random.nextInt(2);
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

        // Ob der Server den Block behaelt, wird nebenbei geprueft, waehrend schon der naechste Block angepeilt wird
        // (wie ein Spieler, der nach dem Klick gleich weiterschaut). Die Pause bis zum naechsten Klick bleibt.
        int extra = Ep2Configs.PRINTER_FAST_BUILD.getBooleanValue() ? 0 : this.random.nextInt(Ep2Configs.PRINTER_EXTRA_DELAY.getIntegerValue() + 1);
        this.nextClickTick = now + Ep2Configs.DELAY_TICKS.getIntegerValue() + extra;
        this.pending.add(new Pending(t, now));
        this.current = null;
        this.setState(State.SELECTING, "");
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
                Reorient.placedByPrinter(pos);
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

        if (this.canWalk() && Walking.goToAny(List.of(target)))
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
        Walking.cancel();
        Walking.setScaffold(false, List.of());
        this.refill.cancel(mc);
        this.cleaner.cancel(mc);
        this.excavator.cancel(mc);
        this.water.cancel(mc);
        this.reorienter.cancel(mc);
        this.adjuster.cancel();
        this.tower.stop(mc);
        this.tunnel.stop(mc);
        this.bridge.stop(mc);
        this.support.cancel();
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
                    Walking.setScaffold(Ep2Configs.PRINTER_SCAFFOLD.getBooleanValue(), ScaffoldTracker.INSTANCE.usable());

                    if (Walking.goToXZ(x, z))
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

    // ---------------------------------------------------------------- Einstellen, Ausrichtung, Ausprobieren

    /**
     * Repeater, Komparatoren, Notenbloecke, Tageslichtsensoren einstellen.
     *
     * @param walk auch zu weiter entfernten hinlaufen (am Ende), sonst nur, was von hier aus anklickbar ist
     */
    private boolean tryAdjust(Minecraft mc, LocalPlayer player, ClientLevel level, Level schematic, SchematicScanner.Snapshot snap,
                              long now, boolean walk)
    {
        if (!Ep2Configs.PRINTER_ADJUST.getBooleanValue() || snap.adjust().isEmpty())
        {
            return false;
        }

        Vec3 eye = player.getEyePosition();
        double reach = RefillUtil.maxDistance(player) + 0.5;
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (BlockPos p : snap.adjust())
        {
            if (this.adjustAttempts.getOrDefault(p, 0) >= 3 || !level.hasChunkAt(p))
            {
                continue;
            }

            BlockState wanted = schematic.getBlockState(p);

            if (BlockAdjuster.clicksNeeded(level.getBlockState(p), wanted) == 0)
            {
                continue;
            }

            double dist = Vec3.atCenterOf(p).distanceTo(eye);

            if (dist <= reach && this.adjuster.start(player, level, p, wanted))
            {
                // Nie schleichend klicken (sonst wuerde das Item in der Hand gesetzt)
                this.bridge.release(mc);
                this.setState(State.ADJUSTING, blockName(wanted));
                return true;
            }

            if (dist < nearestDist && !this.nearFailedArea(p))
            {
                nearestDist = dist;
                nearest = p;
            }
        }

        if (walk && nearest != null && this.canWalk())
        {
            // Jeder Anlauf zaehlt, damit es nie endlos hin und her geht
            this.adjustAttempts.merge(nearest, 1, Integer::sum);
            return this.walkNear(mc, player, level, nearest, now, blockName(schematic.getBlockState(nearest)));
        }

        return false;
    }

    /**
     * Falsch herum stehende Bloecke abbauen (danach werden sie gleich richtig gesetzt).
     *
     * @param walk auch weiter entfernte (am Ende), sonst nur die in Reichweite
     */
    private boolean tryReorient(Minecraft mc, LocalPlayer player, ClientLevel level, Level schematic, long now, boolean walk)
    {
        if (!Ep2Configs.PRINTER_FIX_ROTATION.getBooleanValue())
        {
            return false;
        }

        Vec3 eye = player.getEyePosition();
        double reach = RefillUtil.maxDistance(player) + 0.5;

        if (this.reorienter.start(player, level, schematic, p -> walk || Vec3.atCenterOf(p).distanceTo(eye) <= reach, now))
        {
            this.reorienterTarget = this.reorienter.target();
            this.bridge.release(mc);
            AimController.INSTANCE.stop();
            this.setState(State.FIXING, this.reorienter.detail());
            return true;
        }

        return false;
    }

    /** Diese Stelle gleich wieder setzen, ohne auf den naechsten Scan zu warten. */
    private void inject(BlockPos pos, Level schematic)
    {
        BlockState wanted = schematic.getBlockState(pos);
        ItemStack required = fi.dy.masa.litematica.materials.MaterialCache.getInstance().getRequiredBuildItemForState(wanted, schematic, pos);

        if (!required.isEmpty() && required.getItem() instanceof BlockItem)
        {
            this.injected.removeIf(t -> t.pos().equals(pos));
            this.injected.add(new SchematicScanner.Target(pos.immutable(), wanted, required.getItem()));
        }
    }

    /** Schnell in die Naehe laufen (Baritone nimmt den kuerzesten Weg, angehalten wird in Reichweite). */
    private boolean walkNear(Minecraft mc, LocalPlayer player, ClientLevel level, BlockPos pos, long now, String detail)
    {
        int range = Math.max(2, (int) Math.floor(RefillUtil.maxDistance(player) - 1.0));

        if (!StandSpots.anyStandable(level, pos, range) || !Walking.goNear(pos, range))
        {
            this.markFailed(pos, now);
            return false;
        }

        this.bridge.release(mc);
        AimController.INSTANCE.stop();
        this.moveTarget = pos;
        this.walkingNear = true;
        this.moveStartTick = now;
        this.moveStartPos = player.position();
        this.watchdog.startMoving(player.position());
        this.setState(State.MOVING, detail);
        return true;
    }

    /** Der Weg dorthin ging gerade nicht: diese Ecke eine Weile meiden und erst etwas anderes ausprobieren. */
    private void markFailed(@Nullable BlockPos pos, long now)
    {
        if (pos == null)
        {
            return;
        }

        this.failedAreas.put(pos.immutable(), now + TRY_OTHER_TICKS);
        LocalPlayer player = Minecraft.getInstance().player;

        // Liegt die Stelle 2+ Bloecke ueber den Fuessen, nicht sperren: dann wird dort hochgebaut (siehe select)
        if (player == null || pos.getY() - player.getBlockY() < 2)
        {
            this.blacklist.put(pos.immutable(), now + TRY_OTHER_TICKS);
        }
    }

    /** Liegt die Stelle dort, wo der Weg gerade nicht ging? */
    private boolean nearFailedArea(BlockPos pos)
    {
        for (BlockPos failed : this.failedAreas.keySet())
        {
            if (failed.distSqr(pos) <= FAILED_AREA_RADIUS * FAILED_AREA_RADIUS)
            {
                return true;
            }
        }

        return false;
    }

    /** Wie viele Bloecke seit dem Start per Rechtsklick eingestellt wurden. */
    public int getAdjusted()
    {
        return this.adjusted;
    }

    /**
     * Hochbauen: einen Platz neben den untersten Bloecken suchen (nah am Spieler), hinlaufen, Turm bauen.
     *
     * @return true, wenn etwas gestartet wurde
     */
    private boolean tryTower(Minecraft mc, LocalPlayer player, ClientLevel level, Level schematic, SchematicScanner.Snapshot snap,
                             List<SchematicScanner.Target> layer, int lowest, long now)
    {
        TowerUp.Plan plan = this.towerPlan;

        if (plan == null || plan.topFeetY() != lowest + 1 || this.blacklist.containsKey(plan.ground()))
        {
            List<BlockPos> cells = layer.stream().map(SchematicScanner.Target::pos).filter(p -> p.getY() == lowest).toList();
            plan = TowerUp.find(player, level, schematic, cells, lowest, this.blacklist::containsKey);
        }

        if (plan == null)
        {
            debug("Kein Platz zum Hochbauen gefunden (unterste Schicht y={})", lowest);
            return false;
        }

        this.towerPlan = plan;
        Item item = SupportBlocks.item(player);
        int height = plan.topFeetY() - Math.max(plan.ground().getY(), TowerUp.atGround(player, plan) ? player.getBlockY() : plan.ground().getY());

        if (item == null || InventoryManager.countItem(player, item) < height)
        {
            if (!this.towerHintShown)
            {
                this.towerHintShown = true;
                message(Component.translatable("easyplace2.printer.msg.no_tower_item", Math.max(1, height)).withStyle(ChatFormatting.GOLD));
            }

            this.towerPlan = null;
            return false;
        }

        if (TowerUp.atGround(player, plan))
        {
            if (!player.getMainHandItem().is(item))
            {
                if (this.inventory.request(player, item, snap.neededItems().keySet()))
                {
                    this.setState(State.FETCHING_ITEM, new ItemStack(item).getHoverName().getString());
                    return true;
                }

                return false;
            }

            this.bridge.release(mc);
            AimController.INSTANCE.stop();
            this.tower.start(plan);

            if (!this.towers.contains(plan))
            {
                this.towers.add(plan);
            }

            this.setState(State.TOWERING, String.valueOf(height));
            return true;
        }

        // Erst hinlaufen (am Boden, also ein Weg, den Baritone sofort findet)
        if (this.canWalk() && Walking.goToAny(List.of(plan.ground())))
        {
            this.bridge.release(mc);
            AimController.INSTANCE.stop();
            this.moveTarget = plan.ground();
            this.walkingNear = false;
            this.moveStartTick = now;
            this.moveStartPos = player.position();
            this.watchdog.startMoving(player.position());
            this.setState(State.MOVING, plan.ground().toShortString());
            return true;
        }

        if (LaneWalker.directWalkable(level, player.position(), plan.ground(), 0.3))
        {
            this.bridge.release(mc);
            AimController.INSTANCE.stop();
            this.directWalk = true;
            this.walker.startToward(plan.ground(), player, 0.3, Ep2Configs.PRINTER_SPRINT.getBooleanValue());
            this.setState(State.LANE_WALKING, plan.ground().toShortString());
            return true;
        }

        this.blacklist.put(plan.ground(), now + TRY_OTHER_TICKS);
        this.towerPlan = null;
        return false;
    }

    /**
     * Am Ende: auf den zuletzt gebauten Turm stellen und ihn von oben nach unten abbauen.
     *
     * @return true, wenn etwas gestartet wurde
     */
    private boolean descendTower(LocalPlayer player, ClientLevel level, Level schematic, long now)
    {
        Set<BlockPos> tracked = ScaffoldTracker.INSTANCE.positions();

        while (!this.towers.isEmpty())
        {
            TowerUp.Plan plan = this.towers.get(this.towers.size() - 1);
            List<BlockPos> cells = new ArrayList<>();

            for (int y = plan.ground().getY(); y < plan.topFeetY(); y++)
            {
                BlockPos p = new BlockPos(plan.ground().getX(), y, plan.ground().getZ());

                if (tracked.contains(p) && !level.getBlockState(p).isAir())
                {
                    cells.add(p);
                }
            }

            if (cells.isEmpty())
            {
                this.towers.remove(this.towers.size() - 1);
                continue;
            }

            BlockPos top = cells.get(cells.size() - 1);
            BlockPos feet = player.blockPosition();

            // Mittig oben drauf (sonst steht man halb auf dem Nachbarblock und faellt beim Abbauen nicht mit)
            boolean centered = Math.abs(player.getX() - (top.getX() + 0.5)) < 0.2 && Math.abs(player.getZ() - (top.getZ() + 0.5)) < 0.2;

            if (player.onGround() && centered && feet.getX() == top.getX() && feet.getZ() == top.getZ() && feet.getY() == top.getY() + 1)
            {
                Set<BlockPos> set = new HashSet<>(cells);

                if (this.cleaner.start(player, level, schematic, set::contains, now))
                {
                    AimController.INSTANCE.stop();
                    this.setState(State.CLEANING, this.cleaner.detail());
                    return true;
                }
            }
            else if (this.towerWalks.merge(plan, 1, Integer::sum) <= 3 && this.canWalk() && Walking.goToAny(List.of(top.above())))
            {
                AimController.INSTANCE.stop();
                this.moveTarget = top.above();
                this.walkingNear = false;
                this.moveStartTick = now;
                this.moveStartPos = player.position();
                this.watchdog.startMoving(player.position());
                this.setState(State.MOVING, top.above().toShortString());
                return true;
            }

            // Geht nicht: der normale Geruest-Abbau kuemmert sich darum
            this.towers.remove(this.towers.size() - 1);
        }

        return false;
    }

    // ---------------------------------------------------------------- Nur aushoehlen / Star-Linie

    private void finishExcavate(LocalPlayer player, ClientLevel level, Level schematic, SchematicScanner.Snapshot snap, long now)
    {
        if (this.excavator.hasWork(level, schematic, p -> true, now))
        {
            // Es gibt noch etwas, nur gerade nicht startbar: gleich noch einmal planen
            this.setState(State.SELECTING, "");
            return;
        }

        int skipped = this.excavator.skippedCount(now);

        if (skipped > 0 && this.excavateRetries++ < 3)
        {
            // Nicht lange warten: gleich noch einmal versuchen (vielleicht ist es von hier aus jetzt erreichbar)
            this.excavator.clearSkipped();
            this.setState(State.SELECTING, "");
            return;
        }

        if (this.cleaner.start(player, level, schematic, p -> true, now))
        {
            AimController.INSTANCE.stop();
            this.setState(State.CLEANING, this.cleaner.detail());
            return;
        }

        if (!snap.unloaded().isEmpty() && this.canWalk() && this.unloadedAttempts.size() < 20)
        {
            this.walkToUnloaded(player, snap.unloaded(), now);

            if (this.state == State.MOVING)
            {
                return;
            }
        }

        int broken = this.excavator.broken() + this.tunnel.broken() - this.tunnelStart;
        this.stop(Component.translatable("easyplace2.printer.msg.excavate_done", broken).withStyle(ChatFormatting.GREEN));

        if (skipped > 0)
        {
            message(Component.translatable("easyplace2.printer.msg.excavate_left", skipped).withStyle(ChatFormatting.GOLD));
        }
    }

    private static boolean lineWanted(Level level, Level schematic, BlockPos pos)
    {
        BlockState current = level.getBlockState(pos);

        if (current.isAir() || current.canBeReplaced() || !Excavation.canDig(level, pos, current))
        {
            return false;
        }

        BlockState wanted = schematic.getBlockState(pos);
        return wanted.isAir() || !StateMatcher.isComplete(current, wanted);
    }

    private void selectLine(Minecraft mc, LocalPlayer player, ClientLevel level, Level schematic)
    {
        if (this.lineStarted)
        {
            // Die Linie ist fertig (oder ging nicht weiter)
            this.stop(Component.translatable("easyplace2.printer.msg.line_done", this.tunnel.broken() - this.tunnelStart).withStyle(ChatFormatting.GREEN));
            return;
        }

        this.lineStarted = true;
        int slot = io.github.recher1001.easyplace2.printer.lanes.StarTool.findSlot(player);

        if (slot < 0)
        {
            this.stop(Component.translatable("easyplace2.printer.msg.no_star", Ep2Configs.PRINTER_STAR_TOOL_NAME.getStringValue())
                    .withStyle(ChatFormatting.RED));
            return;
        }

        // Von den Fuessen aus geradeaus in Blickrichtung, solange die 3x3-Wand in der Schematic liegt
        net.minecraft.core.Direction dir = player.getDirection();
        BlockPos feet = player.blockPosition();
        List<io.github.recher1001.easyplace2.printer.lanes.LanePath.Step> run = new ArrayList<>();

        for (int i = 1; i <= 128; i++)
        {
            io.github.recher1001.easyplace2.printer.lanes.LanePath.Step step =
                    new io.github.recher1001.easyplace2.printer.lanes.LanePath.Step(feet.relative(dir, i), dir);
            boolean inside = false;

            for (BlockPos p : io.github.recher1001.easyplace2.printer.lanes.LanePath.crossSection(step, 1, 3))
            {
                if (this.scanner.inside(p))
                {
                    inside = true;
                    break;
                }
            }

            if (!inside)
            {
                break;
            }

            run.add(step);
        }

        if (run.isEmpty())
        {
            this.stop(Component.translatable("easyplace2.printer.msg.line_outside").withStyle(ChatFormatting.RED));
            return;
        }

        // Innerhalb der Schematic alles, was nicht richtig steht: wo sie Luft will und auch falsche (rote) Bloecke.
        // Was schon richtig steht, bleibt.
        this.tunnel.startWithCleanup(run, p -> this.scanner.inside(p) && lineWanted(level, schematic, p), slot);
        this.bridge.release(mc);
        AimController.INSTANCE.stop();
        this.setState(State.TUNNELING, run.size() + "");
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
        return Ep2Configs.PRINTER_USE_BARITONE.getBooleanValue() && Walking.isAvailable();
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
