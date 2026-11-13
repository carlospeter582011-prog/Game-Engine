# Nexus Engine for Android and Web

The Flutter app hosts the Nexus 2D editor offline. The app includes the scene editor, inspector, block logic, Python, preview, and editable ZIP import/export. Download the editor APK and AAB from [the latest GitHub release](https://github.com/carlospeter582011-prog/Game-Engine/releases/latest). Every push to main builds both artifacts and publishes a versioned release.

## Export a game APK

1. Choose **Home → Export** and select the open project or a saved project.
2. Choose **Android APK**, **Standalone HTML game**, or **Editable project ZIP**.
3. Select **Inside Nexus** to keep a copy in **Saved exports**, or **Device files** to download or save a file.

On the website, fill in the app name, package ID, launcher icon, orientation, version and build number. Nexus builds and signs the APK in the browser without uploading the game. The signing key is saved locally by package ID. Use the encrypted signing key backup to move it to another browser. The browser's signing keys and the Android app's signing keys are separate.

On Android, **Device files** opens the native APK exporter and file picker.

The export works offline. The finished APK opens directly in an Android WebView with system bars visible on Android 7+. Games run without the editor installed. The editor keeps the signing key in Android Keystore. To update an installed game, export from the same Nexus installation, reuse its package ID, and increase the build number. To keep game updates portable across devices, choose your own password-protected PKCS#12 signing key before the first export and keep a backup of that file. Android Keystore keys stay on their device. An editor release intended for store distribution can be signed using the repository’s existing signing secrets.

## Python and blocks

Choose **Logic → Python** and select an object. Apply a script and use Play. Both Python and linked block graphs can run on the same object. The original Nexus Script behavior still works for existing projects.

```python
from math import sin

time = 0

def on_event(event, dt, game):
    global time
    if event == "OnUpdate":
        time += dt
        game["x"] += sin(time) * 40 * dt
```

Python 3 scripting is powered by [Skulpt](https://github.com/skulpt/skulpt). Its supported syntax and built-ins include classes, comprehensions and functions. Nexus exposes position, velocity, input, events and commands for variables, scenes, spawning, audio, UI text, messages and save slots through the `game` dictionary and includes `math` and `random` imports. Scripts have a time limit and run offline in editor preview and exported games. Project ZIPs preserve the scripts.

## Local build

Use Flutter 3.50.0, JDK 21, and the Android SDK. Run `bash tools/fetch-python.sh`, `bash tools/build-player.sh`, `flutter pub get`, and `flutter build apk --release`. The workflow downloads the same pinned Python runtime and verifies the SHA-256 of its bundled files.

GitHub Actions builds the editor and embeds the reusable player template. Its packaging smoke test verifies the signed APK, application ID, version, orientation, custom icon and embedded HTML.


## Build a multi-level game

Load **Checkpoint Campaign** for an editable main menu, three platformer scenes and an ending. Use **Home → Game Start Scene** and **Game End Scene** to set the game’s entry and ending. Wire `EndGame` with a win or loss outcome, and use `SaveGame` / `LoadGame` for local slots. Slots store the current scene and global variables. Use `Broadcast` / `OnMessage` to connect different objects or Python scripts.

The Logic editor includes descriptions of every block’s inputs, outputs and settings. Press the small round help button on a selected block. Play preview includes device sizes, frame stepping, collision visualization and pause-at-end-of-frame block breakpoints.

**Create → Tilemap** supports layers, resize, zoom, history and import of finite orthogonal Tiled JSON with one square tileset. Choose the sprite-sheet asset and solid tile IDs in Inspect. In **Assets & Prefabs** you can import multiple images and sounds, then add them to the scene or play them through logic.

Use **Inspect → SpriteRenderer → Slice sprite sheet** to see the frame grid. Add **SpriteStates** to assign Idle, Run and Jump frame ranges. Use **Create → UI Builder → Place** to drag touch controls and HUD widgets into place.

On phones, Create uses a workspace picker. Drawing tools stay above the canvas, and options open on demand. Undo and redo in the header use the current workspace’s history.

## Flutter web editor

Import the GitHub repository into Vercel and deploy from the repository root. The checked-in `vercel.json` sets the framework to Other, runs `bash tools/build-web.sh`, and publishes `build/web`. The script installs the pinned Flutter SDK when Flutter is absent. Set `main` as the production branch.

The browser editor supports preview, Python, editable project ZIPs, standalone HTML games and signed Android APKs. Use a project ZIP to move editable projects between devices.

Run `bash tools/build-web.sh` locally or download `Nexus-Engine-Website.zip` from the latest release. Serve the contents of `build/web` or the extracted ZIP from the root of an HTTP server. GitHub Actions also uploads the website as a build artifact.

## Visual scripts and game systems

- Use **Logic → Blocks** to add event stacks and change settings directly on the block. Add a value block inside an input or use **Wires** for a graph view. Attach the script to a scene object.
- Create a reusable script with **When called as a custom block** and invoke it from **Run custom block**. Use **Script packs** to move scripts between projects.
- Use **Data & input** to edit project records and bind actions to keyboard keys, touch controls and gamepad buttons. The **When an action starts** event fires once per press.
- Blocks and Python share commands for finding objects, reading and writing component properties and tiles, raycasts, camera zoom, object state, data records, turns, grid navigation, line of sight and seeded random values. **Save world** stores active scene objects and global variables in a local versioned slot.
- In **Create → Tilemap**, zoom with two fingers or the mouse wheel and use **Map** to jump to a region. **Generate terrain** fills the selected layer with a repeatable landscape from a seed.

After modifying the Android player, regenerate its checked-in browser template with `bash tools/build-player.sh` followed by `python3 tools/export-player-template.py`. The Android workflow verifies both exporters using the Android build tools.

## Workshop

Open [Nexus Studio](https://2dgame-engine-carlos.vercel.app/) and use the studio menu to switch between Home, the editor, exports and AI. Rotate the device for a landscape editor, or choose a viewport preset in project settings to change the game itself. The 20 Workshop templates cover five gameplay families. Desert Dino includes automatic obstacle scrolling, jumping, score and restart.

- In **Logic → Make block**, select a connected set of actions and name it. Nexus extracts a shared definition and replaces the selection with a call. Edit that definition to change every call in the project.
- **For each number**, **For each item in list**, **While**, and **Repeat until** have body and completion branches. Numeric ranges exclude their end value. The runtime bounds each synchronous loop. **Forever** schedules its body on each frame.
- **Create → UI Builder** has a Restart button preset. For custom actions, name a UI button event and select it in **When a button is tapped**. In Inspect, edit text, alignment, font size, corner radius, opacity and padding.
- **Build with AI** in the web editor accepts HTTPS OpenAI-compatible chat endpoints that allow browser CORS. Each provider has a model ID and one or more keys. Nexus tries the keys in order before moving to the next provider. Keys stay in the tab and are sent only to the provider configured with them. Generate a game, tilemap, pixel art, sound effect or object logic. Review and apply the result in Nexus. **Assets & Prefabs → Import files** imports your own audio and images.

Browser storage is per domain. Export an editable project ZIP from the old address and import it at the new one. Use encrypted signing key backups to move APK signing keys.
