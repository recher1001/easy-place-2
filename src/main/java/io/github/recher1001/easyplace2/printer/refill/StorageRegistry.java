package io.github.recher1001.easyplace2.printer.refill;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import fi.dy.masa.malilib.util.FileUtils;

import io.github.recher1001.easyplace2.EasyPlace2;
import io.github.recher1001.easyplace2.printer.ShulkerContents;

/**
 * Lager-Container (Truhen, Faesser, Shulker ...), die der Spieler markiert hat, zusammen mit dem
 * zuletzt gesehenen Inhalt. Getrennt nach Server/Welt und Dimension, gespeichert in
 * {@code config/easyplace2_storage.json}.
 * <p>
 * Der Inhalt wird gemerkt, sobald ein markierter Container geoeffnet ist, egal ob vom Spieler
 * selbst oder vom Printer. Items in Shulker-Kisten im Container werden mitgezaehlt.
 */
public final class StorageRegistry
{
    public static final StorageRegistry INSTANCE = new StorageRegistry();

    private static final String FILE_NAME = EasyPlace2.MOD_ID + "_storage.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Ein markierter Container. */
    public static final class Entry
    {
        public final BlockPos pos;
        /** Item → Anzahl, direkt im Container */
        public final Map<Item, Integer> direct = new LinkedHashMap<>();
        /** Item → Anzahl in Shulkern, die im Container liegen */
        public final Map<Item, Integer> inShulkers = new LinkedHashMap<>();
        public boolean known;

        Entry(BlockPos pos)
        {
            this.pos = pos;
        }

        public int total(Item item)
        {
            return this.direct.getOrDefault(item, 0) + this.inShulkers.getOrDefault(item, 0);
        }
    }

    private final Map<String, List<Entry>> worlds = new HashMap<>();
    private boolean loaded;
    @Nullable private BlockPos lastUsed;
    private boolean watching;

    private StorageRegistry()
    {
    }

    public void register()
    {
        // Welcher Block wurde zuletzt mit Rechtsklick benutzt? (Damit wissen wir, welcher Container offen ist.)
        UseBlockCallback.EVENT.register((player, world, hand, hit) ->
        {
            this.lastUsed = hit.getBlockPos().immutable();
            return InteractionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    // ---------------------------------------------------------------- Zugriff

    public List<Entry> entries()
    {
        this.ensureLoaded();
        return this.worlds.computeIfAbsent(worldKey(), k -> new ArrayList<>());
    }

    @Nullable
    public Entry get(BlockPos pos)
    {
        for (Entry e : this.entries())
        {
            if (e.pos.equals(pos))
            {
                return e;
            }
        }

        return null;
    }

    /** Container hinzufuegen oder (wenn schon markiert) entfernen. @return true wenn er jetzt markiert ist */
    public boolean toggle(BlockPos pos)
    {
        Entry existing = this.get(pos);

        if (existing != null)
        {
            this.entries().remove(existing);
            this.save();
            return false;
        }

        this.entries().add(new Entry(pos.immutable()));
        this.save();
        return true;
    }

    public void remove(Entry entry)
    {
        this.entries().remove(entry);
        this.save();
    }

    /** Wie viele Items dieser Art laut letztem Stand im Lager sind (inklusive Shulker darin). */
    public int total(Item item)
    {
        int count = 0;

        for (Entry e : this.entries())
        {
            count += e.total(item);
        }

        return count;
    }

    /**
     * Den Container im Fadenkreuz markieren oder die Markierung entfernen (Hotkey / Knopf im Menue).
     *
     * @return true, wenn ein Container im Fadenkreuz war
     */
    public static boolean toggleLookedAt(Minecraft mc)
    {
        if (mc.player == null || mc.level == null || !(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr) ||
            bhr.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || !isContainer(mc.level, bhr.getBlockPos()))
        {
            if (mc.player != null)
            {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.translatable("easyplace2.printer.msg.storage_none"), true);
            }

            return false;
        }

        boolean added = INSTANCE.toggle(bhr.getBlockPos());
        mc.player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                added ? "easyplace2.printer.msg.storage_added" : "easyplace2.printer.msg.storage_removed",
                bhr.getBlockPos().toShortString()), true);
        return true;
    }

