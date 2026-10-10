package io.github.recher1001.easyplace2.printer.refill;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.litematica.world.SchematicWorldHandler;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.printer.AimController;
import io.github.recher1001.easyplace2.printer.BaritoneBridge;
import io.github.recher1001.easyplace2.printer.InventoryManager;
import io.github.recher1001.easyplace2.printer.StandSpots;

/**
 * Holt Material aus einem markierten Lager-Container: (mit Baritone) hinlaufen, hinschauen,
 * normal rechtsklicken, gebrauchte Items oder Shulker mit diesen Items per Shift-Klick nehmen, schliessen.
 */
public final class StorageFetch implements Refill
{
    private enum Step { CHECK, MOVING, AIM_OPEN, WAIT_SCREEN, TAKE, CLOSE, DONE }

    private static final int MAX_MOVES = 2;
    private static final int SCREEN_TIMEOUT = 50;
    private static final int AIM_TIMEOUT = 20 * 4;
    private static final int MOVE_TIMEOUT = 20 * 60;

    private final Random random = new Random();
    private final InventoryManager inventory;
    private final Set<Item> wanted;
    private final BlockPos pos;
    private final String label;

    private Step step = Step.CHECK;
    private int wait;
    private int stepTicks;
    private int moves;
    private int took;
    @Nullable private Screen ownScreen;

    private StorageFetch(InventoryManager inventory, Set<Item> wanted, BlockPos pos, String label)
    {
        this.inventory = inventory;
        this.wanted = wanted;
        this.pos = pos;
        this.label = label;
    }

    /**
     * Waehlt den naechsten Lager-Container, der laut letztem Stand eines der Items hat
     * (oder dessen Inhalt noch unbekannt ist).
     */
    @Nullable
    public static StorageFetch create(LocalPlayer player, Level level, Set<Item> wanted, InventoryManager inventory,
                                      Map<BlockPos, Long> cooldown, long now)
    {
        Vec3 eye = player.getEyePosition();
        StorageRegistry.Entry best = null;
        Item bestItem = null;
        double bestScore = Double.MAX_VALUE;

        for (StorageRegistry.Entry e : StorageRegistry.INSTANCE.entries())
        {
            if (cooldown.getOrDefault(e.pos, 0L) > now || !level.hasChunkAt(e.pos) || !StorageRegistry.isContainer(level, e.pos))
            {
                continue;
            }

            Item has = null;

            for (Item item : wanted)
            {
                if (e.total(item) > 0)
                {
                    has = item;
                    break;
                }
            }

            if (e.known && has == null)
            {
                continue;
            }

            // Bekannter Inhalt zuerst, dann der naechste
            double score = Vec3.atCenterOf(e.pos).distanceTo(eye) + (has == null ? 1000 : 0);

            if (score < bestScore)
            {
                bestScore = score;
                best = e;
                bestItem = has;
            }
        }

        if (best == null)
        {
            return null;
        }

        String label = bestItem != null ? new ItemStack(bestItem).getHoverName().getString() : best.pos.toShortString();
        return new StorageFetch(inventory, wanted, best.pos, label);
    }

    public BlockPos pos()
    {
        return this.pos;
    }

    /** Wie viele Stapel genommen wurden. */
    public int took()
    {
        return this.took;
    }

