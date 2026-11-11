# HolzAffe

Gehörbildung gegen die Uhr: Es erklingen zwei Akkorde direkt hintereinander, und du wählst aus drei
Antworten die richtige Akkordfolge (z. B. `i → IV`). Auf dem Handy kann HolzAffe außerdem andere Apps
sperren, bis du ein paar Folgen richtig erkannt hast.

## Das Spiel

- **1:30 Zeit**, so viele Akkordfolgen wie möglich.
- Der erste Akkord ist immer die Tonika (`I` oder `i`), der zweite wechselt. Dur: `ii iii IV V vi`,
  Moll: `III iv IV v V VI VII`. Beide Akkorde kommen in zufälliger Umkehrung und Tonart.
- Gespeichert werden das **All-time High** (Richtige in einem Run) und die **längste Serie**
  richtiger Antworten in Folge.
- Optional endet ein Run nach einer einstellbaren Zahl von Fehlern (Standard: aus).

## App-Sperre (nur Android)

Öffnest du eine gesperrte App, erscheint davor die Challenge. Nach 5 richtigen Antworten (einstellbar)
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
./gradlew :desktopApp:packageDmg       # DMG (auf dem Mac)
./tools/build-linux.sh                 # .deb + .tar.gz (unter Linux)
```
