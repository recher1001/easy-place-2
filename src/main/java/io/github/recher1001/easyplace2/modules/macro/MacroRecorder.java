package io.github.recher1001.easyplace2.modules.macro;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Nimmt ein Makro auf: wohin geschaut wird, was abgebaut und benutzt wird, welches Item in die Hand kommt,
 * welche Lauftasten gehalten werden, und die Pausen dazwischen. Danach laesst sich alles im Menue aendern.
 */
public final class MacroRecorder
{
    public static final MacroRecorder INSTANCE = new MacroRecorder();

    /** So lange (Ticks) muss der Blick still stehen, damit er als "hingeschaut" zaehlt. */
    private static final int LOOK_STABLE_TICKS = 4;
    private static final double LOOK_MOVE_DEGREES = 0.5;

    private boolean recording;
    private List<MacroStep> steps = new ArrayList<>();
    private long lastEventTick;
    private long now;

    private float lastYaw, lastPitch;
    private boolean looking;
    private int stableTicks;
    private long lookStartTick;

    private int lastSlot;
    @Nullable private BlockPos minePos;
    private String mineBlock = "";
    private long mineStart;
    private boolean useDown;
    private int keyMask;
    private long keyStart;

    private MacroRecorder()
    {
    }

    public void register()
    {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    public boolean isRecording()
    {
        return this.recording;
    }

    public int stepCount()
    {
        return this.steps.size();
    }

    public void toggle(Minecraft mc)
    {
        if (this.recording)
        {
            this.stop(mc);
        }
        else
        {
            this.start(mc);
        }
    }

    public void start(Minecraft mc)
    {
        LocalPlayer player = mc.player;

        if (player == null || mc.level == null || this.recording)
        {
            return;
        }

        MacroPlayer.INSTANCE.stop(mc, null);
        this.recording = true;
        this.steps = new ArrayList<>();
        this.now = mc.level.getGameTime();
        this.lastEventTick = this.now;
        this.lastYaw = player.getYRot();
        this.lastPitch = player.getXRot();
        this.looking = false;
        this.lastSlot = player.getInventory().getSelectedSlot();
        this.minePos = null;
        this.useDown = mc.options.keyUse.isDown();
        this.keyMask = 0;

        // Startpunkt: Blick und Item in der Hand, damit das Makro immer gleich beginnt
        this.steps.add(MacroStep.look(player.getYRot(), player.getXRot()));
        ItemStack held = player.getInventory().getItem(this.lastSlot);
        this.steps.add(MacroStep.select(held.isEmpty() ? "" : MacroStep.id(held.getItem()), this.lastSlot));
        message(mc, Component.translatable("easyplace2.macro.msg.recording").withStyle(ChatFormatting.RED));
    }

    public void stop(Minecraft mc)
    {
        if (!this.recording)
        {
            return;
        }

        LocalPlayer player = mc.player;

        if (player != null)
        {
            this.flushLook(player);

            if (this.keyMask != 0)
            {
                this.emit(this.keyStart, MacroStep.keys(this.keyMask, (int) (this.now - this.keyStart)), this.now);
            }
        }

        this.recording = false;

        if (this.steps.size() <= 2)
        {
            message(mc, Component.translatable("easyplace2.macro.msg.empty").withStyle(ChatFormatting.GOLD));
            return;
        }

        Macro macro = new Macro(MacroStore.INSTANCE.nextName(), this.steps);
        MacroStore.INSTANCE.add(macro);
        message(mc, Component.translatable("easyplace2.macro.msg.saved", macro.name, macro.steps.size()).withStyle(ChatFormatting.GREEN));
    }

    private void tick(Minecraft mc)
    {
        LocalPlayer player = mc.player;

        if (!this.recording)
        {
            return;
        }

        if (player == null || mc.level == null)
        {
            this.recording = false;
            return;
        }

        // Im Menue (z. B. zum Beenden der Aufnahme) wird nichts aufgenommen
        if (mc.screen != null)
        {
            return;
        }

        this.now = mc.level.getGameTime();
        Options options = mc.options;

        // Item gewechselt (Mausrad, Zahlentaste)
        int slot = player.getInventory().getSelectedSlot();

        if (slot != this.lastSlot)
        {
            ItemStack stack = player.getInventory().getItem(slot);
            this.emit(this.now, MacroStep.select(stack.isEmpty() ? "" : MacroStep.id(stack.getItem()), slot), this.now);
            this.lastSlot = slot;
        }

        // Blick: erst merken, wenn er wieder still steht
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        double moved = Math.abs(Mth.wrapDegrees(yaw - this.lastYaw)) + Math.abs(pitch - this.lastPitch);

        if (moved > LOOK_MOVE_DEGREES)
        {
            if (!this.looking)
            {
                this.looking = true;
                this.lookStartTick = this.now;
            }

            this.stableTicks = 0;
            this.lastYaw = yaw;
            this.lastPitch = pitch;
        }
        else if (this.looking && ++this.stableTicks >= LOOK_STABLE_TICKS)
        {
            this.flushLook(player);
        }

        // Lauf-, Sprung- und Schleichtasten
        int mask = (options.keyUp.isDown() ? MacroStep.KEY_FORWARD : 0) | (options.keyDown.isDown() ? MacroStep.KEY_BACK : 0) |
                   (options.keyLeft.isDown() ? MacroStep.KEY_LEFT : 0) | (options.keyRight.isDown() ? MacroStep.KEY_RIGHT : 0) |
                   (options.keyJump.isDown() ? MacroStep.KEY_JUMP : 0) | (options.keyShift.isDown() ? MacroStep.KEY_SNEAK : 0);

        if (mask != this.keyMask)
        {
            if (this.keyMask != 0)
            {
                this.emit(this.keyStart, MacroStep.keys(this.keyMask, Math.max(1, (int) (this.now - this.keyStart))), this.now);
            }

            this.keyMask = mask;
            this.keyStart = this.now;
        }

        // Abbauen: ein Schritt pro abgebautem Block
        boolean attack = options.keyAttack.isDown();
        BlockHitResult hit = mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK ? bhr : null;

        if (this.minePos != null && mc.level.getBlockState(this.minePos).isAir())
        {
            this.flushLook(player);
            this.emit(this.mineStart, MacroStep.mine(MacroStep.MineMode.ONCE, this.mineBlock), this.now);
            this.minePos = null;
        }

        if (!attack)
        {
            this.minePos = null;
        }
        else if (hit != null && (this.minePos == null || !this.minePos.equals(hit.getBlockPos())))
        {
            this.minePos = hit.getBlockPos().immutable();
            this.mineBlock = MacroStep.id(mc.level.getBlockState(this.minePos));
            this.mineStart = this.now;
        }

        // Benutzen (Rechtsklick)
        boolean use = options.keyUse.isDown();

        if (use && !this.useDown)
        {
            this.flushLook(player);
            this.emit(this.now, MacroStep.use(), this.now);
        }

        this.useDown = use;
    }

    /** Ein noch nicht gemerktes Hinschauen jetzt als Schritt speichern. */
    private void flushLook(LocalPlayer player)
    {
        if (this.looking)
        {
            this.looking = false;
            this.emit(this.lookStartTick, MacroStep.look(player.getYRot(), player.getXRot()), this.now);
        }
    }

    /** Schritt anhaengen, davor die Pause seit dem letzten Schritt. */
    private void emit(long startTick, MacroStep step, long endTick)
    {
        long gap = startTick - this.lastEventTick;

        if (gap >= 2)
        {
            this.steps.add(MacroStep.wait((int) gap));
        }

        this.steps.add(step);
        this.lastEventTick = Math.max(this.lastEventTick, endTick);
    }

    private static void message(Minecraft mc, Component text)
    {
        if (mc.player != null)
        {
            mc.player.displayClientMessage(Component.literal("[Makro] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(text), false);
        }
    }
}
