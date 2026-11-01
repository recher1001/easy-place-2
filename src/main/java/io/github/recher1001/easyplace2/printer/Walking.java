package io.github.recher1001.easyplace2.printer;

import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.printer.path.PathWalker;

/**
 * Laufen fuer den Printer. Standard ist das eigene Laufen ({@link PathWalker}: eigene Wegsuche, laeuft mit den
 * normalen Tasten), ganz ohne Baritone. Nur wenn im Menue "Mit Baritone laufen" an ist und Baritone da ist,
 * geht es ueber {@link BaritoneBridge}.
 */
public final class Walking
{
    private Walking()
    {
    }

    public static void register()
    {
        ClientTickEvents.END_CLIENT_TICK.register(PathWalker.INSTANCE::tick);
    }

    /** Wird Baritone zum Laufen benutzt (statt dem eigenen Laufen)? */
    public static boolean usesBaritone()
    {
        return Ep2Configs.PRINTER_WALK_BARITONE.getBooleanValue() && BaritoneBridge.isAvailable();
    }

    /** Laufen geht immer (eigenes Laufen braucht nichts weiter). */
    public static boolean isAvailable()
    {
        return true;
    }

    public static boolean goToAny(List<BlockPos> spots)
    {
        return usesBaritone() ? BaritoneBridge.goToAny(spots) : PathWalker.INSTANCE.goToAny(spots);
    }

    public static boolean goNear(BlockPos pos, int range)
    {
        return usesBaritone() ? BaritoneBridge.goNear(pos, range) : PathWalker.INSTANCE.goNear(pos, range);
    }

    public static boolean goToXZ(int x, int z)
    {
        return usesBaritone() ? BaritoneBridge.goToXZ(x, z) : PathWalker.INSTANCE.goToXZ(x, z);
    }

    public static boolean isBusy()
    {
        return PathWalker.INSTANCE.isBusy() || (usesBaritone() && BaritoneBridge.isBusy());
    }

    public static void cancel()
    {
        PathWalker.INSTANCE.cancel();

        if (BaritoneBridge.isAvailable())
        {
            BaritoneBridge.cancel();
        }
    }

    /** Nur mit Baritone: darf es beim Laufen Geruest setzen? (Das eigene Laufen setzt nie etwas.) */
    public static void setScaffold(boolean allowed, List<Item> items)
    {
        if (usesBaritone())
        {
            BaritoneBridge.setScaffold(allowed, items);
        }
    }

    public static void applySettings(boolean sprint)
    {
        if (usesBaritone())
        {
            BaritoneBridge.applySettings(sprint);
        }
    }

    public static void restoreSettings()
    {
        BaritoneBridge.restoreSettings();
    }
}
