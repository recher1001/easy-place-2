package io.github.recher1001.easyplace2.mixin.litematica;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fi.dy.masa.malilib.gui.button.ButtonBase;

import io.github.recher1001.easyplace2.config.Ep2ConfigTab;

/**
 * Klick auf einen der normalen Litematica-Reiter: unseren Reiter verlassen.
 */
@Mixin(targets = "fi.dy.masa.litematica.gui.GuiConfigs$ButtonListener", remap = false)
public abstract class GuiConfigsTabButtonMixin
{
    @Inject(method = "actionPerformedWithButton", at = @At("HEAD"))
    private void easyplace2$leaveOwnTab(ButtonBase button, int mouseButton, CallbackInfo ci)
    {
        Ep2ConfigTab.setActive(false);
    }
}
