package io.github.recher1001.easyplace2.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import io.github.recher1001.easyplace2.config.Ep2Configs;
import io.github.recher1001.easyplace2.mixin.ChatComponentAccessor;

/**
 * Mittelklick auf eine Chat-Nachricht fuehrt sofort den Invsee-Befehl aus: bei Teleport-Anfragen fuer den
 * Spieler, von dem die Anfrage kommt, sonst fuer den Spieler, der die Nachricht geschrieben hat.
 */
public final class ChatInvsee
{
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{3,16}");

    /** Zuletzt erkannter Name (fuer den Spieltest). */
    @Nullable public static String lastName;

    private ChatInvsee()
    {
    }

    /** @return true, wenn der Klick verarbeitet wurde (dann macht Vanilla nichts mehr damit) */
    public static boolean onMiddleClick(Minecraft mc, double mouseX, double mouseY)
    {
        if (!Ep2Configs.MODULE_INVSEE_CHAT_CLICK.getBooleanValue() || mc.player == null || mc.getConnection() == null)
        {
            return false;
        }

        List<GuiMessage.Line> message = messageAt(mc, mouseX, mouseY);

        if (message.isEmpty())
        {
            return false;
        }

        String name = nameIn(mc, message);
        lastName = name;

        if (name == null)
        {
            mc.player.displayClientMessage(Component.translatable("easyplace2.module.invsee.chat_none"), true);
        }
        else
        {
            InvseeModule.run(mc, name);
        }

        return true;
    }

    /** Der Name, um den es in der Nachricht an dieser Stelle geht, oder null (fuer Tests). */
    @Nullable
    public static String nameAt(Minecraft mc, double mouseX, double mouseY)
    {
        List<GuiMessage.Line> message = messageAt(mc, mouseX, mouseY);
        return message.isEmpty() ? null : nameIn(mc, message);
    }

    /**
     * Alle Zeilen der Nachricht unter der Maus (eine lange Nachricht ist auf mehrere Zeilen umgebrochen).
     * Rechnet genau wie Minecraft: die Zeilen stehen von unten nach oben, 40 px ueber dem unteren Rand.
     */
    private static List<GuiMessage.Line> messageAt(Minecraft mc, double mouseX, double mouseY)
    {
        ChatComponentAccessor chat = (ChatComponentAccessor) mc.gui.getChat();
        List<GuiMessage.Line> lines = chat.easyplace2$lines();
        double scale = chat.easyplace2$scale();
        double x = mouseX / scale - 4.0;
        double row = (mc.getWindow().getGuiScaledHeight() - mouseY - 40.0) / (scale * chat.easyplace2$lineHeight());
        int visible = Math.min(mc.gui.getChat().getLinesPerPage(), lines.size() - chat.easyplace2$scroll());

        if (x < -4.0 || x > Math.floor(chat.easyplace2$width() / scale) || row < 0 || row >= visible)
        {
            return List.of();
        }

        int index = (int) Math.floor(row) + chat.easyplace2$scroll();

        if (index < 0 || index >= lines.size())
        {
            return List.of();
        }

        // Neueste Zeile hat Index 0; die letzte Zeile einer Nachricht ist als "Ende" markiert
        int newest = index;

        while (newest > 0 && !lines.get(newest).endOfEntry())
        {
            newest--;
        }

        int oldest = index;

        while (oldest + 1 < lines.size() && !lines.get(oldest + 1).endOfEntry())
        {
            oldest++;
        }

        List<GuiMessage.Line> message = new ArrayList<>();

        for (int i = oldest; i >= newest; i--)
        {
            message.add(lines.get(i));
        }

        return message;
    }

    @Nullable
    private static String nameIn(Minecraft mc, List<GuiMessage.Line> message)
    {
        StringBuilder text = new StringBuilder();
        List<String> commands = new ArrayList<>();
        List<String> insertions = new ArrayList<>();

        for (GuiMessage.Line line : message)
        {
            if (!text.isEmpty())
            {
                text.append(' ');
            }

            line.content().accept((index, style, codepoint) ->
            {
                text.appendCodePoint(codepoint);

                if (style.getClickEvent() instanceof ClickEvent.RunCommand(String command) && !commands.contains(command))
                {
                    commands.add(command);
                }
                else if (style.getClickEvent() instanceof ClickEvent.SuggestCommand(String command) && !commands.contains(command))
                {
                    commands.add(command);
                }

                if (style.getInsertion() != null && !insertions.contains(style.getInsertion()))
                {
                    insertions.add(style.getInsertion());
                }

                return true;
            });
        }

        String self = mc.player != null ? mc.player.getGameProfile().name() : "";
        List<String> online = new ArrayList<>();

        if (mc.getConnection() != null)
        {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers())
            {
                online.add(info.getProfile().name());
            }
        }

        String name = ChatNameFinder.find(text.toString(), commands, online, self);

        if (name != null)
        {
            return name;
        }

        // Vanilla-Chat: Shift-Klick-Einfuegetext ist der Name des Absenders
        for (String insertion : insertions)
        {
            if (VALID.matcher(insertion).matches() && !insertion.equalsIgnoreCase(self))
            {
                return insertion;
            }
        }

        return null;
    }
}
