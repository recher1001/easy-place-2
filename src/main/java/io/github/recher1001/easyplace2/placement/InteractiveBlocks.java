package io.github.recher1001.easyplace2.placement;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Erkennt Bloecke, die auf Rechtsklick selbst reagieren (Kisten, Tueren, Werkbaenke, ...).
 * Vanilla wuerde bei einem Klick darauf den Block benutzen statt etwas daran zu platzieren,
 * ausser der Spieler schleicht. Easy Place 2.0 klickt solche Bloecke deshalb nur an, wenn der
 * Spieler selbst schleicht.
 * <p>
 * Erkannt wird das daran, ob die Block-Klasse eine der Vanilla-Methoden "useWithoutItem" oder
 * "useItemOn" ueberschreibt. Gesucht wird ueber die Parametertypen, damit es unabhaengig von
 * Methodennamen (Mappings) funktioniert.
 */
public class InteractiveBlocks
{
    private static final Class<?>[] USE_WITHOUT_ITEM_PARAMS = { BlockState.class, Level.class, BlockPos.class, Player.class, BlockHitResult.class };
    private static final Class<?>[] USE_ITEM_ON_PARAMS = { ItemStack.class, BlockState.class, Level.class, BlockPos.class, Player.class, InteractionHand.class, BlockHitResult.class };

    private static final Map<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>();

    public static boolean isInteractive(Block block)
    {
        return CACHE.computeIfAbsent(block.getClass(), InteractiveBlocks::overridesUse);
    }

    private static boolean overridesUse(Class<?> clazz)
    {
        for (Class<?> c = clazz; c != null && c != Block.class && c != BlockBehaviour.class && c != Object.class; c = c.getSuperclass())
        {
            for (Method method : c.getDeclaredMethods())
            {
                if (method.getReturnType() == InteractionResult.class)
                {
                    Class<?>[] params = method.getParameterTypes();

                    if (Arrays.equals(params, USE_WITHOUT_ITEM_PARAMS) || Arrays.equals(params, USE_ITEM_ON_PARAMS))
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
