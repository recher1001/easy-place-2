package io.github.recher1001.easyplace2.printer.refill;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.printer.InventoryManager;

/**
 * Entscheidet, woher fehlendes Material kommt: zuerst aus Shulkern im Inventar, sonst aus dem Lager.
 * Eine noch stehende Shulker wird immer zuerst wieder abgebaut.
 */
public final class RefillManager
{
    /** So lange wird es nach einem erfolglosen Versuch nicht erneut probiert (Ticks). */
    private static final int SHULKER_COOLDOWN = 20 * 5;
    private static final int STORAGE_COOLDOWN = 20 * 30;

    @Nullable private Refill active;
    @Nullable private ShulkerRefill leftover;
    private long shulkerCooldownUntil;
    private final Map<BlockPos, Long> storageCooldown = new HashMap<>();

    public boolean isActive()
    {
        return this.active != null;
    }

    /** Steht noch eine Shulker vom letzten (abgebrochenen) Vorgang herum? */
    public boolean hasLeftover()
    {
        return this.leftover != null && this.leftover.isPlaced();
    }

    public String detail()
    {
        if (this.active instanceof ShulkerRefill s)
        {
            return "Shulker: " + s.detail();
        }

        return this.active != null ? "Lager: " + this.active.detail() : "";
    }

    /** Erst die stehen gebliebene Shulker wieder einsammeln. */
    public boolean resumeLeftover()
    {
        if (this.hasLeftover())
        {
            this.active = this.leftover;
            this.leftover = null;
            return true;
        }

        return false;
    }

    /**
     * Versucht, Material fuer die angegebenen Items zu besorgen.
     *
     * @return true, wenn ein Vorgang gestartet wurde
     */
    public boolean tryStart(LocalPlayer player, Level level, @Nullable Level schematic, Set<Item> wanted,
                            InventoryManager inventory, long now)
    {
        if (this.active != null || this.resumeLeftover())
        {
            return true;
        }

        if (wanted.isEmpty())
        {
            return false;
        }

        if (Ep2Configs.PRINTER_SHULKER_REFILL.getBooleanValue() && now >= this.shulkerCooldownUntil)
        {
            ShulkerRefill refill = ShulkerRefill.create(player, level, schematic, wanted, inventory);

            if (refill != null)
            {
                this.active = refill;
                return true;
            }

            this.shulkerCooldownUntil = now + SHULKER_COOLDOWN;
        }

        if (Ep2Configs.PRINTER_STORAGE_REFILL.getBooleanValue())
        {
            this.storageCooldown.values().removeIf(until -> until <= now);
            StorageFetch fetch = StorageFetch.create(player, level, wanted, inventory, this.storageCooldown, now);

            if (fetch != null)
            {
                this.active = fetch;
                return true;
            }
        }

        return false;
    }

    /** @return true, wenn der laufende Vorgang fertig ist */
    public boolean tick(Minecraft mc, long now)
    {
        if (this.active == null)
        {
            return true;
        }

        if (!this.active.tick(mc))
        {
            return false;
        }

        if (this.active instanceof ShulkerRefill shulker)
        {
            if (shulker.isPlaced())
            {
                this.leftover = shulker;
            }
            else if (shulker.took() == 0)
            {
                this.shulkerCooldownUntil = now + SHULKER_COOLDOWN;
            }
        }
        else if (this.active instanceof StorageFetch fetch && fetch.took() == 0)
        {
            // Dort war nichts (mehr): eine Weile andere Container probieren
            this.storageCooldown.put(fetch.pos(), now + STORAGE_COOLDOWN);
        }

        this.active = null;
        return true;
    }

    public boolean ownsScreen(@Nullable Screen screen)
    {
        return this.active != null && this.active.ownsScreen(screen);
    }

    public void cancel(Minecraft mc)
    {
        if (this.active != null)
        {
            this.active.cancel(mc);

            if (this.active instanceof ShulkerRefill shulker && shulker.isPlaced())
            {
                this.leftover = shulker;
            }
        }

        this.active = null;
    }

    public void reset()
    {
        this.active = null;
        this.leftover = null;
        this.shulkerCooldownUntil = 0;
        this.storageCooldown.clear();
    }
}
