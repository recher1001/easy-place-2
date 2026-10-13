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
   - `easy-place-2-mc1.21.11-0.2.0.jar` (diese Mod, siehe unten „Wo bekomme ich die .jar?“)
3. Minecraft mit dem Fabric-Profil starten.

## Benutzen

1. Im Spiel Litematicas Menü öffnen (Standard: `M`) → **Konfiguration** → Reiter **„Easy Place 2.0“**.
2. Dort:
   - **Easy Place 2.0**: Ein/Aus. Rechts daneben kannst du einen eigenen Hotkey zum Umschalten festlegen.
   - **Verzögerung (Ticks)**: Wartezeit beim Gedrückthalten der rechten Maustaste. 4 = Vanilla-Tempo, höher = langsamer.
   - **Strenges Fadenkreuz**: Wenn an, wird nur die Fläche angeklickt, die dein Fadenkreuz wirklich trifft
     (der Block hinter dem Geisterblock), genau wie ein normaler Rechtsklick.
   - **Nur an Schematic-Plätzen setzen** (Standard an): Solange Easy Place 2.0 an ist, kannst du Blöcke nur an
     der richtigen Stelle der Schematic setzen. Ein Klick, der einen Block woanders hinsetzen würde, wird gesperrt.
   - **Debug-Meldungen**: Zeigt in der Aktionsleiste (und im Log), warum ein Block übersprungen wurde.
3. Das **alte Easy Place** von Litematica (Reiter „Generic“ → *Easy Place Mode*) bitte **aus** lassen.
4. Schematic laden, auf einen Geisterblock schauen, rechtsklicken.

Gut zu wissen:
- Schaust du **nicht** auf einen Geisterblock, ist der Rechtsklick ganz normal (Kisten öffnen, essen …). Nur
  Blöcke setzen geht dann nicht, solange „Nur an Schematic-Plätzen setzen“ an ist.
- Stehst du selbst in der Schematic, zählen die Geisterblöcke an deinem Kopf und deinen Füßen nicht. Gebaut wird
  der Block, auf den du schaust.
- Blöcke wie Treppen, Öfen oder Trichter bekommen ihre Richtung wie in Vanilla von deiner Blickrichtung
  bzw. von der angeklickten Fläche. Passt das nicht, dreh dich passend hin. Die Debug-Meldung sagt dir, was nicht passt.
- Auf Kisten, Türen usw. wird nicht geklickt, weil Vanilla sie sonst öffnen würde. Wenn du selbst schleichst, geht es.

## Printer: ganze Schematic automatisch bauen

Der Printer baut alle aktiven Litematica-Placements selbstständig. Platziert wird dabei **immer über
Easy Place 2.0 mit strengem Fadenkreuz**, also mit genau dem Rechtsklick, den du auch selbst machen würdest.

