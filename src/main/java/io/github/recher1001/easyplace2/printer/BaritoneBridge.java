package io.github.recher1001.easyplace2.printer;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;

import io.github.recher1001.easyplace2.EasyPlace2;

/**
 * Duenne Bruecke zu Baritone. Baritone wird nur zum Laufen benutzt, nie zum Bauen oder Abbauen.
 * <p>
 * Die Aufrufe gehen per Reflection ueber die stabile {@code baritone.api}-Schnittstelle. So braucht
 * Easy Place 2.0 Baritone nicht zum Kompilieren und laeuft mit jeder Baritone-Version fuer 1.21.11
 * (eigenstaendig oder z. B. aus Meteor). Ohne Baritone baut der Printer nur, was in Reichweite ist.
 */
public final class BaritoneBridge
{
    /** Baritone-Einstellungen, die waehrend des Druckens fest gesetzt werden (danach wieder zurueck). */
    private static final String[] FORCED_SETTINGS = { "allowBreak", "allowPlace", "freeLook", "allowSprint" };

    private static Boolean available;
    private static final Map<String, Object> savedSettings = new HashMap<>();

    private BaritoneBridge()
    {
    }

    public static boolean isAvailable()
    {
        if (available == null)
        {
            try
            {
                Class.forName("baritone.api.BaritoneAPI");
                available = true;
            }
            catch (Throwable t)
            {
                available = false;
            }
        }

        return available;
    }

    /**
     * Laeuft zu einer der angegebenen Stehpositionen (Baritone nimmt die guenstigste).
     */
    public static boolean goToAny(List<BlockPos> standPositions)
    {
        if (!isAvailable() || standPositions.isEmpty())
        {
            return false;
        }

        try
        {
            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
            Class<?> goalBlock = Class.forName("baritone.api.pathing.goals.GoalBlock");
            Object goals = Array.newInstance(goalClass, standPositions.size());

            for (int i = 0; i < standPositions.size(); i++)
            {
                BlockPos p = standPositions.get(i);
                Array.set(goals, i, goalBlock.getConstructor(int.class, int.class, int.class).newInstance(p.getX(), p.getY(), p.getZ()));
            }

            Object goal = Class.forName("baritone.api.pathing.goals.GoalComposite")
                    .getConstructor(goals.getClass()).newInstance(goals);
            Object process = call(baritone(), "getCustomGoalProcess");
            process.getClass().getMethod("setGoalAndPath", goalClass).invoke(process, goal);
            return true;
        }
        catch (Throwable t)
        {
            EasyPlace2.LOGGER.warn("Baritone-Ziel konnte nicht gesetzt werden", t);
            return false;
        }
    }

    /** Laeuft in die Naehe einer X/Z-Position, z. B. zu einem Teil der Schematic, der noch nicht geladen ist. */
    public static boolean goToXZ(int x, int z)
    {
        if (!isAvailable())
        {
            return false;
        }

        try
        {
            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
            Object goal = Class.forName("baritone.api.pathing.goals.GoalXZ").getConstructor(int.class, int.class).newInstance(x, z);
            Object process = call(baritone(), "getCustomGoalProcess");
            process.getClass().getMethod("setGoalAndPath", goalClass).invoke(process, goal);
            return true;
        }
        catch (Throwable t)
        {
            EasyPlace2.LOGGER.warn("Baritone-Ziel konnte nicht gesetzt werden", t);
            return false;
        }
    }

    /** true solange Baritone noch einen Weg sucht oder laeuft. */
    public static boolean isBusy()
    {
        if (!isAvailable())
        {
            return false;
        }

        try
        {
            Object baritone = baritone();
            boolean pathing = (Boolean) call(call(baritone, "getPathingBehavior"), "isPathing");
            boolean active = (Boolean) call(call(baritone, "getCustomGoalProcess"), "isActive");
            return pathing || active;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    public static void cancel()
    {
        if (!isAvailable())
        {
            return;
        }

        try
        {
            call(call(baritone(), "getPathingBehavior"), "cancelEverything");
        }
        catch (Throwable t)
        {
            EasyPlace2.LOGGER.warn("Baritone konnte nicht gestoppt werden", t);
        }
    }

    /**
     * Baritone darf nichts abbauen oder setzen, und die Kamera dreht sich sichtbar mit (kein freeLook).
     */
    public static void applySettings(boolean sprint)
    {
        if (!isAvailable())
        {
            return;
        }

        Map<String, Boolean> wanted = Map.of("allowBreak", false, "allowPlace", false, "freeLook", false, "allowSprint", sprint);

        for (String name : FORCED_SETTINGS)
        {
            Object setting = setting(name);

            if (setting != null)
            {
                try
                {
                    Field value = setting.getClass().getField("value");
                    savedSettings.putIfAbsent(name, value.get(setting));
                    value.set(setting, wanted.get(name));
                }
                catch (Throwable t)
                {
                    EasyPlace2.LOGGER.warn("Baritone-Einstellung '{}' nicht gesetzt", name, t);
                }
            }
        }
    }

    public static void restoreSettings()
    {
        for (Map.Entry<String, Object> entry : savedSettings.entrySet())
        {
            Object setting = setting(entry.getKey());

            if (setting != null)
            {
                try
                {
                    setting.getClass().getField("value").set(setting, entry.getValue());
                }
                catch (Throwable ignored)
                {
                }
            }
        }

        savedSettings.clear();
    }

    @Nullable
    private static Object setting(String name)
    {
        try
        {
            Object settings = Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
            return settings.getClass().getField(name).get(settings);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private static Object baritone() throws Exception
    {
        Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
        return call(provider, "getPrimaryBaritone");
    }

    private static Object call(Object target, String method) throws Exception
    {
        // Ueber die oeffentliche API-Schnittstelle aufrufen, die Implementierungsklassen sind obfuskiert
        for (Class<?> iface : allInterfaces(target.getClass()))
        {
            try
            {
                return iface.getMethod(method).invoke(target);
            }
            catch (NoSuchMethodException ignored)
            {
            }
        }

        return target.getClass().getMethod(method).invoke(target);
    }

    private static List<Class<?>> allInterfaces(Class<?> clazz)
    {
        java.util.ArrayList<Class<?>> list = new java.util.ArrayList<>();

        for (Class<?> c = clazz; c != null; c = c.getSuperclass())
        {
            for (Class<?> i : c.getInterfaces())
            {
                collect(i, list);
            }
        }

        return list;
    }

    private static void collect(Class<?> iface, List<Class<?>> list)
    {
        if (!list.contains(iface))
        {
            list.add(iface);

            for (Class<?> parent : iface.getInterfaces())
            {
                collect(parent, list);
            }
        }
    }
}
