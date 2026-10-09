package io.github.recher1001.easyplace2.printer;

import java.util.Random;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import io.github.recher1001.easyplace2.config.Ep2Configs;

/**
 * Holt Items wie ein Spieler in die Hand, Schritt fuer Schritt mit menschlichen Pausen:
 * <ol>
 *     <li>liegt das Item in der Hotbar: nur den Hotbar-Platz wechseln (wie Mausrad / Zahlentaste)</li>
 *     <li>sonst: Inventar oeffnen, warten, Item per Zahlentaste in die Hotbar legen, warten,
 *         Inventar schliessen, warten, Hotbar-Platz waehlen</li>
 * </ol>
 * Es werden nur die normalen Pakete eines Spielers verschickt (Inventarklick, Schliessen, Slotwechsel).
 */
public final class InventoryManager
{
    private enum Step { IDLE, OPEN, SWAP, CLOSE, SELECT }

    private static final int HOTBAR_SIZE = Inventory.getSelectionSize();
    /** Hauptinventar ohne Hotbar (Index im Inventar = Index im InventoryMenu). */
    private static final int MAIN_START = 9;
    private static final int MAIN_END = 36;

    private final Random random = new Random();
    private Step step = Step.IDLE;
    private int wait;
    private int sourceSlot = -1;
    private int hotbarSlot = -1;
    @Nullable private Screen ownScreen;

    public boolean isBusy()
    {
        return this.step != Step.IDLE;
    }

    /** Ist das gerade offene Inventar vom Printer selbst geoeffnet worden? */
    public boolean ownsScreen(@Nullable Screen screen)
    {
        return screen != null && screen == this.ownScreen;
    }

    public static boolean isInHotbar(LocalPlayer player, Item item)
    {
        return findSlot(player.getInventory(), item, 0, HOTBAR_SIZE) >= 0;
    }

    public static int countItem(LocalPlayer player, Item item)
    {
        Inventory inv = player.getInventory();
        int count = 0;

        for (int i = 0; i < MAIN_END; i++)
        {
            ItemStack stack = inv.getItem(i);

            if (stack.is(item))
            {
                count += stack.getCount();
            }
        }

        ItemStack offhand = player.getOffhandItem();
        return offhand.is(item) ? count + offhand.getCount() : count;
    }

    /**
     * Startet das Holen eines Items.
     *
     * @param keep Items, die noch gebraucht werden und deshalb nicht aus der Hotbar verdraengt werden sollen
     * @return false, wenn das Item gar nicht im Inventar ist
     */
    public boolean request(LocalPlayer player, Item item, Set<Item> keep)
    {
        Inventory inv = player.getInventory();
        int hotbar = findSlot(inv, item, 0, HOTBAR_SIZE);

        if (hotbar >= 0)
        {
            this.hotbarSlot = hotbar;
            this.go(Step.SELECT, this.shortDelay());
            return true;
        }

        int source = findSlot(inv, item, MAIN_START, MAIN_END);

        if (source < 0)
        {
            return false;
        }

        this.sourceSlot = source;
        this.hotbarSlot = this.chooseHotbarSlot(inv, keep);
        this.go(Step.OPEN, this.shortDelay());
        return true;
    }

    public void cancel(Minecraft mc)
    {
        if (this.ownScreen != null && mc.screen == this.ownScreen && mc.player != null)
        {
            mc.player.closeContainer();
        }

        this.ownScreen = null;
        this.step = Step.IDLE;
    }

    public void tick(Minecraft mc)
    {
        LocalPlayer player = mc.player;

        if (this.step == Step.IDLE || player == null || mc.gameMode == null)
        {
            return;
        }

        if (--this.wait > 0)
        {
            return;
        }

        switch (this.step)
        {
            case OPEN ->
            {
                // Wie die Inventartaste: in Survival oeffnet der Client das eigene Inventar selbst
                InventoryScreen screen = new InventoryScreen(player);
                mc.setScreen(screen);
                this.ownScreen = screen;
                this.go(Step.SWAP, this.humanDelay());
            }
            case SWAP ->
            {
                if (mc.screen != this.ownScreen)
                {
                    // Spieler hat das Inventar selbst geschlossen oder etwas anderes geoeffnet
                    this.ownScreen = null;
                    this.step = Step.IDLE;
                    return;
                }

                // Maus ueber dem Item + Zahlentaste: tauscht es mit dem Hotbar-Platz
                mc.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, this.sourceSlot,
                        this.hotbarSlot, ClickType.SWAP, player);
                this.go(Step.CLOSE, this.humanDelay());
            }
            case CLOSE ->
            {
                if (mc.screen == this.ownScreen)
                {
                    player.closeContainer();
                }

                this.ownScreen = null;
                this.go(Step.SELECT, this.humanDelay());
            }
            case SELECT ->
            {
                player.getInventory().setSelectedSlot(this.hotbarSlot);
                this.step = Step.IDLE;
            }
            default -> this.step = Step.IDLE;
        }
    }

    private int chooseHotbarSlot(Inventory inv, Set<Item> keep)
    {
        // 1. leerer Platz, 2. Platz mit einem Item, das die Schematic nicht braucht, 3. der aktuelle Platz
        for (int i = 0; i < HOTBAR_SIZE; i++)
        {
            if (inv.getItem(i).isEmpty())
            {
                return i;
            }
        }

        int selected = inv.getSelectedSlot();

        for (int n = 1; n <= HOTBAR_SIZE; n++)
        {
            int i = (selected + n) % HOTBAR_SIZE;

            if (!keep.contains(inv.getItem(i).getItem()))
            {
                return i;
            }
        }

        return selected;
    }

    private static int findSlot(Inventory inv, Item item, int from, int to)
    {
        for (int i = from; i < to; i++)
        {
            if (inv.getItem(i).is(item))
            {
                return i;
            }
        }

        return -1;
    }

    private void go(Step next, int delay)
    {
        this.step = next;
        this.wait = delay;
    }

    private int humanDelay()
    {
        int min = Ep2Configs.PRINTER_INV_DELAY_MIN.getIntegerValue();
        int max = Math.max(min, Ep2Configs.PRINTER_INV_DELAY_MAX.getIntegerValue());
        return min + this.random.nextInt(max - min + 1);
    }

    private int shortDelay()
    {
        return 2 + this.random.nextInt(3);
    }
}
