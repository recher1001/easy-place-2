package io.github.recher1001.easyplace2.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;

/** Zugriff auf die sichtbaren Chat-Zeilen, um herauszufinden, welche Nachricht angeklickt wurde. */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor
{
    @Accessor("trimmedMessages")
    List<GuiMessage.Line> easyplace2$lines();

    @Accessor("chatScrollbarPos")
    int easyplace2$scroll();

    @Invoker("getScale")
    double easyplace2$scale();

    @Invoker("getLineHeight")
    int easyplace2$lineHeight();

    @Invoker("getWidth")
    int easyplace2$width();
}