**Zusätzlich installieren (optional, aber empfohlen):** [Baritone](https://github.com/cabaletta/baritone) für 1.21.11
(eigenständige Fabric-Version oder z. B. das Baritone aus Meteor). Ohne Baritone baut der Printer nur, was du
von deinem Standort aus erreichst.

**Bedienung:**
1. Schematic in Litematica laden und platzieren.
2. Taste **`O`** öffnet das Printer-Menü (änderbar im Menü unter **Tasten**).
3. Unter **Übersicht** auf **Starten** drücken. Das Menü schließt sich und der Printer legt los. Ein eigener
   Hotkey für Start/Stopp lässt sich ebenfalls belegen.

**Das Menü:**
- Links eine Seitenleiste mit allen Kategorien:
  - Printer: Übersicht, Platzieren, Drehen, Zeiten, Laufen, Inventar, Verhalten
  - Allgemein: Optik, Theme, Tasten, Info
- Rechts stehen die Optionen als Karten mit Titel, kurzer Beschreibung, Schalter oder Schieberegler.
  Oben rechts kannst du zwischen **Liste** und **Kacheln** wechseln, mit dem Suchfeld alle Optionen
  durchsuchen und mit **Zurücksetzen** eine Kategorie auf Standard stellen.
- Die **Übersicht** zeigt Fortschritt, Blöcke pro Minute, Restzeit, Status und das benötigte bzw. fehlende Material.
- Unter **Theme** gibt es 12 Farbschemata: Vault, Nova, Rose, Ember, Forest, Indigo, Magenta, Ocean, Crimson,
  Lime, Gold und Aurora. Unter **Optik** schaltest du den Glas-Effekt (durchsichtiges Menü mit unscharfer Welt) und das HUD.
- Unter **Tasten**: anklicken und neue Taste drücken (ESC bricht ab, Entf löscht die Taste).

**Was der Printer für jeden Block macht:**
1. Er sucht den nächsten fehlenden Block, von unten nach oben (optional nur in den angezeigten Litematica-Ebenen).
2. Ist der Block außer Reichweite, läuft er mit Baritone zu einer Stelle, von der aus Vanilla den Block **richtig
   herum** setzt (z. B. bei Treppen auf die passende Seite). Baritone baut und baut dabei nie selbst ab.
3. Fehlt das Item in der Hotbar, macht er es wie ein Spieler: Inventar öffnen → kurz warten → Item per Zahlentaste
   in die Hotbar → warten → Inventar schließen → Hotbar-Platz wählen. Die Pausen sind einstellbar.
4. Er dreht die Kamera sichtbar und flüssig auf die Nachbarfläche. Optional gibt es die **Schnelldrehung**: Der Kopf
   dreht innerhalb eines Ticks hin, aber nur bis zu einem einstellbaren Winkel.
5. Er prüft, dass das echte Fadenkreuz genau auf dieser Fläche liegt und Litematica den Geisterblock davor sieht.
   Dann folgt ein normaler Rechtsklick und eine Kontrolle, ob der Block wirklich steht.

**Material besorgen (Shulker und Lager):**
- **Materialliste** (Menü → Material): zeigt pro Item, wie viel die Schematic noch braucht und wie viel du im
  Inventar, in Shulkern im Inventar und im Lager hast. Darunter siehst du jede Shulker im Inventar mit ihrem Inhalt.
- **Shulker aus dem Inventar:** Fehlt Material, stellt der Printer eine passende Shulker neben sich auf den Boden,
  öffnet sie, nimmt das Material per Shift-Klick, schließt sie, baut sie wieder ab (mit Spitzhacke, falls in der
  Hotbar) und sammelt sie ein. Alles mit normalen Klicks und Pausen. Eine aufgestellte Shulker wird nie stehen gelassen.
- **Lager:** Truhen, Fässer oder Shulker an einem festen Platz markierst du im Menü unter **Lager**
  („+ Container im Fadenkreuz“) oder mit der Taste „Lager markieren“. Öffnest du einen markierten Container, merkt
  sich der Printer den Inhalt. Hat keine Shulker im Inventar das Material, läuft er mit Baritone zum Lager, öffnet den
  Container, nimmt das Material (oder Shulker, in denen es liegt) und baut dann weiter.
- Beides lässt sich unter **Inventar** einzeln abschalten. Die Lager-Liste liegt in `config/easyplace2_storage.json`,
  getrennt nach Server/Welt und Dimension.

**Gut zu wissen:**
- Fehlt Material, zeigt das Menü die fehlenden Items an. Der Printer baut erst andere Blöcke weiter und wartet
  dann. Sobald das Item wieder im Inventar ist, geht es automatisch weiter.
- Falsche Blöcke werden nie abgebaut, sondern nur gezählt.
- Während ein Menü offen ist, wartet der Printer, weil ein Spieler dann auch nicht klicken könnte.
- Bei Schaden pausiert er kurz (abschaltbar). Stirbst du oder trennst die Verbindung, stoppt er.
- Alle Einstellungen stehen zusätzlich im Litematica-Reiter „Easy Place 2.0“ und werden in
  `config/easyplace2.json` gespeichert.
- Nutze den Printer nur auf Servern, auf denen das erlaubt ist. Wenn dort ein Anti-Cheat läuft, kann der
  Server-Admin deinen Spieler davon ausnehmen (bei Grim z. B. mit der Berechtigung `grim.exempt`).

## Gerüst und Festhängen

**Gerüst (Menü → Laufen → „Gerüst setzen“, standardmäßig an):** Kommt der Printer an einen Block vom Boden
aus nicht heran, darf Baritone Füllblöcke setzen, um hoch oder über eine Lücke zu kommen, auch mitten in
der Schematic.

- Verbaut werden nur die Blöcke aus `printerScaffoldItems` (Standard: Erde, Bruchstein, Netherrack,
  Bruchtiefenschiefer), und davon nie etwas, das die Schematic selbst braucht.
- Abbauen darf Baritone nie etwas. Der Printer merkt sich jeden Gerüst-Block (auch über einen Neustart
  hinweg, in `config/easyplace2_scaffold.json`) und baut ihn selbst wieder ab:
  - Gerüst auf einem Platz der Schematic wird abgebaut und durch den richtigen Block ersetzt, sobald diese
    Schicht dran ist. Höheres Gerüst bleibt so lange stehen, damit er sich den Weg nach oben nicht abbaut.
  - Alles andere Gerüst wird abgebaut, bevor der Printer „Fertig“ meldet.
  - Immer von oben nach unten, nie der Block unter den eigenen Füßen (außer beim Turm-Abstieg ohne
    Fallschaden). Was nicht erreichbar ist, wird angelaufen, sonst eine Weile übersprungen und später neu
    versucht.
- Fremde Blöcke fasst der Printer nie an, nur das eigene Gerüst.

**Nie endlos im Kreis:** Ein Aufpasser prüft die ganze Zeit, ob es vorangeht.

- Steckt der Spieler beim Laufen fest (mehrere Sekunden kein Stück weiter), nimmt der Printer ein anderes
  Ziel. Findet Baritone keinen Weg, zählt das als Fehlversuch, und nach ein paar Versuchen wird der Block
  übersprungen.
- Wird eine Zeit lang (Menü → Laufen → „Feststecken nach“, Standard 90 s) weder ein Block gesetzt noch Gerüst
  abgebaut, greift er stufenweise ein:
  1. alles Übersprungene neu versuchen,
  2. aus der Lage herauslaufen,
  3. Gerüst in der Nähe abbauen.
- Hilft das alles nicht, pausiert er und schreibt in den Chat, was los ist. Mit **Weiter** im Menü geht es weiter.
- Wartet der Printer bewusst (Material fehlt, oder nichts in Reichweite und kein Baritone), läuft diese Uhr nicht.

## Modul: Invsee

Im Menü unter **Module → Invsee**. Wenn du die Taste drückst (unter **Tasten** belegen), schreibt die Mod
`/invsee <Name>` für den Spieler, der dir am nächsten steht und höchstens den eingestellten
**Blickwinkel** neben deiner Blickrichtung ist (Standard 45°, Reichweite 32 Blöcke).

- Verwendet wird nur der echte Spielername, ohne Rang, Präfix oder Farben.
- Den Befehl stellt der Server bereit (z. B. ein Plugin). Ob er erlaubt ist, entscheidet der Server.
- Der Befehl selbst steht in `config/easyplace2.json` (`moduleInvseeCommand`, Standard `invsee {name}`).

## Wo bekomme ich die .jar?

**Automatisch von GitHub (am einfachsten):**
Bei jedem Push baut GitHub die Mod. Auf der Repository-Seite oben auf **Actions** klicken →
den neuesten grünen Lauf „build“ öffnen → ganz unten bei **Artifacts** auf **easy-place-2** klicken.
Du bekommst eine ZIP-Datei; darin liegt `easy-place-2-mc1.21.11-0.2.0.jar`.

**Selbst bauen (optional):**
1. Java **21** oder neuer installieren (z. B. <https://adoptium.net>), besser gleich Java 25.
2. Projekt herunterladen (grüner Button „Code“ → „Download ZIP“) und entpacken.
3. Im Projektordner ein Terminal öffnen und ausführen:
   - Windows: `gradlew.bat build`
   - macOS/Linux: `./gradlew build`
4. Die fertige Datei liegt danach in `build/libs/easy-place-2-mc1.21.11-0.2.0.jar`.

**Mit IntelliJ IDEA:** *File → Open* → den Projektordner wählen → warten, bis Gradle fertig geladen hat.
Rechts im Gradle-Fenster *Tasks → build → build* doppelklicken (die .jar landet wieder in `build/libs/`),
oder *Tasks → fabric → runClient* starten, um direkt ein Test-Minecraft mit allen Mods zu öffnen.

## Automatischer Test

`./gradlew runClientGameTest` (bzw. der Job `client_game_test` auf GitHub) startet Minecraft mit
Litematica und Easy Place 2.0, verbindet sich mit einem **Vanilla-Server** (ohne Mods) und prüft:
Treppe wird richtig herum gesetzt, obere Halbplatte wird gesetzt, Treppe in falscher Blickrichtung
wird übersprungen, schwebender Block wird übersprungen. Danach öffnet er das Printer-Menü und lässt den
Printer eine kleine Schematic bauen. Die Hotbar ist dabei voll mit Erde, die Bausteine muss er also über das
Inventar holen. Geprüft wird auf dem Server, auch die Richtung der Treppe.

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
| Printer-Ablauf (Zustandsautomat) | `printer/PrinterController.java` |
| Schematic mit der Welt vergleichen | `printer/SchematicScanner.java` |
| Klickpunkte + Stehplätze mit richtiger Ausrichtung | `printer/AimPlanner.java`, `printer/StandSpots.java` |
| Kamera drehen | `printer/AimController.java` |
| Items wie ein Spieler holen | `printer/InventoryManager.java` |
| Baritone (nur Laufen, per Reflection) | `printer/BaritoneBridge.java` |
| Menü und HUD | `printer/gui/*` |
