# Nexus Engine for Android

The Flutter app hosts the Nexus 2D editor offline. The app includes the scene editor, inspector, block logic, Python, preview, and editable ZIP import/export. Download the editor APK and AAB from [the latest GitHub release](https://github.com/carlospeter582011-prog/Game-Engine/releases/latest). Every push to main builds both artifacts and publishes a versioned release.

## Export a game APK

1. Open a project and choose **Home → Export Android APK**.
2. Set the app name, unique Android package ID, launcher icon, orientation, version name, and build number.
3. Tap **Build APK** and then **Save APK**. The editor packages the game HTML and assets into a lightweight Android player, signs it on the device, and opens the Android save dialog.

The export works offline. The finished APK opens directly in a Android WebView with system bars visible on Android 7+. Games run without the editor installed. The editor keeps the signing key in Android Keystore. To update an installed game, export from the same Nexus installation, reuse its package ID, and increase the build number. To keep game updates portable across devices, choose your own password-protected PKCS#12 signing key before the first export and keep a backup of that file. Android Keystore keys stay on their device. An editor release intended for store distribution can be signed using the repository’s existing signing secrets.

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
