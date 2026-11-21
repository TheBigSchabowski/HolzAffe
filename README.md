# HolzAffe

Gehörbildung gegen die Uhr: Du hörst Akkorde oder Akkordfolgen und wählst im 3×3-Raster aus neun
Antworten die richtige Lösung. Auf dem Handy kann HolzAffe außerdem andere Apps sperren, bis du
ein paar Aufgaben richtig erkannt hast.

## Das Spiel

- **1:30 Zeit** im Puzzle-Rush-Format, mit **♪ Zerlegt** (Arpeggio) und **⏸ Check** (Check-Modus).
- **Check-Modus (`⏸ Check`)**: Hält die Zeit im Run an, zeigt sowohl die Stufe (z. B. `I → ♭VII`) als auch
  die konkreten Akkordnamen in der gespielten Tonart (z. B. `G-Dur → F-Dur`) samt Einzeltönen und lässt
  jeden Akkord einzeln zum Doppelchecken (z. B. auf der Gitarre) abspielen.
- **Vier Trainings-Modi plus Mix** (jeweils 9 Antworten zur Auswahl, auch für die App-Sperre wählbar):
  - **Stufen** (`I → …` / `i → …`): Dur `ii iii IV iv V ♭VI vi ♭VII vii°`, Moll `ii° III iv IV v V VI VII vii°`.
  - **Akkordtypen**: `Dur`, `Moll`, `dim`, `aug`, `sus2`, `sus4`, `maj7`, `7`, `m7` (eng und weit gelegt).
  - **Kadenzen**: typische 3-Akkord-Verbindungen in Dur und Moll (z. B. `I → IV → V`, `i → VI → VII`).
  - **Umkehrungen**: `Dur`, `Moll` und `dim` jeweils in Grundstellung, 1. und 2. Umkehrung (auf einheitlicher Basshöhe).
  - **Mix**: mischt alle vier Modi gleichmäßig.
- **Fehler-Wiederholung**: Falsch beantwortete Aufgaben kehren nach kurzem Abstand automatisch zurück
  (auch über Runs, die App-Sperre und Neustarts hinweg), bis sie zweimal sicher sitzen.
- Gespeichert werden das **All-time High** pro Modus, die **aktuelle Serie**
  richtiger Antworten in Folge und die **längste Serie**. Die aktuelle Serie läuft über Runs
  und App-Neustarts weiter, bis eine Antwort falsch ist.
- Optional endet ein Run nach einer einstellbaren Zahl von Fehlern (Standard: aus).

## App-Sperre (nur Android)

Öffnest du eine gesperrte App, erscheint davor die Challenge im gewählten Sperr-Modus. Nach 5 richtigen Antworten (einstellbar)
geht die App auf und bleibt 10 Minuten (einstellbar) frei. Dafür braucht HolzAffe die Bedienungshilfe
„HolzAffe App-Sperre“; sie erkennt nur, welche App in den Vordergrund kommt, und liest keine
Bildschirminhalte. Bei einer von Hand installierten APK muss vorher in der App-Info
„Eingeschränkte Einstellungen zulassen“ gewählt werden.

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
