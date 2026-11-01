package io.github.recher1001.easyplace2.printer;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import com.google.common.collect.ImmutableSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

import io.github.recher1001.easyplace2.config.Ep2Configs;

/**
 * Falsch ausgerichtete Bloecke: der richtige Block steht da, aber z. B. in die falsche Richtung (Repeater
 * verkehrt herum, Treppe oben statt unten). Er wird abgebaut und danach ganz normal richtig neu gesetzt.
 * <p>
 * Nur die Ausrichtung zaehlt. Was sich von selbst aendert (an/aus, mit Strom, verbunden ...) wird nie angefasst.
 * Das hat keine Eile: der Printer macht es erst, wenn er in der Naehe gerade nichts zu setzen hat, und den Rest
 * am Ende. Ein Block muss ausserdem eine Weile falsch sein (der Server korrigiert manchmal kurz danach).
 */
public final class Reorient implements BlockDigger.Source
{
    /** Diese Eigenschaften legt man nur beim Setzen fest (Richtung, oben/unten, Achse ...). */
    private static final Set<Property<?>> ORIENTATION = ImmutableSet.of(
            BlockStateProperties.FACING,
            BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.VERTICAL_DIRECTION,
            BlockStateProperties.AXIS,
            BlockStateProperties.HORIZONTAL_AXIS,
            BlockStateProperties.HALF,
            BlockStateProperties.SLAB_TYPE,
            BlockStateProperties.ATTACH_FACE,
            BlockStateProperties.ROTATION_16,
            BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.ORIENTATION,
            BlockStateProperties.HANGING
    );

    /** So lange (Ticks) muss ein Block falsch sein, bevor er abgebaut wird. */
    private static final int WAIT_TICKS = 40;
    /** Pro Stelle hoechstens so oft abbauen (falls er sich immer wieder falsch dreht). */
    private static final int MAX_BREAKS = 2;

    /** Bloecke mit Inhalt (Trichter, Spender ...) nur, wenn der Printer sie selbst gesetzt hat. */
    private static final Set<BlockPos> PLACED_BY_PRINTER = new HashSet<>();

    private List<BlockPos> targets = List.of();
    private final Map<BlockPos, Long> firstSeen = new HashMap<>();
    private final Map<BlockPos, Integer> breaks = new HashMap<>();
    private final ArrayDeque<BlockPos> broken = new ArrayDeque<>();
    private long now;

    public void reset()
    {
        this.targets = List.of();
        this.firstSeen.clear();
        this.breaks.clear();
        this.broken.clear();
        PLACED_BY_PRINTER.clear();
    }

    /** Die aktuelle Liste aus dem letzten Scan. */
    public void update(List<BlockPos> list, long now)
    {
        this.now = now;

        if (list != this.targets)
        {
            this.targets = list;
            Set<BlockPos> set = new HashSet<>(list);
            this.firstSeen.keySet().removeIf(p -> !set.contains(p));

            for (BlockPos p : list)
            {
                this.firstSeen.putIfAbsent(p, now);
            }
        }
    }

    public static void placedByPrinter(BlockPos pos)
    {
        PLACED_BY_PRINTER.add(pos.immutable());
    }

    /** Zuletzt abgebaute Stelle (dort setzt der Printer gleich den richtigen Block), oder null. */
    @Nullable
    public BlockPos pollBroken()
    {
        return this.broken.poll();
    }

    @Override
    public Iterable<BlockPos> candidates(Level level, Level schematic)
    {
        return this.targets;
    }

    @Override
    public boolean stillWanted(Level level, Level schematic, BlockPos pos)
    {
        Long seen = this.firstSeen.get(pos);
        return Ep2Configs.PRINTER_FIX_ROTATION.getBooleanValue() && seen != null && this.now - seen >= WAIT_TICKS &&
               this.breaks.getOrDefault(pos, 0) < MAX_BREAKS &&
               needsFix(level.getBlockState(pos), schematic.getBlockState(pos)) && canBreak(level, pos, level.getBlockState(pos));
    }

    @Override
    public void done(BlockPos pos)
    {
        // Nur zaehlen, was wirklich abgebaut wurde (sonst ist die Stelle einfach nicht mehr falsch)
    }

    /** Vom Controller: diese Stelle wurde abgebaut. */
    public void brokeAt(BlockPos pos)
    {
        this.breaks.merge(pos.immutable(), 1, Integer::sum);
        this.firstSeen.remove(pos);
        this.broken.add(pos.immutable());
    }

    /** Wie viele Stellen gerade noch falsch ausgerichtet sind. */
    public int remaining(Level level, Level schematic)
    {
        int n = 0;

        for (BlockPos p : this.targets)
        {
            if (level.hasChunkAt(p) && needsFix(level.getBlockState(p), schematic.getBlockState(p)))
            {
                n++;
            }
        }

        return n;
    }

    /** Gibt es Stellen, die noch falsch sind, aber erst gleich dran sind (Wartezeit laeuft noch)? */
    public boolean hasWaiting(Level level, Level schematic)
    {
        if (!Ep2Configs.PRINTER_FIX_ROTATION.getBooleanValue())
        {
            return false;
        }

        for (BlockPos p : this.targets)
        {
            Long seen = this.firstSeen.get(p);

            if (seen != null && this.now - seen < WAIT_TICKS && this.breaks.getOrDefault(p, 0) < MAX_BREAKS && level.hasChunkAt(p) &&
                needsFix(level.getBlockState(p), schematic.getBlockState(p)) && canBreak(level, p, level.getBlockState(p)))
            {
                return true;
            }
        }

        return false;
    }

    /** Derselbe Block, aber falsch ausgerichtet (und das laesst sich nicht durch weiteres Setzen beheben)? */
    public static boolean needsFix(BlockState current, BlockState wanted)
    {
        if (current.getBlock() != wanted.getBlock() || wanted.hasProperty(BlockStateProperties.BED_PART))
        {
            return false;
        }

        for (Property<?> property : ORIENTATION)
        {
            if (!wanted.hasProperty(property) || !current.hasProperty(property) ||
                current.getValue(property).equals(wanted.getValue(property)))
            {
                continue;
            }

            // Zauntore drehen sich beim Oeffnen zum Spieler hin: dieselbe Achse ist richtig
            if (property == BlockStateProperties.HORIZONTAL_FACING && wanted.getBlock() instanceof FenceGateBlock &&
                current.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxis() == wanted.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxis())
            {
                continue;
            }

            // Halbe Stufe, die doppelt werden soll: einfach die zweite Haelfte dazu
            if (property == BlockStateProperties.SLAB_TYPE && wanted.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE)
            {
                continue;
            }

            return true;
        }

        return false;
    }

    /** Darf dieser Block zum Neu-Setzen abgebaut werden? */
    public static boolean canBreak(Level level, BlockPos pos, BlockState state)
    {
        boolean entityOk = !state.hasBlockEntity() || state.getBlock() instanceof ComparatorBlock || PLACED_BY_PRINTER.contains(pos);
        return entityOk && Excavation.canDig(level, pos, state, true);
    }
}
