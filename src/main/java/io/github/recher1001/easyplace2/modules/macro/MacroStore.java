package io.github.recher1001.easyplace2.modules.macro;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import fi.dy.masa.malilib.util.FileUtils;

import io.github.recher1001.easyplace2.EasyPlace2;

/** Alle Makros, gespeichert in {@code config/easyplace2_macros.json}. */
public final class MacroStore
{
    public static final MacroStore INSTANCE = new MacroStore();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = EasyPlace2.MOD_ID + "_macros.json";

    private static final class Data
    {
        List<Macro> macros = new ArrayList<>();
        int selected;
    }

    private Data data = new Data();
    private boolean loaded;

    private MacroStore()
    {
    }

    public List<Macro> macros()
    {
        this.ensureLoaded();
        return this.data.macros;
    }

    public int selectedIndex()
    {
        this.ensureLoaded();
        return this.data.macros.isEmpty() ? -1 : Math.floorMod(this.data.selected, this.data.macros.size());
    }

    @Nullable
    public Macro selected()
    {
        int i = this.selectedIndex();
        return i < 0 ? null : this.data.macros.get(i);
    }

    public void select(int index)
    {
        this.ensureLoaded();

        if (!this.data.macros.isEmpty())
        {
            this.data.selected = Math.floorMod(index, this.data.macros.size());
            this.save();
        }
    }

    public void add(Macro macro)
    {
        this.ensureLoaded();
        this.data.macros.add(macro);
        this.data.selected = this.data.macros.size() - 1;
        this.save();
    }

    public void remove(Macro macro)
    {
        this.ensureLoaded();
        this.data.macros.remove(macro);
        this.data.selected = Math.max(0, Math.min(this.data.selected, this.data.macros.size() - 1));
        this.save();
    }

    /** Ein freier Name wie "Makro 3". */
    public String nextName()
    {
        int n = this.macros().size() + 1;

        while (true)
        {
            String name = "Makro " + n;

            if (this.macros().stream().noneMatch(m -> m.name.equals(name)))
            {
                return name;
            }

            n++;
        }
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
            Data read = GSON.fromJson(reader, Data.class);

            if (read != null && read.macros != null)
            {
                read.macros.removeIf(m -> m == null || m.steps == null);
                this.data = read;
            }
        }
        catch (Exception ex)
        {
            EasyPlace2.LOGGER.error("Konnte '{}' nicht lesen", file, ex);
        }
    }

    public void save()
    {
        try
        {
            Files.createDirectories(this.file().getParent());

            try (Writer writer = Files.newBufferedWriter(this.file(), StandardCharsets.UTF_8))
            {
                GSON.toJson(this.data, writer);
            }
        }
        catch (Exception ex)
        {
            EasyPlace2.LOGGER.error("Konnte '{}' nicht speichern", this.file(), ex);
        }
    }
}
