# HolzAffe

Gehörbildung gegen die Uhr: Du hörst Akkorde oder Akkordfolgen und wählst im 3×3-Raster aus neun
Antworten die richtige Lösung. Auf dem Handy kann HolzAffe außerdem andere Apps sperren, bis du
ein paar Aufgaben richtig erkannt hast.

## Das Spiel

- **Aufgeräumte 4-Tab-Oberfläche**: **Speedrun**, **Runs**, **Aufgaben** und **Weiterleitungen** (Desktop: **Speedrun** & **Runs**), plus Einstellungen (⚙) und App-Sperre-Status in der Kopfzeile.
- **Multi-Run-Speedrun** mit einstellbarer Run-Länge (Standard 1:30, von 0:15 bis 10:00), **♪ Zerlegt** (Arpeggio) und **⏸ Check** (Check-Modus). Beliebig viele Modi und eigene `★`-Runs lassen sich kombinieren; Bestwerte werden pro Auswahl *und* Länge gespeichert. Lange auf einen Run oder Modus drücken öffnet direkt den Editor.
- **Check-Modus (`⏸ Check`)**: Hält die Zeit im Run an, zeigt sowohl die Stufe (z. B. `I → ♭VII`) als auch
  die konkreten Akkordnamen in der gespielten Tonart (z. B. `G-Dur → F-Dur`) samt Einzeltönen und lässt
  jeden Akkord einzeln zum Doppelchecken (z. B. auf der Gitarre) abspielen.
- **Vier Trainings-Modi plus Mix** (jeweils 9 Antworten zur Auswahl, auch für die App-Sperre kombinierbar):
  - **Stufen** (`I → …` / `i → …`): Dur `ii iii IV iv V ♭VI vi ♭VII vii°`, Moll `ii° III iv IV v V VI VII vii°`.
  - **Akkordtypen**: `Dur`, `Moll`, `dim`, `aug`, `sus2`, `sus4`, `maj7`, `7`, `m7` (eng und weit gelegt).
  - **Kadenzen**: typische 3-Akkord-Verbindungen in Dur und Moll (z. B. `I → IV → V`, `i → VI → VII`).
  - **Umkehrungen**: `Dur`, `Moll` und `dim` jeweils in Grundstellung, 1. und 2. Umkehrung (auf einheitlicher Basshöhe).
  - **Mix**: alle vier Modi gemeinsam ausgewählt.
- **Tempo & Takt (BPM)**: Einstellbar über BPM (40–240) und Taktwert (`1/4`, `2/4`, `3/4`, `4/4`; der letzte Akkord klingt doppelt so lang aus), direkt im Speedrun-Tab, unter *Runs* und in den *Einstellungen*, sowie optional pro eigenem Run.
- **Eigene Runs, synthetisierte Akkorde & Audios**: Unter **Runs → Eigene Inhalte** baust du eigene 1–4-Akkord-Übungen (19 Akkordtypen von Dur/Moll bis `m7♭5`, `dim7`, `add9`, `maj9`, `m9`, Umkehrungen, transponierend oder feste Tonart), lädst eigene Aufnahmen hoch (Android: MP3, M4A, WAV, OGG …; Desktop: WAV/AIFF) und stellst daraus eigene Runs zusammen. Verpasste Aufgaben kommen im Run kurz danach wieder.
- **Fehler-Wiederholung**: Falsch beantwortete Aufgaben kehren nach kurzem Abstand automatisch zurück
  (auch über Runs, die App-Sperre und Neustarts hinweg), bis sie zweimal sicher sitzen.
- Gespeichert werden das **All-time High** pro Modus, die **aktuelle Serie**
  richtiger Antworten in Folge und die **längste Serie**. Die aktuelle Serie läuft über Runs
  und App-Neustarts weiter, bis eine Antwort falsch ist.
- Optional endet ein Run nach einer einstellbaren Zahl von Fehlern (Standard: aus).

## App-Sperre (nur Android)

Öffnest du eine gesperrte App, erscheint davor die Challenge im gewählten Sperr-Modus. Pro App-Start
zieht HolzAffe standardmäßig **7 unterschiedliche Aufgaben** (einstellbar von 2 bis 18), die sich in
ständig wechselnder Reihenfolge wiederholen – standardmäßig in **einer festen Tonart** (nur Dur oder
nur Moll, z. B. nur F-Dur), die erst beim nächsten Öffnen wechselt (alternativ: gleicher Grundton mit
Dur+Moll oder komplettes Wirrwarr). Die **Fehler-Anzeigedauer** (Rot/Grün nach falscher Antwort) ist
einstellbar (Standard 3 Sekunden).

Statt eines Modus kann die App-Sperre auch einen **eigenen Run** abfragen (inkl. eigener Audios).
Unter **Abgefragte Aufgaben** legst du fest, welche Akkordfolgen (bzw. Kadenzen, Akkordtypen und
Umkehrungen) beim Öffnen überhaupt drankommen, z. B. nur `I → IV`, `I → V` und `I → vi`. Jede Gruppe
lässt sich per **Alle**/**Keine** umschalten, und ▶ spielt eine Hörprobe. Die Tonart-Einstellung
richtet sich danach: Sind nur Dur-Folgen ausgewählt, bleibt die Sperre auch in Dur. Ist nichts
ausgewählt, wird wieder alles abgefragt.

Zusätzlich lassen sich unter **Sperr-Aufgaben** eigene **Text-Aufgaben**, **App-Weiterleitungen**
(z. B. erst 1–3 Min. Widder, Anki oder Duolingo üben, mit schwebendem Countdown) sowie **konfigurierte
Android-Widgets** (z. B. ein spezielles Widder-Widget) mit 1–3 Min. Timer erstellen und ins aktive Feld
ziehen: Bei mehreren aktiven Einträgen wechselt die App-Sperre bei jedem Öffnen dazwischen. Sobald der
Timer abläuft oder die Challenge gelöst ist, bestätigt ein kurzes Vibrieren und die Ziel-App öffnet sich
automatisch. Unter **Wieder sperren** lässt sich wählen, ob die App nach dem Lösen
für eine feste Zeit (**Timer**, Standard 10 Min.), bis zum **Display-Aus** oder nur bis zum **Verlassen
der App** offen bleibt. In der App-Auswahl hilft eine Suchleiste (z. B. `whats` für WhatsApp),
und die nötigen Berechtigungen (**Über anderen Apps einblenden** + **Bedienungshilfe**, inkl. Direkt-Link
zur App-Info für Android 13+ „Eingeschränkte Einstellungen“) lassen sich direkt in HolzAffe einrichten.
Es werden keine Bildschirminhalte gelesen.

## Installieren

Pakete liegen unter [Releases](../../releases): APK (Android 8.1+), DMG (macOS, Apple Silicon),
`.deb` und `.tar.gz` (Linux x86_64). Die macOS-App ist nicht notarisiert: beim ersten Start
Rechtsklick → Öffnen.

## Bauen

Kotlin Multiplatform mit Compose: `shared` (Spiel, Synthesizer, Oberfläche), `androidApp`
(App-Sperre), `desktopApp` (macOS und Linux).

```
./gradlew :shared:desktopTest          # Tests
./gradlew :desktopApp:run              # Desktop starten
./gradlew :androidApp:assembleRelease  # APK
./gradlew :desktopApp:packageDmg       # DMG (auf dem Mac; bei einem Release baut es GitHub Actions)
./tools/build-linux.sh                 # .deb + .tar.gz (unter Linux)
```
