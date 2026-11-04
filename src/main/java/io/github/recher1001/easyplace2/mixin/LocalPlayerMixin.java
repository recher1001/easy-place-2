package io.github.recher1001.easyplace2.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.player.LocalPlayer;

import io.github.recher1001.easyplace2.printer.ServerRotation;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin
{
    /** Lambda-Modus: die Printer-Drehung geht nur mit dem Bewegungspaket an den Server, die Kamera bleibt frei. */
    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void easyplace2$beforeSendPosition(CallbackInfo ci)
    {
        ServerRotation.beforeSend((LocalPlayer) (Object) this);
    }

    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void easyplace2$afterSendPosition(CallbackInfo ci)
    {
        ServerRotation.afterSend((LocalPlayer) (Object) this);
    }
}
