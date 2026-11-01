package io.github.recher1001.easyplace2.printer;

import java.util.List;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.mixin.MinecraftAccessor;
import io.github.recher1001.easyplace2.placement.EasyPlace2Handler;
import io.github.recher1001.easyplace2.printer.refill.RefillUtil;

/**
 * Einstellungen, die man nach dem Setzen per Rechtsklick waehlt: Repeater-Verzoegerung, Komparator-Modus
 * (vergleichen/subtrahieren), Ton eines Notenblocks, Tageslichtsensor umgekehrt.
 * <p>
 * Wie ein Spieler: auf den Block schauen, das echte Fadenkreuz muss darauf liegen, normaler Rechtsklick
 * (nie schleichend, sonst wuerde das Item in der Hand gesetzt). Nach jedem Klick wird der neue Zustand
 * abgewartet; nimmt der Server ihn zurueck, wird nachgeklickt, aber nie endlos.
 */
public final class BlockAdjuster
{
    public enum Result { RUNNING, DONE, FAILED }

    private static final int AIM_TIMEOUT = 20 * 3;
    private static final int CONFIRM_TICKS = 4;

    @Nullable private BlockPos pos;
    @Nullable private BlockState wanted;
    @Nullable private Vec3 point;
    private int ticks;
    private int clicks;
    private int maxClicks;
    private int stableTicks;
    private long nextClick;

    public boolean isActive()
    {
        return this.pos != null;
    }

    @Nullable
    public BlockPos pos()
    {
        return this.pos;
    }

    /** Wie viele Rechtsklicks noch fehlen, bis {@code current} wie {@code wanted} eingestellt ist (0 = passt). */
    public static int clicksNeeded(BlockState current, BlockState wanted)
    {
        if (current.getBlock() != wanted.getBlock())
        {
            return 0;
        }

        if (wanted.hasProperty(BlockStateProperties.DELAY))
        {
            // 1 → 2 → 3 → 4 → 1
            return Math.floorMod(wanted.getValue(BlockStateProperties.DELAY) - current.getValue(BlockStateProperties.DELAY), 4);
        }

        if (wanted.hasProperty(BlockStateProperties.MODE_COMPARATOR))
        {
            return current.getValue(BlockStateProperties.MODE_COMPARATOR) != wanted.getValue(BlockStateProperties.MODE_COMPARATOR) ? 1 : 0;
        }

        if (wanted.hasProperty(BlockStateProperties.NOTE))
        {
            return Math.floorMod(wanted.getValue(BlockStateProperties.NOTE) - current.getValue(BlockStateProperties.NOTE), 25);
        }

        if (wanted.hasProperty(BlockStateProperties.INVERTED))
        {
            return current.getValue(BlockStateProperties.INVERTED).equals(wanted.getValue(BlockStateProperties.INVERTED)) ? 0 : 1;
        }

        return 0;
    }

    @Nullable
    private static BlockHitResult aim(LocalPlayer player, Level level, BlockPos pos)
    {
        List<BlockHitResult> points = RefillUtil.visiblePoints(player, level, null, pos, null);
        return points.isEmpty() ? null : points.get(0);
    }

    /** Kann der Block von hier aus angeklickt werden? */
    public static boolean reachable(LocalPlayer player, Level level, BlockPos pos)
    {
        return aim(player, level, pos) != null;
    }

    public boolean start(LocalPlayer player, Level level, BlockPos pos, BlockState wanted)
    {
        BlockHitResult hit = aim(player, level, pos);
        int need = clicksNeeded(level.getBlockState(pos), wanted);

        if (hit == null || need == 0)
        {
            return false;
        }

        this.pos = pos.immutable();
        this.wanted = wanted;
        this.point = hit.getLocation();
        this.ticks = 0;
        this.clicks = 0;
        this.maxClicks = need + 3;
        this.stableTicks = 0;
        this.nextClick = 0;
        return true;
    }

    public Result tick(Minecraft mc, long now)
    {
        LocalPlayer player = mc.player;
        Level level = mc.level;

        if (player == null || level == null || this.pos == null || this.wanted == null || this.point == null)
        {
            this.cancel();
            return Result.FAILED;
        }

        this.ticks++;
        BlockState current = level.getBlockState(this.pos);

        if (current.getBlock() != this.wanted.getBlock())
        {
            this.cancel();
            return Result.FAILED;
        }

        if (clicksNeeded(current, this.wanted) == 0)
        {
            // Passt: ein paar Ticks abwarten, ob der Server es so laesst
            if (++this.stableTicks >= CONFIRM_TICKS)
            {
                this.cancel();
                return Result.DONE;
            }

            return Result.RUNNING;
        }

        this.stableTicks = 0;

        if (this.clicks >= this.maxClicks)
        {
            this.cancel();
            return Result.FAILED;
        }

        AimController.INSTANCE.aimAt(this.point, Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());

        if (AimController.INSTANCE.isOnTarget() && RefillUtil.crosshairOn(mc, this.pos, null) && !player.isShiftKeyDown() && now >= this.nextClick)
        {
            // Ganz normaler Rechtsklick auf den Block (Easy Place 2.0 haelt sich hier raus)
            EasyPlace2Handler.runVanilla(((MinecraftAccessor) mc)::easyplace2$startUseItem);
            this.clicks++;
            this.nextClick = now + Math.max(2, Ep2Configs.DELAY_TICKS.getIntegerValue());
            this.ticks = 0;
            return Result.RUNNING;
        }

        if (this.ticks > AIM_TIMEOUT)
        {
            this.cancel();
            return Result.FAILED;
        }

        return Result.RUNNING;
    }

    public void cancel()
    {
        this.pos = null;
        this.wanted = null;
        this.point = null;
        AimController.INSTANCE.stop();
    }
}
