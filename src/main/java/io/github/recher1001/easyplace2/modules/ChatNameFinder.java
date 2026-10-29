package io.github.recher1001.easyplace2.modules;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Findet in einer Chat-Nachricht den Spieler, um den es geht:
 * <ul>
 *     <li>Teleport-Anfragen ({@code /tpa}, {@code /tpahere}): der Spieler hinter {@code /tpaccept}
 *         bzw. {@code /tpdeny} (oder "Name hat dir eine Teleportations-Anfrage gesendet").</li>
 *     <li>Normale Chat-Nachrichten: der Absender, also der Name vor dem Doppelpunkt
 *         ({@code [Rang] Name: Text}) oder in spitzen Klammern ({@code <Name> Text}).</li>
 *     <li>Sonst: ein Wort, das genau ein Spieler ist, der gerade online ist.</li>
 * </ul>
 * Farbcodes, Raenge und Praefixe zaehlen nicht mit.
 */
public final class ChatNameFinder
{
    private static final String NAME = "[A-Za-z0-9_]{3,16}";
    private static final Pattern TP_COMMAND = Pattern.compile("/(?:tpaccept|tpyes|tpdeny|tpno|tpacancel)\\s+(" + NAME + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern TP_REQUEST = Pattern.compile("(" + NAME + ")\\s+(?:hat dir eine (?:Teleport|TP)|m(?:ö|oe)chte(?:,)? (?:sich )?(?:zu dir|dass du)|has requested|wants to teleport|sent you a teleport)",
            Pattern.CASE_INSENSITIVE);
    /** "[Rang] Name: Text", "Rang | Name » Text", "Name >> Text" */
    private static final Pattern CHAT = Pattern.compile("(?:^|[\\s\\]|)])~?(" + NAME + ")\\s*(?::|»|>>|->)\\s");
    /** "<Name> Text" (Vanilla) */
    private static final Pattern VANILLA = Pattern.compile("<(?:[^<>]*\\s)?(" + NAME + ")>");
    private static final Pattern WORD = Pattern.compile("(?<![A-Za-z0-9_])(" + NAME + ")(?![A-Za-z0-9_])");

    private ChatNameFinder()
    {
    }

    /**
     * @param text      reiner Text der Nachricht (ohne Farben)
     * @param commands  Befehle aus Klick-Ereignissen der Nachricht (z. B. "/tpaccept MqanYT")
     * @param online    Namen der Spieler, die gerade online sind
     * @param self      eigener Name (wird nie gewaehlt)
     */
    @Nullable
    public static String find(String text, List<String> commands, Collection<String> online, String self)
    {
        String plain = text.replaceAll("§.", "");

        // 1. Teleport-Anfrage: Klick-Befehl oder Text "/tpaccept Name"
        for (String command : commands)
        {
            String name = first(TP_COMMAND, command, self);

            if (name != null)
            {
                return canonical(name, online);
            }
        }

        String name = first(TP_COMMAND, plain, self);

        if (name == null)
        {
            name = first(TP_REQUEST, plain, self);
        }

        // 2. Normale Nachricht: Absender vor dem Doppelpunkt bzw. in <>
        if (name == null)
        {
            name = preferOnline(VANILLA, plain, online, self);
        }

        if (name == null)
        {
            name = preferOnline(CHAT, plain, online, self);
        }

        if (name != null)
        {
            return canonical(name, online);
        }

        // 3. Irgendein Wort, das ein Spieler ist, der online ist
        Matcher m = WORD.matcher(plain);

        while (m.find())
        {
            String word = m.group(1);

            if (!word.equalsIgnoreCase(self))
            {
                for (String player : online)
                {
                    if (player.equalsIgnoreCase(word))
                    {
                        return player;
                    }
                }
            }
        }

        return null;
    }

    @Nullable
    private static String first(Pattern pattern, String text, String self)
    {
        Matcher m = pattern.matcher(text);

        while (m.find())
        {
            if (!m.group(1).equalsIgnoreCase(self))
            {
                return m.group(1);
            }
        }

        return null;
    }

    /** Von allen Treffern der erste, der online ist; sonst der erste Treffer. */
    @Nullable
    private static String preferOnline(Pattern pattern, String text, Collection<String> online, String self)
    {
        Matcher m = pattern.matcher(text);
        String fallback = null;

        while (m.find())
        {
            String candidate = m.group(1);

            if (candidate.equalsIgnoreCase(self))
            {
                continue;
            }

            if (online.stream().anyMatch(candidate::equalsIgnoreCase))
            {
                return candidate;
            }

            if (fallback == null)
            {
                fallback = candidate;
            }
        }

        return fallback;
    }

    /** Schreibweise wie in der Spielerliste (falls online). */
    private static String canonical(String name, Collection<String> online)
    {
        String lower = name.toLowerCase(Locale.ROOT);

        for (String player : online)
        {
            if (player.toLowerCase(Locale.ROOT).equals(lower))
            {
                return player;
            }
        }

        return name;
    }
}
