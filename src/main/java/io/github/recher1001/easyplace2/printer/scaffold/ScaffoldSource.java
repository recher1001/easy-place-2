package io.github.recher1001.easyplace2.printer.scaffold;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import io.github.recher1001.easyplace2.placement.StateMatcher;
import io.github.recher1001.easyplace2.printer.BlockDigger;

/** Fuer den {@link BlockDigger}: das Geruest, das Baritone fuer den Printer gesetzt hat. */
public final class ScaffoldSource implements BlockDigger.Source
{
    @Override
    public Iterable<BlockPos> candidates(Level level, Level schematic)
    {
        ScaffoldTracker tracker = ScaffoldTracker.INSTANCE;
        tracker.forgetGone(level);
        List<BlockPos> nowPartOfBuild = new ArrayList<>();

        for (BlockPos p : tracker.positions())
        {
            if (level.hasChunkAt(p) && isPartOfBuild(level, schematic, p))
            {
                nowPartOfBuild.add(p);
            }
        }

        // Wollte die Schematic genau diesen Block hier haben, ist es kein Geruest mehr
        nowPartOfBuild.forEach(tracker::remove);
        return List.copyOf(tracker.positions());
    }

    @Override
    public boolean stillWanted(Level level, Level schematic, BlockPos pos)
    {
        return ScaffoldTracker.INSTANCE.isStillScaffold(level.getBlockState(pos)) && !isPartOfBuild(level, schematic, pos);
    }

    @Override
    public void done(BlockPos pos)
    {
        ScaffoldTracker.INSTANCE.remove(pos);
    }

    private static boolean isPartOfBuild(Level level, Level schematic, BlockPos pos)
    {
        BlockState wanted = schematic.getBlockState(pos);
        return !wanted.isAir() && StateMatcher.isComplete(level.getBlockState(pos), wanted);
    }
}
