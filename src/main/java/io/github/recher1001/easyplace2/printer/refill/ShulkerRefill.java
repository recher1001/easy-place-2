package io.github.recher1001.easyplace2.printer.refill;

import java.util.List;
import java.util.Random;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import fi.dy.masa.litematica.world.SchematicWorldHandler;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.placement.InteractiveBlocks;
import io.github.recher1001.easyplace2.printer.AimController;
import io.github.recher1001.easyplace2.printer.BaritoneBridge;
import io.github.recher1001.easyplace2.printer.InventoryManager;
import io.github.recher1001.easyplace2.printer.ShulkerContents;

/**
 * Holt Material aus einer Shulker-Kiste im eigenen Inventar, so wie ein Spieler es machen wuerde:
 * <ol>
 *     <li>Shulker in die Hand nehmen (ueber das Inventar, mit Pausen)</li>
 *     <li>neben sich auf den Boden stellen (normaler Rechtsklick auf die Oberseite des Bodens)</li>
 *     <li>oeffnen, die gebrauchten Items per Shift-Klick nehmen, schliessen</li>
 *     <li>wieder abbauen (Angriff gedrueckt halten, mit Spitzhacke falls vorhanden) und einsammeln</li>
 * </ol>
 * Steht die Kiste einmal, wird sie in jedem Fall wieder abgebaut.
 */
public final class ShulkerRefill implements Refill
{
    private enum Step { FETCH, AIM_PLACE, WAIT_PLACED, AIM_OPEN, WAIT_SCREEN, TAKE, CLOSE, AIM_BREAK, BREAK, PICKUP, WALK_PICKUP, DONE }

    private static final int PLACE_TIMEOUT = 30;
    private static final int SCREEN_TIMEOUT = 50;
    private static final int BREAK_TIMEOUT = 20 * 15;
    private static final int PICKUP_TIMEOUT = 50;
    private static final int AIM_TIMEOUT = 20 * 4;

    private final Random random = new Random();
    private final InventoryManager inventory;
    private final Set<Item> wanted;
    private final int shulkerSlot;
    private final int shulkersBefore;
    private final BlockPos spot;
    private final String label;

    private Step step = Step.FETCH;
    private int wait;
    private int stepTicks;
    private boolean placed;
    /** Die Kiste stand in diesem Vorgang schon einmal (muss also wieder eingesammelt werden). */
    private boolean everPlaced;
    private boolean fetchStarted;
    private int took;
    @Nullable private Screen ownScreen;
    @Nullable private Direction breakFace;

    private ShulkerRefill(InventoryManager inventory, Set<Item> wanted, int shulkerSlot, int shulkersBefore, BlockPos spot, String label)
    {
        this.inventory = inventory;
        this.wanted = wanted;
        this.shulkerSlot = shulkerSlot;
        this.shulkersBefore = shulkersBefore;
        this.spot = spot;
        this.label = label;
    }

    /**
     * @return ein neuer Vorgang, oder null wenn keine passende Shulker oder kein freier Platz da ist
     */
    @Nullable
    public static ShulkerRefill create(LocalPlayer player, Level level, @Nullable Level schematic, Set<Item> wanted, InventoryManager inventory)
    {
        if (ShulkerContents.freeSlots(player) < 1)
        {
            return null;
        }

        // Die Shulker mit den meisten gebrauchten Items
        int bestSlot = -1, bestCount = 0;
        Item bestItem = null;

        for (int slot : ShulkerContents.shulkerSlots(player))
        {
            ItemStack shulker = player.getInventory().getItem(slot);
            int count = 0;
            Item top = null;

            for (Item item : wanted)
            {
                int c = ShulkerContents.count(shulker, item);
                count += c;

                if (c > 0 && top == null)
                {
                    top = item;
                }
            }

            if (count > bestCount)
            {
                bestCount = count;
                bestSlot = slot;
                bestItem = top;
            }
        }

        if (bestSlot < 0)
        {
            return null;
        }

        BlockPos spot = findSpot(player, level, schematic);

        if (spot == null)
        {
            return null;
        }

        int shulkers = ShulkerContents.shulkerSlots(player).size();
        String label = new ItemStack(bestItem).getHoverName().getString();
        return new ShulkerRefill(inventory, wanted, bestSlot, shulkers, spot, label);
    }

