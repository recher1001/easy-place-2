package io.github.recher1001.easyplace2.placement;

import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.tool.ToolMode;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;

import io.github.recher1001.easyplace2.EasyPlace2;
import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.mixin.MinecraftAccessor;
import io.github.recher1001.easyplace2.printer.PrinterController;

/**
 * Easy Place 2.0: wird bei jedem normalen Rechtsklick (Minecraft#startUseItem) gefragt.
 * <p>
 * Zeigt das Fadenkreuz auf einen Geisterblock der Schematic, der noch fehlt, wird das passende
 * Item ausgewaehlt und ein normaler Vanilla-Klick auf eine sichtbare Nachbarflaeche ausgefuehrt.
 * In allen anderen Faellen laeuft der Rechtsklick ganz normal weiter.
 */
public class EasyPlace2Handler
{
    /** Kurze Sperre, damit dieselbe Position nicht doppelt angeklickt wird, bevor der Server antwortet. */
    private static final long POSITION_CACHE_MS = 500;
    private static final Map<BlockPos, Long> RECENTLY_PLACED = new HashMap<>();

    /**
     * @return true wenn Easy Place 2.0 diesen Rechtsklick uebernommen hat (Vanilla soll dann nichts mehr tun)
     */
    public static boolean onUseItem(Minecraft mc)
    {
        if (!Ep2Configs.EASY_PLACE_2.getBooleanValue())
        {
            return false;
        }

        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        Entity camera = mc.getCameraEntity();
        Level schematicWorld = SchematicWorldHandler.getSchematicWorld();

        if (player == null || level == null || camera == null || mc.gameMode == null || schematicWorld == null ||
            mc.gameMode.isDestroying() || player.isHandsBusy() ||
            mc.gameMode.getPlayerMode() == GameType.SPECTATOR || !player.getAbilities().mayBuild ||
            DataManager.getToolMode() == ToolMode.REBUILD)
        {
            return false;
        }

        RayTraceUtils.RayTraceWrapper trace = RayTraceUtils.getGenericTrace(level, camera, player.blockInteractionRange());

        // Fadenkreuz zeigt nicht auf einen Geisterblock -> normaler Rechtsklick
        if (trace == null || trace.getHitType() != RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK)
        {
            return false;
        }

        BlockPos target = trace.getBlockHitResult().getBlockPos();
        BlockState stateSchematic = schematicWorld.getBlockState(target);
        BlockState stateClient = level.getBlockState(target);

        // Schon richtig, oder dort steht ein anderer fester Block -> normaler Rechtsklick
        if (stateSchematic.isAir() || StateMatcher.isComplete(stateClient, stateSchematic) ||
            (!stateClient.canBeReplaced() && stateClient.getBlock() != stateSchematic.getBlock()))
        {
            return false;
        }

        // Ab hier gehoert der Klick Easy Place 2.0, auch wenn am Ende nichts platziert wird.
        // Sonst wuerde Vanilla das Item in der Hand irgendwo hinter dem Geisterblock platzieren.
        ((MinecraftAccessor) mc).easyplace2$setRightClickDelay(Ep2Configs.DELAY_TICKS.getIntegerValue());

        if (isRecentlyPlaced(target))
        {
            return true;
        }

        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(stateSchematic, schematicWorld, target);

        if (required.isEmpty() || !(required.getItem() instanceof BlockItem blockItem))
        {
            debug(target, stateSchematic, "kein platzierbares Item fuer diesen Block");
            return true;
        }

        BlockHitResult crosshair = mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK ? bhr : null;
        boolean strict = Ep2Configs.STRICT_CROSSHAIR.getBooleanValue();

        // Erst pruefen, ob ein Vanilla-Klick ueberhaupt moeglich ist, bevor Items getauscht werden
        PlacementPlanner.Result plan = new PlacementPlanner(player, level, target, stateSchematic,
                InteractionHand.MAIN_HAND, required, blockItem).plan(crosshair, strict);

        if (plan.hit() == null)
        {
            debug(target, stateSchematic, plan.reason());
            return true;
        }

        // Item mit Litematicas eigener Logik in die Hand holen (Hotbar, Inventar, Shulker-Option).
        // Der Printer holt Items selbst wie ein Spieler ueber das Inventar, dann wird hier nichts getauscht.
        InteractionHand hand = EntityUtils.getUsedHandForItem(player, required);

        if (hand == null && !PrinterController.INSTANCE.isRunning())
        {
            InventoryUtils.schematicWorldPickBlock(required, target, schematicWorld, mc);
            hand = EntityUtils.getUsedHandForItem(player, required);
        }

        if (hand == null)
        {
            debug(target, stateSchematic, "benoetigtes Item nicht im Inventar: " + required.getHoverName().getString());
            return true;
        }

        // Mit dem echten Item in der echten Hand nochmal pruefen
        ItemStack held = player.getItemInHand(hand);

        if (!(held.getItem() instanceof BlockItem heldItem) ||
            !new PlacementPlanner(player, level, target, stateSchematic, hand, held, heldItem).test(plan.hit()))
        {
            debug(target, stateSchematic, "Klick passt mit dem Item in der Hand nicht mehr");
            return true;
        }

        clickBlock(mc, player, hand, held, plan.hit());
        RECENTLY_PLACED.put(target.immutable(), System.currentTimeMillis());

        return true;
    }

    /**
     * Ein ganz normaler "Block benutzen"-Klick, wie ihn Minecraft#startUseItem selbst ausfuehrt.
     */
    private static void clickBlock(Minecraft mc, LocalPlayer player, InteractionHand hand, ItemStack held, BlockHitResult hit)
    {
        int countBefore = held.getCount();
        InteractionResult result;

        // Falls das alte Easy Place im "Post Rewrite"-Modus aktiv ist, soll es diesen Klick nicht abfangen
        boolean wasHandling = EasyPlaceUtils.isHandling();
        EasyPlaceUtils.setHandling(true);

        try
        {
            result = mc.gameMode.useItemOn(player, hand, hit);
        }
        finally
        {
            EasyPlaceUtils.setHandling(wasHandling);
        }

        if (result instanceof InteractionResult.Success success &&
            success.swingSource() == InteractionResult.SwingSource.CLIENT)
        {
            player.swing(hand);

            if (!held.isEmpty() && (held.getCount() != countBefore || player.hasInfiniteMaterials()))
            {
                mc.getEntityRenderDispatcher().getItemInHandRenderer().itemUsed(hand);
            }
        }
    }

    private static boolean isRecentlyPlaced(BlockPos pos)
    {
        long now = System.currentTimeMillis();
        RECENTLY_PLACED.values().removeIf(time -> now - time > POSITION_CACHE_MS);
        return RECENTLY_PLACED.containsKey(pos);
    }

    private static void debug(BlockPos pos, @Nullable BlockState state, String reason)
    {
        if (Ep2Configs.DEBUG.getBooleanValue())
        {
            String block = state != null ? state.getBlock().getName().getString() : "?";
            String text = "Easy Place 2.0: " + block + " bei " + pos.toShortString() + " uebersprungen - " + reason;
            EasyPlace2.LOGGER.info(text);
            InfoUtils.printActionbarMessage(text);
        }
    }
}