    @Override
    public boolean tick(Minecraft mc)
    {
        LocalPlayer player = mc.player;
        Level level = mc.level;

        if (player == null || level == null || mc.gameMode == null)
        {
            return true;
        }

        this.stepTicks++;

        if (this.wait > 0)
        {
            this.wait--;
            return false;
        }

        Level schematic = SchematicWorldHandler.getSchematicWorld();

        switch (this.step)
        {
            case CHECK ->
            {
                if (!StorageRegistry.isContainer(level, this.pos))
                {
                    this.go(Step.DONE, 0);
                }
                else if (!RefillUtil.visiblePoints(player, level, schematic, this.pos, null).isEmpty())
                {
                    this.go(Step.AIM_OPEN, 0);
                }
                else if (this.moves < MAX_MOVES && this.walk(player, level, schematic))
                {
                    this.moves++;
                    this.go(Step.MOVING, 0);
                }
                else
                {
                    this.go(Step.DONE, 0);
                }
            }
            case MOVING ->
            {
                if ((this.stepTicks > 10 && !BaritoneBridge.isBusy()) || this.stepTicks > MOVE_TIMEOUT)
                {
                    BaritoneBridge.cancel();
                    this.go(Step.CHECK, 3 + this.random.nextInt(4));
                }
            }
            case AIM_OPEN ->
            {
                List<BlockHitResult> points = RefillUtil.visiblePoints(player, level, schematic, this.pos, null);

                if (points.isEmpty() || this.stepTicks > AIM_TIMEOUT)
                {
                    this.go(Step.CHECK, 0);
                    return false;
                }

                AimController.INSTANCE.aimAt(points.get(0).getLocation(), Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());

                if (AimController.INSTANCE.isOnTarget() && RefillUtil.crosshairOn(mc, this.pos, null) && this.stepTicks > 1)
                {
                    RefillUtil.rightClick(mc);
                    this.go(Step.WAIT_SCREEN, 0);
                }
            }
            case WAIT_SCREEN ->
            {
                if (mc.screen instanceof AbstractContainerScreen<?> screen && !(screen instanceof InventoryScreen))
                {
                    this.ownScreen = screen;
                    this.go(Step.TAKE, this.inventory.humanDelay());
                }
                else if (this.stepTicks > SCREEN_TIMEOUT)
                {
                    this.go(Step.DONE, 0);
                }
            }
            case TAKE ->
            {
                if (this.ownScreen == null || mc.screen != this.ownScreen)
                {
                    this.ownScreen = null;
                    this.go(Step.DONE, 0);
                    return false;
                }

                int slot = RefillUtil.nextSlotToTake(player.containerMenu,
                        stack -> RefillUtil.isWanted(stack, this.wanted, true) && RefillUtil.hasRoomFor(player, stack));

                if (slot < 0)
                {
                    this.go(Step.CLOSE, this.inventory.humanDelay());
                }
                else
                {
                    RefillUtil.quickMove(mc, slot);
                    this.took++;
                    this.wait = this.inventory.humanDelay();
                }
            }
            case CLOSE ->
            {
                if (mc.screen == this.ownScreen)
                {
                    player.closeContainer();
                }

                this.ownScreen = null;
                this.go(Step.DONE, this.inventory.humanDelay());
            }
            case DONE ->
            {
                AimController.INSTANCE.stop();
                return true;
            }
        }

        return false;
    }

    /** Mit Baritone zu einer Stelle laufen, von der aus der Container sichtbar und erreichbar ist. */
    private boolean walk(LocalPlayer player, Level level, @Nullable Level schematic)
    {
        if (!Ep2Configs.PRINTER_USE_BARITONE.getBooleanValue() || !BaritoneBridge.isAvailable())
        {
            return false;
        }

        List<BlockPos> spots = StandSpots.find(level, schematic != null ? schematic : level, this.pos,
                RefillUtil.maxDistance(player), player.position(), feet -> RefillUtil.visibleFrom(player, feet, level, schematic, this.pos));

        return !spots.isEmpty() && BaritoneBridge.goToAny(spots.stream().sorted(Comparator.comparingDouble(
                p -> p.distSqr(player.blockPosition()))).toList());
    }

    private void go(Step next, int delay)
    {
        this.step = next;
        this.wait = delay;
        this.stepTicks = 0;
    }

    @Override
    public boolean ownsScreen(@Nullable Screen screen)
    {
        return screen != null && screen == this.ownScreen;
    }

    @Override
    public void cancel(Minecraft mc)
    {
        if (this.ownScreen != null && mc.screen == this.ownScreen && mc.player != null)
        {
            mc.player.closeContainer();
        }

        this.ownScreen = null;
        AimController.INSTANCE.stop();
        BaritoneBridge.cancel();
        this.go(Step.DONE, 0);
    }

    @Override
    public String detail()
    {
        return this.label;
    }
}
