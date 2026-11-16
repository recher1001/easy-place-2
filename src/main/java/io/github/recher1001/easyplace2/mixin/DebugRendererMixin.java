package io.github.recher1001.easyplace2.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;

import io.github.recher1001.easyplace2.modules.singleplayer.CaveOutlines;

@Mixin(DebugRenderer.class)
public abstract class DebugRendererMixin
{
    /** Hier sammelt Minecraft die Debug-Linien eines Bildes: die Hoehlenumrisse kommen dazu. */
    @Inject(method = "emitGizmos", at = @At("HEAD"))
    private void easyplace2$emitCaves(Frustum frustum, double x, double y, double z, float partialTick, CallbackInfo ci)
    {
        CaveOutlines.INSTANCE.emit();
    }
}
