package io.github.recher1001.easyplace2.printer.scaffold;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.malilib.util.FileUtils;

import io.github.recher1001.easyplace2.EasyPlace2;
import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.printer.BaritoneBridge;
import io.github.recher1001.easyplace2.printer.PrinterController;
import io.github.recher1001.easyplace2.printer.refill.StorageRegistry;

/**
 * Merkt sich jeden Geruest-Block, den Baritone beim Laufen fuer den Printer setzt (zum Hochkommen
 * oder ueber Luecken), damit der Printer ihn spaeter wieder abbaut. Getrennt nach Server/Welt und
 * Dimension, gespeichert in {@code config/easyplace2_scaffold.json}, damit auch nach einem Absturz
 * nichts stehen bleibt.
 */
public final class ScaffoldTracker
{
    public static final ScaffoldTracker INSTANCE = new ScaffoldTracker();

    private static final String FILE_NAME = EasyPlace2.MOD_ID + "_scaffold.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** So lange (Ticks) wird nach einem Klick geprueft, ob dort wirklich ein Block steht. */
    private static final int CONFIRM_TICKS = 20;

    private record Candidate(BlockPos pos, long tick) {}

    private final Map<String, Set<BlockPos>> worlds = new HashMap<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private boolean loaded;
    private boolean dirty;
    private String itemsSource = "";
    private Set<Item> items = Set.of();

    private ScaffoldTracker()
    {
    }

    public void register()
    {
        // Baritone setzt Bloecke mit einem ganz normalen Rechtsklick auf eine Flaeche
        UseBlockCallback.EVENT.register((player, world, hand, hit) ->
        {
            // Nur der eigene Client (im Einzelspieler ruft auch der Server dieses Ereignis auf)
            if (player instanceof LocalPlayer && PrinterController.INSTANCE.isRunning() && BaritoneBridge.isBusy() &&
                this.isScaffoldItem(player.getItemInHand(hand)))
            {
                long now = world.getGameTime();
                this.candidates.add(new Candidate(hit.getBlockPos().relative(hit.getDirection()).immutable(), now));
                // Ersetzbare Bloecke (Gras, Schnee ...) werden direkt ersetzt
                this.candidates.add(new Candidate(hit.getBlockPos().immutable(), now));
            }

            return InteractionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc)
    {
        if (mc.level == null)
        {
            this.candidates.clear();
            return;
        }

        long now = mc.level.getGameTime();
        Iterator<Candidate> it = this.candidates.iterator();

        while (it.hasNext())
        {
            Candidate c = it.next();

            if (now - c.tick() < 1)
            {
                continue;
            }

            if (this.isScaffoldBlock(mc.level.getBlockState(c.pos())))
            {
                if (this.positions().add(c.pos()))
                {
                    this.dirty = true;
                }

                it.remove();
            }
            else if (now - c.tick() > CONFIRM_TICKS)
            {
                it.remove();
            }
        }

        if (this.dirty)
        {
            this.save();
        }
    }

    // ---------------------------------------------------------------- Zugriff

    /** Alle gemerkten Geruest-Bloecke der aktuellen Welt (lebende Menge). */
    public Set<BlockPos> positions()
    {
        this.ensureLoaded();
        return this.worlds.computeIfAbsent(StorageRegistry.worldKey(), k -> new LinkedHashSet<>());
    }

    public boolean contains(BlockPos pos)
    {
        return this.positions().contains(pos);
    }

    public int count()
    {
        return this.positions().size();
    }

    public void add(BlockPos pos)
    {
        if (this.positions().add(pos.immutable()))
        {
            this.save();
        }
    }

    public void remove(BlockPos pos)
    {
        if (this.positions().remove(pos))
        {
            this.save();
        }
    }

    /** Bloecke vergessen, die dort nicht mehr stehen (abgebaut oder ersetzt). Nur in geladenen Chunks. */
    public void forgetGone(Level level)
    {
        if (this.positions().removeIf(p -> level.hasChunkAt(p) && !this.isScaffoldBlock(level.getBlockState(p))))
        {
            this.save();
        }
    }

    // ---------------------------------------------------------------- Geruest-Items

    /** Die Geruest-Items aus der Einstellung (z. B. Erde, Bruchstein). */
    public Set<Item> items()
    {
        String source = Ep2Configs.PRINTER_SCAFFOLD_ITEMS.getStringValue();

        if (!source.equals(this.itemsSource))
        {
            Set<Item> parsed = new LinkedHashSet<>();

            for (String part : source.split(","))
            {
                Identifier id = Identifier.tryParse(part.trim());
                Item item = id != null ? BuiltInRegistries.ITEM.getValue(id) : Items.AIR;

                if (item instanceof BlockItem)
                {
                    parsed.add(item);
                }
            }

            this.itemsSource = source;
            this.items = Set.copyOf(parsed);
        }

        return this.items;
    }

    private List<Item> usable = List.of();

    /**
     * Die Geruest-Items, die Baritone gerade verbauen darf: im Inventar vorhanden und nicht als
     * Baumaterial der Schematic gebraucht. Wird vom Printer beim Planen aktuell gehalten.
     */
    public List<Item> usable()
    {
        return this.usable;
    }

    public void setUsable(List<Item> items)
    {
        this.usable = List.copyOf(items);
    }

    public boolean isScaffoldItem(ItemStack stack)
    {
        return !stack.isEmpty() && this.items().contains(stack.getItem());
    }

    public boolean isScaffoldBlock(BlockState state)
    {
        return !state.isAir() && this.items().contains(state.getBlock().asItem());
    }

    // ---------------------------------------------------------------- Datei

    private Path file()
    {
        return FileUtils.getConfigDirectoryAsPath().resolve(FILE_NAME);
    }

    private void ensureLoaded()
    {
        if (this.loaded)
        {
            return;
        }

        this.loaded = true;
        Path file = this.file();

        if (!Files.isReadable(file))
        {
            return;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
        {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);

            for (Map.Entry<String, JsonElement> world : root.entrySet())
            {
                Set<BlockPos> set = new LinkedHashSet<>();

                for (JsonElement el : world.getValue().getAsJsonArray())
                {
                    JsonArray a = el.getAsJsonArray();
                    set.add(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
                }

                this.worlds.put(world.getKey(), set);
            }
        }
        catch (Exception ex)
        {
            EasyPlace2.LOGGER.error("Konnte '{}' nicht lesen", file, ex);
        }
    }

    private void save()
    {
        this.dirty = false;
        JsonObject root = new JsonObject();
        Map<String, Set<BlockPos>> sorted = new LinkedHashMap<>(this.worlds);

        for (Map.Entry<String, Set<BlockPos>> world : sorted.entrySet())
        {
            if (world.getValue().isEmpty())
            {
                continue;
            }

            JsonArray array = new JsonArray();

            for (BlockPos p : world.getValue())
            {
                JsonArray a = new JsonArray();
                a.add(p.getX());
                a.add(p.getY());
                a.add(p.getZ());
                array.add(a);
            }

            root.add(world.getKey(), array);
        }

        try
        {
            Files.createDirectories(this.file().getParent());

            try (Writer writer = Files.newBufferedWriter(this.file(), StandardCharsets.UTF_8))
            {
                GSON.toJson(root, writer);
            }
        }
        catch (Exception ex)
        {
            EasyPlace2.LOGGER.error("Konnte '{}' nicht speichern", this.file(), ex);
        }
    }
}
