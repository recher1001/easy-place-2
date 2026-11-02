package io.github.recher1001.easyplace2.printer.lanes;

import java.util.Locale;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import io.github.recher1001.easyplace2.config.Ep2Configs;

/**
 * Die Star-Spitzhacke (ein Server-Item, das 3x3 abbaut): die Netherite-Spitzhacke in der Hotbar (oder, falls der Name
 * lesbar ist, das Item mit dem eingestellten Namen).
 */
public final class StarTool
{
    private StarTool()
    {
    }

    /** Das Hotbar-Fach der Star-Spitzhacke, wenn der Star-Tool-Modus an ist, sonst -1. */
    public static int slotForMining(LocalPlayer player)
    {
        return Ep2Configs.PRINTER_STAR_TOOL.getBooleanValue() ? findSlot(player) : -1;
    }

    /**
     * Das Hotbar-Fach eines Items mit dem eingestellten Namen (egal ob der Modus an ist). Damit wird die
     * Star-Spitzhacke beim normalen Abbauen gemieden, sonst wuerde sie ungewollt 3x3 abbauen.
     */
    public static int findSlot(LocalPlayer player)
    {
        String name = Ep2Configs.PRINTER_STAR_TOOL_NAME.getStringValue().trim().toLowerCase(Locale.ROOT);
        Inventory inv = player.getInventory();

        // Zuerst nach dem Namen (falls lesbar) ...
        if (!name.isEmpty())
        {
            for (int i = 0; i < Inventory.getSelectionSize(); i++)
            {
                if (isStarTool(inv.getItem(i), name))
                {
                    return i;
                }
            }
        }

        // ... sonst ist die Star-Spitzhacke die Netherite-Spitzhacke (der Name ist oft nicht lesbar, z. B. eigene Schrift)
        for (int i = 0; i < Inventory.getSelectionSize(); i++)
        {
            if (inv.getItem(i).is(Items.NETHERITE_PICKAXE))
            {
                return i;
            }
        }

        return -1;
    }

    public static boolean isStarTool(ItemStack stack)
    {
        String name = Ep2Configs.PRINTER_STAR_TOOL_NAME.getStringValue().trim().toLowerCase(Locale.ROOT);
        return stack.is(Items.NETHERITE_PICKAXE) || (!name.isEmpty() && isStarTool(stack, name));
    }

    private static boolean isStarTool(ItemStack stack, String name)
    {
        return !stack.isEmpty() && stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(name);
    }
}
