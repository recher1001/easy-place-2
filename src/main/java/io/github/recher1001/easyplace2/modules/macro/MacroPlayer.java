package io.github.recher1001.easyplace2.modules.macro;

import java.util.List;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.mixin.MinecraftAccessor;
import io.github.recher1001.easyplace2.placement.EasyPlace2Handler;
import io.github.recher1001.easyplace2.printer.AimController;
import io.github.recher1001.easyplace2.printer.BlockBreaker;
import io.github.recher1001.easyplace2.printer.InventoryManager;
import io.github.recher1001.easyplace2.printer.PrinterController;
import io.github.recher1001.easyplace2.printer.refill.RefillUtil;

/**
 * Spielt ein Makro ab, Schritt fuer Schritt wie ein Spieler: sichtbar hindrehen, Maustaste "halten",
 * Rechtsklick, Item ueber Hotbar bzw. Inventar holen, Lauftasten halten, warten. Laeuft in Schleife,
 * bis es fertig ist oder gestoppt wird.
 */
public final class MacroPlayer
{
    public static final MacroPlayer INSTANCE = new MacroPlayer();

    private static final int LOOK_TIMEOUT = 20 * 3;
    /** So lange darf beim Abbauen nichts Passendes im Fadenkreuz sein, dann ist der Schritt fertig. */
    private static final int MINE_IDLE_TICKS = 10;
    private static final int BLOCK_TIMEOUT = 20 * 30;
    /** So weit (Bloecke) wird beim "Abbauen bis weg" nach weiteren passenden Bloecken gesucht. */
    private static final int SEARCH_RADIUS = 5;
    /** So lange darf das Hindrehen zu einem gefundenen Block dauern, dann ist der Schritt fertig. */
    private static final int SEARCH_TIMEOUT = 20 * 4;

    private final InventoryManager inventory = new InventoryManager();
    @Nullable private Macro macro;
    private int index;
    private int stepTicks;
    private int loops;
    private int heldKeys;
    @Nullable private BlockPos miningPos;
    private int miningTicks;
    private int idleTicks;
    private int searchTicks;
    private boolean fetching;

    private MacroPlayer()
    {
    }

