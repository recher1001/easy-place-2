package io.github.recher1001.easyplace2.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;

import io.github.recher1001.easyplace2.placement.EasyPlace2Handler;
import io.github.recher1001.easyplace2.printer.AimController;
import io.github.recher1001.easyplace2.printer.BlockBreaker;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin
{
    /**
     * Normaler Rechtsklick ("Gegenstand benutzen"). Easy Place 2.0 darf ihn uebernehmen,
     * wenn das Fadenkreuz auf einen fehlenden Schematic-Block zeigt.
     */
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void easyplace2$onStartUseItem(CallbackInfo ci)
    {
        if (EasyPlace2Handler.onUseItem((Minecraft) (Object) this))
        {
            ci.cancel();
        }
    }

    /**
     * Ohne gedrueckte Maustaste bricht Vanilla hier jeden Tick das Abbauen ab. Baut der Printer gerade ab
     * (er "haelt" die Maustaste), darf das nicht passieren, sonst geht der Fortschritt immer wieder verloren.
     */
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void easyplace2$onContinueAttack(boolean leftClick, CallbackInfo ci)
    {
        if (!leftClick && BlockBreaker.isMining(((Minecraft) (Object) this).level))
        {
            ci.cancel();
        }
    }

    /** Einmal pro Bild: die Drehung des Printers laeuft so fluessig wie eine Mausbewegung. */
    @Inject(method = "runTick", at = @At("HEAD"))
    private void easyplace2$onFrame(CallbackInfo ci)
    {
        AimController.INSTANCE.onFrame((Minecraft) (Object) this);
    }
}
