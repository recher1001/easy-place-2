package io.github.recher1001.easyplace2.test;

import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiConfigs;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;

import io.github.recher1001.easyplace2.config.Ep2ConfigTab;
import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.mixin.ChatComponentAccessor;
import io.github.recher1001.easyplace2.modules.ChatInvsee;
import io.github.recher1001.easyplace2.modules.macro.Macro;
import io.github.recher1001.easyplace2.modules.macro.MacroPlayer;
import io.github.recher1001.easyplace2.modules.macro.MacroRecorder;
import io.github.recher1001.easyplace2.modules.macro.MacroStep;
import io.github.recher1001.easyplace2.modules.macro.MacroStore;
import io.github.recher1001.easyplace2.printer.PrinterController;
import io.github.recher1001.easyplace2.printer.Watchdog;
import io.github.recher1001.easyplace2.printer.lanes.LanePath;
import io.github.recher1001.easyplace2.printer.scaffold.ScaffoldTracker;
import io.github.recher1001.easyplace2.printer.gui.OptionEntry;
import io.github.recher1001.easyplace2.printer.gui.PrinterScreen;
import io.github.recher1001.easyplace2.printer.refill.StorageRegistry;

/**
 * Ende-zu-Ende-Test: echter Vanilla-Dedicated-Server (ohne Litematica und ohne Easy Place 2.0),
 * Client mit Litematica + Easy Place 2.0. Es wird nur ganz normal rechtsgeklickt.
 */
public class EasyPlace2ClientGameTest implements FabricClientGameTest
{
    // Flache Welt: Grasoberflaeche bei y=-61, der Spieler steht auf y=-60
    private static final int Y = -60;
    private static final Vec3 PLAYER_POS = new Vec3(0.5, Y, 0.5);
    private static final Vec3 EYE = PLAYER_POS.add(0, 1.62, 0);

    private static final BlockPos STAIRS_EAST = new BlockPos(3, Y, 0);
    private static final BlockPos SLAB_TOP = new BlockPos(3, Y, 1);
    private static final BlockPos SLAB_SUPPORT = new BlockPos(4, Y, 1);
    private static final BlockPos STAIRS_NORTH = new BlockPos(3, Y, -1);
    private static final BlockPos FLOATING = new BlockPos(3, Y + 3, -2);

