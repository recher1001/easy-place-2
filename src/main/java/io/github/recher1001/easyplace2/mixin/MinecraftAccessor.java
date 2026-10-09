package io.github.recher1001.easyplace2.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.Minecraft;

@Mixin(Minecraft.class)
public interface MinecraftAccessor
{
    /** Wartezeit bis zum naechsten Rechtsklick, solange die Taste gehalten wird (Vanilla: 4 Ticks). */
    @Accessor("rightClickDelay")
    void easyplace2$setRightClickDelay(int ticks);
}
