package io.github.recher1001.easyplace2.printer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;

import io.github.recher1001.easyplace2.EasyPlace2;
import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.placement.StateMatcher;

/**
 * Vergleicht alle aktiven Litematica-Placements mit der echten Welt.
 * <p>
 * Gescannt wird in kleinen Stuecken pro Tick, damit auch grosse Schematics nicht ruckeln.
 * Ist ein Durchlauf fertig, steht das Ergebnis als {@link Snapshot} bereit und der naechste beginnt.
 */
public final class SchematicScanner
{
    private static final int BLOCKS_PER_TICK = 40_000;

    public record Target(BlockPos pos, BlockState state, Item item) {}

    public record Snapshot(List<Target> missing, int total, int done, int wrong, int unplaceable,
                           List<BlockPos> unloaded, Map<Item, Integer> neededItems, String names)
    {
        public double progress()
        {
            return this.total <= 0 ? 0.0 : (double) this.done / this.total;
        }
    }

    private record Region(BlockPos min, BlockPos max) {}

    @Nullable private Snapshot latest;

    // Laufender Durchlauf
    private List<Region> regions = List.of();
    private int regionIndex;
    @Nullable private BlockPos.MutableBlockPos cursor;
    private List<Target> missing = new ArrayList<>();
    private Map<Item, Integer> needed = new HashMap<>();
    private Set<Long> unloadedChunks = new HashSet<>();
    private int total, done, wrong, unplaceable;
    private String names = "";
    private int lastLoggedMissing = -1;

    @Nullable
    public Snapshot latest()
    {
        return this.latest;
    }

    public void reset()
    {
        this.latest = null;
        this.regions = List.of();
        this.cursor = null;
    }

    public void tick(ClientLevel level)
    {
        Level schematic = SchematicWorldHandler.getSchematicWorld();

        if (schematic == null)
        {
            return;
        }

        if (this.cursor == null)
        {
            this.beginPass();

            if (this.regions.isEmpty())
            {
                this.latest = new Snapshot(List.of(), 0, 0, 0, 0, List.of(), Map.of(), "");
                return;
            }
        }

        boolean useLayers = Ep2Configs.PRINTER_LAYER_RANGE.getBooleanValue();
        int budget = BLOCKS_PER_TICK;

        while (budget-- > 0 && this.cursor != null)
        {
            this.visit(level, schematic, this.cursor, useLayers);
            this.advance();
        }

        if (this.cursor == null)
        {
            List<BlockPos> unloaded = new ArrayList<>();

            for (long key : this.unloadedChunks)
            {
                ChunkPos chunk = new ChunkPos(key);
                unloaded.add(new BlockPos(chunk.getMiddleBlockX(), 0, chunk.getMiddleBlockZ()));
            }

            this.latest = new Snapshot(List.copyOf(this.missing), this.total, this.done, this.wrong, this.unplaceable,
                    unloaded, Map.copyOf(this.needed), this.names);

            if (Ep2Configs.DEBUG.getBooleanValue() && this.latest.missing().size() != this.lastLoggedMissing)
            {
                this.lastLoggedMissing = this.latest.missing().size();
                EasyPlace2.LOGGER.info("[Printer] Scan: {} Bloecke, {} fertig, {} fehlen, {} falsch, {} nicht setzbar, {} Chunks nicht geladen",
                        this.total, this.done, this.missing.size(), this.wrong, this.unplaceable, unloaded.size());
            }
        }
    }

    private void beginPass()
    {
        List<Region> list = new ArrayList<>();
        List<String> placementNames = new ArrayList<>();

        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            if (!placement.isEnabled())
            {
                continue;
            }

            placementNames.add(placement.getName());

            for (Box box : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values())
            {
                BlockPos a = box.getPos1();
                BlockPos b = box.getPos2();

                if (a != null && b != null)
                {
                    list.add(new Region(
                            new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                            new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))));
                }
            }
        }

        this.regions = list;
        this.regionIndex = 0;
        this.cursor = list.isEmpty() ? null : list.get(0).min().mutable();
        this.missing = new ArrayList<>();
        this.needed = new HashMap<>();
        this.unloadedChunks = new HashSet<>();
        this.total = this.done = this.wrong = this.unplaceable = 0;
        this.names = String.join(", ", placementNames);
    }

    private void visit(ClientLevel level, Level schematic, BlockPos pos, boolean useLayers)
    {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;

        // Nur die echte Welt pruefen: Litematica laedt die Schematic-Chunks selbst passend zu den geladenen Chunks
        if (!level.hasChunk(cx, cz))
        {
            this.unloadedChunks.add(ChunkPos.asLong(cx, cz));
            return;
        }

        if (useLayers && !DataManager.getRenderLayerRange().isPositionWithinRange(pos))
        {
            return;
        }

        BlockState wanted = schematic.getBlockState(pos);

        if (wanted.isAir())
        {
            return;
        }

        this.total++;
        BlockState current = level.getBlockState(pos);

        if (StateMatcher.isComplete(current, wanted))
        {
            this.done++;
            return;
        }

        // Falsche Bloecke werden nie abgebaut, nur gezaehlt
        if (!current.canBeReplaced() && current.getBlock() != wanted.getBlock())
        {
            this.wrong++;
            return;
        }

        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(wanted, schematic, pos);

        if (required.isEmpty() || !(required.getItem() instanceof BlockItem))
        {
            this.unplaceable++;
            return;
        }

        Item item = required.getItem();
        this.missing.add(new Target(pos.immutable(), wanted, item));
        this.needed.merge(item, 1, Integer::sum);
    }

    private void advance()
    {
        BlockPos.MutableBlockPos c = this.cursor;
        Region r = this.regions.get(this.regionIndex);

        if (c.getX() < r.max().getX())
        {
            c.setX(c.getX() + 1);
        }
        else if (c.getZ() < r.max().getZ())
        {
            c.set(r.min().getX(), c.getY(), c.getZ() + 1);
        }
        else if (c.getY() < r.max().getY())
        {
            c.set(r.min().getX(), c.getY() + 1, r.min().getZ());
        }
        else if (++this.regionIndex < this.regions.size())
        {
            this.cursor = this.regions.get(this.regionIndex).min().mutable();
        }
        else
        {
            this.cursor = null;
        }
    }
}
