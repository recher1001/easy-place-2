package io.github.recher1001.easyplace2.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(BlockItem.class)
public interface BlockItemInvoker
{
    /**
     * Vanillas eigene Berechnung, welcher Zustand bei diesem Klick entsteht (inkl. Wand-/Bodenvariante,
     * Halt am Untergrund und Kollision). Wird nur zum Vorausberechnen benutzt.
     */
    @Invoker("getPlacementState")
    @Nullable
    BlockState easyplace2$getPlacementState(BlockPlaceContext context);
}