    /** Ein Feld direkt neben dem Spieler, auf dem die Kiste stehen und aufgehen kann. */
    @Nullable
    private static BlockPos findSpot(LocalPlayer player, Level level, @Nullable Level schematic)
    {
        BlockPos feet = player.blockPosition();
        AABB body = player.getBoundingBox();
        BlockPos best = null;

        for (int pass = 0; pass < 2 && best == null; pass++)
        {
            for (int dx = -1; dx <= 1 && best == null; dx++)
            {
                for (int dz = -1; dz <= 1 && best == null; dz++)
                {
                    boolean straight = dx == 0 || dz == 0;

                    // zuerst die geraden Nachbarn, dann die schraegen
                    if ((dx == 0 && dz == 0) || straight != (pass == 0))
                    {
                        continue;
                    }

                    BlockPos spot = feet.offset(dx, 0, dz);

                    if (isFreeSpot(player, level, schematic, spot, body))
                    {
                        best = spot;
                    }
                }
            }
        }

        return best;
    }

    private static boolean isFreeSpot(LocalPlayer player, Level level, @Nullable Level schematic, BlockPos spot, AABB body)
    {
        BlockPos above = spot.above();
        BlockPos below = spot.below();

        if (!level.getBlockState(spot).isAir() || !level.getBlockState(above).isAir() || new AABB(spot).intersects(body))
        {
            return false;
        }

        // Nicht dort hinstellen, wo die Schematic selbst etwas braucht
        if (schematic != null && (!schematic.getBlockState(spot).isAir() || !schematic.getBlockState(above).isAir()))
        {
            return false;
        }

        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) ||
            InteractiveBlocks.isInteractive(level.getBlockState(below).getBlock()))
        {
            return false;
        }

        return !RefillUtil.visiblePoints(player, level, schematic, below, Direction.UP).isEmpty();
    }

    // ---------------------------------------------------------------- Ablauf

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
            case FETCH ->
            {
                if (!this.fetchStarted)
                {
                    this.fetchStarted = true;

                    if (!this.inventory.requestSlot(player, this.shulkerSlot, this.wanted))
                    {
                        this.step = Step.DONE;
                    }

                    return false;
                }

                if (this.inventory.isBusy())
                {
                    this.inventory.tick(mc);
                    return false;
                }

                this.go(ShulkerContents.isShulker(player.getMainHandItem()) ? Step.AIM_PLACE : Step.DONE, 0);
            }
            case AIM_PLACE ->
            {
                BlockPos below = this.spot.below();
                List<BlockHitResult> points = RefillUtil.visiblePoints(player, level, schematic, below, Direction.UP);

                if (points.isEmpty() || !ShulkerContents.isShulker(player.getMainHandItem()) || this.stepTicks > AIM_TIMEOUT)
                {
                    this.go(Step.DONE, 0);
                    return false;
                }

                AimController.INSTANCE.aimAt(points.get(0).getLocation(), Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());

                if (AimController.INSTANCE.isOnTarget() && RefillUtil.crosshairOn(mc, below, Direction.UP))
                {
                    RefillUtil.rightClick(mc);
                    this.go(Step.WAIT_PLACED, 0);
                }
            }
            case WAIT_PLACED ->
            {
                if (level.getBlockState(this.spot).getBlock() instanceof ShulkerBoxBlock)
                {
                    this.placed = true;
                    this.everPlaced = true;
                    this.go(Step.AIM_OPEN, this.inventory.humanDelay());
                }
                else if (this.stepTicks > PLACE_TIMEOUT)
                {
                    this.go(Step.DONE, 0);
                }
            }
            case AIM_OPEN ->
            {
                if (this.aimAtSpot(mc, player, level, schematic) && this.stepTicks > 1)
                {
                    RefillUtil.rightClick(mc);
                    this.go(Step.WAIT_SCREEN, 0);
                }
                else if (this.stepTicks > AIM_TIMEOUT)
                {
                    this.go(Step.AIM_BREAK, 0);
                }
            }
            case WAIT_SCREEN ->
            {
                if (mc.screen instanceof AbstractContainerScreen<?> screen && screen.getMenu() instanceof ShulkerBoxMenu)
                {
                    this.ownScreen = screen;
                    this.go(Step.TAKE, this.inventory.humanDelay());
                }
                else if (this.stepTicks > SCREEN_TIMEOUT)
                {
                    this.go(Step.AIM_BREAK, 0);
                }
            }
            case TAKE ->
            {
                if (mc.screen != this.ownScreen || this.ownScreen == null)
                {
                    this.ownScreen = null;
                    this.go(Step.AIM_BREAK, this.inventory.humanDelay());
                    return false;
                }

                int slot = RefillUtil.nextSlotToTake(player.containerMenu,
                        stack -> this.wanted.contains(stack.getItem()) && RefillUtil.hasRoomFor(player, stack));

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
                this.go(Step.AIM_BREAK, this.inventory.humanDelay());
            }
            case AIM_BREAK ->
            {
                if (!(level.getBlockState(this.spot).getBlock() instanceof ShulkerBoxBlock))
                {
                    this.placed = false;
                    this.go(this.everPlaced ? Step.PICKUP : Step.DONE, 0);
                    return false;
                }

                selectPickaxe(player);

                if (this.aimAtSpot(mc, player, level, schematic) && mc.hitResult instanceof BlockHitResult bhr)
                {
                    this.breakFace = bhr.getDirection();
                    mc.gameMode.startDestroyBlock(this.spot, this.breakFace);
                    player.swing(InteractionHand.MAIN_HAND);
                    this.go(Step.BREAK, 0);
                }
            }
            case BREAK ->
            {
                if (!(level.getBlockState(this.spot).getBlock() instanceof ShulkerBoxBlock))
                {
                    this.placed = false;
                    this.go(Step.PICKUP, 0);
                    return false;
                }

                // Wie gedrueckt gehaltene Angriffstaste: weiter abbauen, solange das Fadenkreuz auf der Kiste ist
                if (RefillUtil.crosshairOn(mc, this.spot, null) && mc.hitResult instanceof BlockHitResult bhr)
                {
                    mc.gameMode.continueDestroyBlock(this.spot, bhr.getDirection());
                    player.swing(InteractionHand.MAIN_HAND);
                }
                else
                {
                    this.go(Step.AIM_BREAK, 0);
                }

                if (this.stepTicks > BREAK_TIMEOUT)
                {
                    this.go(Step.AIM_BREAK, 0);
                }
            }
            case PICKUP ->
            {
                if (ShulkerContents.shulkerSlots(player).size() >= this.shulkersBefore)
                {
                    this.go(Step.DONE, 0);
                }
                else if (this.stepTicks > PICKUP_TIMEOUT)
                {
                    // Die Kiste liegt noch am Boden: hingehen, wenn Baritone da ist
                    if (BaritoneBridge.isAvailable() && BaritoneBridge.goToAny(List.of(this.spot)))
                    {
                        this.go(Step.WALK_PICKUP, 0);
                    }
                    else
                    {
                        this.go(Step.DONE, 0);
                    }
                }
            }
            case WALK_PICKUP ->
            {
                if (ShulkerContents.shulkerSlots(player).size() >= this.shulkersBefore ||
                    (this.stepTicks > 10 && !BaritoneBridge.isBusy()) || this.stepTicks > 20 * 20)
                {
                    this.go(Step.DONE, 0);
                }
            }
            case DONE ->
            {
                AimController.INSTANCE.stop();
                return true;
            }
        }

        return false;
    }

    private boolean aimAtSpot(Minecraft mc, LocalPlayer player, Level level, @Nullable Level schematic)
    {
        List<BlockHitResult> points = RefillUtil.visiblePoints(player, level, schematic, this.spot, null);

        if (points.isEmpty())
        {
            return false;
        }

        AimController.INSTANCE.aimAt(points.get(0).getLocation(), Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());
        return AimController.INSTANCE.isOnTarget() && RefillUtil.crosshairOn(mc, this.spot, null);
    }

    private static void selectPickaxe(LocalPlayer player)
    {
        for (int i = 0; i < 9; i++)
        {
            if (player.getInventory().getItem(i).is(ItemTags.PICKAXES))
            {
                player.getInventory().setSelectedSlot(i);
                return;
            }
        }
    }

    private void go(Step next, int delay)
    {
        if (Ep2Configs.DEBUG.getBooleanValue() && next != this.step)
        {
            io.github.recher1001.easyplace2.EasyPlace2.LOGGER.info("[Printer] Shulker: {} -> {} (Platz {})", this.step, next, this.spot.toShortString());
        }

        this.step = next;
        this.wait = delay;
        this.stepTicks = 0;
    }

    /** Wie viele Stapel aus der Kiste genommen wurden. */
    public int took()
    {
        return this.took;
    }

    /** Steht die Kiste noch und muss beim naechsten Mal zuerst abgebaut werden? */
    public boolean isPlaced()
    {
        return this.placed;
    }

    @Override
    public boolean ownsScreen(@Nullable Screen screen)
    {
        // Auch die Kiste, die gerade per Rechtsklick geoeffnet wird (bevor WAIT_SCREEN sie uebernimmt)
        return screen != null && (screen == this.ownScreen || (this.step == Step.WAIT_SCREEN && isShulkerScreen(screen)));
    }

    private static boolean isShulkerScreen(Screen screen)
    {
        return screen instanceof AbstractContainerScreen<?> container && container.getMenu() instanceof ShulkerBoxMenu;
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
        this.inventory.cancel(mc);

        // Steht die Kiste schon, geht es beim naechsten Mal direkt mit dem Abbauen weiter
        this.go(this.placed ? Step.AIM_BREAK : Step.DONE, 0);
    }

    @Override
    public String detail()
    {
        return this.label;
    }
}
