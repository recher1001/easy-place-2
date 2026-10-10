package io.github.recher1001.easyplace2.printer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * Liest den Inhalt von Shulker-Kisten als Items (so wie der Tooltip ihn zeigt).
 */
public final class ShulkerContents
{
    /** Hauptinventar inklusive Hotbar. */
    public static final int INVENTORY_SLOTS = 36;

    private ShulkerContents()
    {
    }

    public static boolean isShulker(ItemStack stack)
    {
        return !stack.isEmpty() && isShulkerItem(stack.getItem());
    }

    public static boolean isShulkerItem(Item item)
    {
        return Block.byItem(item) instanceof ShulkerBoxBlock;
    }

    public static List<ItemStack> contents(ItemStack stack)
    {
        List<ItemStack> list = new ArrayList<>();
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);

        if (contents != null)
        {
            for (ItemStack inner : contents.nonEmptyItems())
            {
                list.add(inner);
            }
        }

        return list;
    }

    public static int count(ItemStack shulker, Item item)
    {
        int count = 0;

        for (ItemStack inner : contents(shulker))
        {
            if (inner.is(item))
            {
                count += inner.getCount();
            }
        }

        return count;
    }

    /** Wie viele Items dieser Art in allen Shulkern im Inventar liegen. */
    public static int countInInventoryShulkers(LocalPlayer player, Item item)
    {
        Inventory inv = player.getInventory();
        int count = 0;

        for (int i = 0; i < INVENTORY_SLOTS; i++)
        {
            ItemStack stack = inv.getItem(i);

            if (isShulker(stack))
            {
                count += count(stack, item);
            }
        }

        return count;
    }

    /** Zusammengefasster Inhalt einer Shulker (Item → Anzahl), groesste Menge zuerst. */
    public static Map<Item, Integer> summary(ItemStack shulker)
    {
        Map<Item, Integer> map = new LinkedHashMap<>();

        for (ItemStack inner : contents(shulker))
        {
            map.merge(inner.getItem(), inner.getCount(), Integer::sum);
        }

        List<Map.Entry<Item, Integer>> entries = new ArrayList<>(map.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<Item, Integer> sorted = new LinkedHashMap<>();
        entries.forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /** Slots im Inventar, in denen eine Shulker liegt. */
    public static List<Integer> shulkerSlots(LocalPlayer player)
    {
        List<Integer> slots = new ArrayList<>();
        Inventory inv = player.getInventory();

        for (int i = 0; i < INVENTORY_SLOTS; i++)
        {
            if (isShulker(inv.getItem(i)))
            {
                slots.add(i);
            }
        }

        return slots;
    }

    /** Freie Plaetze im Hauptinventar (fuer das, was aus Kisten geholt wird). */
    public static int freeSlots(LocalPlayer player)
    {
        Inventory inv = player.getInventory();
        int free = 0;

        for (int i = 0; i < INVENTORY_SLOTS; i++)
        {
            if (inv.getItem(i).isEmpty())
            {
                free++;
            }
        }

        return free;
    }
}
