# Easy Place 2.0 – Litematica-Addon (Fabric, Minecraft 1.21.11)

Ein eigener, zusätzlicher Easy-Place-Modus für [Litematica](https://modrinth.com/mod/litematica).
Litematica sagt nur, **welcher** Block **wohin** gehört. Platziert wird ausschließlich so,
wie es ein Spieler mit einem normalen Rechtsklick auch könnte.

- Du schaust auf einen fehlenden Block der Schematic (den „Geisterblock“) und machst einen Rechtsklick.
- Das richtige Item wird automatisch aus Hotbar oder Inventar geholt (Litematicas eigene Pick-Block-Logik).
- Die Mod sucht eine echte Nachbarfläche, die du von deiner Position aus **sehen** und **erreichen** kannst,
  und führt darauf den ganz normalen Vanilla-Klick „Block benutzen“ aus.
- Vorher rechnet sie mit Vanillas eigener Platzierungslogik nach, was dabei herauskommt. Nur wenn das
  zur Schematic passt (z. B. Treppe richtig herum, Halbplatte oben/unten), wird geklickt.

**Was die Mod nicht macht:** kein Air-Place, keine gefälschte Blickrichtung, keine
Easy-Place- oder Accurate-Placement-Pakete, keine Server-Mod. Blöcke, die so nicht gehen
(schwebend, falsche Blickrichtung …), werden übersprungen. Das alte Easy Place von Litematica
bleibt unverändert und wird nicht gebraucht.

## Installation (zum Spielen)

1. **Fabric Loader** für Minecraft **1.21.11** installieren: <https://fabricmc.net/use/installer/>
2. Diese Dateien in den Ordner `.minecraft/mods` legen:
   - [Fabric API](https://modrinth.com/mod/fabric-api) für 1.21.11
   - [MaLiLib](https://modrinth.com/mod/malilib) **0.27.20** (oder neuer) für 1.21.11
   - [Litematica](https://modrinth.com/mod/litematica) **0.26.16** (oder neuer) für 1.21.11
   - `easy-place-2-mc1.21.11-0.1.0.jar` (diese Mod, siehe unten „Wo bekomme ich die .jar?“)
3. Minecraft mit dem Fabric-Profil starten.

## Benutzen

1. Im Spiel Litematicas Menü öffnen (Standard: `M`) → **Konfiguration** → Reiter **„Easy Place 2.0“**.
2. Dort:
   - **Easy Place 2.0**: Ein/Aus. Rechts daneben kannst du einen eigenen Hotkey zum Umschalten festlegen.
   - **Verzögerung (Ticks)**: Wartezeit beim Gedrückthalten der rechten Maustaste. 4 = Vanilla-Tempo, höher = langsamer.
   - **Strenges Fadenkreuz**: Wenn an, wird nur die Fläche angeklickt, die dein Fadenkreuz wirklich trifft
     (der Block hinter dem Geisterblock), genau wie ein normaler Rechtsklick.
   - **Debug-Meldungen**: Zeigt in der Aktionsleiste (und im Log), warum ein Block übersprungen wurde.
3. Das **alte Easy Place** von Litematica (Reiter „Generic“ → *Easy Place Mode*) bitte **aus** lassen.
4. Schematic laden, auf einen Geisterblock schauen, rechtsklicken.

Gut zu wissen:
- Schaust du **nicht** auf einen Geisterblock, ist der Rechtsklick ganz normal (Kisten öffnen, essen …).
- Blöcke wie Treppen, Öfen oder Trichter bekommen ihre Richtung wie in Vanilla von deiner Blickrichtung
  bzw. von der angeklickten Fläche. Passt das nicht, dreh dich passend hin. Die Debug-Meldung sagt dir, was nicht passt.
- Auf Kisten, Türen usw. wird nicht geklickt, weil Vanilla sie sonst öffnen würde. Wenn du selbst schleichst, geht es.

## Wo bekomme ich die .jar?

**Automatisch von GitHub (am einfachsten):**
Bei jedem Push baut GitHub die Mod. Auf der Repository-Seite oben auf **Actions** klicken →
den neuesten grünen Lauf „build“ öffnen → ganz unten bei **Artifacts** auf **easy-place-2** klicken.
Du bekommst eine ZIP-Datei; darin liegt `easy-place-2-mc1.21.11-0.1.0.jar`.

**Selbst bauen (optional):**
1. Java **21** oder neuer installieren (z. B. <https://adoptium.net>), besser gleich Java 25.
2. Projekt herunterladen (grüner Button „Code“ → „Download ZIP“) und entpacken.
3. Im Projektordner ein Terminal öffnen und ausführen:
   - Windows: `gradlew.bat build`
   - macOS/Linux: `./gradlew build`
4. Die fertige Datei liegt danach in `build/libs/easy-place-2-mc1.21.11-0.1.0.jar`.

**Mit IntelliJ IDEA:** *File → Open* → den Projektordner wählen → warten, bis Gradle fertig geladen hat.
Rechts im Gradle-Fenster *Tasks → build → build* doppelklicken (die .jar landet wieder in `build/libs/`),
oder *Tasks → fabric → runClient* starten, um direkt ein Test-Minecraft mit allen Mods zu öffnen.

## Automatischer Test

`./gradlew runClientGameTest` (bzw. der Job `client_game_test` auf GitHub) startet Minecraft mit
Litematica und Easy Place 2.0, verbindet sich mit einem **Vanilla-Server** (ohne Mods) und prüft:
Treppe wird richtig herum gesetzt, obere Halbplatte wird gesetzt, Treppe in falscher Blickrichtung
wird übersprungen, schwebender Block wird übersprungen.

## Technik (kurz)

| Teil | Datei |
|---|---|
| Einstellungen + Datei `config/easyplace2.json` | `config/Ep2Configs.java` |
| Hotkey-Registrierung bei MaLiLib | `config/Ep2Hotkeys.java` |
| Reiter im Litematica-Menü | `mixin/litematica/GuiConfigsMixin.java` |
| Rechtsklick abfangen (`Minecraft#startUseItem`) | `mixin/MinecraftMixin.java` |
| Ablauf: Ziel bestimmen, Item holen, klicken | `placement/EasyPlace2Handler.java` |
| Sichtbare Nachbarflächen suchen + Vanilla-Ergebnis vorausberechnen | `placement/PlacementPlanner.java` |
| Zustandsvergleich mit der Schematic | `placement/StateMatcher.java` |