    @Override
    public void runTest(ClientGameTestContext context)
    {
        testConfigTab(context);

        Properties props = new Properties();
        props.setProperty("level-type", "minecraft:flat");
        props.setProperty("spawn-protection", "0");
        props.setProperty("difficulty", "peaceful");
        props.setProperty("generate-structures", "false");

        try (TestDedicatedServerContext server = context.worldBuilder().createServer(props);
             TestServerConnection connection = server.connect())
        {
            connection.getClientWorld().waitForChunksRender();

            server.runCommand("gamemode survival @a");
            server.runCommand("time set day");
            server.runCommand("gamerule doDaylightCycle false");

            testManualMining(context, server);

            // Vorlage bauen, als Schematic aufnehmen und wieder entfernen
            server.runCommand(setblock(STAIRS_EAST, "oak_stairs[facing=east,half=bottom]"));
            server.runCommand(setblock(SLAB_TOP, "oak_slab[type=top]"));
            server.runCommand(setblock(SLAB_SUPPORT, "stone"));
            server.runCommand(setblock(STAIRS_NORTH, "oak_stairs[facing=north,half=bottom]"));
            server.runCommand(setblock(FLOATING, "stone"));
            context.waitTicks(20);

            BlockPos min = new BlockPos(3, Y, -2);
            BlockPos max = new BlockPos(3, Y + 3, 1);

            context.runOnClient(mc ->
            {
                AreaSelection area = new AreaSelection();
                area.addSubRegionBox(new Box(min, max, "test"), false);
                area.setExplicitOrigin(min);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, true), "test", msg -> System.out.println("[EP2-TEST] " + msg));

                if (schematic == null)
                {
                    throw new AssertionError("Schematic konnte nicht erstellt werden");
                }

                SchematicPlacement placement = SchematicPlacement.createFor(schematic, min, "test", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
            });

            server.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d air", min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
            context.waitFor(mc -> mc.level.getBlockState(STAIRS_EAST).isAir() && mc.level.getBlockState(FLOATING).isAir());
            context.waitFor(mc -> SchematicWorldHandler.getSchematicWorld() != null &&
                                  SchematicWorldHandler.getSchematicWorld().getBlockState(STAIRS_EAST).is(Blocks.OAK_STAIRS), 200);

            server.runCommand("give @a oak_stairs 8");
            server.runCommand("give @a oak_slab 8");
            server.runCommand("give @a stone 8");
            context.waitTicks(10);

            context.runOnClient(mc ->
            {
                Ep2Configs.EASY_PLACE_2.setBooleanValue(true);
                Ep2Configs.DEBUG.setBooleanValue(true);
                // Leerer Hotbar-Platz: Easy Place 2.0 muss das Item selbst auswaehlen
                mc.player.getInventory().setSelectedSlot(8);
            });

            // A: Treppe nach Osten, Spieler schaut nach Osten -> wird platziert
            lookAndClick(context, server, new Vec3(3.5, Y + 0.4, 0.5));
            assertBlock(server, STAIRS_EAST, state -> state.is(Blocks.OAK_STAIRS) && state.getValue(StairBlock.FACING) == Direction.EAST,
                    "Treppe (facing=east) wurde nicht platziert");

            // B: obere Halbplatte, angeklickt wird die obere Haelfte der Steinseite -> wird platziert
            lookAndClick(context, server, new Vec3(3.5, Y + 0.75, 1.5));
            assertBlock(server, SLAB_TOP, state -> state.is(Blocks.OAK_SLAB) && state.getValue(SlabBlock.TYPE) == SlabType.TOP,
                    "obere Halbplatte wurde nicht platziert");

            // C: Treppe nach Norden, Spieler schaut aber nach Osten -> darf NICHT platziert werden
            lookAndClick(context, server, new Vec3(3.5, Y + 0.4, -0.5));
            assertBlock(server, STAIRS_NORTH, BlockState::isAir, "Treppe in falscher Blickrichtung haette uebersprungen werden muessen");

            // D: schwebender Block ohne Nachbarflaeche -> darf NICHT platziert werden (kein Air-Place)
            lookAndClick(context, server, new Vec3(3.5, Y + 3.5, -1.5));
            assertBlock(server, FLOATING, BlockState::isAir, "schwebender Block haette uebersprungen werden muessen");

            context.takeScreenshot("easyplace2-world");
            System.out.println("[EP2-TEST] Alle Platzierungstests bestanden");

            testPrinterMenu(context);
            testPrinter(context, server);
            testScaffold(context, server);
            testWatchdog(context);
            testStandingInSchematic(context, server);
            testExcavate(context, server);
            testLanePath(context);
            testLaneExcavate(context, server);
            testMacro(context, server);
            testChatInvsee(context);
            testWater(context, server);
        }
    }

    /** Printer-Menue: jede wichtige Seite einmal oeffnen und fotografieren (Liste, Kacheln, Themes). */
    private static void testPrinterMenu(ClientGameTestContext context)
    {
        menuScreenshot(context, OptionEntry.Page.OVERVIEW, false, "easyplace2-printer-menu");
        menuScreenshot(context, OptionEntry.Page.TURN, false, "easyplace2-printer-options-list");
        menuScreenshot(context, OptionEntry.Page.TURN, true, "easyplace2-printer-options-grid");
        menuScreenshot(context, OptionEntry.Page.THEME, false, "easyplace2-printer-themes");
        menuScreenshot(context, OptionEntry.Page.KEYS, false, "easyplace2-printer-keys");
        menuScreenshot(context, OptionEntry.Page.INFO, false, "easyplace2-printer-info");
        menuScreenshot(context, OptionEntry.Page.INVSEE, false, "easyplace2-module-invsee");
        context.runOnClient(mc -> Ep2Configs.PRINTER_GRID_VIEW.setBooleanValue(false));
        System.out.println("[EP2-TEST] Printer-Menue ok");
    }

    private static void menuScreenshot(ClientGameTestContext context, OptionEntry.Page page, boolean grid, String name)
    {
        context.runOnClient(mc -> Ep2Configs.PRINTER_GRID_VIEW.setBooleanValue(grid));
        context.setScreen(() -> new PrinterScreen(null, page));
        context.waitForScreen(PrinterScreen.class);
        context.waitTicks(15);
        context.takeScreenshot(name);
        context.setScreen(() -> null);
        context.waitTicks(2);
    }

    /**
     * Printer ohne Baritone (alles in Reichweite): Die Hotbar ist voll mit Erde, die Bausteine liegen
     * im Inventar. Der Printer muss sie also wie ein Spieler ueber das Inventar holen, sich hindrehen
     * und normal klicken. Geprueft wird auf dem Vanilla-Server, auch die Richtung der Treppe.
     */
    private static void testPrinter(ClientGameTestContext context, TestDedicatedServerContext server)
    {
        // So verteilt, dass kein Blick auf einen Block durch einen anderen (Geister-)Block geht
        BlockPos stoneA = new BlockPos(-2, Y, -2);
        BlockPos stoneB = new BlockPos(-2, Y, 2);
        BlockPos stoneC = new BlockPos(1, Y, -2);
        BlockPos stairsWest = new BlockPos(-2, Y, 0);
        BlockPos min = new BlockPos(-2, Y, -2);
        BlockPos max = new BlockPos(1, Y, 2);

        server.runCommand(setblock(stoneA, "cobblestone"));
        server.runCommand(setblock(stoneB, "cobblestone"));
        server.runCommand(setblock(stoneC, "cobblestone"));
        server.runCommand(setblock(stairsWest, "oak_stairs[facing=west,half=bottom]"));
        context.waitTicks(20);

        context.runOnClient(mc ->
        {
            AreaSelection area = new AreaSelection();
            area.addSubRegionBox(new Box(min, max, "printer"), false);
            area.setExplicitOrigin(min);
            LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                    new LitematicaSchematic.SchematicSaveInfo(false, true), "printer", msg -> System.out.println("[EP2-TEST] " + msg));

            if (schematic == null)
            {
                throw new AssertionError("Printer-Schematic konnte nicht erstellt werden");
            }

            // Nur noch die Printer-Schematic aktiv lassen
            for (SchematicPlacement old : List.copyOf(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
            {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
            }

            SchematicPlacement placement = SchematicPlacement.createFor(schematic, min, "printer", true, true);
            DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        });

        server.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d air", min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
        context.waitFor(mc -> mc.level.getBlockState(stoneA).isAir() && mc.level.getBlockState(stairsWest).isAir());
        context.waitFor(mc -> SchematicWorldHandler.getSchematicWorld() != null &&
                              SchematicWorldHandler.getSchematicWorld().getBlockState(stairsWest).is(Blocks.OAK_STAIRS), 200);

        // Spieler zurueck in die Mitte, Blick nach Westen; Hotbar voll Erde, Bausteine nur im Inventar
        server.runCommand(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f 90 30", PLAYER_POS.x, PLAYER_POS.y, PLAYER_POS.z));
        server.runCommand("clear @a");

        for (int i = 0; i < 9; i++)
        {
            server.runCommand("item replace entity @a hotbar." + i + " with dirt 64");
        }

        // Bruchstein nur in einer Shulker im Inventar, Treppen nur in einer markierten Lager-Truhe
        BlockPos chest = new BlockPos(2, Y, 1);
        server.runCommand("give @a minecraft:shulker_box[minecraft:container=[{slot:0,item:{id:\"minecraft:cobblestone\",count:8}}]] 1");
        server.runCommand(setblock(chest, "chest{Items:[{Slot:0b,id:\"minecraft:oak_stairs\",count:4}]}"));
        context.waitTicks(20);
        context.runOnClient(mc -> StorageRegistry.INSTANCE.toggle(chest));

        context.runOnClient(mc -> PrinterController.INSTANCE.start());
        context.waitTicks(25);
        context.takeScreenshot("easyplace2-printer-running");

        try
        {
            context.waitFor(mc -> !PrinterController.INSTANCE.isRunning() ||
                                  (mc.level.getBlockState(stoneA).is(Blocks.COBBLESTONE) &&
                                   mc.level.getBlockState(stoneB).is(Blocks.COBBLESTONE) &&
                                   mc.level.getBlockState(stoneC).is(Blocks.COBBLESTONE) &&
                                   mc.level.getBlockState(stairsWest).is(Blocks.OAK_STAIRS)), 2400);
        }
        catch (AssertionError e)
        {
            context.takeScreenshot("easyplace2-printer-timeout");
            context.runOnClient(mc -> System.out.println("[EP2-TEST] Printer haengt: Zustand " + PrinterController.INSTANCE.getState() +
                    " (" + PrinterController.INSTANCE.getDetail() + "), platziert " + PrinterController.INSTANCE.getPlaced() +
                    ", Spieler " + mc.player.position() + " Blick " + mc.player.getYRot() + "/" + mc.player.getXRot() +
                    ", Fadenkreuz " + mc.hitResult + ", Hand " + mc.player.getMainHandItem()));
            throw e;
        }

        context.waitTicks(20);
        context.takeScreenshot("easyplace2-printer-world");
        context.runOnClient(mc -> PrinterController.INSTANCE.stop(null));

        assertBlock(server, stoneA, state -> state.is(Blocks.COBBLESTONE), "Printer hat Stein A nicht gesetzt");
        assertBlock(server, stoneB, state -> state.is(Blocks.COBBLESTONE), "Printer hat Stein B nicht gesetzt");
        assertBlock(server, stoneC, state -> state.is(Blocks.COBBLESTONE), "Printer hat Stein C nicht gesetzt");
        assertBlock(server, stairsWest, state -> state.is(Blocks.OAK_STAIRS) && state.getValue(StairBlock.FACING) == Direction.WEST,
                "Printer hat die Treppe nicht richtig herum gesetzt");
        // Material kam aus Shulker und Truhe; die Shulker ist wieder eingesammelt, nichts steht mehr herum
        int stairsLeft = server.computeOnServer(s -> ((Container) s.overworld().getBlockEntity(chest)).countItem(Items.OAK_STAIRS));
        int shulkers = server.computeOnServer(s -> s.getPlayerList().getPlayers().get(0).getInventory().countItem(Items.SHULKER_BOX));
        boolean shulkerStanding = server.computeOnServer(s ->
        {
            for (BlockPos p : BlockPos.betweenClosed(-2, Y, -2, 2, Y + 1, 2))
            {
                if (s.overworld().getBlockState(p).getBlock() instanceof ShulkerBoxBlock)
                {
                    return true;
                }
            }

            return false;
        });

        if (stairsLeft != 0 || shulkers < 1 || shulkerStanding)
        {
            String items = server.computeOnServer(s ->
            {
                StringBuilder sb = new StringBuilder();
                ServerPlayer p = s.getPlayerList().getPlayers().get(0);
                sb.append("Spieler ").append(p.position()).append(" Inventar:");

                for (int i = 0; i < p.getInventory().getContainerSize(); i++)
                {
                    if (!p.getInventory().getItem(i).isEmpty())
                    {
                        sb.append(' ').append(i).append('=').append(p.getInventory().getItem(i));
                    }
                }

                sb.append(" | Items am Boden:");

                for (ItemEntity e : s.overworld().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(16)))
                {
                    sb.append(' ').append(e.getItem()).append('@').append(e.position());
                }

                return sb.toString();
            });
            throw new AssertionError("Material holen: Treppen in Truhe " + stairsLeft + ", Shulker im Inventar " + shulkers +
                                     ", Shulker steht noch " + shulkerStanding + " | " + items);
        }

        System.out.println("[EP2-TEST] ok: Material aus Shulker und Lager geholt, Shulker wieder eingesammelt");
        menuScreenshot(context, OptionEntry.Page.MATERIALS, false, "easyplace2-printer-materials");
        menuScreenshot(context, OptionEntry.Page.STORAGE, false, "easyplace2-printer-storage");
        System.out.println("[EP2-TEST] Printer ok");
    }

    /**
     * Geruest: Ein Geruest-Block steht genau dort, wo die Schematic Bruchstein will, ein zweiter daneben.
     * Der Printer muss den ersten abbauen und durch Bruchstein ersetzen, den zweiten abbauen und erst
     * danach "fertig" melden.
     */
    private static void testScaffold(ClientGameTestContext context, TestDedicatedServerContext server)
    {
        BlockPos inSchematic = new BlockPos(0, Y, -2);
        BlockPos outside = new BlockPos(1, Y, 2);

        server.runCommand(setblock(inSchematic, "cobblestone"));
        context.waitFor(mc -> mc.level.getBlockState(inSchematic).is(Blocks.COBBLESTONE));

        context.runOnClient(mc ->
        {
            AreaSelection area = new AreaSelection();
            area.addSubRegionBox(new Box(inSchematic, inSchematic, "scaffold"), false);
            area.setExplicitOrigin(inSchematic);
            LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                    new LitematicaSchematic.SchematicSaveInfo(false, true), "scaffold", msg -> System.out.println("[EP2-TEST] " + msg));

            if (schematic == null)
            {
                throw new AssertionError("Geruest-Schematic konnte nicht erstellt werden");
            }

            for (SchematicPlacement old : List.copyOf(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
            {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
            }

            DataManager.getSchematicPlacementManager().addSchematicPlacement(
                    SchematicPlacement.createFor(schematic, inSchematic, "scaffold", true, true), false);
        });

        // Wie von Baritone gesetzt: Erde an beiden Stellen, beide als Geruest gemerkt. Neben der Schematic ist
        // die Erde schon zu Gras geworden (passiert von selbst), das muss trotzdem als Geruest gelten.
        server.runCommand(setblock(inSchematic, "dirt"));
        server.runCommand(setblock(outside, "grass_block"));
        server.runCommand(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f 180 30", PLAYER_POS.x, PLAYER_POS.y, PLAYER_POS.z));
        server.runCommand("clear @a");
        server.runCommand("give @a cobblestone 4");
        context.waitFor(mc -> mc.level.getBlockState(inSchematic).is(Blocks.DIRT) && mc.level.getBlockState(outside).is(Blocks.GRASS_BLOCK));
        context.waitFor(mc -> SchematicWorldHandler.getSchematicWorld() != null &&
                              SchematicWorldHandler.getSchematicWorld().getBlockState(inSchematic).is(Blocks.COBBLESTONE), 200);
        context.runOnClient(mc ->
        {
            ScaffoldTracker.INSTANCE.add(inSchematic);
            ScaffoldTracker.INSTANCE.add(outside);
            PrinterController.INSTANCE.start();
        });

        try
        {
            context.waitFor(mc -> !PrinterController.INSTANCE.isRunning(), 1200);
        }
        catch (AssertionError e)
        {
            context.takeScreenshot("easyplace2-scaffold-timeout");
            context.runOnClient(mc -> System.out.println("[EP2-TEST] Geruest haengt: Zustand " + PrinterController.INSTANCE.getState() +
                    " (" + PrinterController.INSTANCE.getDetail() + "), Geruest " + ScaffoldTracker.INSTANCE.positions() +
                    ", Spieler " + mc.player.position() + ", Hand " + mc.player.getMainHandItem()));
            context.runOnClient(mc -> PrinterController.INSTANCE.stop(null));
            throw e;
        }

        assertBlock(server, inSchematic, state -> state.is(Blocks.COBBLESTONE), "Geruest in der Schematic wurde nicht ersetzt");
        assertBlock(server, outside, BlockState::isAir, "Geruest neben der Schematic wurde nicht abgebaut");
        int left = context.computeOnClient(mc -> ScaffoldTracker.INSTANCE.count());

        if (left != 0)
        {
            throw new AssertionError("Geruest-Liste ist nicht leer: " + left);
        }

        System.out.println("[EP2-TEST] Geruest ok");
    }

    /** Watchdog: ohne Fortschritt kommen die Stufen in der richtigen Reihenfolge und Zeit, danach nichts mehr. */
    private static void testWatchdog(ClientGameTestContext context)
    {
        String result = context.computeOnClient(mc ->
        {
            int before = Ep2Configs.PRINTER_STUCK_SECONDS.getIntegerValue();
            Ep2Configs.PRINTER_STUCK_SECONDS.setIntegerValue(20);

            try
            {
                Watchdog dog = new Watchdog();
                StringBuilder sb = new StringBuilder();

                for (int tick = 1; tick <= 20 * 200; tick++)
                {
                    Watchdog.Level level = dog.tick();

                    if (level != null)
                    {
                        sb.append(level).append('@').append(tick).append(' ');
                    }

                    // Fortschritt setzt alles zurueck
                    if (tick == 20 * 100)
                    {
                        dog.progress();
                        sb.append("| ");
                    }
                }

                return sb.toString().trim();
            }
            finally
            {
                Ep2Configs.PRINTER_STUCK_SECONDS.setIntegerValue(before);
            }
        });

        String expected = "RETRY@400 ESCAPE@600 CLEAR_SCAFFOLD@800 GIVE_UP@1000 | RETRY@2400 ESCAPE@2600 CLEAR_SCAFFOLD@2800 GIVE_UP@3000";

        if (!expected.equals(result))
        {
            throw new AssertionError("Watchdog-Stufen falsch: " + result);
        }

        System.out.println("[EP2-TEST] Watchdog ok: " + result);
    }

    /**
     * Der Spieler steht selbst in der Schematic (an seinem Kopf fehlt ein Block). Easy Place 2.0 muss
     * trotzdem den Block setzen, auf den er schaut, und nicht den an seinem Kopf. Danach: Ausserhalb der
     * Schematic laesst sich mit Easy Place 2.0 kein Block setzen, ausser die Sperre ist aus.
     */
    private static void testStandingInSchematic(ClientGameTestContext context, TestDedicatedServerContext server)
    {
        BlockPos head = new BlockPos(0, Y + 1, 0);
        BlockPos target = new BlockPos(0, Y, 2);
        BlockPos front = new BlockPos(0, Y, 1);
        BlockPos min = new BlockPos(0, Y, 0);
        BlockPos max = new BlockPos(0, Y + 1, 2);
        BlockPos elsewhere = new BlockPos(-1, Y, -1);

        server.runCommand(setblock(head, "stone"));
        server.runCommand(setblock(target, "cobblestone"));
        server.runCommand(setblock(front, "stone"));
        context.waitFor(mc -> mc.level.getBlockState(head).is(Blocks.STONE) && mc.level.getBlockState(target).is(Blocks.COBBLESTONE) &&
                              mc.level.getBlockState(front).is(Blocks.STONE));

        context.runOnClient(mc ->
        {
            AreaSelection area = new AreaSelection();
            area.addSubRegionBox(new Box(min, max, "inside"), false);
            area.setExplicitOrigin(min);
            LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                    new LitematicaSchematic.SchematicSaveInfo(false, true), "inside", msg -> System.out.println("[EP2-TEST] " + msg));

            if (schematic == null)
            {
                throw new AssertionError("Schematic konnte nicht erstellt werden");
            }

            for (SchematicPlacement old : List.copyOf(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
            {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
            }

            DataManager.getSchematicPlacementManager().addSchematicPlacement(SchematicPlacement.createFor(schematic, min, "inside", true, true), false);
        });

        server.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d air", min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
        server.runCommand("clear @a");
        server.runCommand("give @a cobblestone 4");
        context.waitFor(mc -> mc.level.getBlockState(head).isAir() && mc.level.getBlockState(target).isAir());
        context.waitFor(mc -> SchematicWorldHandler.getSchematicWorld() != null &&
                              SchematicWorldHandler.getSchematicWorld().getBlockState(head).is(Blocks.STONE), 200);
        context.runOnClient(mc ->
        {
            Ep2Configs.EASY_PLACE_2.setBooleanValue(true);
            Ep2Configs.STRICT_CROSSHAIR.setBooleanValue(true);
            Ep2Configs.RESTRICT_PLACEMENT.setBooleanValue(true);
        });

        // Der Kopf steckt im Geisterblock "Stein"; geschaut wird auf den Boden unter dem Ziel. Dabei liegt
        // noch ein fehlender Block davor: gesetzt werden muss der hinterste
        lookAndClick(context, server, new Vec3(0.5, Y, 2.5));
        assertBlock(server, target, state -> state.is(Blocks.COBBLESTONE), "Hinterster Block im Blick wurde nicht gesetzt (Kopf steckt in der Schematic)");
        assertBlock(server, head, BlockState::isAir, "Easy Place 2.0 hat am eigenen Kopf gebaut");
        assertBlock(server, front, BlockState::isAir, "Easy Place 2.0 hat den vorderen statt den hintersten Block genommen");

        // Ausserhalb der Schematic: mit Sperre kein Block, ohne Sperre ganz normal
        server.runCommand("item replace entity @a hotbar.0 with dirt 4");
        context.waitTicks(5);
        context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        lookAndClick(context, server, new Vec3(-0.5, Y, -0.5));
        assertBlock(server, elsewhere, BlockState::isAir, "Sperre: Block wurde ausserhalb der Schematic gesetzt");

        context.runOnClient(mc -> Ep2Configs.RESTRICT_PLACEMENT.setBooleanValue(false));
        lookAndClick(context, server, new Vec3(-0.5, Y, -0.5));
        assertBlock(server, elsewhere, state -> state.is(Blocks.DIRT), "Ohne Sperre muss ein normaler Klick einen Block setzen");
        context.runOnClient(mc -> Ep2Configs.RESTRICT_PLACEMENT.setBooleanValue(true));
        server.runCommand(setblock(elsewhere, "air"));

        System.out.println("[EP2-TEST] In der Schematic stehen, hinterster Block und Sperre ok");
    }

    /**
     * Aushoehlen: Wo die Schematic Luft will, stehen zwei Erdbloecke uebereinander, die weg muessen. An der
     * Stelle des Bruchsteins steht Stein (falscher Block), der mit "Falsche Bloecke ersetzen" abgebaut und
     * durch Bruchstein ersetzt werden muss.
     */
    private static void testExcavate(ClientGameTestContext context, TestDedicatedServerContext server)
    {
        BlockPos build = new BlockPos(-1, Y, 0);
        BlockPos airLow = new BlockPos(-1, Y, 1);
        BlockPos airHigh = new BlockPos(-1, Y + 1, 1);
        BlockPos min = new BlockPos(-1, Y, 0);
        BlockPos max = new BlockPos(-1, Y + 1, 1);

        server.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d air", min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
        server.runCommand(setblock(build, "cobblestone"));
        context.waitFor(mc -> mc.level.getBlockState(build).is(Blocks.COBBLESTONE) && mc.level.getBlockState(airLow).isAir());

        context.runOnClient(mc ->
        {
            AreaSelection area = new AreaSelection();
            area.addSubRegionBox(new Box(min, max, "dig"), false);
            area.setExplicitOrigin(min);
            LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                    new LitematicaSchematic.SchematicSaveInfo(false, true), "dig", msg -> System.out.println("[EP2-TEST] " + msg));

            if (schematic == null)
            {
                throw new AssertionError("Aushoehlen-Schematic konnte nicht erstellt werden");
            }

            for (SchematicPlacement old : List.copyOf(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
            {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
            }

            DataManager.getSchematicPlacementManager().addSchematicPlacement(SchematicPlacement.createFor(schematic, min, "dig", true, true), false);
        });

        // Jetzt steht dort, wo Luft sein soll, Erde, und statt Bruchstein steht Stein
        server.runCommand(setblock(build, "stone"));
        server.runCommand(setblock(airLow, "dirt"));
        server.runCommand(setblock(airHigh, "dirt"));
        server.runCommand(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f 90 30", PLAYER_POS.x, PLAYER_POS.y, PLAYER_POS.z));
        server.runCommand("clear @a");
        server.runCommand("give @a cobblestone 4");
        server.runCommand("give @a wooden_pickaxe");
        context.waitFor(mc -> mc.level.getBlockState(build).is(Blocks.STONE) && mc.level.getBlockState(airHigh).is(Blocks.DIRT));
        context.waitFor(mc -> SchematicWorldHandler.getSchematicWorld() != null &&
                              SchematicWorldHandler.getSchematicWorld().getBlockState(build).is(Blocks.COBBLESTONE), 200);
        context.runOnClient(mc ->
        {
            Ep2Configs.PRINTER_EXCAVATE.setBooleanValue(true);
            Ep2Configs.PRINTER_REPLACE_WRONG.setBooleanValue(true);
            Ep2Configs.PRINTER_RUN_IN_MENUS.setBooleanValue(true);
            PrinterController.INSTANCE.start();
        });

        // Der Spieler drueckt ESC: der Printer soll hinter dem Pause-Menue weiterarbeiten
        context.setScreen(() -> new PauseScreen(true));

        try
        {
            context.waitFor(mc -> !PrinterController.INSTANCE.isRunning(), 1800);
        }
        catch (AssertionError e)
        {
            context.takeScreenshot("easyplace2-excavate-timeout");
            context.runOnClient(mc -> System.out.println("[EP2-TEST] Aushoehlen haengt: Zustand " + PrinterController.INSTANCE.getState() +
                    " (" + PrinterController.INSTANCE.getDetail() + "), Spieler " + mc.player.position() + ", Hand " + mc.player.getMainHandItem()));
            context.runOnClient(mc -> PrinterController.INSTANCE.stop(null));
            throw e;
        }
        finally
        {
            context.runOnClient(mc -> Ep2Configs.PRINTER_REPLACE_WRONG.setBooleanValue(false));
        }

        boolean menuStillOpen = context.computeOnClient(mc -> mc.screen instanceof PauseScreen);
        context.setScreen(() -> null);

        if (!menuStillOpen)
        {
            throw new AssertionError("Das Pause-Menue wurde vom Printer geschlossen");
        }

        assertBlock(server, airHigh, BlockState::isAir, "Aushoehlen: oberer Block wurde nicht abgebaut");
        assertBlock(server, airLow, BlockState::isAir, "Aushoehlen: unterer Block wurde nicht abgebaut");
        assertBlock(server, build, state -> state.is(Blocks.COBBLESTONE), "Falscher Block wurde nicht ersetzt");
        System.out.println("[EP2-TEST] Aushoehlen und falsche Bloecke ersetzen (hinter dem Pause-Menue) ok");
    }

    /**
     * Selbst abbauen muss immer gehen, auch wenn der Printer noch nie etwas abgebaut hat und gerade ein
     * Menue offen war (frueher war das Abbauen dann gesperrt, bis der Printer einmal abgebaut hatte).
     */
    private static void testManualMining(ClientGameTestContext context, TestDedicatedServerContext server)
    {
        BlockPos dirt = new BlockPos(0, Y, -1);
        server.runCommand(setblock(dirt, "dirt"));
        server.runCommand("clear @a");
        context.waitFor(mc -> mc.level.getBlockState(dirt).is(Blocks.DIRT));

        // ESC auf und wieder zu
        context.setScreen(() -> new PauseScreen(true));
        context.waitTicks(5);
        context.setScreen(() -> null);
        context.waitTicks(5);

        Vec3 aim = new Vec3(0.5, Y + 0.5, -0.5);
        Vec3 d = aim.subtract(EYE);
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        double pitch = Math.toDegrees(Math.atan2(-d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        server.runCommand(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %.2f %.2f", PLAYER_POS.x, PLAYER_POS.y, PLAYER_POS.z, yaw, pitch));
        context.waitTicks(10);

        // Linke Maustaste halten wie ein Spieler (Erde mit der Hand: unter einer Sekunde)
        context.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        context.waitTicks(40);
        context.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        context.waitTicks(5);

        assertBlock(server, dirt, BlockState::isAir, "Selbst abbauen geht nicht (Mod sperrt das Abbauen)");
        System.out.println("[EP2-TEST] Selbst abbauen ok");
    }

    /** Bahnen: geradeaus, Kurve, daneben zurueck; mit Star-Tool 3 breit und 3 Bahnen Abstand. */
    private static void testLanePath(ClientGameTestContext context)
    {
        String normal = describe(LanePath.build(0, 3, 0, 2, 0, 1, 0, new Vec3(0.5, 0, 0.5)));
        String expected = "0,0E 1,0E 2,0E 3,0E 3,1S 2,1W 1,1W 0,1W 0,2S 1,2E 2,2E 3,2E";

        if (!expected.equals(normal))
        {
            throw new AssertionError("Bahnen falsch: " + normal);
        }

        String star = describe(LanePath.build(0, 5, 0, 5, 0, 3, 1, new Vec3(0.5, 0, 0.5)));
        String expectedStar = "0,1E 1,1E 2,1E 3,1E 4,1E 5,1E 5,2S 5,3S 5,4S 4,4W 3,4W 2,4W 1,4W 0,4W";

        if (!expectedStar.equals(star))
        {
            throw new AssertionError("Star-Bahnen falsch: " + star);
        }

        System.out.println("[EP2-TEST] Bahnen ok");
    }

    private static String describe(List<LanePath.Step> path)
    {
        StringBuilder sb = new StringBuilder();

        for (LanePath.Step st : path)
        {
            sb.append(sb.isEmpty() ? "" : " ").append(st.pos().getX()).append(',').append(st.pos().getZ())
              .append(st.dir().getName().toUpperCase(Locale.ROOT).charAt(0));
        }

        return sb.toString();
    }

    /**
     * Aushoehlen in Bahnen ohne Baritone: zwei 8 Bloecke lange, 2 hohe Bahnen aus Erde, wo die Schematic Luft
     * will. Der Printer muss sie Gang fuer Gang abbauen und dabei selbst geradeaus hineinlaufen.
     */
    private static void testLaneExcavate(ClientGameTestContext context, TestDedicatedServerContext server)
    {
        BlockPos min = new BlockPos(5, Y, -3);
        BlockPos max = new BlockPos(12, Y + 1, -1);
        BlockPos keep = new BlockPos(12, Y, -1);

        server.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d air", min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
        server.runCommand(setblock(keep, "stone"));
        context.waitFor(mc -> mc.level.getBlockState(keep).is(Blocks.STONE));

        context.runOnClient(mc ->
        {
            AreaSelection area = new AreaSelection();
            area.addSubRegionBox(new Box(min, max, "lanes"), false);
            area.setExplicitOrigin(min);
            LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                    new LitematicaSchematic.SchematicSaveInfo(false, true), "lanes", msg -> System.out.println("[EP2-TEST] " + msg));

            if (schematic == null)
            {
                throw new AssertionError("Bahnen-Schematic konnte nicht erstellt werden");
            }

            for (SchematicPlacement old : List.copyOf(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
            {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
            }

            DataManager.getSchematicPlacementManager().addSchematicPlacement(SchematicPlacement.createFor(schematic, min, "lanes", true, true), false);
        });

        // Zwei Bahnen voll Erde (z = -3 und -2), die dritte Reihe bleibt frei
        server.runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d dirt", min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), -2));
        server.runCommand("clear @a");
        server.runCommand("tp @a 4.5 " + Y + " -2.5 -90 0");
        context.waitFor(mc -> mc.level.getBlockState(new BlockPos(12, Y + 1, -2)).is(Blocks.DIRT));
        context.waitFor(mc -> SchematicWorldHandler.getSchematicWorld() != null &&
                              SchematicWorldHandler.getSchematicWorld().getBlockState(keep).is(Blocks.STONE), 200);
        context.runOnClient(mc ->
        {
            Ep2Configs.PRINTER_EXCAVATE.setBooleanValue(true);
            Ep2Configs.PRINTER_LANE_MODE.setBooleanValue(true);
            Ep2Configs.PRINTER_STAR_TOOL.setBooleanValue(false);
            PrinterController.INSTANCE.start();
        });

        double[] maxX = { 0 };

        try
        {
            // Nebenbei merken, wie weit der Spieler in die Bahn hineingelaufen ist
            context.waitFor(mc ->
            {
                maxX[0] = Math.max(maxX[0], mc.player.getX());
                return !PrinterController.INSTANCE.isRunning();
            }, 3000);
        }
        catch (AssertionError e)
        {
            context.takeScreenshot("easyplace2-lanes-timeout");
            context.runOnClient(mc -> System.out.println("[EP2-TEST] Bahnen haengen: Zustand " + PrinterController.INSTANCE.getState() +
                    " (" + PrinterController.INSTANCE.getDetail() + "), Spieler " + mc.player.position()));
            context.runOnClient(mc -> PrinterController.INSTANCE.stop(null));
            throw e;
        }

        int left = server.computeOnServer(s ->
        {
            int n = 0;

            for (BlockPos p : BlockPos.betweenClosed(5, Y, -3, 12, Y + 1, -2))
            {
                if (!s.overworld().getBlockState(p).isAir())
                {
                    n++;
                }
            }

            return n;
        });
        double x = maxX[0];

        if (left != 0)
        {
            throw new AssertionError("Bahnen: " + left + " Bloecke wurden nicht abgebaut");
        }

        if (x < 7.0)
        {
            throw new AssertionError("Bahnen: der Printer ist nicht in die Bahn gelaufen (weitestes x = " + x + ")");
        }

        context.takeScreenshot("easyplace2-lanes-done");
        System.out.println("[EP2-TEST] Aushoehlen in Bahnen ok (weitestes x = " + x + ")");
    }

    /**
     * Makros: eine kurze Aufnahme (Blick und Item werden erkannt), dann ein Baum-Makro abspielen:
     * Axt nehmen, auf den Stamm schauen, abbauen bis kein Holz mehr da ist (auch die Bloecke weiter oben),
     * Setzling nehmen, auf den Boden schauen, setzen und warten, bis der Setzling im Fadenkreuz ist.
     */
    private static void testMacro(ClientGameTestContext context, TestDedicatedServerContext server)
    {
        context.runOnClient(mc ->
        {
            PrinterController.INSTANCE.stop(null);

            for (SchematicPlacement old : List.copyOf(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
            {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
            }
        });

        BlockPos ground = new BlockPos(-4, Y - 1, 4);
        BlockPos[] logs = { new BlockPos(-4, Y, 4), new BlockPos(-4, Y + 1, 4), new BlockPos(-4, Y + 2, 4) };
        server.runCommand("fill -6 " + Y + " 1 -2 " + (Y + 4) + " 6 air");
        server.runCommand(setblock(ground, "grass_block"));

        for (BlockPos log : logs)
        {
            server.runCommand(setblock(log, "oak_log"));
        }

        server.runCommand("clear @a");
        server.runCommand("give @a iron_axe");
        server.runCommand("give @a oak_sapling 4");
        server.runCommand("tp @a -3.5 " + Y + " 2.5 0 0");
        context.waitFor(mc -> mc.level.getBlockState(logs[2]).is(Blocks.OAK_LOG) && mc.player.getInventory().countItem(Items.OAK_SAPLING) == 4);
        context.waitTicks(10);

        // Aufnahme: anderes Fach nehmen und anders hinschauen
        context.runOnClient(mc -> MacroRecorder.INSTANCE.start(mc));
        context.waitTicks(10);
        context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot((mc.player.getInventory().getSelectedSlot() + 1) % 9));
        context.waitTicks(5);
        server.runCommand("tp @a -3.5 " + Y + " 2.5 40 20");
        context.waitTicks(20);
        int[] recorded = new int[3];
        context.runOnClient(mc ->
        {
            MacroRecorder.INSTANCE.stop(mc);
            Macro macro = MacroStore.INSTANCE.selected();
            recorded[0] = macro == null ? 0 : macro.steps.size();
            recorded[1] = macro == null ? 0 : (int) macro.steps.stream().filter(s -> s.type == MacroStep.Type.LOOK).count();
            recorded[2] = macro == null ? 0 : (int) macro.steps.stream().filter(s -> s.type == MacroStep.Type.SELECT).count();
        });

        // Start: Blick + Fach, dazu der neue Blick und das neue Fach
        if (recorded[1] < 2 || recorded[2] < 2)
        {
            throw new AssertionError("Makro-Aufnahme: " + recorded[0] + " Schritte, " + recorded[1] + "x Blick, " + recorded[2] + "x Item");
        }

        System.out.println("[EP2-TEST] Makro-Aufnahme ok (" + recorded[0] + " Schritte)");

        Vec3 eye = new Vec3(-3.5, Y + 1.62, 2.5);
        Macro tree = new Macro("Baum", List.of(
                MacroStep.select("minecraft:iron_axe", -1),
                lookAt(eye, new Vec3(-3.5, Y + 0.5, 4.0)),
                MacroStep.mine(MacroStep.MineMode.WHILE_BLOCK, "minecraft:oak_log"),
                MacroStep.select("minecraft:oak_sapling", -1),
                lookAt(eye, new Vec3(-3.5, Y, 4.5)),
                MacroStep.use(),
                MacroStep.waitForBlock("minecraft:oak_sapling", 60),
                MacroStep.wait(5)));
        tree.repeats = 1;
        server.runCommand("tp @a -3.5 " + Y + " 2.5 0 0");
        context.waitTicks(10);
        context.runOnClient(mc ->
        {
            MacroStore.INSTANCE.add(tree);
            MacroPlayer.INSTANCE.play(mc, tree);
        });

        try
        {
            context.waitFor(mc -> !MacroPlayer.INSTANCE.isPlaying(), 1200);
        }
        catch (AssertionError e)
        {
            context.takeScreenshot("easyplace2-macro-timeout");
            context.runOnClient(mc -> System.out.println("[EP2-TEST] Makro haengt bei Schritt " + MacroPlayer.INSTANCE.stepIndex() +
                    ", Fadenkreuz " + mc.hitResult.getLocation()));
            context.runOnClient(mc -> MacroPlayer.INSTANCE.stop(mc, null));
            throw e;
        }

        context.waitTicks(10);

        for (BlockPos log : logs)
        {
            assertBlock(server, log, state -> !state.is(Blocks.OAK_LOG), "Makro: Holz wurde nicht ganz abgebaut");
        }

        assertBlock(server, logs[0], state -> state.is(Blocks.OAK_SAPLING), "Makro: Setzling wurde nicht gesetzt");
        context.takeScreenshot("easyplace2-macro-done");
        menuScreenshot(context, OptionEntry.Page.MACROS, false, "easyplace2-module-macros");
        System.out.println("[EP2-TEST] Makro (Baum faellen, Setzling setzen) ok");
    }

    private static MacroStep lookAt(Vec3 eye, Vec3 point)
    {
        Vec3 d = point.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return MacroStep.look(yaw, pitch);
    }

    /**
     * Mittelklick im Chat: bei einer Teleport-Anfrage der Spieler hinter /tpaccept, bei normalen Nachrichten
     * der Absender. Die Zeilen werden genau dort angeklickt, wo Minecraft sie zeichnet.
     */
    private static void testChatInvsee(ClientGameTestContext context)
    {
        context.runOnClient(mc ->
        {
            mc.gui.getChat().clearMessages(false);
            mc.gui.getChat().addMessage(Component.literal("[DevV2] 1tsFufu: ?rtp"));
            mc.gui.getChat().addMessage(Component.literal("[HugoSMP] MqanYT hat dir eine Teleportations-Anfrage gesendet!"));
            mc.gui.getChat().addMessage(Component.literal("Annehmen - ").append(Component.literal("/tpaccept MqanYT tpa")
                    .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand("/tpaccept MqanYT tpa")))));
            mc.gui.getChat().addMessage(Component.literal("Ablehnen - /tpdeny MqanYT tpa"));
        });
        context.setScreen(() -> new ChatScreen("", false));
        context.waitForScreen(ChatScreen.class);
        context.waitTicks(5);
        context.takeScreenshot("easyplace2-chat-invsee");

        // Zeile 0 = unterste (neueste) Nachricht; die lange TPA-Zeile darf umgebrochen sein
        List<String> got = new java.util.ArrayList<>();
        String[] clicked = new String[1];

        context.runOnClient(mc ->
        {
            ChatComponentAccessor chat = (ChatComponentAccessor) mc.gui.getChat();
            double lineH = chat.easyplace2$lineHeight() * chat.easyplace2$scale();
            int bottom = mc.getWindow().getGuiScaledHeight() - 40;
            int rows = chat.easyplace2$lines().size();

            for (int i = 0; i < rows; i++)
            {
                got.add(ChatInvsee.nameAt(mc, 12, bottom - (i + 0.5) * lineH));
            }

            // Ueber den echten Klick-Weg (Mixin im Chat-Fenster), oberste Zeile = Chat-Nachricht von 1tsFufu
            ChatInvsee.lastName = null;
            mc.screen.mouseClicked(new MouseButtonEvent(12, bottom - (rows - 0.5) * lineH, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, 0)), false);
            clicked[0] = ChatInvsee.lastName;
        });
        context.setScreen(() -> null);
        System.out.println("[EP2-TEST] Chat-Zeilen -> " + got);

        if (got.size() < 4 || !"1tsFufu".equals(got.get(got.size() - 1)) ||
            got.subList(0, got.size() - 1).stream().anyMatch(n -> !"MqanYT".equals(n)))
        {
            throw new AssertionError("Chat-Invsee: falsche Namen " + got + " (erwartet MqanYT ..., 1tsFufu)");
        }

        if (!"1tsFufu".equals(clicked[0]))
        {
            throw new AssertionError("Chat-Invsee: Mittelklick ergab " + clicked[0] + " statt 1tsFufu");
        }

        System.out.println("[EP2-TEST] Mittelklick im Chat ok (TPA -> MqanYT, Nachricht -> 1tsFufu)");
    }

    /**
     * Wasser: ein Becken (3x3, Steinwand) wird mit nur zwei Wassereimern gefuellt (diagonal setzen, dann aus der
     * entstandenen unendlichen Quelle nachfuellen). Ein zweites Wasser ohne Wand darf nicht gesetzt werden,
     * weil es auslaufen wuerde.
     */
    private static void testWater(ClientGameTestContext context, TestDedicatedServerContext server)
    {
        BlockPos basinMin = new BlockPos(20, Y - 1, 10);
        BlockPos basinMax = new BlockPos(24, Y, 14);
        BlockPos open = new BlockPos(26, Y, 12);

        server.runCommand(String.format(Locale.ROOT, "fill 18 %d 8 29 %d 16 air", Y, Y + 3));
        server.runCommand(String.format(Locale.ROOT, "fill 20 %d 10 24 %d 14 stone", Y, Y));
        server.runCommand(String.format(Locale.ROOT, "fill 21 %d 11 23 %d 13 water", Y, Y));
        // Das offene Wasser kurz mit Glas einsperren (das Glas liegt ausserhalb der Schematic)
        server.runCommand(String.format(Locale.ROOT, "fill 25 %d 11 27 %d 13 glass", Y, Y));
        server.runCommand(setblock(open, "water"));
        context.waitFor(mc -> mc.level.getBlockState(open).is(Blocks.WATER) && mc.level.getBlockState(new BlockPos(22, Y, 12)).is(Blocks.WATER));

        context.runOnClient(mc ->
        {
            PrinterController.INSTANCE.stop(null);
            AreaSelection area = new AreaSelection();
            area.addSubRegionBox(new Box(basinMin, basinMax, "basin"), false);
            area.addSubRegionBox(new Box(open, open, "open"), false);
            area.setExplicitOrigin(basinMin);
            LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                    new LitematicaSchematic.SchematicSaveInfo(false, true), "water", msg -> System.out.println("[EP2-TEST] " + msg));

            if (schematic == null)
            {
                throw new AssertionError("Wasser-Schematic konnte nicht erstellt werden");
            }

            for (SchematicPlacement old : List.copyOf(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
            {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
            }

            DataManager.getSchematicPlacementManager().addSchematicPlacement(SchematicPlacement.createFor(schematic, basinMin, "water", true, true), false);
        });

        server.runCommand(String.format(Locale.ROOT, "fill 21 %d 11 23 %d 13 air", Y, Y));
        server.runCommand(String.format(Locale.ROOT, "fill 25 %d 11 27 %d 13 air", Y, Y));
        server.runCommand("clear @a");
        server.runCommand("give @a water_bucket 2");
        // Auf der Wand stehen und ins Becken schauen
        server.runCommand(String.format(Locale.ROOT, "tp @a 22.5 %d 10.5 0 60", Y + 1));
        context.waitFor(mc -> mc.level.getBlockState(new BlockPos(22, Y, 12)).isAir() && mc.level.getBlockState(open).isAir() &&
                              mc.player.getInventory().countItem(Items.WATER_BUCKET) == 2);
        context.waitFor(mc -> SchematicWorldHandler.getSchematicWorld() != null &&
                              SchematicWorldHandler.getSchematicWorld().getBlockState(new BlockPos(22, Y, 12)).is(Blocks.WATER), 200);

        double[] margin = new double[1];
        context.runOnClient(mc ->
        {
            margin[0] = Ep2Configs.PRINTER_REACH_MARGIN.getDoubleValue();
            Ep2Configs.PRINTER_REACH_MARGIN.setDoubleValue(0.0);
            Ep2Configs.PRINTER_LANE_MODE.setBooleanValue(false);
            Ep2Configs.PRINTER_WATER.setBooleanValue(true);
            PrinterController.INSTANCE.start();
        });

        try
        {
            context.waitFor(mc -> !PrinterController.INSTANCE.isRunning(), 2400);
        }
        catch (AssertionError e)
        {
            context.takeScreenshot("easyplace2-water-timeout");
            context.runOnClient(mc -> System.out.println("[EP2-TEST] Wasser haengt: Zustand " + PrinterController.INSTANCE.getState() +
                    " (" + PrinterController.INSTANCE.getDetail() + ")"));
            context.runOnClient(mc -> PrinterController.INSTANCE.stop(null));
            throw e;
        }
        finally
        {
            context.runOnClient(mc -> Ep2Configs.PRINTER_REACH_MARGIN.setDoubleValue(margin[0]));
        }

        context.waitTicks(20);
        int filled = server.computeOnServer(s ->
        {
            int n = 0;

            for (BlockPos p : BlockPos.betweenClosed(21, Y, 11, 23, Y, 13))
            {
                BlockState state = s.overworld().getBlockState(p);

                if (state.is(Blocks.WATER) && state.getFluidState().isSource())
                {
                    n++;
                }
            }

            return n;
        });

        context.takeScreenshot("easyplace2-water-done");

        if (filled != 9)
        {
            throw new AssertionError("Wasser: nur " + filled + " von 9 Feldern im Becken gefuellt");
        }

        assertBlock(server, open, BlockState::isAir, "Wasser ohne Wand haette nicht gesetzt werden duerfen (laeuft aus)");
        assertBlock(server, open.east(), state -> state.getFluidState().isEmpty(), "Wasser ist ausgelaufen");
        assertBlock(server, open.west(), state -> state.getFluidState().isEmpty(), "Wasser ist ausgelaufen");
        System.out.println("[EP2-TEST] Wasser ok: Becken mit 2 Eimern gefuellt, offenes Wasser nicht gesetzt");
    }

    /** Oeffnet Litematicas Konfigurationsmenue und wechselt in den Reiter "Easy Place 2.0". */
    private static void testConfigTab(ClientGameTestContext context)
    {
        context.setScreen(GuiConfigs::new);
        context.waitForScreen(GuiConfigs.class);
        context.runOnClient(mc -> ((Ep2ConfigTab.Access) mc.screen).easyplace2$openTab());
        context.waitTicks(2);

        List<GuiConfigsBase.ConfigOptionWrapper> configs = context.computeOnClient(mc -> ((GuiConfigs) mc.screen).getConfigs());

        if (configs.size() != Ep2Configs.OPTIONS.size() || configs.get(0).getConfig() != Ep2Configs.EASY_PLACE_2)
        {
            throw new AssertionError("Reiter 'Easy Place 2.0' zeigt nicht die eigenen Optionen");
        }

        context.takeScreenshot("easyplace2-config-tab");
        context.setScreen(() -> null);
        System.out.println("[EP2-TEST] Konfigurationsreiter ok");
    }

    /** Spieler per Teleport auf ein Ziel ausrichten (echte Blickrichtung) und einmal normal rechtsklicken. */
    private static void lookAndClick(ClientGameTestContext context, TestDedicatedServerContext server, Vec3 aim)
    {
        Vec3 d = aim.subtract(EYE);
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        double pitch = Math.toDegrees(Math.atan2(-d.y, Math.sqrt(d.x * d.x + d.z * d.z)));

        server.runCommand(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %.2f %.2f", PLAYER_POS.x, PLAYER_POS.y, PLAYER_POS.z, yaw, pitch));
        context.waitTicks(10);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        context.waitTicks(10);
    }

    private static void assertBlock(TestDedicatedServerContext server, BlockPos pos, java.util.function.Predicate<BlockState> check, String message)
    {
        BlockState state = server.computeOnServer(s -> s.overworld().getBlockState(pos));

        if (!check.test(state))
        {
            throw new AssertionError(message + " (Server hat bei " + pos.toShortString() + ": " + state + ")");
        }

        System.out.println("[EP2-TEST] ok: " + pos.toShortString() + " = " + state);
    }

    private static String setblock(BlockPos pos, String block)
    {
        return String.format(Locale.ROOT, "setblock %d %d %d %s", pos.getX(), pos.getY(), pos.getZ(), block);
    }
}
