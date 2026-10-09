package io.github.recher1001.easyplace2.placement;

import java.util.HashSet;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import com.google.common.collect.ImmutableSet;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import fi.dy.masa.litematica.util.PlacementHandler;

/**
 * Vergleicht den Zustand, den Vanilla beim Klick erzeugen wuerde, mit dem Zustand aus der Schematic.
 * <p>
 * Verglichen werden nur Eigenschaften, die beim Platzieren durch Klickposition, Klickflaeche oder
 * Blickrichtung festgelegt werden (Ausrichtung, obere/untere Haelfte usw.). Eigenschaften, die sich
 * durch Nachbarbloecke von selbst anpassen oder die man nachtraeglich per Rechtsklick einstellt
 * (Verbindungen, Wasser, Repeater-Verzoegerung, ...), werden ignoriert.
 */
public class StateMatcher
{
    /** Diese Eigenschaften kann man nach dem Platzieren noch selbst umstellen, oder sie passen sich von selbst an. */
    private static final Set<Property<?>> ADJUSTED_LATER = ImmutableSet.of(
            BlockStateProperties.OPEN,
            BlockStateProperties.INVERTED,
            BlockStateProperties.MODE_COMPARATOR,
            BlockStateProperties.DELAY,
            BlockStateProperties.NOTE,
            BlockStateProperties.BITES,
            BlockStateProperties.STAIRS_SHAPE
    );

    /** Mengen-Eigenschaften: mehrere Klicks auf dieselbe Stelle erhoehen die Anzahl um eins. */
    private static final Set<IntegerProperty> COUNTS = ImmutableSet.of(
            BlockStateProperties.CANDLES,
            BlockStateProperties.PICKLES,
            BlockStateProperties.EGGS,
            BlockStateProperties.LAYERS,
            BlockStateProperties.FLOWER_AMOUNT,
            BlockStateProperties.SEGMENT_AMOUNT
    );

    private static final Set<Property<?>> MUST_MATCH;

    static
    {
        // Litematicas Liste der "Platzierungs"-Eigenschaften wiederverwenden ...
        Set<Property<?>> set = new HashSet<>(PlacementHandler.WHITELISTED_PROPERTIES);
        set.removeAll(ADJUSTED_LATER);
        // ... ergaenzt um Eigenschaften, die ebenfalls von der Klickflaeche abhaengen
        set.add(BlockStateProperties.DOUBLE_BLOCK_HALF);
        set.add(BlockStateProperties.BED_PART);
        set.add(BlockStateProperties.HANGING);
        set.add(BlockStateProperties.VERTICAL_DIRECTION);
        MUST_MATCH = ImmutableSet.copyOf(set);
    }

    /**
     * @return null wenn der simulierte Zustand passt, sonst eine kurze Beschreibung des Unterschieds
     */
    @Nullable
    public static String findMismatch(BlockState simulated, BlockState target)
    {
        if (simulated.getBlock() != target.getBlock())
        {
            return blockName(simulated) + " statt " + blockName(target);
        }

        for (Property<?> property : target.getProperties())
        {
            if (COUNTS.contains(property))
            {
                IntegerProperty count = (IntegerProperty) property;

                if (simulated.getValue(count) > target.getValue(count))
                {
                    return describe(property, simulated, target);
                }
            }
            else if (MUST_MATCH.contains(property) && !simulated.getValue(property).equals(target.getValue(property)))
            {
                return describe(property, simulated, target);
            }
        }

        return null;
    }

    private static String describe(Property<?> property, BlockState simulated, BlockState target)
    {
        return property.getName() + "=" + valueName(simulated, property) + " statt " + valueName(target, property);
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property)
    {
        return property.getName(state.getValue(property));
    }

    private static String blockName(BlockState state)
    {
        return state.getBlock().getName().getString();
    }
}
