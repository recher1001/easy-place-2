package io.github.recher1001.easyplace2.mixin;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;

import io.github.recher1001.easyplace2.modules.ChatInvsee;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin
{
    /** Mittelklick auf eine Chat-Nachricht: Invsee fuer den Absender (bzw. den Spieler der TPA-Anfrage). */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void easyplace2$onMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir)
    {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && ChatInvsee.onMiddleClick(Minecraft.getInstance(), event.x(), event.y()))
        {
            cir.setReturnValue(true);
        }
    }
}
