package io.github.recher1001.easyplace2.printer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.mixin.MinecraftAccessor;
import io.github.recher1001.easyplace2.placement.EasyPlace2Handler;
import io.github.recher1001.easyplace2.placement.StateMatcher;

/**
 * Printer nach dem Vorbild des Lambda-Printers, in Java fuer Easy Place 2.0 umgesetzt.
 *
 * So wie der Lambda-Printer:
 * <ul>
 *     <li>baut alles, was in Reichweite um den Spieler fehlt, die naechsten Bloecke zuerst; er laeuft nicht selbst,</li>
 *     <li>sucht pro Block eine sichtbare Nachbarflaeche, auf die ein normaler Klick genau den Schematic-Block setzt
 *         (kein Air-Place, Richtung von Treppen usw. wird beim Hinschauen mitgeprueft),</li>
 *     <li>dreht sich erst hin und klickt dann (eine Aktion nach der anderen),</li>
 *     <li>wartet auf die Bestaetigung vom Server und versucht es sonst spaeter noch einmal,</li>
 *     <li>holt das Material selbst in die Hotbar.</li>
 * </ul>
 * Geklickt wird ueber Easy Place 2.0 (strenges Fadenkreuz), also wie ein Spieler mit Rechtsklick.
 */
public final class PrinterController
{
    public static final PrinterController INSTANCE = new PrinterController();

    /** Fuer das Menue behalten. Gebaut wird immer wie beim Lambda-Printer: alles in Reichweite um den Spieler. */
    public enum Mode { BUILD, EXCAVATE, LINE, AROUND }

    public enum State
    {
        OFF, SCANNING, SELECTING, FETCHING_ITEM, REFILLING, CLEANING, EXCAVATING, FIXING, ADJUSTING, WATERING, BRIDGING, SUPPORTING, TOWERING, TUNNELING, LANE_WALKING, MOVING, AIMING, CLICKING,
        PAUSED, PAUSED_SCREEN, MISSING_MATERIAL, NOTHING_IN_REACH, DONE
    }

    /** Wie oft ein Block scheitern darf, bevor er eine Weile uebersprungen wird. */
    private static final int MAX_FAILURES = 3;
    private static final int BLACKLIST_TICKS = 20 * 30;
    /** So lange (Ticks) nach dem Klick wird geprueft, ob der Server den Block behaelt. */
    private static final int CONFIRM_TICKS = 4;
    private static final int AIM_TIMEOUT_TICKS = 20 * 3;
    /** So viele Kandidaten werden pro Tick genau geprueft (die naechsten zuerst). */
    private static final int CANDIDATES_PER_TICK = 24;

    private final SchematicScanner scanner = new SchematicScanner();
    private final InventoryManager inventory = new InventoryManager();
    private final Random random = new Random();
    private final Map<Item, Integer> missingMaterials = new LinkedHashMap<>();
    private final Map<BlockPos, Integer> failures = new HashMap<>();
    private final Map<BlockPos, Long> blacklist = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();

    private boolean running;
    private boolean userPaused;
    private Mode mode = Mode.BUILD;
    private State state = State.OFF;
    private String detail = "";
    private int stateTicks;

    @Nullable private SchematicScanner.Target current;
    private List<BlockHitResult> aimHits = List.of();
    private int aimIndex;
    private int settleTicks;
    private long nextClickTick;

    private int placed;
    private long startMillis;

    /** Gesetzte Bloecke, bei denen noch geprueft wird, ob der Server sie behaelt. */
    private record Pending(SchematicScanner.Target target, long tick) {}

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

    public Mode getMode()
    {
        return this.mode;
    }

    /** Der Printer baut nur in Reichweite um den Spieler; fuer den Scanner gilt jede Stelle. */
    public boolean aroundAllows(BlockPos pos)
    {
        return true;
    }

    /** Einen reinen Aushoehlen-Modus gibt es beim Lambda-Printer nicht. */
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

