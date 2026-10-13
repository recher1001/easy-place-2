package io.github.recher1001.easyplace2.printer.refill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.mixin.MinecraftAccessor;
import io.github.recher1001.easyplace2.placement.EasyPlace2Handler;
import io.github.recher1001.easyplace2.placement.GhostTrace;
import io.github.recher1001.easyplace2.printer.ShulkerContents;

/**
 * Gemeinsame Hilfen fuer das Holen von Material: sichtbare Klickpunkte auf einem Block finden,
 * normal rechtsklicken und Items aus einem offenen Container per Shift-Klick nehmen.
 */
public final class RefillUtil
{
    private static final double[] OFFSETS = { 0.5, 0.25, 0.75 };

    private RefillUtil()
    {
    }

    public static double maxDistance(LocalPlayer player)
    {
        return player.blockInteractionRange() - Ep2Configs.PRINTER_REACH_MARGIN.getDoubleValue();
    }

    /**
     * Punkte auf den Flaechen von {@code pos}, die der Spieler gerade sehen und erreichen kann.
     * Liegt ein Geisterblock der Schematic davor, zaehlt der Punkt nicht, denn sonst wuerde
     * Easy Place 2.0 den Rechtsklick uebernehmen.
     */
    public static List<BlockHitResult> visiblePoints(LocalPlayer player, Level level, @Nullable Level schematic, BlockPos pos,
                                                     @Nullable Direction onlyFace)
    {
        List<BlockHitResult> list = new ArrayList<>();
        Vec3 eye = player.getEyePosition();
        double max = maxDistance(player);
        // Echte Form des Blocks (eine Truhe ist schmaler und niedriger als ein ganzer Block)
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        AABB box = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();

        for (Direction dir : Direction.values())
        {
            if (onlyFace != null && dir != onlyFace)
            {
                continue;
            }

            for (double u : OFFSETS)
            {
                for (double v : OFFSETS)
                {
                    Vec3 point = facePoint(pos, box, dir, u, v);

                    if (point.distanceTo(eye) > max)
                    {
                        continue;
                    }

                    Vec3 beyond = eye.add(point.subtract(eye).scale(1.002));
                    BlockHitResult hit = level.clip(new ClipContext(eye, beyond, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));

                    if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos) || hit.getDirection() != dir)
                    {
                        continue;
                    }

                    if (schematic != null && ghostInFront(player, schematic, eye, hit.getLocation(), pos))
                    {
                        continue;
                    }

                    list.add(hit);
                }
            }
        }

        list.sort(Comparator.comparingDouble(h -> h.getLocation().distanceToSqr(eye)));
        return list;
    }

    /**
     * Waere der Block von dieser Stehposition aus sichtbar? Die Position wird nur kurz im Speicher
     * gesetzt und sofort zurueckgesetzt (kein Paket).
     */
    public static boolean visibleFrom(LocalPlayer player, BlockPos feet, Level level, @Nullable Level schematic, BlockPos pos)
    {
        Vec3 old = player.position();

        try
        {
            player.setPosRaw(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
            return !visiblePoints(player, level, schematic, pos, null).isEmpty();
        }
        finally
        {
            player.setPosRaw(old.x, old.y, old.z);
        }
    }

    /** Liegt auf dem Weg vom Auge zum Punkt ein Geisterblock (der nicht der Zielblock selbst ist)? */
    public static boolean ghostInFront(LocalPlayer player, Level schematic, Vec3 eye, Vec3 point, BlockPos target)
    {
        Vec3 beyond = eye.add(point.subtract(eye).scale(1.002));
        // Bloecke, in denen der Spieler selbst steht, zaehlen nicht (die sieht Easy Place 2.0 auch nicht)
        BlockHitResult ghost = GhostTrace.firstGhost(schematic, player, eye, beyond);
        return ghost != null && !ghost.getBlockPos().equals(target);
    }

    private static Vec3 facePoint(BlockPos pos, AABB box, Direction dir, double u, double v)
    {
        double x0 = pos.getX() + box.minX, y0 = pos.getY() + box.minY, z0 = pos.getZ() + box.minZ;
        double dx = box.maxX - box.minX, dy = box.maxY - box.minY, dz = box.maxZ - box.minZ;

        return switch (dir.getAxis())
        {
            case X -> new Vec3(dir == Direction.EAST ? x0 + dx : x0, y0 + v * dy, z0 + u * dz);
            case Y -> new Vec3(x0 + u * dx, dir == Direction.UP ? y0 + dy : y0, z0 + v * dz);
            case Z -> new Vec3(x0 + u * dx, y0 + v * dy, dir == Direction.SOUTH ? z0 + dz : z0);
        };
    }

    /** Zeigt das echte Fadenkreuz gerade auf diesen Block (und, falls angegeben, diese Seite)? */
    public static boolean crosshairOn(Minecraft mc, BlockPos pos, @Nullable Direction face)
    {
        return mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK &&
               bhr.getBlockPos().equals(pos) && (face == null || bhr.getDirection() == face);
    }

    /** Ein ganz normaler Rechtsklick, wie mit der Maus. */
    public static void rightClick(Minecraft mc)
    {
        // Eigene Klicks des Printers (Shulker abstellen, Kiste oeffnen) sperrt Easy Place 2.0 nicht
        EasyPlace2Handler.runUnrestricted(((MinecraftAccessor) mc)::easyplace2$startUseItem);
    }

    /** Anzahl Slots, die zum Container gehoeren (der Rest des Menues ist das Spielerinventar). */
    public static int containerSlots(AbstractContainerMenu menu)
    {
        return Math.max(0, menu.slots.size() - ShulkerContents.INVENTORY_SLOTS);
    }

    /**
     * Sucht im offenen Container den naechsten Slot, der genommen werden soll.
     *
     * @return Menue-Slotnummer oder -1
     */
    public static int nextSlotToTake(AbstractContainerMenu menu, Predicate<ItemStack> wanted)
    {
        int size = containerSlots(menu);

        for (int i = 0; i < size; i++)
        {
            Slot slot = menu.slots.get(i);

            if (slot.hasItem() && wanted.test(slot.getItem()))
            {
                return i;
            }
        }

        return -1;
    }

    /** Shift-Klick auf einen Slot: legt den Stapel ins eigene Inventar, wie bei einem Spieler. */
    public static void quickMove(Minecraft mc, int menuSlot)
    {
        LocalPlayer player = mc.player;

        if (player != null && mc.gameMode != null)
        {
            mc.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, menuSlot, 0, ClickType.QUICK_MOVE, player);
        }
    }

    /** Wird ein Stapel dieses Items noch ins Inventar passen? */
    public static boolean hasRoomFor(LocalPlayer player, ItemStack stack)
    {
        if (ShulkerContents.freeSlots(player) > 0)
        {
            return true;
        }

        for (int i = 0; i < ShulkerContents.INVENTORY_SLOTS; i++)
        {
            ItemStack own = player.getInventory().getItem(i);

            if (ItemStack.isSameItemSameComponents(own, stack) && own.getCount() < own.getMaxStackSize())
            {
                return true;
            }
        }

        return false;
    }

    /** Ist das Item oder eine Shulker mit diesem Item gewuenscht? */
    public static boolean isWanted(ItemStack stack, Set<Item> wanted, boolean includeShulkers)
    {
        if (wanted.contains(stack.getItem()))
        {
            return true;
        }

        if (includeShulkers && ShulkerContents.isShulker(stack))
        {
            for (ItemStack inner : ShulkerContents.contents(stack))
            {
                if (wanted.contains(inner.getItem()))
                {
                    return true;
                }
            }
        }

        return false;
    }
}
