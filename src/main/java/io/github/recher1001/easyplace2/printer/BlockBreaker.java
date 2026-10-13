package io.github.recher1001.easyplace2.printer;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.printer.refill.RefillUtil;

/**
 * Baut einen Block ab wie ein Spieler: bestes Werkzeug aus der Hotbar waehlen, sichtbar hindrehen
 * und die Angriffstaste halten, solange das Fadenkreuz auf dem Block liegt.
 */
public final class BlockBreaker
{
    public enum Result { RUNNING, DONE, FAILED }

    private static final int TIMEOUT = 20 * 20;

    /** Spielzeit, zu der der Printer zuletzt abgebaut hat (siehe {@link #isMining}). */
    private static long lastMiningTick = Long.MIN_VALUE;
    private static final int NOT_VISIBLE_GRACE = 20;

    private final BlockPos pos;
    private boolean toolChosen;
    private boolean destroying;
    private int ticks;
    private int invisibleTicks;

    public BlockBreaker(BlockPos pos)
    {
        this.pos = pos.immutable();
    }

    public BlockPos pos()
    {
        return this.pos;
    }

    public Result tick(Minecraft mc, LocalPlayer player, Level level)
    {
        if (mc.gameMode == null)
        {
            return Result.FAILED;
        }

        BlockState state = level.getBlockState(this.pos);

        if (state.isAir() || state.canBeReplaced())
        {
            this.stop(mc);
            return Result.DONE;
        }

        if (++this.ticks > TIMEOUT)
        {
            this.stop(mc);
            return Result.FAILED;
        }

        if (!this.toolChosen)
        {
            this.toolChosen = true;
            selectBestTool(player, state);
        }

        // Fuers Abbauen zaehlen Geisterbloecke nicht: der Linksklick trifft nur echte Bloecke
        List<BlockHitResult> points = RefillUtil.visiblePoints(player, level, null, this.pos, null);

        if (points.isEmpty())
        {
            this.destroying = false;

            if (++this.invisibleTicks > NOT_VISIBLE_GRACE)
            {
                this.stop(mc);
                return Result.FAILED;
            }

            return Result.RUNNING;
        }

        this.invisibleTicks = 0;

        if (!this.destroying)
        {
            AimController.INSTANCE.aimAt(points.get(0).getLocation(), Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());
        }

        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getBlockPos().equals(this.pos) &&
            (this.destroying || AimController.INSTANCE.isOnTarget()))
        {
            if (!this.destroying)
            {
                mc.gameMode.startDestroyBlock(this.pos, bhr.getDirection());
                this.destroying = true;
            }
            else
            {
                mc.gameMode.continueDestroyBlock(this.pos, bhr.getDirection());
            }

            markMining(level);
            player.swing(InteractionHand.MAIN_HAND);
        }
        else if (this.destroying)
        {
            // Fadenkreuz ist runtergerutscht: neu zielen
            this.destroying = false;
        }

        return Result.RUNNING;
    }

    public void stop(Minecraft mc)
    {
        if (this.destroying && mc.gameMode != null)
        {
            mc.gameMode.stopDestroyBlock();
        }

        this.destroying = false;
        AimController.INSTANCE.stop();
    }

    /**
     * Der Printer baut gerade ab (wie mit gehaltener linker Maustaste). Vanilla bricht sonst in jedem Tick
     * ohne gedrueckte Maustaste das Abbauen ab und man finge immer wieder bei null an.
     */
    public static void markMining(Level level)
    {
        lastMiningTick = level.getGameTime();
    }

    /** Hat der Printer in diesem oder dem letzten Tick abgebaut? */
    public static boolean isMining(@org.jetbrains.annotations.Nullable Level level)
    {
        return level != null && level.getGameTime() - lastMiningTick <= 1;
    }

    /** Wie ein Spieler mit dem Mausrad: das schnellste Werkzeug aus der Hotbar nehmen. */
    private static void selectBestTool(LocalPlayer player, BlockState state)
    {
        Inventory inv = player.getInventory();
        int best = inv.getSelectedSlot();
        float bestSpeed = inv.getItem(best).getDestroySpeed(state);

        for (int i = 0; i < Inventory.getSelectionSize(); i++)
        {
            float speed = inv.getItem(i).getDestroySpeed(state);

            if (speed > bestSpeed)
            {
                best = i;
                bestSpeed = speed;
            }
        }

        inv.setSelectedSlot(best);
    }
}