        if (this.running || mc.player == null || mc.level == null)
        {
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

        // Platziert wird ueber Easy Place 2.0, und zwar nur dort, wo das Fadenkreuz wirklich hinzeigt
        Ep2Configs.EASY_PLACE_2.setBooleanValue(true);
        Ep2Configs.STRICT_CROSSHAIR.setBooleanValue(true);

        this.scanner.reset();
        this.inventory.cancel(mc);
        this.missingMaterials.clear();
        this.failures.clear();
        this.blacklist.clear();
        this.pending.clear();
        this.current = null;
        this.aimHits = List.of();
        this.placed = 0;
        this.startMillis = System.currentTimeMillis();
        this.nextClickTick = 0;
        this.mode = mode;
        this.userPaused = false;
        this.running = true;
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
        AimController.INSTANCE.stop();
        this.inventory.cancel(mc);
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
            this.current = null;
            AimController.INSTANCE.stop();
            this.inventory.cancel(Minecraft.getInstance());
            this.setState(State.PAUSED, "");
        }
        else
        {
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

    /** Der Printer wartet, solange ein fremdes Menue offen ist (nur sein eigenes Inventar-Fenster ist erlaubt). */
    public boolean worksBehind(@Nullable Screen screen)
    {
        return screen != null && this.inventory.ownsScreen(screen);
    }

    // ---------------------------------------------------------------- Tick

    private void tick(Minecraft mc)
    {
        if (!this.running)
        {
            return;
        }

        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;

        if (player == null || level == null)
        {
            return;
        }

        if (!player.isAlive())
        {
            this.stop(Component.translatable("easyplace2.printer.msg.died").withStyle(ChatFormatting.RED));
            return;
        }

        Level schematic = SchematicWorldHandler.getSchematicWorld();

        if (schematic == null)
        {
            this.stop(Component.translatable("easyplace2.printer.msg.no_schematic").withStyle(ChatFormatting.RED));
            return;
        }

        this.stateTicks++;
        this.scanner.tick(level);
        long now = level.getGameTime();
        this.checkPending(level, now);

        if (this.userPaused)
        {
            return;
        }

        // Material holen (oeffnet dafuer selbst das Inventar)
        if (this.inventory.isBusy())
        {
            this.inventory.tick(mc);
            return;
        }

        // Bei fremden Menues wartet der Printer, wie ein Spieler
        if (mc.screen != null && !this.worksBehind(mc.screen))
        {
            if (this.current != null)
            {
                this.current = null;
                AimController.INSTANCE.stop();
            }

            this.setState(State.PAUSED_SCREEN, "");
            return;
        }

        SchematicScanner.Snapshot snap = this.scanner.latest();

        if (snap == null)
        {
            this.setState(State.SCANNING, "");
            return;
        }

        if (snap.missing().isEmpty() && snap.total() > 0 && this.pending.isEmpty())
        {
            this.stop(Component.translatable("easyplace2.printer.msg.done", snap.done(), snap.total(), snap.wrong())
                    .withStyle(ChatFormatting.GREEN));
            this.setState(State.DONE, "");
            return;
        }

        switch (this.state)
        {
            case AIMING -> this.tickAiming(level, now);
            case CLICKING -> this.tickClicking(mc, player, level, now);
            default -> this.select(player, level, schematic, snap, now);
        }
    }

    /** Wie der Lambda-Printer: von den fehlenden Bloecken in Reichweite den naechsten nehmen, der sich setzen laesst. */
    private void select(LocalPlayer player, ClientLevel level, Level schematic, SchematicScanner.Snapshot snap, long now)
    {
        this.blacklist.values().removeIf(until -> until <= now);
        Vec3 eye = player.getEyePosition();
        double range = Math.min(Ep2Configs.PRINTER_AROUND_RANGE.getDoubleValue(), player.blockInteractionRange());
        double reach = player.blockInteractionRange() - Ep2Configs.PRINTER_REACH_MARGIN.getDoubleValue();

        List<SchematicScanner.Target> inRange = new ArrayList<>();

        for (SchematicScanner.Target t : snap.missing())
        {
            if (!(t.item() instanceof BlockItem) || this.blacklist.containsKey(t.pos()) || this.isPending(t.pos()))
            {
                continue;
            }

            if (Vec3.atCenterOf(t.pos()).distanceTo(eye) <= range + 0.5)
            {
                inRange.add(t);
            }
        }

        inRange.sort(Comparator.comparingDouble(t -> Vec3.atCenterOf(t.pos()).distanceToSqr(eye)));
        this.missingMaterials.clear();

        SchematicScanner.Target best = null;
        List<BlockHitResult> bestHits = List.of();
        double bestScore = Double.MAX_VALUE;
        int checked = 0;

        for (SchematicScanner.Target t : inRange)
        {
            if (checked >= CANDIDATES_PER_TICK)
            {
                break;
            }

            Item item = t.item();

            if (InventoryManager.countItem(player, item) <= 0)
            {
                this.missingMaterials.merge(item, 1, Integer::sum);
                continue;
            }

            checked++;
            ItemStack stack = new ItemStack(item);
            boolean held = EntityUtils.getUsedHandForItem(player, stack) != null;
            boolean inHotbar = held || InventoryManager.isInHotbar(player, item);
            List<BlockHitResult> hits = AimPlanner.find(player, level, schematic, t.pos(), t.state(), stack, (BlockItem) item, reach);

            if (hits.isEmpty())
            {
                continue;
            }

            double score = Vec3.atCenterOf(t.pos()).distanceTo(eye) + AimController.angleTo(player, hits.get(0).getLocation()) / 45.0
                           + (held ? 0 : inHotbar ? 1 : 3);

            if (score < bestScore)
            {
                best = t;
                bestHits = hits;
                bestScore = score;
            }
        }

        if (best == null)
        {
            this.setState(this.missingMaterials.isEmpty() ? State.NOTHING_IN_REACH : State.MISSING_MATERIAL, "");
            return;
        }

        ItemStack stack = new ItemStack(best.item());

        // Erst das Item wie ein Spieler holen, danach wird neu geplant
        if (EntityUtils.getUsedHandForItem(player, stack) == null)
        {
            if (this.inventory.request(player, best.item(), snap.neededItems().keySet()))
            {
                this.setState(State.FETCHING_ITEM, stack.getHoverName().getString());
            }
            else
            {
                this.fail(best.pos(), now);
            }

            return;
        }

        this.current = best;
        this.aimHits = bestHits;
        this.aimIndex = 0;
        AimController.INSTANCE.aimAt(bestHits.get(0).getLocation(), Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());
        this.setState(State.AIMING, best.state().getBlock().getName().getString());
    }

    private void tickAiming(ClientLevel level, long now)
    {
        if (this.current == null || !isStillMissing(level, this.current))
        {
            this.current = null;
            AimController.INSTANCE.stop();
            this.setState(State.SELECTING, "");
            return;
        }

        if (AimController.INSTANCE.isOnTarget())
        {
            // Kurz auf dem Ziel bleiben, dann klicken (schnell bauen: sofort)
            int min = Ep2Configs.PRINTER_REACTION_MIN.getIntegerValue();
            int max = Math.max(min, Ep2Configs.PRINTER_REACTION_MAX.getIntegerValue());
            this.settleTicks = Ep2Configs.PRINTER_FAST_BUILD.getBooleanValue() ? 0 : min - 1 + this.random.nextInt(max - min + 1);
            this.setState(State.CLICKING, this.detail);
        }
        else if (this.stateTicks > AIM_TIMEOUT_TICKS)
        {
            this.nextAimPoint(now);
        }
    }

    private void tickClicking(Minecraft mc, LocalPlayer player, ClientLevel level, long now)
    {
        SchematicScanner.Target t = this.current;

        if (t == null || !isStillMissing(level, t))
        {
            this.current = null;
            this.setState(State.SELECTING, "");
            return;
        }

        if (this.settleTicks-- > 0 || now < this.nextClickTick)
        {
            return;
        }

        if (EntityUtils.getUsedHandForItem(player, new ItemStack(t.item())) == null)
        {
            this.current = null;
            this.setState(State.SELECTING, "");
            return;
        }

        // Das echte Fadenkreuz muss genau auf der geplanten Flaeche liegen, und Easy Place 2.0 muss genau
        // diesen Geisterblock waehlen (sonst wuerde etwas anderes gesetzt)
        BlockHitResult planned = this.aimHits.get(this.aimIndex);
        boolean crosshairOk = mc.hitResult instanceof BlockHitResult bhr &&
                              bhr.getBlockPos().equals(planned.getBlockPos()) &&
                              bhr.getDirection() == planned.getDirection();
        Level schematicWorld = SchematicWorldHandler.getSchematicWorld();
        EasyPlace2Handler.Choice choice = schematicWorld != null ? EasyPlace2Handler.choose(mc, player, level, schematicWorld, player) : null;
        boolean ghostOk = choice != null && choice.plan() != null && choice.target().equals(t.pos());

        if (!crosshairOk || !ghostOk)
        {
            this.nextAimPoint(now);
            return;
        }

        ((MinecraftAccessor) mc).easyplace2$startUseItem();

        int extra = Ep2Configs.PRINTER_FAST_BUILD.getBooleanValue() ? 0 : this.random.nextInt(Ep2Configs.PRINTER_EXTRA_DELAY.getIntegerValue() + 1);
        this.nextClickTick = now + Ep2Configs.DELAY_TICKS.getIntegerValue() + extra;
        this.pending.add(new Pending(t, now));
        this.current = null;
        this.setState(State.SELECTING, "");
    }

    private void checkPending(ClientLevel level, long now)
    {
        Iterator<Pending> it = this.pending.iterator();

        while (it.hasNext())
        {
            Pending p = it.next();

            if (now - p.tick() < CONFIRM_TICKS)
            {
                continue;
            }

            it.remove();
            BlockPos pos = p.target().pos();

            if (StateMatcher.isComplete(level.getBlockState(pos), p.target().state()))
            {
                this.placed++;
                this.failures.remove(pos);
            }
            else
            {
                this.fail(pos, now);
            }
        }
    }

    private boolean isPending(BlockPos pos)
    {
        for (Pending p : this.pending)
        {
            if (p.target().pos().equals(pos))
            {
                return true;
            }
        }

        return false;
    }

    private void nextAimPoint(long now)
    {
        if (++this.aimIndex < this.aimHits.size())
        {
            AimController.INSTANCE.aimAt(this.aimHits.get(this.aimIndex).getLocation(), Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());
            this.setState(State.AIMING, this.detail);
            this.stateTicks = 0;
            return;
        }

        if (this.current != null)
        {
            this.fail(this.current.pos(), now);
        }

        this.current = null;
        AimController.INSTANCE.stop();
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

    private static boolean isStillMissing(ClientLevel level, SchematicScanner.Target t)
    {
        return !StateMatcher.isComplete(level.getBlockState(t.pos()), t.state());
    }

    private void setState(State state, String detail)
    {
        if (this.state != state)
        {
            this.stateTicks = 0;
        }

        this.state = state;
        this.detail = detail;
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