    public void register()
    {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> this.stop(mc, null));
    }

    public boolean isPlaying()
    {
        return this.macro != null;
    }

    @Nullable
    public Macro current()
    {
        return this.macro;
    }

    public int stepIndex()
    {
        return this.index;
    }

    public int loops()
    {
        return this.loops;
    }

    public void toggle(Minecraft mc)
    {
        if (this.isPlaying())
        {
            this.stop(mc, Component.translatable("easyplace2.macro.msg.stopped"));
        }
        else
        {
            Macro selected = MacroStore.INSTANCE.selected();

            if (selected == null)
            {
                message(mc, Component.translatable("easyplace2.macro.msg.none").withStyle(ChatFormatting.GOLD));
            }
            else
            {
                this.play(mc, selected);
            }
        }
    }

    public void play(Minecraft mc, Macro macro)
    {
        if (mc.player == null || macro.steps.isEmpty())
        {
            return;
        }

        MacroRecorder.INSTANCE.stop(mc);

        if (PrinterController.INSTANCE.isRunning())
        {
            PrinterController.INSTANCE.stop(null);
        }

        this.macro = macro;
        this.index = 0;
        this.loops = 0;
        this.enterStep();
        message(mc, Component.translatable("easyplace2.macro.msg.started", macro.name).withStyle(ChatFormatting.GREEN));
    }

    public void stop(Minecraft mc, @Nullable Component reason)
    {
        if (this.macro == null)
        {
            return;
        }

        this.releaseAll(mc);
        this.inventory.cancel(mc);
        AimController.INSTANCE.stop();
        this.macro = null;

        if (reason != null)
        {
            message(mc, reason);
        }
    }

    private void tick(Minecraft mc)
    {
        Macro macro = this.macro;
        LocalPlayer player = mc.player;
        Level level = mc.level;

        if (macro == null)
        {
            return;
        }

        if (player == null || level == null || mc.gameMode == null || player.isDeadOrDying())
        {
            this.stop(mc, null);
            return;
        }

        if (macro.stopOnDamage && player.hurtTime > 0)
        {
            this.stop(mc, Component.translatable("easyplace2.macro.msg.damage").withStyle(ChatFormatting.RED));
            return;
        }

        // Bei Kisten/Inventar warten (ausser es ist das eigene Inventar zum Item-Holen), hinter dem
        // Pause-Menue oder Chat nur mit "Hinter Menues weiterbauen"
        if (mc.screen != null && !this.worksBehind(mc.screen) && !this.inventory.ownsScreen(mc.screen))
        {
            this.releaseAll(mc);
            return;
        }

        if (this.index >= macro.steps.size())
        {
            this.nextLoop(mc);
            return;
        }

        MacroStep step = macro.steps.get(this.index);
        this.stepTicks++;

        switch (step.type)
        {
            case WAIT ->
            {
                if (this.stepTicks >= step.ticks)
                {
                    this.next(mc);
                }
            }
            case LOOK ->
            {
                AimController.INSTANCE.aimAt(player.getEyePosition().add(direction(step.yaw, step.pitch).scale(5.0)),
                        Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());

                if ((AimController.INSTANCE.isOnTarget() && this.stepTicks > 1) || this.stepTicks > LOOK_TIMEOUT)
                {
                    AimController.INSTANCE.stop();
                    this.next(mc);
                }
            }
            case KEYS ->
            {
                this.setKeys(mc.options, step.keys);

                if (this.stepTicks >= Math.max(1, step.ticks))
                {
                    this.setKeys(mc.options, 0);
                    this.next(mc);
                }
            }
            case USE ->
            {
                // Eigener Klick: die Platzier-Sperre von Easy Place 2.0 gilt dafuer nicht
                EasyPlace2Handler.runUnrestricted(((MinecraftAccessor) mc)::easyplace2$startUseItem);
                this.next(mc);
            }
            case SELECT -> this.tickSelect(mc, player, step);
            case MINE -> this.tickMine(mc, player, level, step);
            case WAIT_FOR_BLOCK ->
            {
                if (step.block.equals(crosshairBlock(mc, level)) || (step.ticks > 0 && this.stepTicks > step.ticks))
                {
                    this.next(mc);
                }
            }
        }
    }

    private void tickSelect(Minecraft mc, LocalPlayer player, MacroStep step)
    {
        if (this.fetching)
        {
            this.inventory.tick(mc);

            if (!this.inventory.isBusy())
            {
                this.fetching = false;
                this.next(mc);
            }

            return;
        }

        Inventory inv = player.getInventory();

        if (step.item.isEmpty())
        {
            if (step.slot >= 0 && step.slot < Inventory.getSelectionSize())
            {
                inv.setSelectedSlot(step.slot);
            }

            this.next(mc);
            return;
        }

        Item item = MacroStep.item(step.item);

        for (int i = 0; i < Inventory.getSelectionSize(); i++)
        {
            if (inv.getItem(i).is(item))
            {
                inv.setSelectedSlot(i);
                this.next(mc);
                return;
            }
        }

        // Nicht in der Hotbar: wie ein Spieler aus dem Inventar holen (nicht hinter einem offenen Menue)
        if (item != Items.AIR && mc.screen == null && this.inventory.request(player, item, java.util.Set.of()))
        {
            this.fetching = true;
            return;
        }

        if (mc.screen == null)
        {
            this.stop(mc, Component.translatable("easyplace2.macro.msg.missing_item", MacroStep.itemName(step.item)).withStyle(ChatFormatting.RED));
        }
    }

    private void tickMine(Minecraft mc, LocalPlayer player, Level level, MacroStep step)
    {
        // Fertig mit dem aktuellen Block?
        if (this.miningPos != null && level.getBlockState(this.miningPos).isAir())
        {
            this.miningPos = null;

            if (step.mode == MacroStep.MineMode.ONCE)
            {
                this.next(mc);
                return;
            }
        }

        BlockHitResult hit = mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK ? bhr : null;
        boolean fits = hit != null && !level.getBlockState(hit.getBlockPos()).isAir() &&
                       (step.mode == MacroStep.MineMode.ONCE || step.block.isEmpty() ||
                        step.block.equals(MacroStep.id(level.getBlockState(hit.getBlockPos()))));

        if (!fits)
        {
            this.stopMining(mc);

            // "Abbauen bis weg": zum naechsten passenden Block in Reichweite hindrehen (z. B. Holz weiter oben)
            if (step.mode == MacroStep.MineMode.WHILE_BLOCK && !step.block.isEmpty() && this.searchTicks++ < SEARCH_TIMEOUT)
            {
                Vec3 point = nearestVisible(player, level, step.block);

                if (point != null)
                {
                    this.idleTicks = 0;
                    AimController.INSTANCE.aimAt(point, Ep2Configs.PRINTER_FAST_TURN.getBooleanValue());
                    return;
                }
            }

            // Nichts (mehr) zum Abbauen im Fadenkreuz: Schritt fertig
            if (++this.idleTicks > MINE_IDLE_TICKS)
            {
                this.next(mc);
            }

            return;
        }

        this.idleTicks = 0;
        BlockPos pos = hit.getBlockPos();

        if (this.miningPos == null || !this.miningPos.equals(pos))
        {
            // Beim Abbauen nicht weiterdrehen
            AimController.INSTANCE.stop();
            this.searchTicks = 0;
            this.stopMining(mc);
            mc.gameMode.startDestroyBlock(pos, hit.getDirection());
            this.miningPos = pos.immutable();
            this.miningTicks = 0;
        }
        else
        {
            mc.gameMode.continueDestroyBlock(pos, hit.getDirection());
        }

        BlockBreaker.markMining(level);
        player.swing(InteractionHand.MAIN_HAND);

        // Laesst sich nicht abbauen (z. B. Grundgestein): ueberspringen
        if (++this.miningTicks > BLOCK_TIMEOUT)
        {
            this.stopMining(mc);
            this.next(mc);
        }
    }

    /** Der naechste sichtbare Block dieser Art in Reichweite (Punkt zum Hinschauen), oder null. */
    @Nullable
    private static Vec3 nearestVisible(LocalPlayer player, Level level, String block)
    {
        BlockPos eye = BlockPos.containing(player.getEyePosition());
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        List<BlockHitResult> bestPoints = null;

        for (BlockPos pos : BlockPos.betweenClosed(eye.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
                                                   eye.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS)))
        {
            double dist = pos.distToCenterSqr(player.getEyePosition());

            if (dist >= bestDist || !block.equals(MacroStep.id(level.getBlockState(pos))))
            {
                continue;
            }

            List<BlockHitResult> points = RefillUtil.visiblePoints(player, level, null, pos, null);

            if (!points.isEmpty())
            {
                best = pos.immutable();
                bestDist = dist;
                bestPoints = points;
            }
        }

        return best != null ? bestPoints.get(0).getLocation() : null;
    }

    /**
     * Darf das Makro hinter diesem Menue weiterlaufen (Pause-Menue, Chat)? Wie beim Printer nur mit der
     * Einstellung "Hinter Menues weiterbauen" und nie hinter Kisten oder dem Inventar.
     */
    public boolean worksBehind(@Nullable Screen screen)
    {
        return this.isPlaying() && (screen == null ||
               (Ep2Configs.PRINTER_RUN_IN_MENUS.getBooleanValue() && !(screen instanceof AbstractContainerScreen<?>)));
    }

    private void next(Minecraft mc)
    {
        this.stopMining(mc);
        AimController.INSTANCE.stop();
        this.index++;
        this.enterStep();

        if (this.macro != null && this.index >= this.macro.steps.size())
        {
            this.nextLoop(mc);
        }
    }

    private void nextLoop(Minecraft mc)
    {
        Macro macro = this.macro;

        if (macro == null)
        {
            return;
        }

        this.loops++;

        if (macro.repeats > 0 && this.loops >= macro.repeats)
        {
            this.stop(mc, Component.translatable("easyplace2.macro.msg.done", macro.name, this.loops).withStyle(ChatFormatting.GREEN));
            return;
        }

        this.index = 0;
        this.enterStep();
    }

    private void enterStep()
    {
        this.stepTicks = 0;
        this.idleTicks = 0;
        this.searchTicks = 0;
        this.miningPos = null;
        this.fetching = false;
    }

    private void stopMining(Minecraft mc)
    {
        if (this.miningPos != null && mc.gameMode != null)
        {
            mc.gameMode.stopDestroyBlock();
        }

        this.miningPos = null;
    }

    private void releaseAll(Minecraft mc)
    {
        this.setKeys(mc.options, 0);
        this.stopMining(mc);
    }

    private void setKeys(Options options, int keys)
    {
        if (keys == this.heldKeys)
        {
            return;
        }

        options.keyUp.setDown((keys & MacroStep.KEY_FORWARD) != 0);
        options.keyDown.setDown((keys & MacroStep.KEY_BACK) != 0);
        options.keyLeft.setDown((keys & MacroStep.KEY_LEFT) != 0);
        options.keyRight.setDown((keys & MacroStep.KEY_RIGHT) != 0);
        options.keyJump.setDown((keys & MacroStep.KEY_JUMP) != 0);
        options.keyShift.setDown((keys & MacroStep.KEY_SNEAK) != 0);
        this.heldKeys = keys;
    }

    @Nullable
    private static String crosshairBlock(Minecraft mc, Level level)
    {
        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK)
        {
            return MacroStep.id(level.getBlockState(bhr.getBlockPos()));
        }

        return null;
    }

    /** Blickrichtung aus Drehwinkeln (wie Minecraft selbst). */
    public static Vec3 direction(float yaw, float pitch)
    {
        float y = (float) Math.toRadians(yaw);
        float p = (float) Math.toRadians(pitch);
        float cosP = Mth.cos(p);
        return new Vec3(-Mth.sin(y) * cosP, -Mth.sin(p), Mth.cos(y) * cosP);
    }

    private static void message(Minecraft mc, Component text)
    {
        if (mc.player != null)
        {
            mc.player.displayClientMessage(Component.literal("[Makro] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(text), false);
        }
    }

    /** Fuer Tests und das Menue: alle Schritte. */
    public static List<MacroStep> steps(Macro macro)
    {
        return macro.steps;
    }
}
