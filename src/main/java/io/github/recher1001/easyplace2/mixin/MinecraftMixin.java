package io.github.recher1001.easyplace2.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;

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
     * Nur genau dieser Abbruch wird ausgelassen; alles andere (z. B. die Klick-Sperre nach einem Menue)
     * laeuft ganz normal, damit der Spieler selbst jederzeit abbauen kann.
     */
    @Redirect(method = "continueAttack",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;stopDestroyBlock()V"))
    private void easyplace2$stopDestroyBlock(MultiPlayerGameMode gameMode)
    {
        if (!BlockBreaker.isMining(((Minecraft) (Object) this).level))
        {
            gameMode.stopDestroyBlock();
        }
    }

    /** Einmal pro Bild: die Drehung des Printers laeuft so fluessig wie eine Mausbewegung. */
    @Inject(method = "runTick", at = @At("HEAD"))
    private void easyplace2$onFrame(CallbackInfo ci)
    {
        AimController.INSTANCE.onFrame((Minecraft) (Object) this);
    }
}
