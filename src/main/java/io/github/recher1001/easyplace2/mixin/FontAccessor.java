package io.github.recher1001.easyplace2.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.Font;

@Mixin(Font.class)
public interface FontAccessor
{
    /** Woher die Schrift ihre Zeichen bekommt (alle Schriften aus den Ressourcen). */
    @Accessor("provider")
    Font.Provider easyplace2$provider();
}
