package io.github.recher1001.easyplace2.test;

import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
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
import io.github.recher1001.easyplace2.printer.PrinterController;
import io.github.recher1001.easyplace2.printer.gui.OptionEntry;
import io.github.recher1001.easyplace2.printer.gui.PrinterScreen;

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
        BlockPos stoneA = new BlockPos(-2, Y, -1);
        BlockPos stoneB = new BlockPos(-2, Y, 0);
        BlockPos stoneC = new BlockPos(-3, Y, 0);
        BlockPos stairsWest = new BlockPos(-2, Y, 1);
        BlockPos min = new BlockPos(-3, Y, -1);
        BlockPos max = new BlockPos(-2, Y, 1);

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

        server.runCommand("give @a cobblestone 8");
        server.runCommand("give @a oak_stairs 4");
        context.waitTicks(20);

        context.runOnClient(mc -> PrinterController.INSTANCE.start());
        context.waitFor(mc -> !PrinterController.INSTANCE.isRunning() ||
                              (mc.level.getBlockState(stoneA).is(Blocks.COBBLESTONE) &&
                               mc.level.getBlockState(stoneB).is(Blocks.COBBLESTONE) &&
                               mc.level.getBlockState(stoneC).is(Blocks.COBBLESTONE) &&
                               mc.level.getBlockState(stairsWest).is(Blocks.OAK_STAIRS)), 1200);
        context.waitTicks(20);
        context.takeScreenshot("easyplace2-printer-world");
        context.runOnClient(mc -> PrinterController.INSTANCE.stop(null));

        assertBlock(server, stoneA, state -> state.is(Blocks.COBBLESTONE), "Printer hat Stein A nicht gesetzt");
        assertBlock(server, stoneB, state -> state.is(Blocks.COBBLESTONE), "Printer hat Stein B nicht gesetzt");
        assertBlock(server, stoneC, state -> state.is(Blocks.COBBLESTONE), "Printer hat Stein C nicht gesetzt");
        assertBlock(server, stairsWest, state -> state.is(Blocks.OAK_STAIRS) && state.getValue(StairBlock.FACING) == Direction.WEST,
                "Printer hat die Treppe nicht richtig herum gesetzt");
        System.out.println("[EP2-TEST] Printer ok");
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
