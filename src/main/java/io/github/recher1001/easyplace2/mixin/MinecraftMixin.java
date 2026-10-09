package io.github.recher1001.easyplace2.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;

import io.github.recher1001.easyplace2.placement.EasyPlace2Handler;
import io.github.recher1001.easyplace2.printer.AimController;

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

    /** Einmal pro Bild: die Drehung des Printers laeuft so fluessig wie eine Mausbewegung. */
    @Inject(method = "runTick", at = @At("HEAD"))
    private void easyplace2$onFrame(CallbackInfo ci)
    {
        AimController.INSTANCE.onFrame((Minecraft) (Object) this);
    }
}