    /** Ist der Block ein Container, den man als Lager markieren kann? */
    public static boolean isContainer(Level level, BlockPos pos)
    {
        return level.getBlockEntity(pos) instanceof net.minecraft.world.Container;
    }

    // ---------------------------------------------------------------- Inhalt merken

    private void tick(Minecraft mc)
    {
        if (mc.level == null)
        {
            return;
        }

        boolean containerOpen = mc.screen instanceof AbstractContainerScreen<?> &&
                                !(mc.screen instanceof InventoryScreen) && !(mc.screen instanceof CreativeModeInventoryScreen);

        if (containerOpen && this.lastUsed != null && mc.player != null)
        {
            Entry entry = this.get(this.lastUsed);

            if (entry != null)
            {
                this.record(entry, mc.player.containerMenu);
                this.watching = true;
            }
        }
        else if (this.watching)
        {
            // Container wurde geschlossen: letzten Stand speichern
            this.watching = false;
            this.save();
        }
    }

    private void record(Entry entry, AbstractContainerMenu menu)
    {
        entry.direct.clear();
        entry.inShulkers.clear();
        entry.known = true;
        int size = RefillUtil.containerSlots(menu);

        for (int i = 0; i < size; i++)
        {
            ItemStack stack = menu.slots.get(i).getItem();

            if (stack.isEmpty())
            {
                continue;
            }

            entry.direct.merge(stack.getItem(), stack.getCount(), Integer::sum);

            if (ShulkerContents.isShulker(stack))
            {
                ShulkerContents.summary(stack).forEach((item, count) -> entry.inShulkers.merge(item, count, Integer::sum));
            }
        }
    }

    // ---------------------------------------------------------------- Datei

    /** Schluessel fuer Server/Welt + Dimension. */
    /** Schluessel fuer die aktuelle Welt: Server bzw. Einzelspielerwelt plus Dimension. */
    public static String worldKey()
    {
        Minecraft mc = Minecraft.getInstance();
        String world;

        if (mc.getCurrentServer() != null)
        {
            world = "server:" + mc.getCurrentServer().ip;
        }
        else if (mc.getSingleplayerServer() != null)
        {
            world = "local:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        else
        {
            world = "unknown";
        }

        return mc.level != null ? world + "|" + mc.level.dimension() : world;
    }

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
                List<Entry> list = new ArrayList<>();

                for (JsonElement el : world.getValue().getAsJsonArray())
                {
                    JsonObject o = el.getAsJsonObject();
                    Entry e = new Entry(new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
                    e.known = o.has("known") && o.get("known").getAsBoolean();
                    readItems(o.getAsJsonObject("items"), e.direct);
                    readItems(o.getAsJsonObject("inShulkers"), e.inShulkers);
                    list.add(e);
                }

                this.worlds.put(world.getKey(), list);
            }
        }
        catch (Exception ex)
        {
            EasyPlace2.LOGGER.error("Konnte '{}' nicht lesen", file, ex);
        }
    }

    public void save()
    {
        JsonObject root = new JsonObject();

        for (Map.Entry<String, List<Entry>> world : this.worlds.entrySet())
        {
            JsonArray array = new JsonArray();

            for (Entry e : world.getValue())
            {
                JsonObject o = new JsonObject();
                o.addProperty("x", e.pos.getX());
                o.addProperty("y", e.pos.getY());
                o.addProperty("z", e.pos.getZ());
                o.addProperty("known", e.known);
                o.add("items", writeItems(e.direct));
                o.add("inShulkers", writeItems(e.inShulkers));
                array.add(o);
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

    private static void readItems(@Nullable JsonObject json, Map<Item, Integer> target)
    {
        if (json == null)
        {
            return;
        }

        for (Map.Entry<String, JsonElement> e : json.entrySet())
        {
            Identifier id = Identifier.tryParse(e.getKey());
            Item item = id != null ? BuiltInRegistries.ITEM.getValue(id) : Items.AIR;

            if (item != Items.AIR)
            {
                target.put(item, e.getValue().getAsInt());
            }
        }
    }

    private static JsonObject writeItems(Map<Item, Integer> items)
    {
        JsonObject json = new JsonObject();
        items.forEach((item, count) -> json.addProperty(BuiltInRegistries.ITEM.getKey(item).toString(), count));
        return json;
    }
}
