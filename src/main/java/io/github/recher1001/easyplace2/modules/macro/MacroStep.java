package io.github.recher1001.easyplace2.modules.macro;

import java.util.Locale;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ein Schritt eines Makros. Alle Felder sind einfach gehalten, damit sie direkt als JSON gespeichert werden.
 */
public final class MacroStep
{
    public enum Type
    {
        /** In eine Richtung schauen (sichtbar hindrehen). */
        LOOK,
        /** Warten (Ticks). */
        WAIT,
        /** Abbauen, was im Fadenkreuz ist. */
        MINE,
        /** Rechtsklick (z. B. Setzling setzen). */
        USE,
        /** Ein Item in die Hand nehmen. */
        SELECT,
        /** Warten, bis ein bestimmter Block im Fadenkreuz ist. */
        WAIT_FOR_BLOCK,
        /** Lauf-/Sprung-/Schleichtasten eine Weile halten. */
        KEYS
    }

    public enum MineMode
    {
        /** Den Block im Fadenkreuz einmal abbauen. */
        ONCE,
        /** Abbauen, solange der eingestellte Block im Fadenkreuz ist (z. B. bis kein Holz mehr da ist). */
        WHILE_BLOCK
    }

    /** Bits fuer {@link Type#KEYS}. */
    public static final int KEY_FORWARD = 1, KEY_BACK = 2, KEY_LEFT = 4, KEY_RIGHT = 8, KEY_JUMP = 16, KEY_SNEAK = 32;

    public Type type = Type.WAIT;
    public int ticks;
    public float yaw;
    public float pitch;
    public String block = "";
    public String item = "";
    public int slot = -1;
    public int keys;
    public MineMode mode = MineMode.ONCE;

    public static MacroStep wait(int ticks)
    {
        MacroStep s = new MacroStep();
        s.type = Type.WAIT;
        s.ticks = ticks;
        return s;
    }

    public static MacroStep look(float yaw, float pitch)
    {
        MacroStep s = new MacroStep();
        s.type = Type.LOOK;
        s.yaw = yaw;
        s.pitch = pitch;
        return s;
    }

    public static MacroStep mine(MineMode mode, String block)
    {
        MacroStep s = new MacroStep();
        s.type = Type.MINE;
        s.mode = mode;
        s.block = block;
        return s;
    }

    public static MacroStep use()
    {
        MacroStep s = new MacroStep();
        s.type = Type.USE;
        return s;
    }

    public static MacroStep select(String item, int slot)
    {
        MacroStep s = new MacroStep();
        s.type = Type.SELECT;
        s.item = item;
        s.slot = slot;
        return s;
    }

    public static MacroStep waitForBlock(String block, int timeoutTicks)
    {
        MacroStep s = new MacroStep();
        s.type = Type.WAIT_FOR_BLOCK;
        s.block = block;
        s.ticks = timeoutTicks;
        return s;
    }

    public static MacroStep keys(int keys, int ticks)
    {
        MacroStep s = new MacroStep();
        s.type = Type.KEYS;
        s.keys = keys;
        s.ticks = ticks;
        return s;
    }

    public MacroStep copy()
    {
        MacroStep s = new MacroStep();
        s.type = this.type;
        s.ticks = this.ticks;
        s.yaw = this.yaw;
        s.pitch = this.pitch;
        s.block = this.block;
        s.item = this.item;
        s.slot = this.slot;
        s.keys = this.keys;
        s.mode = this.mode;
        return s;
    }

    // ---------------------------------------------------------------- Anzeige

    public Component describe()
    {
        return switch (this.type)
        {
            case LOOK -> Component.translatable("easyplace2.macro.step.look", Math.round(this.yaw), Math.round(this.pitch));
            case WAIT -> Component.translatable("easyplace2.macro.step.wait", seconds(this.ticks));
            case MINE -> this.mode == MineMode.WHILE_BLOCK
                    ? Component.translatable("easyplace2.macro.step.mine_while", blockName(this.block))
                    : Component.translatable("easyplace2.macro.step.mine_once");
            case USE -> Component.translatable("easyplace2.macro.step.use");
            case SELECT -> this.item.isEmpty()
                    ? Component.translatable("easyplace2.macro.step.select_slot", this.slot + 1)
                    : Component.translatable("easyplace2.macro.step.select", itemName(this.item));
            case WAIT_FOR_BLOCK -> this.ticks > 0
                    ? Component.translatable("easyplace2.macro.step.wait_block_max", blockName(this.block), seconds(this.ticks))
                    : Component.translatable("easyplace2.macro.step.wait_block", blockName(this.block));
            case KEYS -> Component.translatable("easyplace2.macro.step.keys", keyNames(this.keys), seconds(this.ticks));
        };
    }

    public static String seconds(int ticks)
    {
        return String.format(Locale.ROOT, "%.2f s", ticks / 20.0);
    }

    private static String keyNames(int keys)
    {
        StringBuilder sb = new StringBuilder();
        String[] names = { "W", "S", "A", "D", "Sprung", "Schleichen" };

        for (int i = 0; i < names.length; i++)
        {
            if ((keys & (1 << i)) != 0)
            {
                sb.append(sb.isEmpty() ? "" : "+").append(names[i]);
            }
        }

        return sb.isEmpty() ? "-" : sb.toString();
    }

    // ---------------------------------------------------------------- Bloecke und Items als Text

    public static String id(BlockState state)
    {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    public static String id(Item item)
    {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static Block block(String id)
    {
        Identifier key = Identifier.tryParse(id);
        return key != null ? BuiltInRegistries.BLOCK.getValue(key) : Blocks.AIR;
    }

    public static Item item(String id)
    {
        Identifier key = Identifier.tryParse(id);
        return key != null ? BuiltInRegistries.ITEM.getValue(key) : Items.AIR;
    }

    public static String blockName(String id)
    {
        return id.isEmpty() ? "?" : block(id).getName().getString();
    }

    public static String itemName(String id)
    {
        return id.isEmpty() ? "?" : item(id).getName().getString();
    }
}
