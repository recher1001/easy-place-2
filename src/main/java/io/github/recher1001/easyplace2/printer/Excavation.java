package io.github.recher1001.easyplace2.printer;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.placement.StateMatcher;

/**
 * Aushoehlen: Bloecke, die im Bereich der Schematic stehen, wo laut Schematic Luft sein soll (z. B. wenn
 * in einen Berg gebaut wird). Auf Wunsch auch falsche Bloecke, die danach durch den richtigen ersetzt werden.
 * <p>
 * Nie angefasst werden: Bloecke mit Inhalt oder Daten (Kisten, Schilder, Spawner ...), unzerstoerbare
 * Bloecke, Fluessigkeiten und Bloecke direkt neben Wasser oder Lava (damit nichts ueberflutet wird).
 */
public final class Excavation implements BlockDigger.Source
{
    private List<BlockPos> targets = List.of();

    /** Die aktuelle Liste aus dem letzten Scan. */
    public void update(List<BlockPos> targets)
    {
        this.targets = targets;
    }

    @Override
    public Iterable<BlockPos> candidates(Level level, Level schematic)
    {
        return this.targets;
    }

    @Override
    public boolean stillWanted(Level level, Level schematic, BlockPos pos)
    {
        BlockState wanted = schematic.getBlockState(pos);
        BlockState current = level.getBlockState(pos);

        if (wanted.isAir())
        {
            return Ep2Configs.PRINTER_EXCAVATE.getBooleanValue() && canDig(level, pos, current);
        }

        return Ep2Configs.PRINTER_REPLACE_WRONG.getBooleanValue() && isWrong(current, wanted) && canDig(level, pos, current);
    }

    @Override
    public void done(BlockPos pos)
    {
        if (this.targets.contains(pos))
        {
            List<BlockPos> copy = new ArrayList<>(this.targets);
            copy.remove(pos);
            this.targets = copy;
        }
    }

    /** Steht dort ein anderer fester Block als der, den die Schematic will? */
    public static boolean isWrong(BlockState current, BlockState wanted)
    {
        return !current.canBeReplaced() && current.getBlock() != wanted.getBlock() && !StateMatcher.isComplete(current, wanted);
    }

    /** Darf dieser Block gefahrlos abgebaut werden? */
    public static boolean canDig(Level level, BlockPos pos, BlockState state)
    {
        // Auch keine Bloecke mit Wasser darin (sonst bleibt eine Wasserquelle stehen)
        if (state.isAir() || state.getBlock() instanceof LiquidBlock || state.hasBlockEntity() ||
            !state.getFluidState().isEmpty() || state.getDestroySpeed(level, pos) < 0)
        {
            return false;
        }

        // Daneben Wasser oder Lava: lieber stehen lassen, sonst laeuft es nach
        for (Direction dir : Direction.values())
        {
            if (dir != Direction.DOWN && !level.getFluidState(pos.relative(dir)).isEmpty())
            {
                return false;
            }
        }

        return true;
    }
}
